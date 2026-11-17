package dev.mikuteto.mtlrender.tune;

import java.util.Locale;

public enum TerrainDrawPath {
	NON_INDEXED,
	INDEXED,
	MESH;

	public static TerrainDrawPath fromConfig(String configured, TerrainDrawPath automatic) {
		if (configured == null || "auto".equalsIgnoreCase(configured)) {
			return automatic;
		}
		return switch (configured.toLowerCase(Locale.ROOT)) {
			case "non_indexed", "non-indexed" -> NON_INDEXED;
			case "indexed" -> INDEXED;
			case "mesh" -> MESH;
			default -> automatic;
		};
	}
}
