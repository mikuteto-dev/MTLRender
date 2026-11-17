package dev.mikuteto.mtlrender.pack.gbuffer;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuBufferSlice.MappedView;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GbufferDrawBridgeTest {
	@Test
	void providerIsFrameLocalAndRemovalCannotLeaveAValue() {
		GbufferDrawState state = GbufferDrawState.zero();
		GpuBufferSlice slice = new GpuBufferSlice(new FakeBuffer(), 16, 32);
		GbufferDrawBridge.Provider provider = () -> Optional.of(new GbufferDrawBridge.Draw(state, "Globals", slice));
		GbufferDrawBridge.install(provider);
		assertEquals(slice, GbufferDrawBridge.current().orElseThrow().uniformSlice());
		GbufferDrawBridge.remove(provider);
		assertTrue(GbufferDrawBridge.current().isEmpty());
	}

	@Test
	void emptyProviderDoesNotProduceAUniformUpdate() {
		GbufferDrawBridge.Provider provider = Optional::<GbufferDrawBridge.Draw>empty;
		GbufferDrawBridge.install(provider);
		assertTrue(GbufferDrawBridge.current().isEmpty());
		GbufferDrawBridge.clear();
	}

	private static final class FakeBuffer extends GpuBuffer {
		private FakeBuffer() {
			super(USAGE_UNIFORM, 48);
		}

		@Override
		public boolean isClosed() {
			return false;
		}

		@Override
		public void close() {
		}

		@Override
		public MappedView map(long offset, long length, boolean read, boolean write) {
			return null;
		}
	}
}
