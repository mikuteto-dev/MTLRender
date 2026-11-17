package dev.mikuteto.mtlrender.pathtracing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VoxelSectionStateCaptureTest {
	@Test
	void indexUsesXThenYThenZ() {
		assertEquals(0, VoxelSectionStateCapture.index(0, 0, 0));
		assertEquals(1, VoxelSectionStateCapture.index(1, 0, 0));
		assertEquals(16, VoxelSectionStateCapture.index(0, 1, 0));
		assertEquals(256, VoxelSectionStateCapture.index(0, 0, 1));
		assertEquals(4095, VoxelSectionStateCapture.index(15, 15, 15));
	}
}
