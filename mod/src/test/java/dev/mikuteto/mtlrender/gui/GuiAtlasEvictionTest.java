package dev.mikuteto.mtlrender.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import org.junit.jupiter.api.Test;

final class GuiAtlasEvictionTest {
	@Test
	void itemAtlasReclaimsUnusedSlotsInStableOrder() {
		GuiItemAtlasState<String> atlas = new GuiItemAtlasState<>(2, 1);
		assertEquals(0, atlas.lookup("first", false, 0L, 0L).x());
		assertEquals(1, atlas.lookup("second", false, 0L, 0L).x());

		atlas.endFrame(Set.of("second"));
		GuiItemAtlasState.Lookup third = atlas.lookup("third", false, 0L, 0L);

		assertEquals(0, third.x());
		assertEquals(2, atlas.size());
		assertNull(atlas.lookup("first", false, 0L, 0L));
	}

	@Test
	void mapAtlasEvictsLeastRecentlyUsedEntryAndReusesItsSlot() {
		GuiMapAtlasState<String> atlas = new GuiMapAtlasState<>(2);
		assertEquals(0, atlas.acquire("first").slot());
		assertEquals(1, atlas.acquire("second").slot());
		assertEquals(1, atlas.acquire("second").slot());

		GuiMapAtlasState.Lookup<String> third = atlas.acquire("third");

		assertEquals("first", third.evicted());
		assertEquals(0, third.slot());
		assertEquals(2, atlas.size());
		assertEquals(-1, atlas.slot("first"));
	}

	@Test
	void mapAtlasCapacityDoesNotDependOnWallClockTime() {
		GuiMapAtlasState<String> atlas = new GuiMapAtlasState<>(3);
		for (int index = 0; index < 100; index++) {
			atlas.acquire("map-" + index);
		}

		assertEquals(3, atlas.size());
		assertEquals(3, atlas.capacity());
		assertEquals(1, atlas.slot("map-97"));
		assertEquals(2, atlas.slot("map-98"));
		assertEquals(0, atlas.slot("map-99"));
	}
}
