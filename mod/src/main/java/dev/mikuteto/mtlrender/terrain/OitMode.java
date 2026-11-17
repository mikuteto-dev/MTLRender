package dev.mikuteto.mtlrender.terrain;

import dev.mikuteto.mtlrender.debug.AbSwitch;

public enum OitMode {
	OFF(0),
	K4(4),
	K5(5),
	K6(6),
	K7(7),
	K8(8);

	public static final int DEFAULT_K = 4;
	public static final int MIN_K = 4;
	public static final int MAX_K = 8;

	private final int k;

	OitMode(int k) {
		this.k = k;
	}

	public int k() {
		return k;
	}

	public boolean enabled() {
		return this != OFF;
	}

	public static OitMode fromK(int k) {
		return switch (k) {
			case 4 -> K4;
			case 5 -> K5;
			case 6 -> K6;
			case 7 -> K7;
			case 8 -> K8;
			default -> OFF;
		};
	}

	public static OitMode resolve(boolean configured) {
		return resolve(configured, DEFAULT_K);
	}

	public static OitMode resolve(boolean configured, int k) {
		return AbSwitch.is("oit", configured) ? fromK(k) : OFF;
	}
}
