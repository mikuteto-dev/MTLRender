package dev.mikuteto.mtlrender.pack.shadow;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ShadowPassPlan {
	public enum CullingMode {
		DEFAULT,
		ADVANCED,
		SAFE_ZONE,
		DISTANCE
	}

	private static final List<String> TARGET_NAMES = List.of(
		"shadowtex0", "shadowtex1", "shadowcolor0", "shadowcolor1", "shadowcolor2", "shadowcolor3",
		"shadowcolor4", "shadowcolor5", "shadowcolor6", "shadowcolor7"
	);

	private final int mapResolution;
	private final float shadowDistance;
	private final Optional<Float> shadowMapFov;
	private final float shadowDistanceRenderMul;
	private final float shadowIntervalSize;
	private final float sunPathRotation;
	private final CullingMode cullingMode;
	private final ShadowMatrices matrices;
	private final boolean terrain;
	private final boolean translucent;
	private final boolean entities;
	private final boolean blockEntities;

	public ShadowPassPlan(int mapResolution, float shadowDistance, Optional<Float> shadowMapFov,
		float shadowDistanceRenderMul, float shadowIntervalSize, float sunPathRotation, CullingMode cullingMode,
		ShadowMatrices matrices, boolean terrain, boolean translucent, boolean entities, boolean blockEntities) {
		if (mapResolution <= 0) throw new IllegalArgumentException("shadow map resolution must be positive");
		if (!Float.isFinite(shadowDistance) || shadowDistance <= 0.0F) throw new IllegalArgumentException("shadow distance must be positive");
		if (!Float.isFinite(shadowDistanceRenderMul) || !Float.isFinite(shadowIntervalSize) || shadowIntervalSize < 0.0F
			|| !Float.isFinite(sunPathRotation)) throw new IllegalArgumentException("shadow settings must be finite");
		Objects.requireNonNull(shadowMapFov, "shadow map FOV");
		shadowMapFov.ifPresent(value -> {
			if (!Float.isFinite(value) || value <= 0.0F || value >= 180.0F) throw new IllegalArgumentException("shadow map FOV must be in (0, 180)");
		});
		this.mapResolution = mapResolution;
		this.shadowDistance = shadowDistance;
		this.shadowMapFov = shadowMapFov;
		this.shadowDistanceRenderMul = shadowDistanceRenderMul;
		this.shadowIntervalSize = shadowIntervalSize;
		this.sunPathRotation = sunPathRotation;
		this.cullingMode = Objects.requireNonNull(cullingMode, "culling mode");
		this.matrices = Objects.requireNonNull(matrices, "matrices");
		this.terrain = terrain;
		this.translucent = translucent;
		this.entities = entities;
		this.blockEntities = blockEntities;
	}

	public static ShadowPassPlan disabled() {
		return new ShadowPassPlan(1024, 160.0F, Optional.empty(), -1.0F, 2.0F, 0.0F,
			CullingMode.DEFAULT, ShadowMatrices.identity(), false, false, false, false);
	}

	public int mapResolution() { return mapResolution; }
	public float shadowDistance() { return shadowDistance; }
	public Optional<Float> shadowMapFov() { return shadowMapFov; }
	public float shadowDistanceRenderMul() { return shadowDistanceRenderMul; }
	public float shadowIntervalSize() { return shadowIntervalSize; }
	public float sunPathRotation() { return sunPathRotation; }
	public CullingMode cullingMode() { return cullingMode; }
	public ShadowMatrices matrices() { return matrices; }
	public boolean shadowTerrain() { return terrain; }
	public boolean shadowTranslucent() { return translucent; }
	public boolean shadowEntities() { return entities; }
	public boolean shadowBlockEntities() { return blockEntities; }
	public List<String> targetNames() { return TARGET_NAMES; }
	public boolean hasTarget(String name) { return TARGET_NAMES.contains(name); }

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof ShadowPassPlan that)) return false;
		return mapResolution == that.mapResolution
			&& Float.compare(shadowDistance, that.shadowDistance) == 0
			&& Float.compare(shadowDistanceRenderMul, that.shadowDistanceRenderMul) == 0
			&& Float.compare(shadowIntervalSize, that.shadowIntervalSize) == 0
			&& Float.compare(sunPathRotation, that.sunPathRotation) == 0
			&& shadowMapFov.equals(that.shadowMapFov)
			&& cullingMode == that.cullingMode
			&& matrices.equals(that.matrices)
			&& terrain == that.terrain
			&& translucent == that.translucent
			&& entities == that.entities
			&& blockEntities == that.blockEntities;
	}

	@Override
	public int hashCode() {
		int result = Integer.hashCode(mapResolution);
		result = 31 * result + Float.hashCode(shadowDistance);
		result = 31 * result + shadowMapFov.hashCode();
		result = 31 * result + Float.hashCode(shadowDistanceRenderMul);
		result = 31 * result + Float.hashCode(shadowIntervalSize);
		result = 31 * result + Float.hashCode(sunPathRotation);
		result = 31 * result + cullingMode.hashCode();
		result = 31 * result + matrices.hashCode();
		result = 31 * result + Boolean.hashCode(terrain);
		result = 31 * result + Boolean.hashCode(translucent);
		result = 31 * result + Boolean.hashCode(entities);
		return 31 * result + Boolean.hashCode(blockEntities);
	}
}
