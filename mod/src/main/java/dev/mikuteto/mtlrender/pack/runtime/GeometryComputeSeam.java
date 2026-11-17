package dev.mikuteto.mtlrender.pack.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class GeometryComputeSeam {
	private static final long MAX_COUNT = Integer.MAX_VALUE;

	private GeometryComputeSeam() {
	}

	public enum StageKind { VS, COUNT, SCAN, GS, COMPACT, INDIRECT }
	public enum PrimitiveTopology { POINTS, LINES, LINE_STRIP, TRIANGLES, TRIANGLE_STRIP }
	public enum ResourceClass { IMAGE, SSBO, ATOMIC }
	public enum Access { READ, WRITE, READ_WRITE }

	public record Context(String pack, String dimension, String program, String stage) {
		public Context {
			pack = name(pack, "pack");
			dimension = name(dimension, "dimension");
			program = name(program, "program");
			stage = name(stage, "stage");
		}
	}

	public record Primitive(PrimitiveTopology topology, boolean primitiveRestart, boolean endPrimitive,
		String varyingPayload, long primitiveId, int layer, Set<GeometryPlan.SideEffect> sideEffects) {
		public Primitive(PrimitiveTopology topology, boolean primitiveRestart, boolean endPrimitive,
			String varyingPayload, long primitiveId, int layer) {
			this(topology, primitiveRestart, endPrimitive, varyingPayload, primitiveId, layer, Set.of());
		}

		public Primitive {
			topology = Objects.requireNonNull(topology, "topology");
			varyingPayload = name(varyingPayload, "varying payload");
			if (primitiveId < 0 || primitiveId > MAX_COUNT) throw new IllegalArgumentException("primitive ID is out of range");
			if (layer < 0) throw new IllegalArgumentException("layer must be non-negative");
			sideEffects = Set.copyOf(Objects.requireNonNull(sideEffects, "side effects"));
			if (primitiveRestart && topology != PrimitiveTopology.LINE_STRIP && topology != PrimitiveTopology.TRIANGLE_STRIP) {
				throw new IllegalArgumentException("primitive restart requires a strip topology");
			}
		}
	}

	public record Output(Primitive primitive, int vertexStride, long vertexCount, long byteSize,
		String varyingPayload, long primitiveId, int layer) {
		public Output {
			primitive = Objects.requireNonNull(primitive, "primitive");
			if (vertexStride <= 0) throw new IllegalArgumentException("vertex stride must be positive");
			if (vertexCount < 0 || vertexCount > MAX_COUNT || byteSize < 0) throw new IllegalArgumentException("output size must be non-negative and bounded");
			varyingPayload = name(varyingPayload, "varying payload");
			if (!varyingPayload.equals(primitive.varyingPayload()) || primitiveId != primitive.primitiveId()
				|| layer != primitive.layer()) throw new IllegalArgumentException("output metadata does not match primitive");
			if (byteSize != multiply(vertexCount, vertexStride)) throw new IllegalArgumentException("output byte size does not match count and stride");
			if (vertexCount > 0 && !validVertexCount(primitive.topology(), vertexCount)) {
				throw new IllegalArgumentException("output vertex count does not match topology");
			}
		}

		private static boolean validVertexCount(PrimitiveTopology topology, long count) {
			return switch (topology) {
				case POINTS -> true;
				case LINES, LINE_STRIP -> count >= 2 && count % 2 == 0;
				case TRIANGLES, TRIANGLE_STRIP -> count >= 3 && count % 3 == 0;
			};
		}
	}

	public record PrefixBoundary(long inputOffset, long inputCount, long outputOffset, long outputCount) {
		public PrefixBoundary {
			if (inputOffset < 0 || inputCount < 0 || outputOffset < 0 || outputCount < 0) {
				throw new IllegalArgumentException("prefix boundary must be non-negative");
			}
			add(inputOffset, inputCount);
			add(outputOffset, outputCount);
		}
	}

	public record IndirectDrawArguments(long indexCount, long instanceCount, long firstIndex,
		long baseVertex, long baseInstance) {
		public IndirectDrawArguments {
			positiveBounded(indexCount, "index count");
			positiveBounded(instanceCount, "instance count");
			bounded(firstIndex, "first index");
			bounded(baseVertex, "base vertex");
			bounded(baseInstance, "base instance");
		}
	}

	public record ResourceAccess(StageKind stage, ResourceClass resourceClass, String resource,
		Access access) {
		public ResourceAccess {
			stage = Objects.requireNonNull(stage, "stage");
			resourceClass = Objects.requireNonNull(resourceClass, "resource class");
			resource = name(resource, "resource");
			access = Objects.requireNonNull(access, "access");
		}

		public ComputePlan.Access planAccess() {
			return ComputePlan.Access.valueOf(access.name());
		}
	}

	public record ProgramFailure(String pack, String dimension, String program, String stage, String message) {
		public ProgramFailure {
			pack = name(pack, "pack");
			dimension = name(dimension, "dimension");
			program = name(program, "program");
			stage = name(stage, "stage");
			message = name(message, "message");
		}

		public ProgramFailure(Context context, String message) {
			this(context.pack(), context.dimension(), context.program(), context.stage(), message);
		}
	}

	public record Execution(GeometryPlan geometry, GeometryComputePlan plan, List<StageKind> stages,
		List<Primitive> primitives, List<Output> outputs, List<PrefixBoundary> prefix,
		IndirectDrawArguments indirect, List<ResourceAccess> accesses, boolean allowConcurrentCompute) {
		public Execution {
			geometry = Objects.requireNonNull(geometry, "geometry");
			plan = Objects.requireNonNull(plan, "plan");
			stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
			primitives = List.copyOf(Objects.requireNonNull(primitives, "primitives"));
			outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
			prefix = List.copyOf(Objects.requireNonNull(prefix, "prefix"));
			accesses = List.copyOf(Objects.requireNonNull(accesses, "accesses"));
			if (!stages.equals(List.of(StageKind.VS, StageKind.COUNT, StageKind.SCAN, StageKind.GS, StageKind.COMPACT, StageKind.INDIRECT))) {
				throw new IllegalArgumentException("geometry compute stages are out of order");
			}
			if (primitives.size() != outputs.size() || primitives.size() != prefix.size()) {
				throw new IllegalArgumentException("primitive, output, and prefix records must correspond");
			}
			long outputCount = 0;
			for (int i = 0; i < primitives.size(); i++) {
				Primitive primitive = primitives.get(i);
				if (!primitive.sideEffects().equals(geometry.sideEffects())) throw new IllegalArgumentException("primitive side effects do not match geometry");
				Output output = outputs.get(i);
				PrefixBoundary boundary = prefix.get(i);
				if (output.primitive() != primitive && !output.primitive().equals(primitive)) throw new IllegalArgumentException("output primitive identity changed");
				if (boundary.inputOffset() != i || boundary.inputCount() != 1 || boundary.outputOffset() != outputCount
					|| boundary.outputCount() != output.vertexCount()) throw new IllegalArgumentException("prefix boundaries are inconsistent");
				if (geometry.layers().semantics() == GeometryPlan.LayerSemantics.NONE && output.layer() != 0) {
					throw new IllegalArgumentException("output layer is out of range");
				}
				if (geometry.layers().semantics() != GeometryPlan.LayerSemantics.NONE && output.layer() >= geometry.layers().layers()) {
					throw new IllegalArgumentException("output layer is out of range");
				}
				if (output.vertexCount() > plan.maxVertices() || output.byteSize() > outputBufferSize(plan)) {
					throw new IllegalArgumentException("output size is out of range");
				}
				outputCount = add(outputCount, output.vertexCount());
			}
			if (indirect != null && indirect.indexCount() != multiply(outputCount, 1)) {
				throw new IllegalArgumentException("indirect count does not match output");
			}
			validateAccesses(accesses);
		}

		public boolean hasOutput() {
			return indirect != null;
		}

		public static Execution of(GeometryPlan geometry, GeometryComputePlan plan, List<Primitive> primitives,
			List<Output> outputs, List<PrefixBoundary> prefix, IndirectDrawArguments indirect,
			List<ResourceAccess> accesses, boolean allowConcurrentCompute) {
			return new Execution(geometry, plan, List.of(StageKind.VS, StageKind.COUNT, StageKind.SCAN,
				StageKind.GS, StageKind.COMPACT, StageKind.INDIRECT), primitives, outputs, prefix, indirect, accesses,
				allowConcurrentCompute);
		}
	}

	private static long outputBufferSize(GeometryComputePlan plan) {
		for (GeometryComputePlan.Buffer buffer : plan.buffers()) {
			if (buffer.kind() == GeometryComputePlan.BufferKind.OUTPUT_VERTICES) return buffer.size();
		}
		throw new IllegalArgumentException("geometry plan has no output vertex buffer");
	}

	private static void validateAccesses(List<ResourceAccess> accesses) {
		Set<String> seen = new java.util.HashSet<>();
		for (ResourceAccess access : accesses) {
			String key = access.stage() + "\u0000" + access.resourceClass() + "\u0000" + access.resource();
			if (!seen.add(key)) throw new IllegalArgumentException("duplicate resource access");
		}
	}

	private static long multiply(long left, long right) {
		try { return Math.multiplyExact(left, right); }
		catch (ArithmeticException exception) { throw new IllegalArgumentException("geometry count overflow", exception); }
	}

	private static long add(long left, long right) {
		try { return Math.addExact(left, right); }
		catch (ArithmeticException exception) { throw new IllegalArgumentException("geometry count overflow", exception); }
	}

	private static void positiveBounded(long value, String label) {
		if (value <= 0 || value > MAX_COUNT) throw new IllegalArgumentException(label + " must be positive and bounded");
	}

	private static void bounded(long value, String label) {
		if (value < 0 || value > MAX_COUNT) throw new IllegalArgumentException(label + " is out of range");
	}

	private static String name(String value, String label) {
		Objects.requireNonNull(value, label);
		if (value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
		return value;
	}
}
