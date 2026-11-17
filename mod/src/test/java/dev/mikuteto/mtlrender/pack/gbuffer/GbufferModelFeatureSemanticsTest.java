package dev.mikuteto.mtlrender.pack.gbuffer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GbufferModelFeatureSemanticsTest {
	@AfterEach
	void clearContext() {
		GbufferSemanticContext.clear();
	}

	@Test
	void keepsEntityBlockAndItemMetadataAcrossNestedModelPaths() {
		Object entityModel = new Object();
		Object blockModel = new Object();
		Object itemMain = new Object();
		Object itemOutline = new Object();
		Object itemFoil = new Object();
		GbufferSubmitMetadata entity = new GbufferSubmitMetadata(17, 0, 0, GbufferStage.Entities);
		GbufferSubmitMetadata block = new GbufferSubmitMetadata(0, 23, 0, GbufferStage.Blocks);
		GbufferSubmitMetadata item = new GbufferSubmitMetadata(17, 0, 41, GbufferStage.Items);
		GbufferSemanticContext.beginFrame();
		GbufferSemanticContext.recordSubmit(entityModel, entity);
		GbufferSemanticContext.recordSubmit(blockModel, block);
		GbufferSemanticContext.recordSubmit(itemMain, item);
		GbufferSemanticContext.recordSubmit(itemOutline, item);
		GbufferSemanticContext.recordSubmit(itemFoil,
			new GbufferSubmitMetadata(17, 0, 41, GbufferStage.Glint));

		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enter(entity)) {
			assertEquals(17, GbufferSemanticContext.current().entityId());
			assertEquals(entity, GbufferSemanticContext.metadata(entityModel).orElseThrow());
			try (GbufferSemanticContext.Scope nested = GbufferSemanticContext.enter(block)) {
				assertEquals(23, GbufferSemanticContext.current().blockEntityId());
			}
			try (GbufferSemanticContext.Scope nested = GbufferSemanticContext.enter(item)) {
				assertEquals(41, GbufferSemanticContext.current().currentRenderedItemId());
			}
		}
		assertEquals(GbufferStage.Sky.ordinal(), GbufferSemanticContext.current().renderStage());
		assertEquals(GbufferStage.Glint, GbufferSemanticContext.metadata(itemFoil).orElseThrow().stage());
	}

	@Test
	void missingMetadataIsInertAndBatchingDoesNotMergeIdentityRecords() {
		Object unknown = new Object();
		GbufferSemanticContext.beginFrame();
		assertEquals(Optional.empty(), GbufferSemanticContext.metadata(unknown));
		GbufferSubmitMetadata first = new GbufferSubmitMetadata(1, 0, 0, GbufferStage.Entities);
		GbufferSubmitMetadata second = new GbufferSubmitMetadata(2, 0, 0, GbufferStage.Entities);
		Object firstNode = new Object();
		Object secondNode = new Object();
		GbufferSemanticContext.recordSubmit(firstNode, first);
		GbufferSemanticContext.recordSubmit(secondNode, second);
		assertEquals(first, GbufferSemanticContext.metadata(firstNode).orElseThrow());
		assertEquals(second, GbufferSemanticContext.metadata(secondNode).orElseThrow());
	}

	@Test
	void restoresStateWhenPreparationThrows() {
		GbufferSubmitMetadata outer = new GbufferSubmitMetadata(8, 0, 0, GbufferStage.Entities);
		GbufferSubmitMetadata inner = new GbufferSubmitMetadata(0, 9, 0, GbufferStage.Blocks);
		GbufferSemanticContext.beginFrame();
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enter(outer)) {
			try {
				try (GbufferSemanticContext.Scope nested = GbufferSemanticContext.enter(inner)) {
					throw new IllegalStateException("test");
				}
			} catch (IllegalStateException expected) {
				assertEquals(8, GbufferSemanticContext.current().entityId());
				assertEquals(0, GbufferSemanticContext.current().blockEntityId());
			}
		}
		assertTrue(GbufferSemanticContext.metadata(new Object()).isEmpty());
	}
}
