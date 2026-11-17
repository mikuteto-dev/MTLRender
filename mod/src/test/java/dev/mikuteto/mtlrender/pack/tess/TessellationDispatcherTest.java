package dev.mikuteto.mtlrender.pack.tess;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TessellationDispatcherTest {
	@Test
	void recordsTwoDispatchesAndOneIndexedDraw() {
		List<String> events = new ArrayList<>();
		TessellationBuffers buffers = new TessellationBuffers(4, 32, 8, 12,
			(name, size, usage) -> new FakeBuffer(usage, size));
		TessellationDispatcher.Commands commands = new TessellationDispatcher.Commands() {
			@Override public void begin(boolean concurrent) { events.add("begin:" + concurrent); }
			@Override public void pipeline(long handle) { events.add("pipeline:" + handle); }
			@Override public void buffer(int slot, GpuBuffer buffer, long offset, dev.mikuteto.mtlrender.backend.MetalAccess.Access access) {
				events.add("buffer:" + slot + ":" + access);
			}
			@Override public void bytes(int slot, byte[] data) { events.add("bytes:" + slot); }
			@Override public void dispatch(int x, int y, int z) { events.add("dispatch:" + x + ":" + y + ":" + z); }
			@Override public void end() { events.add("end"); }
		};
		TessellationDispatcher dispatcher = new TessellationDispatcher(commands, new TessellationDispatcher.Kernels(11, 12, 4, 1, 1));
		TessellationDispatcher.Result result = dispatcher.dispatch(buffers, 12, new byte[16]);
		dispatcher.draw(result, new TessellationDispatcher.Draw() {
			@Override public void vertex(GpuBufferSlice slice) { events.add("vertex:" + slice.length()); }
			@Override public void index(GpuBuffer buffer) { events.add("index:" + buffer.size()); }
			@Override public void indexed(int count, int instances, int first, int base, int firstInstance) {
				events.add("draw:" + count + ":" + instances + ":" + first + ":" + base + ":" + firstInstance);
			}
		});
		assertEquals(List.of("begin:false", "pipeline:11", "buffer:0:READ", "buffer:1:WRITE", "bytes:0",
			"dispatch:4:1:1", "pipeline:12", "buffer:0:READ", "buffer:1:WRITE", "buffer:2:WRITE", "buffer:3:WRITE", "buffer:4:READ",
			"bytes:5", "dispatch:4:1:1", "end", "vertex:512", "index:768", "draw:12:1:0:0:0"), events);
		buffers.close();
	}

	private static final class FakeBuffer extends GpuBuffer {
		private FakeBuffer(int usage, long size) { super(usage, size); }
		@Override public boolean isClosed() { return false; }
		@Override public void close() { }
		@Override public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) { return null; }
	}
}
