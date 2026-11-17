package dev.mikuteto.mtlrender.tune;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import dev.mikuteto.mtlrender.pack.tess.TessellationStrategy;
import dev.mikuteto.mtlrender.terrain.OcclusionMode;

class CalibrationResultTest {
	@Test
	void acceptsNativeModesAndPreservesProfileValues() {
		CalibrationResult result = CalibrationResult.fromPayload(1, OcclusionMode.SINGLE_PHASE_HIZ.code(), 3, true, true, 480, 3, 1280, 720);

		assertEquals(OcclusionMode.SINGLE_PHASE_HIZ, result.occlusionMode());
		assertEquals(TerrainDrawPath.MESH, result.drawPath());
		assertEquals(1280, result.width());
		assertEquals(720, result.height());
		assertEquals(TessellationStrategy.COMPUTE, new TuneProfile(result.drawPath(), result.occlusionMode(),
			result.probeInterval(), result.lodMinRing(), result.compact()).tessellationStrategy());
	}

	@Test
	void rejectsInvalidNativePayload() {
		assertNull(CalibrationResult.fromPayload(2, OcclusionMode.OFF.code(), 1, false, false, 1, 0, 1280, 720));
		assertNull(CalibrationResult.fromPayload(1, 99, 1, false, false, 1, 0, 1280, 720));
		assertNull(CalibrationResult.fromPayload(1, OcclusionMode.CPU_BFS.code(), 1, false, false, 1, 0, 1280, 720));
		assertNull(CalibrationResult.fromPayload(1, OcclusionMode.OFF.code(), 1, false, false, 1, 0, 0, 720));
	}
}
