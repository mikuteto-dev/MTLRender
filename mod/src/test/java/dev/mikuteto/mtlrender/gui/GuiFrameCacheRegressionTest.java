package dev.mikuteto.mtlrender.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikuteto.mtlrender.debug.GuiFrameCacheStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class GuiFrameCacheRegressionTest {
	@AfterEach
	void disableStats() {
		GuiFrameCacheStats.stopObserving();
		GuiFrameCacheStats.reset();
	}

	@Test
	void extractionAndUpdateContinueAcrossAHitWhilePreparationAndEncodingDoNot() {
		GuiFrameCacheStats.observe();
		GuiFrameCacheStats.reset();
		GuiFrameCache<String> cache = new GuiFrameCache<>();
		GuiFrameCacheKey key = GuiFrameCacheKey.staticKey(11L, 22L);
		cache.publish(key, "prepared");

		GuiFrameCacheStats.recordExtraction();
		GuiFrameCacheStats.recordGuiUpdate();
		GuiFrameCache.Lease<String> hit = cache.lookup(key);
		assertEquals("prepared", hit.replayableValue());
		cache.recordReplay();
		hit.close();

		GuiFrameCacheStats.recordExtraction();
		GuiFrameCacheStats.recordGuiUpdate();
		GuiFrameCache.Lease<String> miss = cache.lookup(GuiFrameCacheKey.dynamicKey(11L, 22L, 1L));
		assertEquals(null, miss);
		GuiFrameCacheStats.recordEncoding();
		cache.recordPreparation();

		GuiFrameCacheStats.Snapshot stats = GuiFrameCacheStats.snapshot();
		assertEquals(2L, stats.extractions());
		assertEquals(2L, stats.guiUpdates());
		assertEquals(1L, stats.keyHits());
		assertEquals(1L, stats.keyMisses());
		assertEquals(1L, stats.preparations());
		assertEquals(1L, stats.encodings());
		assertEquals(1L, stats.replays());
		assertTrue(stats.extractions() > stats.preparations());

		GuiFrameCache.Lease<String> released = cache.lookup(key);
		released.close();
		assertThrows(IllegalStateException.class, released::replayableValue);
		assertEquals(1L, GuiFrameCacheStats.snapshot().inFlightLeaseFailures());
	}

	@Test
	void dynamicKeyAndInvalidationMatrixAreObservable() {
		GuiFrameCacheStats.observe();
		GuiFrameCacheStats.reset();
		GuiFrameCacheKey base = GuiFrameCacheKey.staticKey(4L, 8L);
		GuiFrameCacheKey[] dynamicKeys = {
			GuiFrameCacheKey.dynamicKey(4L, 8L, 1L),
			GuiFrameCacheKey.dynamicKey(4L, 8L, 2L),
			GuiFrameCacheKey.staticKey(5L, 8L),
			GuiFrameCacheKey.staticKey(4L, 9L)
		};
		GuiFrameCache<String> cache = new GuiFrameCache<>();
		cache.publish(base, "frame");
		for (GuiFrameCacheKey key : dynamicKeys) {
			assertEquals(null, cache.lookup(key));
		}
		for (GuiFrameCacheStats.InvalidationReason reason : GuiFrameCacheStats.InvalidationReason.values()) {
			GuiFrameCacheStats.recordInvalidation(reason);
		}

		GuiFrameCacheStats.Snapshot stats = GuiFrameCacheStats.snapshot();
		assertEquals(dynamicKeys.length, stats.keyMisses());
		for (GuiFrameCacheStats.InvalidationReason reason : GuiFrameCacheStats.InvalidationReason.values()) {
			assertEquals(1L, stats.invalidations(reason));
		}
	}
}
