package dev.mikuteto.mtlrender.entity;

import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.debug.AbSwitch;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class EntitySpecialCases {
	private static final float BACKFACE_EPSILON = 0.0001F;
	private static final double FAR_ANIMATION_DISTANCE_SQ = 32.0 * 32.0;

	private EntitySpecialCases() {
	}

	public static boolean frontFacesCamera(Matrix4f pose) {
		Vector3f position = pose.transformPosition(0.0F, 0.0F, 0.0F, new Vector3f());
		Vector3f normal = pose.transformDirection(0.0F, 0.0F, 1.0F, new Vector3f());
		return normal.dot(position) <= BACKFACE_EPSILON;
	}

	public static boolean renderBackface(boolean configured, Matrix4f pose) {
		return !enabled(AbSwitch.ENTITY_SPECIAL, configured) || frontFacesCamera(pose);
	}

	public static boolean updateFarPose(double distanceSq, int frame) {
		return updateFarPose(distanceSq, frame, Config.get().entities.farAnimation);
	}

	public static boolean updateFarPose(double distanceSq, int frame, boolean configured) {
		if (!enabled(AbSwitch.FAR_ANIMATION, configured) && !Boolean.getBoolean("mtlrender.farEntityAnimation")) {
			return true;
		}
		if (distanceSq <= FAR_ANIMATION_DISTANCE_SQ) {
			return true;
		}
		return (frame & 1) == 0;
	}

	public static boolean shadowBatchEnabled() {
		return enabled(AbSwitch.ENTITY_SPECIAL, Config.get().entities.shadowBatch);
	}

	private static boolean enabled(String name, boolean configured) {
		return name.equals(AbSwitch.NAME) ? AbSwitch.b() : configured;
	}
}
