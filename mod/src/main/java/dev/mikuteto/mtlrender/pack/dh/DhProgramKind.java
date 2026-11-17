package dev.mikuteto.mtlrender.pack.dh;

import java.util.Arrays;

public enum DhProgramKind {
	TERRAIN("dh_terrain", false),
	WATER("dh_water", false),
	GENERIC("dh_generic", false),
	SHADOW("dh_shadow", true);

	private final String sourceName;
	private final boolean shadow;

	DhProgramKind(String sourceName, boolean shadow) {
		this.sourceName = sourceName;
		this.shadow = shadow;
	}

	public String sourceName() {
		return sourceName;
	}

	public boolean isShadow() {
		return shadow;
	}

	public static DhProgramKind fromSourceName(String sourceName) {
		return Arrays.stream(values())
			.filter(kind -> kind.sourceName.equals(sourceName))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("unknown DH source program: " + sourceName));
	}
}
