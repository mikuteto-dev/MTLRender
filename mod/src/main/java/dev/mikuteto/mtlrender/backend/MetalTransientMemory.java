package dev.mikuteto.mtlrender.backend;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.List;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.util.TransientBlockAllocator;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;

final class MetalTransientMemory implements TransientMemory, AutoCloseable {
	private static final long BLOCK_SIZE = 4L << 20;
	private static final long CPU_ALIGNMENT = 16;
	private static final long GPU_ALIGNMENT = Long.highestOneBit(Long.MAX_VALUE);
	private static final Runnable NOTHING = () -> {
	};

	private final TransientBlockAllocator<Long> cpu = new TransientBlockAllocator<>(
		BLOCK_SIZE, CPU_ALIGNMENT, TransientBlockAllocator.Allocator.create(MemoryUtil::nmemAlloc, MemoryUtil::nmemFree)
	);
	private final TransientBlockAllocator<NativeGpu.Allocation> shared;
	private long generation;

	MetalTransientMemory(AllocationPool pool) {
		this.shared = new TransientBlockAllocator<>(
			BLOCK_SIZE, GPU_ALIGNMENT,
			TransientBlockAllocator.Allocator.create(pool::take, block -> pool.retire(block, block.contents().byteSize()))
		);
	}

	Runnable endSubmit() {
		cpu.rotate().run();
		generation++;
		return shared.rotate();
	}

	@Override
	public void close() {
		cpu.close();
		shared.close();
	}

	@Override
	public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
		TransientBlockAllocator.Allocation<Long> allocation = cpu.allocate(size, alignment, minimumAllocation, elementSize);
		return MemoryUtil.memByteBuffer(allocation.block() + allocation.offset(), Math.toIntExact(allocation.size()));
	}

	@Override
	public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
		return allocate(size, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice allocateGpu(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
		return allocate(size, alignment, usage, minimumAllocation, elementSize).slice();
	}

	@Override
	public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
		return allocate(size, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
		return upload(data, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
		return upload(data, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, int usage) {
		return data.stream().map(buffer -> upload(List.of(buffer), alignment, usage, buffer.remaining(), 1)).toList();
	}

	@Override
	public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, int usage) {
		return multiUploadStaging(data, alignment, usage);
	}

	private GpuBufferSlice.MappedView allocate(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
		TransientBlockAllocator.Allocation<NativeGpu.Allocation> allocation = shared.allocate(size, alignment, minimumAllocation, elementSize);
		TransientBuffer buffer = new TransientBuffer(allocation.block(), usage, generation);
		GpuBufferSlice slice = new GpuBufferSlice(buffer, allocation.offset(), allocation.size());
		return new GpuBufferSlice.MappedView(slice, buffer.view(allocation.offset(), allocation.size()), NOTHING);
	}

	private GpuBufferSlice upload(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
		long total = 0;
		for (ByteBuffer buffer : data) {
			total = Mth.roundToward(total + buffer.remaining(), alignment);
		}
		GpuBufferSlice.MappedView mapped = allocate(total, alignment, usage, minimumAllocation, elementSize);
		MemorySegment target = MemorySegment.ofBuffer(mapped.data());
		long offset = 0;
		for (ByteBuffer buffer : data) {
			long length = Math.min(target.byteSize() - offset, buffer.remaining());
			MemorySegment.copy(MemorySegment.ofBuffer(buffer), 0, target, offset, length);
			offset = Mth.roundToward(offset + buffer.remaining(), alignment);
			if (offset >= target.byteSize()) {
				break;
			}
		}
		return mapped.slice();
	}

	private final class TransientBuffer extends MetalBuffer {
		private final long bufferGeneration;
		private boolean closed;

		TransientBuffer(NativeGpu.Allocation block, int usage, long bufferGeneration) {
			super(block, usage, block.contents().byteSize());
			this.bufferGeneration = bufferGeneration;
		}

		@Override
		public boolean isClosed() {
			closed |= bufferGeneration < generation;
			return closed;
		}

		@Override
		public void close() {
			closed = true;
		}

		@Override
		public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
			throw new IllegalStateException("Cannot map transient buffer");
		}

		@Override
		public GpuBufferSlice slice(long offset, long length) {
			throw new IllegalStateException("Cannot slice transient buffer");
		}

		@Override
		public GpuBufferSlice slice() {
			throw new IllegalStateException("Cannot slice transient buffer");
		}
	}
}
