package dev.mikuteto.mtlrender.tune;

public enum Tier {
	LOW(10),
	MID(20),
	HIGH(40),
	ULTRA(Integer.MAX_VALUE);

	private final int maxGpuCores;

	Tier(int maxGpuCores) {
		this.maxGpuCores = maxGpuCores;
	}

	public static Tier of(Hardware hardware) {
		if (hardware.gpuCores() < 0) {
			return MID;
		}
		int cores = hardware.gpuCores();
		for (Tier tier : values()) {
			if (cores <= tier.maxGpuCores) {
				return tier;
			}
		}
		return ULTRA;
	}
}
