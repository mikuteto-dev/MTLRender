package dev.mikuteto.mtlrender.backend;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.mojang.blaze3d.buffers.GpuBuffer;

abstract class MetalBuffer extends GpuBuffer {
	private NativeGpu.Allocation allocation;

	MetalBuffer(NativeGpu.Allocation allocation, int usage, long size) {
		super(usage, size);
		this.allocation = allocation;
	}

	static long handle(GpuBuffer buffer) {
		return ((MetalBuffer) buffer).allocation.handle();
	}

	NativeGpu.Allocation swap(NativeGpu.Allocation next) {
		NativeGpu.Allocation previous = allocation;
		allocation = next;
		return previous;
	}

	NativeGpu.Allocation allocation() {
		return allocation;
	}

	long handle() {
		return allocation.handle();
	}

	long address() {
		return allocation.address();
	}

	MemorySegment contents() {
		return allocation.contents();
	}

	ByteBuffer view(long offset, long length) {
		return allocation.contents().asSlice(offset, length).asByteBuffer().order(ByteOrder.nativeOrder());
	}
}
