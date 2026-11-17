package dev.mikuteto.mtlrender.tune;

import java.util.Objects;

import dev.mikuteto.mtlrender.terrain.OcclusionMode;

public record CalibrationResult(
	int version,
	OcclusionMode occlusionMode,
	TerrainDrawPath drawPath,
	boolean indexed,
	boolean compact,
	int probeInterval,
	int lodMinRing,
	int width,
	int height
) {
	public static final int VERSION = 1;

	public CalibrationResult {
		Objects.requireNonNull(occlusionMode, "occlusionMode");
		Objects.requireNonNull(drawPath, "drawPath");
		if (version != VERSION || occlusionMode == OcclusionMode.AUTO || occlusionMode == OcclusionMode.CPU_BFS
			|| probeInterval < 1 || lodMinRing < 0 || lodMinRing > 3 || width < 1 || height < 1) {
			throw new IllegalArgumentException("invalid calibration result");
		}
	}

	public static CalibrationResult fromPayload(
		int version,
		int modeCode,
		int drawPathCode,
		boolean indexed,
		boolean compact,
		int probeInterval,
		int lodMinRing,
		int width,
		int height
	) {
		OcclusionMode mode = null;
		for (OcclusionMode candidate : OcclusionMode.values()) {
			if (candidate.code() == modeCode) {
				mode = candidate;
				break;
			}
		}
		TerrainDrawPath path = switch (drawPathCode) {
			case 1 -> TerrainDrawPath.NON_INDEXED;
			case 2 -> TerrainDrawPath.INDEXED;
			case 3 -> TerrainDrawPath.MESH;
			default -> null;
		};
		if (mode == null || mode == OcclusionMode.AUTO || mode == OcclusionMode.CPU_BFS || path == null) {
			return null;
		}
		try {
			return new CalibrationResult(version, mode, path, indexed, compact, probeInterval, lodMinRing, width, height);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
