package dev.mikuteto.mtlrender.pack.tess;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.mikuteto.mtlrender.backend.MetalAccess;

import java.util.Objects;

public final class TessellationBuffers implements AutoCloseable {
	public static final int FACTOR_STRIDE = 24;
	public static final int COORDINATE_STRIDE = 16;
	public static final int INDEX_STRIDE = 16;
	public static final int INDIRECT_STRIDE = 16;
	public static final int MAX_PATCHES = 1 << 20;
	public static final int MAX_FACTOR = 64;

	public static void validateFactors(float[] factors) {
		Objects.requireNonNull(factors, "factors");
		if (factors.length == 0) throw new IllegalArgumentException("tessellation factors must not be empty");
		for (float factor : factors) {
			if (!Float.isFinite(factor) || factor <= 0.0f || factor > MAX_FACTOR) {
				throw new IllegalArgumentException("tessellation factor is outside the supported range");
			}
		}
	}

	@FunctionalInterface
	public interface Allocator {
		GpuBuffer allocate(String name, long size, int usage);
	}

	public record Layout(long patchBytes, long factorBytes, long coordinateBytes, long indexBytes, long indirectBytes) {
		public Layout {
			if (patchBytes <= 0 || factorBytes <= 0 || coordinateBytes <= 0 || indexBytes <= 0 || indirectBytes <= 0) {
				throw new IllegalArgumentException("tessellation buffer sizes must be positive");
			}
		}
	}

	private final GpuBuffer patchInput;
	private final GpuBuffer factorControl;
	private final GpuBuffer coordinates;
	private final GpuBuffer indices;
	private final GpuBuffer indirect;
	private final long patchCount;
	private boolean closed;

	public TessellationBuffers(int patchCount, int patchVertexBytes, int coordinateCount, int indexCount) {
		this(patchCount, patchVertexBytes, coordinateCount, indexCount, (name, size, usage) -> MetalAccess.createStorageBuffer(size));
	}

	public TessellationBuffers(int patchCount, int patchVertexBytes, int coordinateCount, int indexCount, Allocator allocator) {
		this(allocateLayout(patchCount, patchVertexBytes, coordinateCount, indexCount), patchCount, allocator);
	}

	public TessellationBuffers(Layout layout, int patchCount, Allocator allocator) {
		this.patchCount = checkedPatches(patchCount);
		Objects.requireNonNull(layout, "layout");
		Objects.requireNonNull(allocator, "allocator");
		this.patchInput = allocator.allocate("tessellation patch input", layout.patchBytes(), GpuBuffer.USAGE_COPY_DST);
		try {
			this.factorControl = allocator.allocate("tessellation factors and control points", layout.factorBytes(), GpuBuffer.USAGE_COPY_DST);
			this.coordinates = allocator.allocate("tessellation coordinates", layout.coordinateBytes(), GpuBuffer.USAGE_VERTEX);
			this.indices = allocator.allocate("tessellation indices", layout.indexBytes(), GpuBuffer.USAGE_INDEX);
			this.indirect = allocator.allocate("tessellation indirect arguments", layout.indirectBytes(), GpuBuffer.USAGE_INDIRECT_PARAMETERS);
		} catch (RuntimeException failure) {
			patchInput.close();
			throw failure;
		}
	}

	public static Layout allocateLayout(int patchCount, int patchVertexBytes, int coordinateCount, int indexCount) {
		long patches = checkedPatches(patchCount);
		if (patchVertexBytes <= 0 || patchVertexBytes % 4 != 0 || coordinateCount <= 0 || indexCount <= 0) {
			throw new IllegalArgumentException("tessellation counts must be positive and aligned");
		}
		return new Layout(
			multiply(patches, patchVertexBytes, "patch input"),
			multiply(patches, FACTOR_STRIDE, "factor control"),
			multiply(multiply(patches, coordinateCount, "coordinate count"), COORDINATE_STRIDE, "coordinates"),
			multiply(multiply(patches, indexCount, "index count"), INDEX_STRIDE, "indices"),
			multiply(patches, INDIRECT_STRIDE + 8, "indirect arguments")
		);
	}

	private static long checkedPatches(int value) {
		if (value <= 0 || value > MAX_PATCHES) throw new IllegalArgumentException("patch count is outside the tessellation limit");
		return value;
	}

	private static long multiply(long left, long right, String name) {
		try {
			long value = Math.multiplyExact(left, right);
			return value;
		} catch (ArithmeticException failure) {
			throw new IllegalArgumentException(name + " size overflows", failure);
		}
	}

	public long patchCount() { return patchCount; }
	public GpuBuffer patchInput() { return patchInput; }
	public GpuBuffer factorControl() { return factorControl; }
	public GpuBuffer coordinates() { return coordinates; }
	public GpuBuffer indices() { return indices; }
	public GpuBuffer indirect() { return indirect; }
	public GpuBufferSlice patchInputSlice(long offset, long length) { return slice(patchInput, offset, length, "patch input"); }
	public GpuBufferSlice factorControlSlice(long offset, long length) { return slice(factorControl, offset, length, "factor control"); }
	public GpuBufferSlice coordinateSlice(long offset, long length) { return slice(coordinates, offset, length, "coordinates"); }
	public GpuBufferSlice indexSlice(long offset, long length) { return slice(indices, offset, length, "indices"); }
	public GpuBufferSlice indirectSlice(long offset, long length) { return slice(indirect, offset, length, "indirect arguments"); }
	public long offsetsOffset() { return patchCount * INDIRECT_STRIDE; }

	private static GpuBufferSlice slice(GpuBuffer buffer, long offset, long length, String name) {
		if (offset < 0 || length <= 0 || offset > buffer.size() || length > buffer.size() - offset) {
			throw new IllegalArgumentException(name + " slice is outside its buffer");
		}
		return new GpuBufferSlice(buffer, offset, length);
	}

	@Override
	public void close() {
		if (closed) return;
		closed = true;
		Runnable release = () -> {
			patchInput.close();
			factorControl.close();
			coordinates.close();
			indices.close();
			indirect.close();
		};
		if (!MetalAccess.addCompletion(release)) release.run();
	}
}
