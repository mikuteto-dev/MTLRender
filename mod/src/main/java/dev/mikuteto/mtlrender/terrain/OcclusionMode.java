package dev.mikuteto.mtlrender.terrain;

public enum OcclusionMode {
	AUTO(0, "auto"),
	TWO_PHASE_HIZ(1, "two_phase"),
	SINGLE_PHASE_HIZ(2, "single_phase"),
	CPU_BFS(3, "cpu_bfs"),
	OFF(4, "off");

	private final int code;
	private final String value;

	OcclusionMode(int code, String value) {
		this.code = code;
		this.value = value;
	}

	public int code() {
		return code;
	}

	public String configValue() {
		return value;
	}

	boolean usesGpuCulling() {
		return this == TWO_PHASE_HIZ || this == SINGLE_PHASE_HIZ;
	}

	boolean usesCpuVisibleSections() {
		return this == CPU_BFS || this == OFF;
	}

	public static OcclusionMode fromString(String value) {
		if (value == null) {
			return AUTO;
		}
		if ("true".equalsIgnoreCase(value)) {
			return TWO_PHASE_HIZ;
		}
		if ("false".equalsIgnoreCase(value)) {
			return OFF;
		}
		for (OcclusionMode mode : values()) {
			if (mode.value.equalsIgnoreCase(value)) {
				return mode;
			}
		}
		return AUTO;
	}

	public static OcclusionMode fromCode(int code) {
		for (OcclusionMode mode : values()) {
			if (mode.code == code) {
				return mode;
			}
		}
		return AUTO;
	}

	@Override
	public String toString() {
		return value;
	}
}
