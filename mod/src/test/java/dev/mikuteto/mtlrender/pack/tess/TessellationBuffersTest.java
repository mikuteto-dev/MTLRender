package dev.mikuteto.mtlrender.pack.tess;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class TessellationBuffersTest {
	@Test
	void allocatesPerPatchStorageWithRequiredAlignment() {
		List<Long> sizes = new ArrayList<>();
		try (TessellationBuffers buffers = new TessellationBuffers(3, 32, 10, 12,
			(name, size, usage) -> {
				sizes.add(size);
				return new FakeBuffer(usage, size);
			})) {
			assertEquals(List.of(96L, 72L, 480L, 576L, 72L), sizes);
			assertEquals(3, buffers.patchCount());
			assertEquals(480, buffers.coordinateSlice(0, 480).length());
		}
	}

	@Test
	void rejectsOverflowAndInvalidPatchCounts() {
		assertThrows(IllegalArgumentException.class, () -> TessellationBuffers.allocateLayout(0, 32, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> TessellationBuffers.allocateLayout(1, Integer.MAX_VALUE, Integer.MAX_VALUE, 1));
	}

	private static final class FakeBuffer extends GpuBuffer {
		private boolean closed;

		private FakeBuffer(int usage, long size) { super(usage, size); }
		@Override public boolean isClosed() { return closed; }
		@Override public void close() { closed = true; }
		@Override public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) { return null; }
	}
}
