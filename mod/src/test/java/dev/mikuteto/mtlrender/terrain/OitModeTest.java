package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikuteto.mtlrender.debug.AbSwitch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OitModeTest {
	private final String originalName = AbSwitch.NAME;

	@AfterEach
	void restoreSwitch() {
		AbSwitch.NAME = originalName;
		AbSwitch.set(false);
	}

	@Test
	void defaultConfigurationIsOff() {
		AbSwitch.NAME = "";
		assertEquals(OitMode.OFF, OitMode.resolve(false));
		assertEquals(OitMode.K4, OitMode.resolve(true));
	}

	@Test
	void matchingAbRunUsesAAndBAndConfiguredValueOutsideIt() {
		AbSwitch.NAME = "oit";
		AbSwitch.set(false);
		assertEquals(OitMode.OFF, OitMode.resolve(true));
		AbSwitch.set(true);
		assertEquals(OitMode.K4, OitMode.resolve(false));
		AbSwitch.NAME = "other";
		assertEquals(OitMode.K4, OitMode.resolve(true));
	}

	@Test
	void onlyFourThroughEightAreValid() {
		assertFalse(OitMode.fromK(3).enabled());
		assertEquals(OitMode.K4, OitMode.fromK(4));
		assertEquals(OitMode.K8, OitMode.fromK(8));
		assertFalse(OitMode.fromK(9).enabled());
		assertTrue(OitMode.resolve(true, 4).enabled());
		assertEquals(OitMode.OFF, OitMode.resolve(true, 9));
	}
}
