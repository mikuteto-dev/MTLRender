package dev.mikuteto.mtlrender.backend;

import java.lang.foreign.MemorySegment;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;

final class DeviceBuffer extends MetalBuffer {
	static final long RENAME_LIMIT = 4L * 1024 * 1024;

	private static final Runnable NOTHING = () -> {
	};

	private final AllocationPool pool;
	private boolean closed;
	private boolean gpuWritten;

	DeviceBuffer(AllocationPool pool, int usage, long size) {
		super(pool.take(size), usage, size);
		this.pool = pool;
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	@Override
	public void close() {
		if (!closed) {
			closed = true;
			pool.retire(allocation(), size());
		}
	}

	void markGpuWritten() {
		gpuWritten = true;
	}

	boolean cpuWritten() {
		return !closed && !gpuWritten;
	}

	boolean rename(AllocationPool pool, long offset, MemorySegment data) {
		long length = data.byteSize();
		if (closed || gpuWritten || size() > RENAME_LIMIT || (usage() & (GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE)) != 0
			|| offset < 0 || offset + length > size()) {
			return false;
		}
		NativeGpu.Allocation next = pool.take(size());
		if (offset > 0) {
			MemorySegment.copy(contents(), 0, next.contents(), 0, offset);
		}
		if (offset + length < size()) {
			MemorySegment.copy(contents(), offset + length, next.contents(), offset + length, size() - (offset + length));
		}
		MemorySegment.copy(data, 0, next.contents(), offset, length);
		pool.retire(swap(next), size());
		return true;
	}

	@Override
	public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
		return new GpuBufferSlice.MappedView(slice(offset, length), view(offset, length), NOTHING);
	}
}
