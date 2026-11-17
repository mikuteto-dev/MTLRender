package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CpuBfsVisibilityTest {
	@Test
	void preservesVanillaOrderAndRemovesDuplicates() {
		List<String> sections = CpuBfsVisibility.snapshot(List.of("near", "far", "near", "middle"));

		assertEquals(List.of("near", "far", "middle"), sections);
		assertEquals("near", CpuBfsVisibility.at(sections, 0, false));
		assertEquals("middle", CpuBfsVisibility.at(sections, 0, true));
	}

	@Test
	void emptyInputHasNoSections() {
		List<String> sections = CpuBfsVisibility.snapshot(List.of());

		assertTrue(sections.isEmpty());
		assertEquals(0, CpuBfsVisibility.sectionCount(sections, String::length));
	}

	@Test
	void sectionIndexCapacityUsesTheHighestVanillaIndex() {
		List<Integer> sections = CpuBfsVisibility.snapshot(List.of(4, 1, 4, 7));

		assertEquals(8, CpuBfsVisibility.sectionCount(sections, Integer::intValue));
	}
}
