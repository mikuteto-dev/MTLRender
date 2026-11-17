package dev.mikuteto.mtlrender.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.junit.jupiter.api.Test;

class MetalFxCompositionTest {
	@Test
	void inactiveCompositionHasNoTarget() {
		MetalFx.Composition composition = new MetalFx.Composition(null, 0, 0, false, 4L);

		assertFalse(composition.active());
		assertEquals(0, composition.width());
		assertEquals(0, composition.height());
		assertEquals(4L, composition.generation());
	}

	@Test
	void activeCompositionPreservesNativeTargetIdentity() {
		RenderTarget target = target();
		MetalFx.Composition composition = new MetalFx.Composition(target, 1280, 720, true, 5L);

		assertTrue(composition.active());
		assertSame(target, composition.nativeTarget());
		assertEquals(1280, composition.width());
		assertEquals(720, composition.height());
	}

	@Test
	void resizeAndTransitionUseNewGenerations() {
		RenderTarget target = target();
		MetalFx.Composition first = new MetalFx.Composition(target, 1280, 720, true, 1L);
		MetalFx.Composition resized = new MetalFx.Composition(target, 1920, 1080, true, 2L);
		MetalFx.Composition inactive = new MetalFx.Composition(null, 0, 0, false, 3L);
		MetalFx.Composition activeAgain = new MetalFx.Composition(target, 1920, 1080, true, 4L);

		assertTrue(resized.generation() > first.generation());
		assertTrue(inactive.generation() > resized.generation());
		assertTrue(activeAgain.generation() > inactive.generation());
		assertFalse(inactive.active());
	}

	private static RenderTarget target() {
		return new RenderTarget("MetalFX test", false, GpuFormat.RGBA8_UNORM) {
		};
	}
}
