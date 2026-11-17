package dev.mikuteto.mtlrender.backend;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.OptionalLong;

import com.mojang.blaze3d.systems.GpuQueryPool;

final class MetalQueryPool implements GpuQueryPool {
	private static final long NOT_WRITTEN = -1;

	private final NativeGpu gpu;
	private final long handle;
	private final long[] writtenFrames;
	private boolean closed;

	MetalQueryPool(NativeGpu gpu, int size) {
		this.gpu = gpu;
		this.handle = gpu.createQueryPool(size);
		if (handle == 0) {
			throw new IllegalStateException("Failed to create a Metal timestamp heap of " + size + " entries");
		}
		this.writtenFrames = new long[size];
		Arrays.fill(writtenFrames, NOT_WRITTEN);
	}

	static MetalQueryPool cast(GpuQueryPool pool) {
		return (MetalQueryPool) pool;
	}

	long handle() {
		return handle;
	}

	void markWritten(int index, long frame) {
		writtenFrames[index] = frame;
	}

	@Override
	public int size() {
		return writtenFrames.length;
	}

	@Override
	public OptionalLong getValue(int index) {
		return getValues(index, 1)[0];
	}

	@Override
	public OptionalLong[] getValues(int index, int count) {
		if (index < 0 || count < 0 || index + count > writtenFrames.length) {
			throw new IndexOutOfBoundsException("getValues(" + index + ", " + count + ") on a pool of " + writtenFrames.length);
		}
		OptionalLong[] result = new OptionalLong[count];
		Arrays.fill(result, OptionalLong.empty());
		long completed = gpu.refreshCompletedFrames();
		for (int i = 0; i < count; i++) {
			long frame = writtenFrames[index + i];
			if (frame == NOT_WRITTEN || frame >= completed) {
				return result;
			}
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment values = arena.allocate(ValueLayout.JAVA_LONG, Math.max(count, 1));
			if (count > 0 && gpu.resolveQueries(handle, index, count, values)) {
				for (int i = 0; i < count; i++) {
					result[i] = OptionalLong.of(values.getAtIndex(ValueLayout.JAVA_LONG, i));
				}
			}
		}
		return result;
	}

	@Override
	public void close() {
		if (!closed) {
			closed = true;
			gpu.destroy(NativeGpu.QUERY, handle);
		}
	}
}
