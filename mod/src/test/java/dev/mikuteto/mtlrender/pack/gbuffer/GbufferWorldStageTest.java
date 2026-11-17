package dev.mikuteto.mtlrender.pack.gbuffer;

import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class GbufferWorldStageTest {
	@AfterEach
	void clear() {
		GbufferSemanticContext.clear();
	}

	@Test
	void everySkyRendererBoundaryUsesSkyWithoutEntityContext() {
		GbufferSemanticContext.beginFrame();
		String[] methods = {
			"renderSkyDisc", "renderDarkDisc", "renderSunMoonAndStars", "renderSunriseAndSunset", "renderEndSky", "renderEndFlash"
		};
		for (String ignoredMethod : methods) {
			try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enterWorld(GbufferStage.Sky)) {
				assertEquals(GbufferStage.Sky.ordinal(), GbufferSemanticContext.current().renderStage());
				assertEquals(0, GbufferSemanticContext.current().entityId());
				assertEquals(0, GbufferSemanticContext.current().blockEntityId());
				assertEquals(0, GbufferSemanticContext.current().currentRenderedItemId());
			}
		}
	}

	@Test
	void cloudsAndWeatherUseStableStagesIndependentOfTargets() {
		GbufferSemanticContext.beginFrame();
		try (GbufferSemanticContext.Scope clouds = GbufferSemanticContext.enterWorld(GbufferStage.Clouds)) {
			assertEquals(GbufferStage.Clouds.ordinal(), GbufferSemanticContext.current().renderStage());
		}
		try (GbufferSemanticContext.Scope weather = GbufferSemanticContext.enterWorld(GbufferStage.Weather)) {
			assertEquals(GbufferStage.Weather.ordinal(), GbufferSemanticContext.current().renderStage());
		}
	}

	@Test
	void nestedWorldScopesAndExceptionsRestorePreviousScope() {
		GbufferSemanticContext.beginFrame();
		try (GbufferSemanticContext.Scope outer = GbufferSemanticContext.enterWorld(GbufferStage.Clouds)) {
			assertThrows(IllegalStateException.class, () -> {
				try (GbufferSemanticContext.Scope inner = GbufferSemanticContext.enterWorld(GbufferStage.Weather)) {
					throw new IllegalStateException("failure");
				}
			});
			assertEquals(GbufferStage.Clouds.ordinal(), GbufferSemanticContext.current().renderStage());
		}
		assertEquals(GbufferStage.Sky.ordinal(), GbufferSemanticContext.current().renderStage());
	}

	@Test
	void noPackWorldScopesAreNoOps() {
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enterWorld(GbufferStage.Weather)) {
			assertFalse(GbufferSemanticContext.isActive());
			assertEquals(0, GbufferSemanticContext.current().renderStage());
		}
	}

	@Test
	void catalogKeepsDirectPipelineIdentityAuthoritative() {
		assertEquals(GbufferStage.Sky, GbufferPipelineCatalog.find(RenderPipelines.SKY).orElseThrow().stage());
		assertEquals(GbufferStage.Clouds, GbufferPipelineCatalog.find(RenderPipelines.CLOUDS).orElseThrow().stage());
		assertEquals(GbufferStage.Clouds, GbufferPipelineCatalog.find(RenderPipelines.FLAT_CLOUDS).orElseThrow().stage());
		assertEquals(GbufferStage.Weather, GbufferPipelineCatalog.find(RenderPipelines.WEATHER_DEPTH_WRITE).orElseThrow().stage());
		assertEquals(GbufferStage.Weather, GbufferPipelineCatalog.find(RenderPipelines.WEATHER_NO_DEPTH_WRITE).orElseThrow().stage());
		assertEquals(GbufferStage.WorldBorder, GbufferPipelineCatalog.find(RenderPipelines.WORLD_BORDER).orElseThrow().stage());
	}
}
