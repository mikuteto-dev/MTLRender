package dev.mikuteto.mtlrender.pack.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record PackFunctionConstants(
	Map<String, String> optionConstants,
	Map<String, String> uniformConstants,
	boolean genericFallback,
	int hardVariantCap
) {
	public static final String IS_EYE_IN_WATER = "isEyeInWater";
	public static final String RAIN_STRENGTH_ZERO = "rainStrengthZero";

	public PackFunctionConstants {
		optionConstants = values(optionConstants, "option constants");
		uniformConstants = values(uniformConstants, "uniform constants");
		if (hardVariantCap < 1) throw new IllegalArgumentException("variant cap must be positive");
		if (genericFallback && (!optionConstants.isEmpty() || !uniformConstants.isEmpty())) {
			throw new IllegalArgumentException("generic fallback cannot carry specialised constants");
		}
	}

	public static PackFunctionConstants genericFallback(int hardVariantCap) {
		return new PackFunctionConstants(Map.of(), Map.of(), true, hardVariantCap);
	}

	public static PackFunctionConstants specialised(Map<String, String> options, boolean isEyeInWater,
		boolean rainStrengthZero, int hardVariantCap) {
		return new PackFunctionConstants(options,
			Map.of(IS_EYE_IN_WATER, Boolean.toString(isEyeInWater), RAIN_STRENGTH_ZERO,
				Boolean.toString(rainStrengthZero)), false, hardVariantCap);
	}

	public boolean isSpecialised() { return !genericFallback; }

	private static Map<String, String> values(Map<String, String> source, String label) {
		Objects.requireNonNull(source, label);
		TreeMap<String, String> sorted = new TreeMap<>();
		for (Map.Entry<String, String> entry : source.entrySet()) {
			Objects.requireNonNull(entry.getKey(), label + " key");
			Objects.requireNonNull(entry.getValue(), label + " value");
			sorted.put(entry.getKey(), entry.getValue());
		}
		return Map.copyOf(sorted);
	}
}
