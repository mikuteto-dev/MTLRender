package dev.mikuteto.mtlrender.pack.shadow;

import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.ShaderProperties;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ShadowMatrixCalculator {
	private static final float NEAR = -100.05F;
	private static final float FAR = 156.0F;
	private static final int DEFAULT_RESOLUTION = 1024;
	private static final float DEFAULT_DISTANCE = 160.0F;
	private static final float DEFAULT_INTERVAL = 2.0F;

	private ShadowMatrixCalculator() {
	}

	public static ShadowPassPlan calculate(ShaderPack pack, float sunAngleDegrees,
		double cameraX, double cameraY, double cameraZ) {
		Objects.requireNonNull(pack, "pack");
		return calculate(pack.optionValues(), pack.properties(), sunAngleDegrees, cameraX, cameraY, cameraZ);
	}

	public static ShadowPassPlan calculate(Map<String, String> optionValues, ShaderProperties properties,
		float sunAngleDegrees, double cameraX, double cameraY, double cameraZ) {
		Objects.requireNonNull(optionValues, "option values");
		Objects.requireNonNull(properties, "properties");
		finite(sunAngleDegrees, "sun angle");
		finite(cameraX, "camera x");
		finite(cameraY, "camera y");
		finite(cameraZ, "camera z");

		int resolution = integer(optionValues, "shadowMapResolution", DEFAULT_RESOLUTION);
		float distance = number(optionValues, "shadowDistance", DEFAULT_DISTANCE);
		Float fov = optionalNumber(optionValues, "shadowMapFov");
		float renderMultiplier = number(optionValues, "shadowDistanceRenderMul", -1.0F);
		float interval = number(optionValues, "shadowIntervalSize", DEFAULT_INTERVAL);
		float sunPathRotation = number(optionValues, "sunPathRotation", 0.0F);
		if (resolution <= 0 || distance <= 0.0F) {
			throw new IllegalArgumentException("shadow resolution and distance must be positive");
		}
		if (interval < 0.0F) {
			throw new IllegalArgumentException("shadow interval must not be negative");
		}
		Optional<Float> shadowMapFov = fov == null ? Optional.empty() : Optional.of(fov);
		Matrix4f modelView = modelView(sunAngleDegrees, sunPathRotation, interval, cameraX, cameraY, cameraZ);
		Matrix4f modelViewInverse = new Matrix4f(modelView).invert();
		Matrix4f projection = shadowMapFov.isPresent() ? perspective(shadowMapFov.get()) : orthographic(distance);
		Matrix4f projectionInverse = new Matrix4f(projection).invert();
		float offsetX = snap(cameraX, interval);
		float offsetY = snap(cameraY, interval);
		float offsetZ = snap(cameraZ, interval);
		ShadowMatrices matrices = new ShadowMatrices(modelView, modelViewInverse, projection, projectionInverse,
			offsetX, offsetY, offsetZ, interval);
		return new ShadowPassPlan(resolution, distance, shadowMapFov, renderMultiplier, interval, sunPathRotation,
			cullingMode(properties.shadowCulling().orElse("default")), matrices,
			properties.shadowTerrain().orElse(true), properties.shadowTranslucent().orElse(true),
			properties.shadowEntities().orElse(true), properties.shadowBlockEntities().orElse(true));
	}

	public static ShadowMatrices matrices(Map<String, String> optionValues, float sunAngleDegrees,
		double cameraX, double cameraY, double cameraZ) {
		return calculate(optionValues, ShaderProperties.empty(), sunAngleDegrees, cameraX, cameraY, cameraZ).matrices();
	}

	private static Matrix4f modelView(float sunAngleDegrees, float sunPathRotation, float interval,
		double cameraX, double cameraY, double cameraZ) {
		float shadowAngle = normalize(sunAngleDegrees + 90.0F) / 360.0F;
		float skyAngle = shadowAngle < 0.25F ? shadowAngle + 0.75F : shadowAngle - 0.25F;
		Matrix4f result = new Matrix4f()
			.rotateX((float) Math.toRadians(90.0F))
			.rotateZ((float) Math.toRadians(-skyAngle * 360.0F))
			.rotateX((float) Math.toRadians(sunPathRotation));
		if (interval != 0.0F) {
			result.translate(snap(cameraX, interval), snap(cameraY, interval), snap(cameraZ, interval));
		}
		return result;
	}

	private static Matrix4f orthographic(float distance) {
		return new Matrix4f().setOrthoSymmetric(distance * 2.0F, distance * 2.0F, NEAR, FAR, false);
	}

	private static Matrix4f perspective(float fov) {
		float scale = (float) (1.0 / Math.tan(Math.toRadians(fov) * 0.5));
		return new Matrix4f(scale, 0.0F, 0.0F, 0.0F,
			0.0F, scale, 0.0F, 0.0F,
			0.0F, 0.0F, (FAR + NEAR) / (NEAR - FAR), -1.0F,
			0.0F, 0.0F, 2.0F * FAR * NEAR / (NEAR - FAR), 1.0F);
	}

	private static float snap(double coordinate, float interval) {
		if (interval == 0.0F) {
			return 0.0F;
		}
		return (float) coordinate % interval - interval / 2.0F;
	}

	private static ShadowPassPlan.CullingMode cullingMode(String value) {
		return switch (value.trim().toLowerCase()) {
			case "advanced" -> ShadowPassPlan.CullingMode.ADVANCED;
			case "safe_zone", "safezone" -> ShadowPassPlan.CullingMode.SAFE_ZONE;
			case "distance" -> ShadowPassPlan.CullingMode.DISTANCE;
			default -> ShadowPassPlan.CullingMode.DEFAULT;
		};
	}

	private static int integer(Map<String, String> values, String name, int fallback) {
		String value = values.get(name);
		if (value == null || value.isBlank()) return fallback;
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException(name + " must be an integer", exception);
		}
	}

	private static float number(Map<String, String> values, String name, float fallback) {
		String value = values.get(name);
		if (value == null || value.isBlank()) return fallback;
		try {
			float parsed = Float.parseFloat(value.trim());
			finite(parsed, name);
			return parsed;
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException(name + " must be a number", exception);
		}
	}

	private static Float optionalNumber(Map<String, String> values, String name) {
		if (!values.containsKey(name) || values.get(name).isBlank()) return null;
		float value = number(values, name, 0.0F);
		if (value <= 0.0F || value >= 180.0F) throw new IllegalArgumentException(name + " must be in (0, 180)");
		return value;
	}

	private static float normalize(float value) {
		return (value % 360.0F + 360.0F) % 360.0F;
	}

	private static void finite(double value, String name) {
		if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
	}

	private static void finite(float value, String name) {
		if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
	}
}
