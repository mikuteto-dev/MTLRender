package dev.mikuteto.mtlrender.tune;

import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.pack.tess.TessellationStrategy;
import dev.mikuteto.mtlrender.terrain.OcclusionMode;

public record TuneProfile(TerrainDrawPath drawPath, OcclusionMode occlusionMode, int cullingInterval, int lodMinRing, boolean compactVertices,
	TessellationStrategy tessellationStrategy) {
	public TuneProfile(TerrainDrawPath drawPath, OcclusionMode occlusionMode, int cullingInterval, int lodMinRing, boolean compactVertices) {
		this(drawPath, occlusionMode, cullingInterval, lodMinRing, compactVertices, TessellationStrategy.COMPUTE);
	}

	public TuneProfile(boolean indexed, boolean occlusion, int cullingInterval, int lodMinRing, boolean compactVertices) {
		this(indexed ? TerrainDrawPath.INDEXED : TerrainDrawPath.NON_INDEXED, occlusion ? OcclusionMode.TWO_PHASE_HIZ : OcclusionMode.OFF,
			cullingInterval, lodMinRing, compactVertices);
	}

	public TuneProfile(boolean indexed, OcclusionMode occlusionMode, int cullingInterval, int lodMinRing, boolean compactVertices) {
		this(indexed ? TerrainDrawPath.INDEXED : TerrainDrawPath.NON_INDEXED, occlusionMode, cullingInterval, lodMinRing, compactVertices);
	}

	public boolean indexed() {
		return drawPath == TerrainDrawPath.INDEXED;
	}

	public boolean occlusion() {
		return occlusionMode == OcclusionMode.TWO_PHASE_HIZ;
	}

	public static TuneProfile defaults(Tier tier) {
		return switch (tier) {
			case LOW -> new TuneProfile(TerrainDrawPath.NON_INDEXED, OcclusionMode.OFF, 240, 3, true);
			case MID -> new TuneProfile(TerrainDrawPath.INDEXED, OcclusionMode.OFF, 480, 3, true);
			case HIGH -> new TuneProfile(TerrainDrawPath.INDEXED, OcclusionMode.TWO_PHASE_HIZ, 960, 3, true);
			case ULTRA -> new TuneProfile(TerrainDrawPath.INDEXED, OcclusionMode.TWO_PHASE_HIZ, 1920, 3, true);
		};
	}

	public static TuneProfile resolve(Config.Terrain terrain) {
		return resolve(terrain, -1, -1);
	}

	public static TuneProfile resolve(Config.Terrain terrain, int width, int height) {
		TuneProfile automatic = width > 0 && height > 0 ? Calibration.profile(width, height) : Calibration.profile();
		if (automatic == null) {
			automatic = defaults(Calibration.tier());
		}
		TerrainDrawPath drawPath = TerrainDrawPath.fromConfig(terrain.drawPath, automatic.drawPath());
		if (terrain.indexed != null && !"auto".equalsIgnoreCase(terrain.indexed)) {
			drawPath = Boolean.parseBoolean(terrain.indexed) ? TerrainDrawPath.INDEXED : TerrainDrawPath.NON_INDEXED;
		}
		return new TuneProfile(
			drawPath,
			value(terrain.occlusion, automatic.occlusionMode),
			value(terrain.cullingInterval, automatic.cullingInterval),
			value(terrain.lodMinRing, automatic.lodMinRing),
			value(terrain.compactVertices, automatic.compactVertices),
			TessellationStrategy.fromConfig(terrain.tessellationStrategy, automatic.tessellationStrategy)
		);
	}

	private static OcclusionMode value(String configured, OcclusionMode automatic) {
		if (configured == null || "auto".equalsIgnoreCase(configured)) {
			return automatic;
		}
		OcclusionMode selected = OcclusionMode.fromString(configured);
		return selected == OcclusionMode.AUTO ? automatic : selected;
	}

	private static boolean value(String configured, boolean automatic) {
		return configured == null || "auto".equalsIgnoreCase(configured) ? automatic : Boolean.parseBoolean(configured);
	}

	private static int value(String configured, int automatic) {
		if (configured == null || "auto".equalsIgnoreCase(configured)) {
			return automatic;
		}
		try {
			return Integer.parseInt(configured);
		} catch (NumberFormatException e) {
			return automatic;
		}
	}
}
