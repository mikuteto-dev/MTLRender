package dev.mikuteto.mtlrender.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LodStoreTest {
	@Test
	void replacementKeepsOneValueAndReaccountsBytes() {
		LodData.LodSection first = section("overworld", 0);
		LodData.LodSection replacement = section("overworld", 0);
		LodStore store = new LodStore(first.estimatedBytes() * 2);

		assertTrue(store.put(first));
		assertEquals(first.estimatedBytes(), store.currentBytes());
		assertTrue(store.put(replacement));
		assertEquals(1, store.entries());
		assertEquals(replacement.estimatedBytes(), store.currentBytes());
		assertSame(replacement, store.get(replacement.key()).orElseThrow());
		assertEquals(0, store.evictions());
	}

	@Test
	void evictsLeastRecentlyUsedValue() {
		LodData.LodSection first = section("overworld", 0);
		LodData.LodSection second = section("overworld", 32);
		LodData.LodSection third = section("overworld", 64);
		LodStore store = new LodStore(first.estimatedBytes() * 2);

		store.put(first);
		store.put(second);
		assertSame(first, store.get(first.key()).orElseThrow());
		assertTrue(store.put(third));

		assertTrue(store.get(first.key()).isPresent());
		assertFalse(store.get(second.key()).isPresent());
		assertTrue(store.get(third.key()).isPresent());
		assertEquals(1, store.evictions());
		assertEquals(2, store.entries());
		assertTrue(store.currentBytes() <= store.budgetBytes());
	}

	@Test
	void rejectsOversizedValueWithoutChangingStore() {
		LodData.LodSection value = section("overworld", 0);
		LodStore store = new LodStore(value.estimatedBytes() - 1);

		assertFalse(store.put(value));
		assertEquals(0, store.entries());
		assertEquals(0, store.currentBytes());
		assertEquals(0, store.evictions());
	}

	@Test
	void dimensionsAreIsolatedAndCanBeCleared() {
		LodData.LodSection overworld = section("overworld", 0);
		LodData.LodSection nether = section("the_nether", 0);
		LodStore store = new LodStore(overworld.estimatedBytes() * 2);

		store.put(overworld);
		store.put(nether);
		assertEquals(1, store.clearByDimension("overworld"));
		assertFalse(store.get(overworld.key()).isPresent());
		assertTrue(store.get(nether.key()).isPresent());
		assertEquals(nether.estimatedBytes(), store.currentBytes());
		store.clear();
		assertEquals(0, store.entries());
		assertEquals(0, store.currentBytes());
	}

	private static LodData.LodSection section(String dimension, int originX) {
		LodData.LodKey key = new LodData.LodKey(dimension, 0, originX, 0, 0);
		return new LodData.LodSection(key, new int[LodData.LOD_VOXELS], LodData.Heightmap.lod(new int[LodData.LOD_SIZE * LodData.LOD_SIZE]));
	}
}
