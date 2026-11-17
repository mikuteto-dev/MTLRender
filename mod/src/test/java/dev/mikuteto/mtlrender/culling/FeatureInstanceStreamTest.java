package dev.mikuteto.mtlrender.culling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import dev.mikuteto.mtlrender.culling.Visibility;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.junit.jupiter.api.Test;

class FeatureInstanceStreamTest {
	@Test
	void keepsSlotOrderAndPacksTheNativeRecord() {
		FeatureInstanceStream stream = new FeatureInstanceStream();
		stream.beginFrame();
		int first = stream.add(1.0F, 2.0F, 3.0F, 4.0F, 5.0F, 6.0F, FeatureInstanceStream.FLAG_OUTLINE);
		int second = stream.add(-1.0F, -2.0F, -3.0F, 0.0F, 1.0F, 2.0F, FeatureInstanceStream.FLAG_BLOCK_ENTITY);
		assertEquals(0, first);
		assertEquals(1, second);
		assertEquals(2, stream.count());
		ByteBuffer buffer = stream.buffer();
		assertEquals(FeatureInstanceStream.STRIDE * 2, buffer.remaining());
		assertEquals(1.0F, buffer.getFloat(0));
		assertEquals(0, buffer.getInt(24));
		assertEquals(FeatureInstanceStream.FLAG_OUTLINE, buffer.getInt(28));
		assertEquals(1, buffer.getInt(FeatureInstanceStream.STRIDE + 24));
	}

	@Test
	void preservesAllForceVisibleMetadataFlags() {
		FeatureInstanceStream stream = new FeatureInstanceStream();
		stream.beginFrame();
		int flags = FeatureInstanceStream.FLAG_BLOCK_ENTITY | FeatureInstanceStream.FLAG_OUTLINE
			| FeatureInstanceStream.FLAG_NAME_TAG | FeatureInstanceStream.FLAG_CAMERA
			| FeatureInstanceStream.FLAG_VEHICLE | FeatureInstanceStream.FLAG_SPECTATOR
			| FeatureInstanceStream.FLAG_SHADOW;
		stream.add(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, flags);
		assertEquals(flags, stream.instances().get(0).flags());
	}

	@Test
	void consumesOnePreviousMaskInSlotOrderAndKeepsForcedInstances() {
		FeatureInstanceStream stream = new FeatureInstanceStream();
		stream.beginFrame();
		stream.add(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0);
		stream.add(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, FeatureInstanceStream.FLAG_FORCE_VISIBLE);
		try (Arena arena = Arena.ofConfined()) {
			var mask = arena.allocate(8);
			mask.set(ValueLayout.JAVA_INT_UNALIGNED, 0, 0);
			mask.set(ValueLayout.JAVA_INT_UNALIGNED, 4, 0);
			stream.acceptPreviousMask(mask, 2);
		}
		assertTrue(!stream.visible(0));
		assertTrue(stream.visible(1));
	}

	@Test
	void activatesPreviousMaskBeforePreparationAndFailsOpenOnCountChange() {
		FeatureInstanceStream stream = new FeatureInstanceStream();
		stream.beginFrame();
		stream.add(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0);
		try (Arena arena = Arena.ofConfined()) {
			stream.acceptPreviousMask(arena.allocate(4), 1);
		}
		stream.beginFrame();
		stream.add(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0);
		stream.activatePendingMask();
		assertTrue(!stream.visible(0));
		stream.beginFrame();
		stream.add(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0);
		stream.add(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0);
		stream.activatePendingMask();
		assertTrue(stream.visible(0));
		assertTrue(stream.visible(1));
	}

	@Test
	void maskIsOnlyUsableForThePreparingFrame() {
		FeatureInstanceStream stream = new FeatureInstanceStream();
		stream.beginFrame();
		stream.add(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0);
		stream.activatePendingMask();
		assertTrue(stream.maskMatches(stream.frameId()));
		stream.beginFrame();
		assertTrue(!stream.maskMatches(stream.frameId() - 1L));
	}

	@Test
	void exceptionMetadataForcesAllMainCameraVisibilityCases() {
		EntityRenderState state = new EntityRenderState();
		state.outlineColor = 1;
		state.nameTag = net.minecraft.network.chat.Component.literal("name");
		state.shadowRadius = 1.0F;
		int flags = Visibility.exceptionFlags(state, true, true, true);
		int expected = FeatureInstanceStream.FLAG_OUTLINE | FeatureInstanceStream.FLAG_NAME_TAG
			| FeatureInstanceStream.FLAG_CAMERA | FeatureInstanceStream.FLAG_VEHICLE
			| FeatureInstanceStream.FLAG_SPECTATOR | FeatureInstanceStream.FLAG_SHADOW
			| FeatureInstanceStream.FLAG_FORCE_VISIBLE;
		assertEquals(expected, flags);
		assertTrue(Visibility.appliesMask(Visibility.Pass.MAIN_CAMERA));
		assertTrue(!Visibility.appliesMask(Visibility.Pass.SHADOW));
	}

	@Test
	void reportsMaskAccountingWithoutKeepingPerInstanceResults() {
		FeatureInstanceStream stream = new FeatureInstanceStream();
		stream.beginFrame();
		stream.add(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0);
		stream.add(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, FeatureInstanceStream.FLAG_FORCE_VISIBLE);
		try (Arena arena = Arena.ofConfined()) {
			var mask = arena.allocate(8);
			mask.set(ValueLayout.JAVA_INT_UNALIGNED, 0, 0);
			mask.set(ValueLayout.JAVA_INT_UNALIGNED, 4, 0);
			stream.markMaskRequested();
			stream.acceptPreviousMask(mask, 2);
		}
		assertEquals(1L, stream.maskRejected());
		assertEquals(1L, stream.forcedVisible());
		assertTrue(stream.maskRequested());
		assertTrue(stream.maskResultReady());
		assertTrue(!stream.failOpen());
	}

	@Test
	void invalidBoundsFailOpenAndFrameResetInvalidatesOldView() {
		FeatureInstanceStream stream = new FeatureInstanceStream();
		stream.beginFrame();
		int slot = stream.add(Float.NaN, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0);
		assertEquals(0, slot);
		assertTrue(stream.instances().get(0).forceVisible());
		stream.markFailOpen();
		assertTrue(stream.failOpen());
		assertTrue(stream.visible(0));
		stream.invalidate();
		assertEquals(0, stream.count());
		assertTrue(!stream.valid());
	}
}
