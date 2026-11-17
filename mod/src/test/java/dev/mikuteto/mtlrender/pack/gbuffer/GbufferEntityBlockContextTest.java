package dev.mikuteto.mtlrender.pack.gbuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class GbufferEntityBlockContextTest {
	@AfterEach
	void clear() {
		GbufferSemanticContext.clear();
	}

	@Test
	void extractionAssociationSurvivesNodeBatching() {
		Object entityState = new Object();
		Object blockState = new Object();
		Object firstNode = new Object();
		Object secondNode = new Object();
		GbufferSubmitMetadata entity = new GbufferSubmitMetadata(17, 0, 0, GbufferStage.Entities);
		GbufferSubmitMetadata block = new GbufferSubmitMetadata(0, 23, 0, GbufferStage.Blocks);
		GbufferSemanticContext.beginFrame();
		GbufferSemanticContext.recordState(entityState, entity);
		GbufferSemanticContext.recordState(blockState, block);
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enter(entity)) {
			GbufferSemanticContext.recordSubmit(firstNode, GbufferSemanticContext.currentMetadata());
			try (GbufferSemanticContext.Scope nested = GbufferSemanticContext.enter(block)) {
				GbufferSemanticContext.recordSubmit(secondNode, GbufferSemanticContext.currentMetadata());
			}
			assertEquals(entity, GbufferSemanticContext.metadata(firstNode).orElseThrow());
		}
		assertEquals(block, GbufferSemanticContext.metadata(secondNode).orElseThrow());
		assertEquals(entity, GbufferSemanticContext.metadata(entityState).orElseThrow());
		assertEquals(block, GbufferSemanticContext.metadata(blockState).orElseThrow());
	}

	@Test
	void unknownEntriesAreZeroAndExceptionClosesScope() {
		Object unknown = new Object();
		GbufferSemanticContext.beginFrame();
		assertTrue(GbufferSemanticContext.metadata(unknown).isEmpty());
		try {
			try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enter(
				new GbufferSubmitMetadata(31, 0, 0, GbufferStage.Entities))) {
				throw new IllegalStateException("renderer failure");
			}
		} catch (IllegalStateException exception) {
			assertEquals("renderer failure", exception.getMessage());
		}
		assertEquals(0, GbufferSemanticContext.current().entityId());
		GbufferSemanticContext.endFrame();
		assertEquals(Optional.empty(), GbufferSemanticContext.metadata(unknown));
	}

	@Test
	void frameResetDropsAssociations() {
		Object state = new Object();
		GbufferSemanticContext.beginFrame();
		GbufferSemanticContext.recordState(state, new GbufferSubmitMetadata(9, 4, 0, GbufferStage.Blocks));
		assertTrue(GbufferSemanticContext.metadata(state).isPresent());
		GbufferSemanticContext.beginFrame();
		assertTrue(GbufferSemanticContext.metadata(state).isEmpty());
	}
}
