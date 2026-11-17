package dev.mikuteto.mtlrender.tune;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HardwareTest {
	@Test
	void reservesOneCpuForTheRenderAndGameThreads() {
		assertEquals(7, Hardware.mesherWorkers(4, 4));
		assertEquals(11, Hardware.mesherWorkers(8, 4));
	}

	@Test
	void boundsLargeHardwareAndUsesSafeFallback() {
		assertEquals(16, Hardware.mesherWorkers(32, 32));
		assertEquals(2, Hardware.mesherWorkers(0, 0));
		assertEquals(2, Hardware.mesherWorkers(4, 0));
	}
}
