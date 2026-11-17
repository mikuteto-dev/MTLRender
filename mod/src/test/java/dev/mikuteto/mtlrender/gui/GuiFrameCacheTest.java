package dev.mikuteto.mtlrender.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class GuiFrameCacheTest {
	private static GuiFrameCacheKey key() {
		return new GuiFrameCacheKey(1920, 1080, 2.0F, 3L, 4L, 5L, 6, 7, 8L, 9L, false, 0.1F, 0.2F, 0.3F, 1.0F, 10L, 11L, 12L);
	}

	@Test
	void equalKeysCompareByEveryValue() {
		assertEquals(key(), key());
		assertNotEquals(key(), new GuiFrameCacheKey(1921, 1080, 2.0F, 3L, 4L, 5L, 6, 7, 8L, 9L, false, 0.1F, 0.2F, 0.3F, 1.0F, 10L, 11L, 12L));
	}

	@Test
	void everyKeyFieldInvalidatesLookup() {
		GuiFrameCacheKey base = key();
		GuiFrameCacheKey[] variants = {
			new GuiFrameCacheKey(1921, 1080, 2.0F, 3L, 4L, 5L, 6, 7, 8L, 9L, false, 0.1F, 0.2F, 0.3F, 1.0F, 10L, 11L, 12L),
			new GuiFrameCacheKey(1920, 1081, 2.0F, 3L, 4L, 5L, 6, 7, 8L, 9L, false, 0.1F, 0.2F, 0.3F, 1.0F, 10L, 11L, 12L),
			new GuiFrameCacheKey(1920, 1080, 2.1F, 3L, 4L, 5L, 6, 7, 8L, 9L, false, 0.1F, 0.2F, 0.3F, 1.0F, 10L, 11L, 12L),
			new GuiFrameCacheKey(1920, 1080, 2.0F, 13L, 4L, 5L, 6, 7, 8L, 9L, false, 0.1F, 0.2F, 0.3F, 1.0F, 10L, 11L, 12L),
			new GuiFrameCacheKey(1920, 1080, 2.0F, 3L, 14L, 5L, 6, 7, 8L, 9L, false, 0.1F, 0.2F, 0.3F, 1.0F, 10L, 11L, 12L),
			new GuiFrameCacheKey(1920, 1080, 2.0F, 3L, 4L, 15L, 6, 7, 8L, 9L, false, 0.1F, 0.2F, 0.3F, 1.0F, 10L, 11L, 12L),
			new GuiFrameCacheKey(1920, 1080, 2.0F, 3L, 4L, 5L, 16, 7, 8L, 9L, false, 0.1F, 0.2F, 0.3F, 1.0F, 10L, 11L, 12L),
			new GuiFrameCacheKey(1920, 1080, 2.0F, 3L, 4L, 5L, 6, 17, 8L, 9L, false, 0.1F, 0.2F, 0.3F, 1.0F, 10L, 11L, 12L),
			new GuiFrameCacheKey(1920, 1080, 2.0F, 3L, 4L, 5L, 6, 7, 18L, 9L, false, 0.1F, 0.2F, 0.3F, 1.0F, 10L, 11L, 12L),
			new GuiFrameCacheKey(1920, 1080, 2.0F, 3L, 4L, 5L, 6, 7, 8L, 19L, false, 0.1F, 0.2F, 0.3F, 1.0F, 10L, 11L, 12L),
			new GuiFrameCacheKey(1920, 1080, 2.0F, 3L, 4L, 5L, 6, 7, 8L, 9L, true, 0.1F, 0.2F, 0.3F, 1.0F, 10L, 11L, 12L),
			new GuiFrameCacheKey(1920, 1080, 2.0F, 3L, 4L, 5L, 6, 7, 8L, 9L, false, 0.11F, 0.2F, 0.3F, 1.0F, 10L, 11L, 12L),
			new GuiFrameCacheKey(1920, 1080, 2.0F, 3L, 4L, 5L, 6, 7, 8L, 9L, false, 0.1F, 0.21F, 0.3F, 1.0F, 10L, 11L, 12L),
			new GuiFrameCacheKey(1920, 1080, 2.0F, 3L, 4L, 5L, 6, 7, 8L, 9L, false, 0.1F, 0.2F, 0.31F, 1.0F, 10L, 11L, 12L),
			new GuiFrameCacheKey(1920, 1080, 2.0F, 3L, 4L, 5L, 6, 7, 8L, 9L, false, 0.1F, 0.2F, 0.3F, 0.99F, 10L, 11L, 12L),
			new GuiFrameCacheKey(1920, 1080, 2.0F, 3L, 4L, 5L, 6, 7, 8L, 9L, false, 0.1F, 0.2F, 0.3F, 1.0F, 20L, 11L, 12L),
			new GuiFrameCacheKey(1920, 1080, 2.0F, 3L, 4L, 5L, 6, 7, 8L, 9L, false, 0.1F, 0.2F, 0.3F, 1.0F, 10L, 21L, 12L),
			new GuiFrameCacheKey(1920, 1080, 2.0F, 3L, 4L, 5L, 6, 7, 8L, 9L, false, 0.1F, 0.2F, 0.3F, 1.0F, 10L, 11L, 22L)
		};
		for (GuiFrameCacheKey variant : variants) {
			assertNotEquals(base, variant);
		}
	}

	@Test
	void replacementRetainsOldValueForLeases() {
		GuiFrameCache<String> cache = new GuiFrameCache<>();
		cache.publish(key(), "old");
		GuiFrameCache.Lease<String> lease = cache.lookup(key());
		cache.publish(new GuiFrameCacheKey(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, true, 1, 1, 1, 1, 1, 1, 1), "new");
		assertEquals("old", lease.replayableValue());
		assertEquals("new", cache.lookup(new GuiFrameCacheKey(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, true, 1, 1, 1, 1, 1, 1, 1)).replayableValue());
		lease.close();
	}

	@Test
	void invalidationMissesAndCountersAreDeterministic() {
		GuiFrameCache<String> cache = new GuiFrameCache<>();
		cache.publish(key(), "frame");
		cache.recordExtraction();
		cache.recordPreparation();
		cache.recordReplay();
		assertTrue(cache.lookup(key()) != null);
		cache.invalidate();
		assertEquals(null, cache.lookup(key()));
		assertEquals(new GuiFrameCache.Counters(1, 1, 1, 1, 1), cache.counters());
	}

	@Test
	void multipleLeasesRemainValidUntilEachRelease() {
		GuiFrameCache<String> cache = new GuiFrameCache<>();
		cache.publish(key(), "frame");
		GuiFrameCache.Lease<String> first = cache.lookup(key());
		GuiFrameCache.Lease<String> second = cache.lookup(key());
		cache.invalidate();
		first.close();
		assertEquals("frame", second.replayableValue());
		second.close();
		assertThrows(IllegalStateException.class, second::replayableValue);
	}
}
