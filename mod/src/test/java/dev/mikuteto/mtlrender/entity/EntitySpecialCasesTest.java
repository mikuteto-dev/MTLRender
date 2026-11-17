package dev.mikuteto.mtlrender.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class EntitySpecialCasesTest {
	@Test
	void acceptsFrontAndRejectsBackForRotatedPose() {
		Matrix4f front = new Matrix4f().translate(0.0F, 0.0F, -4.0F);
		Matrix4f back = new Matrix4f().translate(0.0F, 0.0F, 4.0F);
		assertTrue(EntitySpecialCases.frontFacesCamera(front));
		assertFalse(EntitySpecialCases.frontFacesCamera(back));
		assertTrue(EntitySpecialCases.frontFacesCamera(new Matrix4f().rotateY((float) Math.PI).translate(0.0F, 0.0F, -4.0F)));
	}

	@Test
	void keepsNearPoseUpdatesAndBoundsFarUpdates() {
		assertTrue(EntitySpecialCases.updateFarPose(31.0 * 31.0, 1, true));
		assertTrue(EntitySpecialCases.updateFarPose(64.0 * 64.0, 0, true));
		assertFalse(EntitySpecialCases.updateFarPose(64.0 * 64.0, 1, true));
	}
}
