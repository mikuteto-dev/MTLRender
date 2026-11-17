package dev.mikuteto.mtlrender.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StaticBlockEntityBakeStatsTest {
	@AfterEach
	void reset() {
		StaticBlockEntityBakeStats.reset();
		AbSwitch.NAME = "";
		AbSwitch.set(false);
	}

	@Test
	void countersResetAtMeasurementBoundary() {
		StaticBlockEntityBakeStats.extracted();
		StaticBlockEntityBakeStats.dynamicSubmission();
		StaticBlockEntityBakeStats.reset();

		StaticBlockEntityBakeStats.Snapshot snapshot = StaticBlockEntityBakeStats.snapshot(false);
		assertEquals(0L, snapshot.extracted());
		assertEquals(0L, snapshot.dynamicSubmissions());
	}

	@Test
	void variantsAreSeparated() {
		AbSwitch.NAME = AbSwitch.BLOCK_ENTITY_BAKE;
		StaticBlockEntityBakeStats.extracted();
		StaticBlockEntityBakeStats.skippedStatic();
		AbSwitch.set(true);
		StaticBlockEntityBakeStats.extracted();
		StaticBlockEntityBakeStats.dynamicSubmission();
		StaticBlockEntityBakeStats.staticPublished();

		StaticBlockEntityBakeStats.Snapshot a = StaticBlockEntityBakeStats.snapshot(false);
		StaticBlockEntityBakeStats.Snapshot b = StaticBlockEntityBakeStats.snapshot(true);
		assertEquals(1L, a.extracted());
		assertEquals(1L, a.skippedStatic());
		assertEquals(0L, a.dynamicSubmissions());
		assertEquals(1L, b.extracted());
		assertEquals(1L, b.dynamicSubmissions());
		assertEquals(1L, b.staticPublished());
	}
}
