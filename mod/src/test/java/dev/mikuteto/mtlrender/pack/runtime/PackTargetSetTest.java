package dev.mikuteto.mtlrender.pack.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackTargetSetTest {
	@Test
	void scalesRelativeAndAbsoluteDimensions() {
		assertEquals(960, PackTargetSet.scaledDimension(true, 0.5f, 1920));
		assertEquals(720, PackTargetSet.scaledDimension(true, 0.75f, 960));
		assertEquals(640, PackTargetSet.scaledDimension(false, 640.0f, 1920));
	}

	@Test
	void computesMipChainAndSeedCommands() {
		assertEquals(11, PackTargetSet.mipLevelCount(1024, 512, true));
		assertEquals(1, PackTargetSet.mipLevelCount(1024, 512, false));
		assertEquals(List.of("colortex0", "depthtex0", "depthtex1", "depthtex2"), PackTargetSet.seedCommands(true, true));
		assertEquals(List.of(), PackTargetSet.seedCommands(false, false));
	}
}
