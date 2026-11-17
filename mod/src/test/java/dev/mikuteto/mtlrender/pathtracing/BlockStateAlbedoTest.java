package dev.mikuteto.mtlrender.pathtracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BlockStateAlbedoTest {
	@Test
	void opaqueAverageUsesLinearChannels() {
		BlockStateAlbedo.Sample sample = BlockStateAlbedo.alphaWeightedAverage(new int[]{0xFFFF0000, 0xFF0000FF});
		assertEquals(0.5F, sample.red(), 0.00001F);
		assertEquals(0.5F, sample.blue(), 0.00001F);
		assertEquals(0.0F, sample.green(), 0.00001F);
		assertEquals(1.0F, sample.weight(), 0.00001F);
	}

	@Test
	void transparentPixelsDoNotAffectTheAverage() {
		BlockStateAlbedo.Sample sample = BlockStateAlbedo.alphaWeightedAverage(new int[]{0x00FFFFFF, 0xFF204060});
		assertEquals(BlockStateAlbedo.srgbToLinear(0x20 / 255.0F), sample.red(), 0.00001F);
		assertEquals(0.5F, sample.weight(), 0.00001F);
	}

	@Test
	void tintIsMultipliedInLinearSpace() {
		BlockStateAlbedo.Sample sample = BlockStateAlbedo.multiplyTint(
			new BlockStateAlbedo.Sample(0.5F, 0.5F, 0.5F, 1.0F), 0xFFFF8000
		);
		assertEquals(0.5F, sample.red(), 0.00001F);
		assertEquals(0.5F * BlockStateAlbedo.srgbToLinear(128.0F / 255.0F), sample.green(), 0.00001F);
		assertEquals(0.0F, sample.blue(), 0.00001F);
	}

	@Test
	void srgbConversionHasTheTransferCurveBoundary() {
		float encoded = 0.04045F;
		float linear = BlockStateAlbedo.srgbToLinear(encoded);
		assertTrue(linear > 0.0F);
		assertEquals(encoded / 12.92F, linear, 0.00001F);
		assertEquals(encoded, BlockStateAlbedo.linearToSrgb(linear), 0.00001F);
	}
}
