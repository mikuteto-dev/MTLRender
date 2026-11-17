package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SectionCpuRetentionTest {
	@Test
	void optOutReleasesCopies() {
		assertFalse(SectionCpuRetention.shouldRetain(false, false));
		assertTrue(SectionCpuRetention.shouldRetain(true, false));
		assertTrue(SectionCpuRetention.shouldRetain(false, true));
	}
}
