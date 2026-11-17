package dev.mikuteto.mtlrender.pack.gbuffer;

import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GbufferSpecialFeatureSemanticsTest {
	@AfterEach
	void clear() {
		GbufferSemanticContext.clear();
	}

	@Test
	void specialStagesKeepEnclosingEntityAndBlockIds() {
		GbufferSemanticContext.beginFrame();
		GbufferSubmitMetadata metadata = new GbufferSubmitMetadata(31, 47, 59, GbufferStage.Entities);
		try (GbufferSemanticContext.Scope outer = GbufferSemanticContext.enter(metadata)) {
			try (GbufferSemanticContext.Scope leash = GbufferSemanticContext.enterStage(GbufferStage.Leash)) {
				assertEquals(31, GbufferSemanticContext.current().entityId());
				assertEquals(47, GbufferSemanticContext.current().blockEntityId());
				assertEquals(GbufferStage.Leash.ordinal(), GbufferSemanticContext.current().renderStage());
			}
			assertEquals(GbufferStage.Entities.ordinal(), GbufferSemanticContext.current().renderStage());
		}
		assertEquals(0, GbufferSemanticContext.current().renderStage());
	}

	@Test
	void specialBoundariesRestoreAfterNestedFailure() {
		GbufferSemanticContext.beginFrame();
		try (GbufferSemanticContext.Scope outer = GbufferSemanticContext.enter(
			new GbufferSubmitMetadata(7, 0, 0, GbufferStage.Entities))) {
			assertThrows(IllegalStateException.class, () -> {
				try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enterStage(GbufferStage.Text)) {
					throw new IllegalStateException("failure");
				}
			});
			assertEquals(GbufferStage.Entities.ordinal(), GbufferSemanticContext.current().renderStage());
		}
		assertEquals(0, GbufferSemanticContext.current().entityId());
		assertEquals(0, GbufferSemanticContext.current().renderStage());
	}

	@Test
	void catalogSeparatesBeaconGlintLinesTextAndPortal() {
		assertEquals(GbufferStage.Beacon, GbufferPipelineCatalog.find(RenderPipelines.BEACON_BEAM_OPAQUE).orElseThrow().stage());
		assertEquals(GbufferStage.Glint, GbufferPipelineCatalog.find(RenderPipelines.GLINT).orElseThrow().stage());
		assertEquals(GbufferStage.Lines, GbufferPipelineCatalog.find(RenderPipelines.LINES).orElseThrow().stage());
		assertEquals(GbufferStage.Lines, GbufferPipelineCatalog.find(RenderPipelines.OUTLINE_NO_CULL).orElseThrow().stage());
		assertEquals(GbufferStage.Text, GbufferPipelineCatalog.find(RenderPipelines.TEXT_SEE_THROUGH).orElseThrow().stage());
		assertEquals(GbufferStage.Portal, GbufferPipelineCatalog.find(RenderPipelines.END_PORTAL).orElseThrow().stage());
	}

	@Test
	void missingIdsAndInactiveScopesAreSafe() {
		GbufferSubmitMetadata missing = new GbufferSubmitMetadata(-1, -1, -1, GbufferStage.Leash);
		assertEquals(0, missing.entityId());
		assertEquals(0, missing.blockEntityId());
		assertEquals(0, missing.currentRenderedItemId());
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enterStage(GbufferStage.Text)) {
			assertTrue(GbufferSemanticContext.featureStage().isEmpty());
		}
		assertEquals(GbufferStage.Sky.ordinal(), GbufferSemanticContext.currentMetadata().stage().ordinal());
	}
}
