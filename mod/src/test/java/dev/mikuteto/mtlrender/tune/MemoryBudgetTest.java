package dev.mikuteto.mtlrender.tune;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MemoryBudgetTest {
	@Test
	void derivesTierBudgetFromRecommendedWorkingSet() {
		Hardware hardware = new Hardware("test", 16L << 30, 8, 4, "", "", 20,
			new Hardware.NativeCaps(9, true, true, 2L << 30, Long.MAX_VALUE), 11);
		MemoryBudget budget = MemoryBudget.derive(hardware, Tier.MID, TuneProfile.defaults(Tier.MID));

		assertEquals(2L << 30, budget.recommendedWorkingSet());
		assertEquals((2L << 30) / 4 / 5, budget.poolCap());
		assertEquals((2L << 30) / 4 / 5, budget.transientCap());
		assertTrue(budget.terrainCap() > budget.poolCap());
	}

	@Test
	void usesPhysicalMemoryFallbackWhenWorkingSetIsUnavailable() {
		Hardware hardware = new Hardware("test", 8L << 30, 4, 4, "", "", 8,
			Hardware.NativeCaps.missing(), 7);
		MemoryBudget budget = MemoryBudget.derive(hardware, Tier.LOW, TuneProfile.defaults(Tier.LOW));

		assertEquals(2L << 30, budget.recommendedWorkingSet());
		assertTrue(budget.terrainCap() > 0);
	}
}
