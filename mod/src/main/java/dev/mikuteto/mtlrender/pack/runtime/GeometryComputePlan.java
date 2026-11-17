package dev.mikuteto.mtlrender.pack.runtime;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record GeometryComputePlan(Context context, List<Stage> stages, List<Buffer> buffers,
	long primitiveCount, long maxVertices, long maxPrimitives, boolean allowConcurrentCompute) {
	public enum StageKind { VERTEX_PREPASS, OUTPUT_COUNT, PREFIX_SUM, GEOMETRY_EXPANSION, INDIRECT_DRAW }
	public enum BufferKind { INPUT_VERTICES, VERTEX_RECORDS, PRIMITIVE_RECORDS, OUTPUT_COUNTS, PREFIX_SUMS,
		OUTPUT_VERTICES, OUTPUT_INDICES, INDIRECT_ARGUMENTS }
	public enum Access { READ, WRITE, READ_WRITE }

	public record Context(String pack, String dimension, String program, String stage) {
		public Context {
			pack = name(pack, "pack");
			dimension = Objects.requireNonNull(dimension, "dimension");
			program = name(program, "program");
			stage = name(stage, "stage");
		}
	}

	public record Buffer(BufferKind kind, long offset, long size, int stride, Access access) {
		public Buffer {
			Objects.requireNonNull(kind, "buffer kind");
			if (offset < 0 || size <= 0 || stride <= 0) throw new IllegalArgumentException("invalid geometry buffer");
			Objects.requireNonNull(access, "buffer access");
		}
	}

	public record Stage(StageKind kind, Set<BufferKind> reads, Set<BufferKind> writes,
		Set<GeometryPlan.SideEffect> sideEffects) {
		public Stage {
			Objects.requireNonNull(kind, "stage kind");
			reads = Set.copyOf(Objects.requireNonNull(reads, "stage reads"));
			writes = Set.copyOf(Objects.requireNonNull(writes, "stage writes"));
			sideEffects = normalizeEffects(Objects.requireNonNull(sideEffects, "stage side effects"));
		}
	}

	public record Input(long primitiveCount, int vertexStride, int primitiveStride) {
		public Input {
			if (primitiveCount <= 0 || vertexStride <= 0 || primitiveStride <= 0) throw new IllegalArgumentException("invalid geometry input");
		}
	}

	public record Limits(long maxBufferSize) {
		public Limits { if (maxBufferSize <= 0) throw new IllegalArgumentException("device buffer limit must be positive"); }
	}

	public record DrawArguments(int indexCount, int instanceCount, int firstIndex, int baseVertex, int baseInstance) {}

	public GeometryComputePlan {
		context = Objects.requireNonNull(context, "context");
		stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
		buffers = List.copyOf(Objects.requireNonNull(buffers, "buffers"));
		if (primitiveCount <= 0 || maxVertices <= 0 || maxPrimitives <= 0) throw new IllegalArgumentException("invalid geometry plan limits");
		if (stages.size() != 5) throw new IllegalArgumentException("geometry compute stage count must be five");
	}

	public static GeometryComputePlan build(Context context, GeometryPlan geometry, Input input, Limits limits,
		boolean allowConcurrentCompute) {
		Objects.requireNonNull(geometry, "geometry");
		Objects.requireNonNull(input, "input");
		Objects.requireNonNull(limits, "limits");
		if (geometry.path() != GeometryPlan.GeometryPath.COMPUTE) throw new IllegalArgumentException("geometry plan is not compute emulation");
		long vertices = multiply(input.primitiveCount(), geometry.outputBudget().vertices());
		long primitives = multiply(input.primitiveCount(), geometry.outputBudget().primitives());
		long inputSize = multiply(input.primitiveCount(), input.primitiveStride());
		long vertexRecords = multiply(vertices, input.vertexStride());
		long primitiveRecords = multiply(primitives, input.primitiveStride());
		long counts = multiply(input.primitiveCount(), Integer.BYTES);
		long prefix = counts;
		long outputVertices = vertexRecords;
		long outputIndices = multiply(primitives, 3L * Integer.BYTES);
		long indirect = 5L * Integer.BYTES;
		long[] sizes = {inputSize, vertexRecords, primitiveRecords, counts, prefix, outputVertices, outputIndices, indirect};
		long total = 0;
		for (long size : sizes) total = add(total, size);
		if (total > limits.maxBufferSize() || java.util.Arrays.stream(sizes).anyMatch(size -> size > limits.maxBufferSize())) throw new IllegalArgumentException("geometry buffers exceed device limit");
		long offset = 0;
		Buffer inputBuffer = buffer(BufferKind.INPUT_VERTICES, offset, inputSize, input.primitiveStride(), Access.READ); offset = add(offset, inputSize);
		Buffer verticesBuffer = buffer(BufferKind.VERTEX_RECORDS, offset, vertexRecords, input.vertexStride(), Access.WRITE); offset = add(offset, vertexRecords);
		Buffer primitivesBuffer = buffer(BufferKind.PRIMITIVE_RECORDS, offset, primitiveRecords, input.primitiveStride(), Access.WRITE); offset = add(offset, primitiveRecords);
		Buffer countsBuffer = buffer(BufferKind.OUTPUT_COUNTS, offset, counts, Integer.BYTES, Access.WRITE); offset = add(offset, counts);
		Buffer prefixBuffer = buffer(BufferKind.PREFIX_SUMS, offset, prefix, Integer.BYTES, Access.READ_WRITE); offset = add(offset, prefix);
		Buffer outputVerticesBuffer = buffer(BufferKind.OUTPUT_VERTICES, offset, outputVertices, input.vertexStride(), Access.WRITE); offset = add(offset, outputVertices);
		Buffer outputIndicesBuffer = buffer(BufferKind.OUTPUT_INDICES, offset, outputIndices, Integer.BYTES, Access.WRITE); offset = add(offset, outputIndices);
		Buffer indirectBuffer = buffer(BufferKind.INDIRECT_ARGUMENTS, offset, indirect, Integer.BYTES, Access.WRITE);
		Set<GeometryPlan.SideEffect> effects = geometry.sideEffects();
		List<Stage> stages = List.of(
			stage(StageKind.VERTEX_PREPASS, Set.of(BufferKind.INPUT_VERTICES), Set.of(BufferKind.VERTEX_RECORDS, BufferKind.PRIMITIVE_RECORDS), effects),
			stage(StageKind.OUTPUT_COUNT, Set.of(BufferKind.VERTEX_RECORDS, BufferKind.PRIMITIVE_RECORDS), Set.of(BufferKind.OUTPUT_COUNTS), effects),
			stage(StageKind.PREFIX_SUM, Set.of(BufferKind.OUTPUT_COUNTS), Set.of(BufferKind.PREFIX_SUMS), Set.of()),
			stage(StageKind.GEOMETRY_EXPANSION, Set.of(BufferKind.VERTEX_RECORDS, BufferKind.PREFIX_SUMS), Set.of(BufferKind.OUTPUT_VERTICES, BufferKind.OUTPUT_INDICES), effects),
			stage(StageKind.INDIRECT_DRAW, Set.of(BufferKind.PREFIX_SUMS), Set.of(BufferKind.INDIRECT_ARGUMENTS), Set.of()));
		return new GeometryComputePlan(context, stages, List.of(inputBuffer, verticesBuffer, primitivesBuffer, countsBuffer,
			prefixBuffer, outputVerticesBuffer, outputIndicesBuffer, indirectBuffer), input.primitiveCount(), vertices, primitives, allowConcurrentCompute);
	}

	public Optional<DrawArguments> drawArguments(long vertices, long primitives) {
		if (vertices <= 0 || primitives <= 0) return Optional.empty();
		long indices = multiply(primitives, 3);
		if (vertices > Integer.MAX_VALUE || indices > Integer.MAX_VALUE) return Optional.empty();
		return Optional.of(new DrawArguments((int) indices, 1, 0, 0, 0));
	}

	private static Stage stage(StageKind kind, Set<BufferKind> reads, Set<BufferKind> writes, Set<GeometryPlan.SideEffect> effects) {
		return new Stage(kind, reads, writes, effects);
	}
	private static Buffer buffer(BufferKind kind, long offset, long size, int stride, Access access) { return new Buffer(kind, offset, size, stride, access); }
	private static long multiply(long left, long right) { try { return Math.multiplyExact(left, right); } catch (ArithmeticException exception) { throw new IllegalArgumentException("geometry size overflow", exception); } }
	private static long add(long left, long right) { try { return Math.addExact(left, right); } catch (ArithmeticException exception) { throw new IllegalArgumentException("geometry size overflow", exception); } }
	private static Set<GeometryPlan.SideEffect> normalizeEffects(Set<GeometryPlan.SideEffect> values) { return values.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(values)); }
	private static String name(String value, String label) { Objects.requireNonNull(value, label); if (value.isBlank()) throw new IllegalArgumentException(label + " must not be blank"); return value; }
}
