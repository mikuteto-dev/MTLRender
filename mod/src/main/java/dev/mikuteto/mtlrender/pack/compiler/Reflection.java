package dev.mikuteto.mtlrender.pack.compiler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record Reflection(
	int stage,
	boolean usesArgumentBuffers,
	List<Resource> resources,
	List<UniformMember> uniformMembers,
	List<VertexInput> vertexInputs,
	List<FragmentOutput> fragmentOutputs,
	ComputeLocalSize computeLocalSize,
	Geometry geometry,
	TessellationDescriptor tessellation
) {
	public Reflection(int stage, boolean usesArgumentBuffers, List<Resource> resources,
		List<UniformMember> uniformMembers, List<VertexInput> vertexInputs,
		List<FragmentOutput> fragmentOutputs, ComputeLocalSize computeLocalSize) {
		this(stage, usesArgumentBuffers, resources, uniformMembers, vertexInputs, fragmentOutputs, computeLocalSize, null, null);
	}

	public record Resource(String name, int kind, int dimension, int format, int binding,
		boolean writable, String addressSpace, int helperVersion, String helper) {
		public boolean isDepthTexture() {
			return kind == 3;
		}

		public boolean isSampler() {
			return kind == 4 || kind == 3;
		}
	}

	public record UniformMember(String name, int type, int offset, int size, int arrayStride) {}
	public record VertexInput(String name, int type, int location) {}
	public record FragmentOutput(String name, int type, int location, int colortex) {}
	public record ComputeLocalSize(int x, int y, int z) {}
	public record Geometry(String kind, String inputTopology, String outputTopology, int producedVertices,
		int producedPrimitives, int instanceCount, List<Integer> layerMapping, String reason,
		boolean imageWrites, boolean ssboWrites, boolean atomics, boolean dynamicControlFlow) {
		public Geometry(String kind, String inputTopology, String outputTopology, int producedVertices,
			int producedPrimitives) {
			this(kind, inputTopology, outputTopology, producedVertices, producedPrimitives, 0, List.of(), "", false, false, false, false);
		}

		public Geometry(String kind, String inputTopology, String outputTopology, int producedVertices,
			int producedPrimitives, int instanceCount, List<Integer> layerMapping) {
			this(kind, inputTopology, outputTopology, producedVertices, producedPrimitives, instanceCount, layerMapping, "", false, false, false, false);
		}

		public Geometry {
			Objects.requireNonNull(kind, "geometry kind");
			Objects.requireNonNull(inputTopology, "geometry input topology");
			Objects.requireNonNull(outputTopology, "geometry output topology");
			Objects.requireNonNull(layerMapping, "geometry layer mapping");
			reason = Objects.requireNonNull(reason, "geometry reason");
			if (instanceCount < 0 || instanceCount != layerMapping.size()) {
				throw new IllegalArgumentException("invalid geometry instance metadata");
			}
			layerMapping = List.copyOf(layerMapping);
		}

		public boolean passThrough() { return kind.equals("PassThrough") || kind.equals("not_lowered"); }
		public boolean layered() { return kind.equals("Layered"); }
	}
	public record TessellationDescriptor(int topology, int spacing, int patchVertices,
		boolean usesInvocationId, boolean usesPrimitiveId, boolean usesTessCoord, boolean usesTessLevels,
		List<String> controlInputs, List<String> controlOutputs, List<String> evaluationInputs,
		List<String> evaluationOutputs) {}

	static Reflection decode(byte[] bytes) {
		Reader reader = new Reader(bytes);
		if (!reader.bytes(8).equals("MTLRGLSL")) throw new IllegalArgumentException("invalid GLSL reflection magic");
		if (reader.u32() != 9) throw new IllegalArgumentException("unsupported GLSL reflection version");
		int stage = reader.u32();
		boolean argumentBuffers = reader.u32() != 0;
		int resourceCount = reader.count();
		int memberCount = reader.count();
		int inputCount = reader.count();
		int outputCount = reader.count();
		int interfaceCount = reader.count();
		boolean hasLocalSize = reader.u32() != 0;
		int x = reader.u32();
		int y = reader.u32();
		int z = reader.u32();
		readSubdivision(reader);
		reader.u32();
		readCountedIntegers(reader);
		readCountedStrings(reader);
		readHelperLocalSizes(reader);
		Geometry geometry = readGeometryValue(reader);
		reader.string();
		TessellationDescriptor tessellation = readTessellation(reader);
		List<Resource> resources = new ArrayList<>(resourceCount);
		for (int i = 0; i < resourceCount; i++) {
			int kind = reader.u32(); int dimension = reader.u32(); int format = reader.u32(); int binding = reader.u32();
			boolean writable = reader.u32() != 0;
			String addressSpace = reader.string();
			int helperVersion = reader.u32();
			String helper = reader.string();
			resources.add(new Resource(reader.string(), kind, dimension, format, binding, writable, addressSpace, helperVersion, helper));
		}
		List<UniformMember> members = new ArrayList<>(memberCount);
		for (int i = 0; i < memberCount; i++) {
			int type = reader.u32(); int offset = reader.u32(); int size = reader.u32(); int stride = reader.u32();
			members.add(new UniformMember(reader.string(), type, offset, size, stride));
		}
		List<VertexInput> inputs = new ArrayList<>(inputCount);
		for (int i = 0; i < inputCount; i++) {
			int location = reader.u32(); int type = reader.u32();
			inputs.add(new VertexInput(reader.string(), type, location));
		}
		List<FragmentOutput> outputs = new ArrayList<>(outputCount);
		for (int i = 0; i < outputCount; i++) {
			int location = reader.u32(); int colortex = reader.u32(); int type = reader.u32();
			outputs.add(new FragmentOutput(reader.string(), type, location, colortex));
		}
		for (int i = 0; i < interfaceCount; i++) {
			reader.u32(); reader.u32(); reader.u32(); reader.u32(); reader.u32(); reader.string();
		}
		if (reader.remaining() != 0) throw new IllegalArgumentException("trailing GLSL reflection bytes");
		return new Reflection(stage, argumentBuffers, List.copyOf(resources), List.copyOf(members), List.copyOf(inputs), List.copyOf(outputs), hasLocalSize ? new ComputeLocalSize(x, y, z) : null, geometry, tessellation);
	}

	private static TessellationDescriptor readTessellation(Reader reader) {
		if (reader.u32() == 0) return null;
		int topology = reader.u32();
		int spacing = reader.u32();
		int patchVertices = reader.u32();
		boolean invocation = reader.u32() != 0;
		boolean primitive = reader.u32() != 0;
		boolean coord = reader.u32() != 0;
		boolean levels = reader.u32() != 0;
		return new TessellationDescriptor(topology, spacing, patchVertices, invocation, primitive, coord, levels,
			readStrings(reader), readStrings(reader), readStrings(reader), readStrings(reader));
	}

	private static List<String> readStrings(Reader reader) {
		int count = reader.count();
		List<String> values = new ArrayList<>(count);
		for (int i = 0; i < count; i++) values.add(reader.string());
		return List.copyOf(values);
	}

	private static void readSubdivision(Reader reader) {
		if (reader.u32() == 0) return;
		reader.u32();
		reader.string(); reader.string();
		reader.u32(); reader.u32(); reader.u32();
		reader.string(); reader.string();
		reader.u32(); reader.u32(); reader.u32(); reader.u32();
	}

	private static void readCountedIntegers(Reader reader) {
		reader.u32();
		int count = reader.count();
		for (int i = 0; i < count; i++) reader.u32();
	}

	private static void readCountedStrings(Reader reader) {
		int count = reader.count();
		for (int i = 0; i < count; i++) reader.string();
	}

	private static void readHelperLocalSizes(Reader reader) {
		int count = reader.count();
		for (int i = 0; i < count; i++) {
			reader.u32(); reader.u32(); reader.u32(); reader.u32(); reader.string();
		}
	}

	private static Geometry readGeometryValue(Reader reader) {
		if (reader.u32() == 0) return null;
		String kind = reader.string();
		String reason = reader.string();
		String input = reader.string();
		String output = reader.string();
		reader.u32(); reader.u32(); reader.u32(); reader.u32(); reader.u32(); reader.u32();
		reader.string();
		int vertices = reader.u32();
		int primitives = reader.u32();
		int instanceCount = reader.u32();
		int mappingCount = reader.count();
		List<Integer> mapping = new ArrayList<>(mappingCount);
		for (int i = 0; i < mappingCount; i++) mapping.add(reader.u32());
		boolean imageWrites = reader.u32() != 0;
		boolean ssboWrites = reader.u32() != 0;
		boolean atomics = reader.u32() != 0;
		boolean dynamicControlFlow = reader.u32() != 0;
		return new Geometry(kind, input, output, vertices, primitives, instanceCount, mapping, reason,
			imageWrites, ssboWrites, atomics, dynamicControlFlow);
	}

	private static final class Reader {
		private final ByteBuffer buffer;
		Reader(byte[] bytes) { buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
		int u32() { if (buffer.remaining() < 4) throw new IllegalArgumentException("truncated GLSL reflection"); return buffer.getInt(); }
		int count() { int value = u32(); if (value < 0 || value > buffer.remaining()) throw new IllegalArgumentException("invalid GLSL reflection count"); return value; }
		String string() { int length = u32(); if (length < 0 || length > buffer.remaining()) throw new IllegalArgumentException("invalid GLSL reflection string"); byte[] value = new byte[length]; buffer.get(value); return new String(value, StandardCharsets.UTF_8); }
		String bytes(int length) { if (buffer.remaining() < length) throw new IllegalArgumentException("truncated GLSL reflection"); byte[] value = new byte[length]; buffer.get(value); return new String(value, StandardCharsets.US_ASCII); }
		int remaining() { return buffer.remaining(); }
	}
}
