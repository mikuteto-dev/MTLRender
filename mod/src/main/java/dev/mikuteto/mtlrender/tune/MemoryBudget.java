package dev.mikuteto.mtlrender.tune;

public record MemoryBudget(long recommendedWorkingSet, long poolCap, long transientCap, long terrainCap) {
	public MemoryBudget {
		if (recommendedWorkingSet < 0 || poolCap < 0 || transientCap < 0 || terrainCap < 0) {
			throw new IllegalArgumentException("memory budget values must be non-negative");
		}
	}

	public static MemoryBudget derive(Hardware hardware, Tier tier, TuneProfile profile) {
		long recommended = hardware.nativeCaps().recommendedMaxWorkingSet();
		if (recommended <= 0) {
			recommended = hardware.memoryBytes() > 0 ? hardware.memoryBytes() / 4 : 512L << 20;
		}
		long tierShare = switch (tier) {
			case LOW -> 20;
			case MID -> 25;
			case HIGH -> 30;
			case ULTRA -> 35;
		};
		long total = saturatingMultiply(recommended, tierShare, 100);
		long pool = total / 5;
		long transientBudget = total / 5;
		long terrain = total - pool - transientBudget;
		if (!profile.compactVertices()) {
			terrain = Math.min(recommended / 2, saturatingAdd(terrain, total / 20));
		}
		return new MemoryBudget(recommended, pool, transientBudget, terrain);
	}

	private static long saturatingMultiply(long value, long multiplier, long divisor) {
		if (value > Long.MAX_VALUE / multiplier) {
			return Long.MAX_VALUE / divisor;
		}
		return value * multiplier / divisor;
	}

	private static long saturatingAdd(long left, long right) {
		return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
	}
}
