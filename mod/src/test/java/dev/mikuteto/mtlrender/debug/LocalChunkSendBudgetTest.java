package dev.mikuteto.mtlrender.debug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LocalChunkSendBudgetTest {
	@AfterEach
	void reset() {
		LocalChunkSendBudget.endDrain();
	}

	@Test
	void absentProcessorDisablesBudget() {
		LocalChunkSendBudget.beginDrain(null);

		assertFalse(LocalChunkSendBudget.shouldYield());
	}

	@Test
	void remoteAndClosedStatesDisableBudget() {
		LocalChunkSendBudget.beginDrainForTest(false);
		assertFalse(LocalChunkSendBudget.shouldYield());

		LocalChunkSendBudget.beginDrainForTest(true);
		LocalChunkSendBudget.endDrain();
		assertFalse(LocalChunkSendBudget.shouldYield());
	}

	@Test
	void emptyDrainDoesNotYield() {
		LocalChunkSendBudget.beginDrainForTest(true);

		assertFalse(LocalChunkSendBudget.shouldYield());
	}

	@Test
	void packetBelowBudgetDoesNotYield() {
		LocalChunkSendBudget.beginDrainForTest(true);
		long sample = LocalChunkSendBudget.beginChunkSampleForTest();
		try {
			assertTrue(sample >= 0L);
		} finally {
			LocalChunkSendBudget.endChunkSampleAtForTest(LocalChunkSendBudget.CHUNK_HANDLING_BUDGET_NANOS - 1L);
		}

		assertFalse(LocalChunkSendBudget.shouldYield());
	}

	@Test
	void packetCrossingBudgetYields() {
		LocalChunkSendBudget.beginDrainForTest(true);
		LocalChunkSendBudget.beginChunkSampleForTest();
		LocalChunkSendBudget.endChunkSampleAtForTest(LocalChunkSendBudget.CHUNK_HANDLING_BUDGET_NANOS);

		assertTrue(LocalChunkSendBudget.shouldYield());
	}

	@Test
	void budgetResetsBetweenDrains() {
		LocalChunkSendBudget.beginDrainForTest(true);
		LocalChunkSendBudget.beginChunkSampleForTest();
		LocalChunkSendBudget.endChunkSampleAtForTest(LocalChunkSendBudget.CHUNK_HANDLING_BUDGET_NANOS);
		assertTrue(LocalChunkSendBudget.shouldYield());

		LocalChunkSendBudget.endDrain();
		LocalChunkSendBudget.beginDrainForTest(true);
		assertFalse(LocalChunkSendBudget.shouldYield());
	}

	@Test
	void sampleClosesWhenHandlerThrows() {
		LocalChunkSendBudget.beginDrainForTest(true);
		try {
			LocalChunkSendBudget.beginChunkSampleForTest();
			throw new IllegalStateException();
		} catch (IllegalStateException ignored) {
			try {
				LocalChunkSendBudget.endChunkSampleAtForTest(LocalChunkSendBudget.CHUNK_HANDLING_BUDGET_NANOS);
			} finally {
				LocalChunkSendBudget.endDrain();
			}
		}

		assertFalse(LocalChunkSendBudget.shouldYield());
	}
}
