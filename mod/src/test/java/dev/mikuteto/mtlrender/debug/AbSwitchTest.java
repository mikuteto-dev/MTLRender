package dev.mikuteto.mtlrender.debug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AbSwitchTest {
	static {
		AbSwitch.NAME = AbSwitch.PARTICLE_PULLING;
	}

	@Test
	void configuredValueWinsOutsideMatchingRun() {
		AbSwitch.set(false);
		assertTrue(AbSwitch.is("other", true));
		assertFalse(AbSwitch.is("other", false));
	}

	@Test
	void matchingRunSelectsAThenB() {
		AbSwitch.set(false);
		assertFalse(AbSwitch.is(AbSwitch.PARTICLE_PULLING, true));
		assertFalse(AbSwitch.is(AbSwitch.PARTICLE_PULLING, false));
		AbSwitch.set(true);
		assertTrue(AbSwitch.is(AbSwitch.PARTICLE_PULLING, false));
		assertTrue(AbSwitch.is(AbSwitch.PARTICLE_PULLING, true));
	}

	@Test
	void guiAtlasSelectsConfiguredAAndConfiguredB() {
		String previousName = AbSwitch.NAME;
		try {
			AbSwitch.NAME = "other";
			assertTrue(AbSwitch.guiAtlas(true));
			AbSwitch.NAME = AbSwitch.GUI_ATLAS;
			AbSwitch.set(false);
			assertFalse(AbSwitch.guiAtlas(true));
			AbSwitch.set(true);
			assertTrue(AbSwitch.guiAtlas(false));
		} finally {
			AbSwitch.NAME = previousName;
		}
	}
}
