package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CullTunerTest {
	@Test
	void adoptsClearlyFasterProbe() {
		CullTuner tuner = new CullTuner(1, OcclusionMode.TWO_PHASE_HIZ, true);
		long counter = 0;
		for (int i = 0; i < 31; i++) {
			counter += 1_000;
			tuner.next(1_000, counter);
		}
		for (int i = 0; i < 15; i++) {
			counter += 500;
			tuner.next(500, counter);
		}
		assertEquals(OcclusionMode.SINGLE_PHASE_HIZ, tuner.preferred());
	}

	@Test
	void retainsModeWhenProbeDoesNotImprove() {
		CullTuner tuner = new CullTuner(1, OcclusionMode.TWO_PHASE_HIZ, true);
		long counter = 0;
		for (int i = 0; i < 46; i++) {
			counter += 1_000;
			tuner.next(1_000, counter);
		}
		assertEquals(OcclusionMode.TWO_PHASE_HIZ, tuner.preferred());
	}

	@Test
	void counterResetDoesNotCreateAFalseImprovement() {
		CullTuner tuner = new CullTuner(1, OcclusionMode.TWO_PHASE_HIZ, true);
		for (int i = 0; i < 31; i++) {
			tuner.next(1_000, i == 0 ? 10_000 : i * 1_000L);
		}
		tuner.next(1_000, 10);
		assertEquals(OcclusionMode.TWO_PHASE_HIZ, tuner.preferred());
	}

	@Test
	void resolutionChangeRestartsProbeWithoutChangingSelection() {
		CullTuner tuner = new CullTuner(1, OcclusionMode.SINGLE_PHASE_HIZ, true);
		tuner.resolution(1920, 1080);
		for (int i = 0; i < 31; i++) {
			tuner.next(1_000, (i + 1L) * 1_000);
		}
		tuner.resolution(1280, 720);
		assertEquals(OcclusionMode.SINGLE_PHASE_HIZ, tuner.preferred());
		assertTrue(tuner.next(1_000, 1_000) != OcclusionMode.OFF);
	}

	@Test
	void fixedModeNeverChanges() {
		CullTuner tuner = new CullTuner(1, OcclusionMode.CPU_BFS, false);
		for (int i = 0; i < 100; i++) {
			assertEquals(OcclusionMode.CPU_BFS, tuner.next(1_000, i == 50 ? 0 : i * 1_000L));
		}
		assertEquals(OcclusionMode.CPU_BFS, tuner.preferred());
	}
}
