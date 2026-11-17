package dev.mikuteto.mtlrender.pack.gbuffer;

import dev.mikuteto.mtlrender.pack.ids.IdMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GbufferItemContextTest {
	@AfterEach
	void clear() {
		GbufferSemanticContext.clear();
	}

	@Test
	void mappedItemIdsUseTheShaderPackMapAndUnknownValuesAreZero() {
		IdMap map = IdMap.fromResolved(new IdMap.Resolved(new int[0], new int[]{0, 17}, new int[0], new int[0]));
		assertEquals(17, map.itemId(1));
		assertEquals(-1, map.itemId(8));
	}

	@Test
	void itemNestingPreservesHandEntityAndBlockContext() {
		GbufferSemanticContext.beginFrame();
		GbufferSubmitMetadata entity = new GbufferSubmitMetadata(41, 0, 0, GbufferStage.Entities);
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enter(entity)) {
			try (GbufferSemanticContext.Scope item = GbufferSemanticContext.enterItem(17)) {
				assertEquals(41, GbufferSemanticContext.current().entityId());
				assertEquals(0, GbufferSemanticContext.current().blockEntityId());
				assertEquals(17, GbufferSemanticContext.current().currentRenderedItemId());
				assertEquals(GbufferStage.Entities.ordinal(), GbufferSemanticContext.current().renderStage());
			}
			assertEquals(0, GbufferSemanticContext.current().currentRenderedItemId());
		}

		GbufferSubmitMetadata block = new GbufferSubmitMetadata(0, 73, 0, GbufferStage.Blocks);
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enter(block)) {
			try (GbufferSemanticContext.Scope item = GbufferSemanticContext.enterItem(0)) {
				assertEquals(73, GbufferSemanticContext.current().blockEntityId());
				assertEquals(0, GbufferSemanticContext.current().currentRenderedItemId());
			}
			assertEquals(0, GbufferSemanticContext.current().currentRenderedItemId());
		}
	}

	@Test
	void noFrameItemScopeIsAStableNoOp() {
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enterItem(19)) {
			assertEquals(0, GbufferSemanticContext.current().currentRenderedItemId());
		}
	}
}
