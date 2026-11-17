package dev.mikuteto.mtlrender.pack.gbuffer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GbufferSemanticContextTest {
	@AfterEach
	void clear() {
		GbufferSemanticContext.clear();
	}

	@Test
	void nestedScopesRestorePreviousValues() {
		GbufferSemanticContext.beginFrame();
		GbufferSubmitMetadata outer = new GbufferSubmitMetadata(7, 8, 9, GbufferStage.Entities);
		GbufferSubmitMetadata inner = new GbufferSubmitMetadata(11, 12, 13, GbufferStage.Items);
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enter(outer)) {
			assertEquals(7, GbufferSemanticContext.current().entityId());
			try (GbufferSemanticContext.Scope nested = GbufferSemanticContext.enter(inner)) {
				assertEquals(13, GbufferSemanticContext.current().currentRenderedItemId());
			}
			assertEquals(GbufferStage.Entities.ordinal(), GbufferSemanticContext.current().renderStage());
		}
		assertEquals(GbufferDrawState.zero().entityId(), GbufferSemanticContext.current().entityId());
	}

	@Test
	void frameResetAndWorldOnlyStagesAreDeterministic() {
		GbufferSemanticContext.beginFrame();
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enter(GbufferStage.Sky)) {
			GbufferDrawState first = GbufferSemanticContext.current();
			GbufferDrawState second = GbufferSemanticContext.current();
			assertEquals(GbufferStage.Sky.ordinal(), first.renderStage());
			assertEquals(first.renderStage(), second.renderStage());
			assertEquals(0, first.entityId());
			assertEquals(0, first.blockEntityId());
			assertEquals(0, first.currentRenderedItemId());
		}
		GbufferSemanticContext.endFrame();
		assertEquals(0, GbufferSemanticContext.current().renderStage());
	}

	@Test
	void metadataUsesIdentityLookupAndClearsWithFrame() {
		GbufferSemanticContext.beginFrame();
		Object submit = new Object();
		Object equalButDifferent = new String("submit");
		GbufferSubmitMetadata metadata = new GbufferSubmitMetadata(3, 4, 5, GbufferStage.Blocks);
		GbufferSemanticContext.recordSubmit(submit, metadata);
		assertEquals(Optional.of(metadata), GbufferSemanticContext.metadata(submit));
		assertTrue(GbufferSemanticContext.metadata(equalButDifferent).isEmpty());
		GbufferSemanticContext.endFrame();
		assertTrue(GbufferSemanticContext.metadata(submit).isEmpty());
	}

	@Test
	void missingIdsAreZeroAndProviderRemovalCleansTheFrame() {
		GbufferSubmitMetadata metadata = new GbufferSubmitMetadata(-1, -2, -3, GbufferStage.Water);
		assertEquals(0, metadata.entityId());
		assertEquals(0, metadata.blockEntityId());
		assertEquals(0, metadata.currentRenderedItemId());
		GbufferSemanticContext.beginFrame();
		Object submit = new Object();
		GbufferSemanticContext.recordSubmit(submit, metadata);
		GbufferDrawBridge.Provider provider = Optional::<GbufferDrawBridge.Draw>empty;
		GbufferDrawBridge.install(provider);
		assertTrue(GbufferSemanticContext.metadata(submit).isEmpty());
		GbufferDrawBridge.remove(provider);
		assertEquals(0, GbufferSemanticContext.current().entityId());
	}

	@Test
	void scopeRestoresWhenOperationThrows() {
		GbufferSemanticContext.beginFrame();
		GbufferSubmitMetadata metadata = new GbufferSubmitMetadata(21, 0, 0, GbufferStage.Entities);
		assertThrows(IllegalStateException.class, () -> {
			try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enter(metadata)) {
				throw new IllegalStateException("failure");
			}
		});
		assertEquals(0, GbufferSemanticContext.current().entityId());
		assertFalse(GbufferSemanticContext.metadata(new Object()).isPresent());
	}
}
