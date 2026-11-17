package dev.mikuteto.mtlrender.debug;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MeshCheckTest {
	private static final String PROPERTY = "mtlrender.debug.meshCheck";

	@AfterEach
	void clearProperty() {
		System.clearProperty(PROPERTY);
	}

	@Test
	void disabledComparisonDoesNotInspectPixels() {
		MeshCheck.ImageComparison result = MeshCheck.compare(1, 1, null, null);
		assertFalse(result.enabled());
		assertFalse(result.outsideLimit());
	}

	@Test
	void identicalImagesMatch() {
		enable();
		MeshCheck.ImageComparison result = MeshCheck.compare(2, 1, new int[] {0xff102030, 0xff405060}, new int[] {0xff102030, 0xff405060});
		assertTrue(result.shapeMatch());
		assertEquals(0, result.mismatchPixels());
		assertEquals(0.0, result.mismatchPercent());
		assertEquals(0, result.maximumChannelDifference());
		assertFalse(result.outsideLimit());
	}

	@Test
	void differenceOfEightIsUnchanged() {
		enable();
		MeshCheck.ImageComparison result = MeshCheck.compare(1, 1, new int[] {0xff000000}, new int[] {0xff080000});
		assertEquals(0, result.mismatchPixels());
		assertEquals(8, result.maximumChannelDifference());
	}

	@Test
	void differenceGreaterThanEightIsChanged() {
		enable();
		MeshCheck.ImageComparison result = MeshCheck.compare(1, 1, new int[] {0xff000000}, new int[] {0xff090000});
		assertEquals(1, result.mismatchPixels());
		assertEquals(100.0, result.mismatchPercent());
		assertTrue(result.outsideLimit());
	}

	@Test
	void alphaOnlyDifferenceIsIgnored() {
		enable();
		MeshCheck.ImageComparison result = MeshCheck.compare(1, 1, new int[] {0x00010203}, new int[] {0xff010203});
		assertEquals(0, result.mismatchPixels());
		assertEquals(0, result.maximumChannelDifference());
		assertFalse(result.outsideLimit());
	}

	@Test
	void shapeAndLengthMismatchIsOutsideLimit() {
		enable();
		MeshCheck.ImageComparison result = MeshCheck.compare(2, 1, new int[] {0}, new int[] {0, 0});
		assertFalse(result.shapeMatch());
		assertTrue(result.outsideLimit());
	}

	@Test
	void layerCountsUseChunkSectionLayerOrder() {
		MeshCheck.LayerCounts counts = new MeshCheck.LayerCounts(1, 2, 3);
		assertArrayEquals(new long[] {1, 2, 3}, counts.inChunkSectionLayerOrder());
		assertFalse(MeshCheck.sameLayerCounts(counts, new MeshCheck.LayerCounts(1, 2, 4)));
		assertTrue(MeshCheck.sameLayerCounts(counts, new MeshCheck.LayerCounts(1, 2, 3)));
	}

	private static void enable() {
		System.setProperty(PROPERTY, "true");
	}
}
