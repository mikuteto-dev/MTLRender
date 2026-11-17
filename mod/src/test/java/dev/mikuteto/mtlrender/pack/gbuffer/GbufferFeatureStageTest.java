package dev.mikuteto.mtlrender.pack.gbuffer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GbufferFeatureStageTest {
	@AfterEach
	void clear() {
		GbufferSemanticContext.clear();
	}

	@Test
	void everyFeatureBoundaryIsVisibleAndRestored() {
		GbufferSemanticContext.beginFrame();
		for (GbufferFeatureStage stage : new GbufferFeatureStage[] {
			GbufferFeatureStage.Solid,
			GbufferFeatureStage.Translucent,
			GbufferFeatureStage.Outline,
			GbufferFeatureStage.AfterTerrain
		}) {
			try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enterFeature(stage)) {
				assertEquals(stage, GbufferSemanticContext.featureStage().orElseThrow());
			}
			assertTrue(GbufferSemanticContext.featureStage().isEmpty());
		}
	}

	@Test
	void particleStagesRemainDistinctInsideEachPhase() {
		GbufferSemanticContext.beginFrame();
		for (GbufferFeatureStage stage : new GbufferFeatureStage[] {
			GbufferFeatureStage.ParticleSolid,
			GbufferFeatureStage.ParticleTranslucent,
			GbufferFeatureStage.ParticleAfterTerrain
		}) {
			try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enterFeature(stage);
				 GbufferSemanticContext.Scope particle = GbufferSemanticContext.enter(GbufferStage.Particles)) {
				assertEquals(stage, GbufferSemanticContext.featureStage().orElseThrow());
				assertEquals(GbufferStage.Particles.ordinal(), GbufferSemanticContext.current().renderStage());
			}
			assertTrue(GbufferSemanticContext.featureStage().isEmpty());
		}
	}

	@Test
	void nestedBoundaryRestoresOuterAndFailureResetsIt() {
		GbufferSemanticContext.beginFrame();
		try (GbufferSemanticContext.Scope outer = GbufferSemanticContext.enterFeature(GbufferFeatureStage.Translucent)) {
			try {
				try (GbufferSemanticContext.Scope inner = GbufferSemanticContext.enterFeature(GbufferFeatureStage.ParticleTranslucent)) {
					throw new IllegalStateException("failure");
				}
			} catch (IllegalStateException ignored) {
			}
			assertEquals(GbufferFeatureStage.Translucent, GbufferSemanticContext.featureStage().orElseThrow());
		}
		assertTrue(GbufferSemanticContext.featureStage().isEmpty());
	}

	@Test
	void inactiveContextIsNoOp() {
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enterFeature(GbufferFeatureStage.Solid)) {
			assertTrue(GbufferSemanticContext.featureStage().isEmpty());
			assertEquals(0, GbufferSemanticContext.current().renderStage());
		}
	}
}
