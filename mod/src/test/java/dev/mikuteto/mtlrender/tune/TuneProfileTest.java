package dev.mikuteto.mtlrender.tune;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.pack.tess.TessellationStrategy;
import dev.mikuteto.mtlrender.terrain.OcclusionMode;
import org.junit.jupiter.api.Test;

class TuneProfileTest {
	@Test
	void fixedOcclusionModesAreNotParsedAsBoolean() {
		Config.Terrain terrain = new Config.Terrain();
		terrain.occlusion = "single_phase";

		assertEquals(OcclusionMode.SINGLE_PHASE_HIZ, TuneProfile.resolve(terrain).occlusionMode());
	}

	@Test
	void invalidOcclusionFallsBackToAutomaticProfile() {
		Config.Terrain terrain = new Config.Terrain();
		terrain.occlusion = "invalid";

		assertEquals(TuneProfile.defaults(Tier.MID).occlusionMode(), TuneProfile.resolve(terrain).occlusionMode());
	}

	@Test
	void defaultTessellationStrategyIsCompute() {
		assertEquals(TessellationStrategy.COMPUTE, TuneProfile.defaults(Tier.MID).tessellationStrategy());
		assertEquals(TessellationStrategy.COMPUTE, TuneProfile.resolve(new Config.Terrain()).tessellationStrategy());
	}

	@Test
	void explicitComputeStrategyWins() {
		Config.Terrain terrain = new Config.Terrain();
		terrain.tessellationStrategy = "compute";

		assertEquals(TessellationStrategy.COMPUTE, TuneProfile.resolve(terrain).tessellationStrategy());
	}
}
