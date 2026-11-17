package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OcclusionModeTest {
	@Test
	void convertsConfigurationValues() {
		assertEquals(OcclusionMode.AUTO, OcclusionMode.fromString("auto"));
		assertEquals(OcclusionMode.TWO_PHASE_HIZ, OcclusionMode.fromString("two_phase"));
		assertEquals(OcclusionMode.SINGLE_PHASE_HIZ, OcclusionMode.fromString("single_phase"));
		assertEquals(OcclusionMode.CPU_BFS, OcclusionMode.fromString("cpu_bfs"));
		assertEquals(OcclusionMode.OFF, OcclusionMode.fromString("off"));
		assertEquals("two_phase", OcclusionMode.TWO_PHASE_HIZ.toString());
	}

	@Test
	void convertsLegacyBooleanValues() {
		assertEquals(OcclusionMode.TWO_PHASE_HIZ, OcclusionMode.fromString("true"));
		assertEquals(OcclusionMode.OFF, OcclusionMode.fromString("false"));
	}

	@Test
	void unknownValuesUseAutomaticMode() {
		assertEquals(OcclusionMode.AUTO, OcclusionMode.fromString("unsupported"));
		assertEquals(OcclusionMode.AUTO, OcclusionMode.fromString(null));
		assertEquals(OcclusionMode.AUTO, OcclusionMode.fromCode(-1));
		assertEquals(OcclusionMode.AUTO, OcclusionMode.fromCode(99));
	}

	@Test
	void onlyHiZModesIssueGpuCulling() {
		assertEquals(true, OcclusionMode.TWO_PHASE_HIZ.usesGpuCulling());
		assertEquals(true, OcclusionMode.SINGLE_PHASE_HIZ.usesGpuCulling());
		assertEquals(false, OcclusionMode.CPU_BFS.usesGpuCulling());
		assertEquals(false, OcclusionMode.OFF.usesGpuCulling());
		assertEquals(true, OcclusionMode.CPU_BFS.usesCpuVisibleSections());
		assertEquals(true, OcclusionMode.OFF.usesCpuVisibleSections());
	}

	@Test
	void numericCodesAreStableAndRoundTrip() {
		assertEquals(0, OcclusionMode.AUTO.code());
		assertEquals(1, OcclusionMode.TWO_PHASE_HIZ.code());
		assertEquals(2, OcclusionMode.SINGLE_PHASE_HIZ.code());
		assertEquals(3, OcclusionMode.CPU_BFS.code());
		assertEquals(4, OcclusionMode.OFF.code());
		for (OcclusionMode mode : OcclusionMode.values()) {
			assertEquals(mode, OcclusionMode.fromCode(mode.code()));
		}
	}
}
