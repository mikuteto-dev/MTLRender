package dev.mikuteto.mtlrender.pack.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public record PackVariantPolicy(Set<String> optionNames, int hardVariantCap) {
	public PackVariantPolicy {
		Objects.requireNonNull(optionNames, "option names");
		TreeSet<String> sorted = new TreeSet<>();
		for (String name : optionNames) {
			if (name == null || name.isEmpty()) throw new IllegalArgumentException("option name must not be empty");
			sorted.add(name);
		}
		optionNames = Set.copyOf(sorted);
		if (hardVariantCap < 1) throw new IllegalArgumentException("variant cap must be positive");
	}

	public PackFunctionConstants genericFallback() {
		return PackFunctionConstants.genericFallback(hardVariantCap);
	}

	public PackFunctionConstants requested(Map<String, String> options, boolean isEyeInWater,
		boolean rainStrengthZero) {
		Objects.requireNonNull(options, "options");
		for (String name : options.keySet()) {
			if (!optionNames.contains(name)) throw new IllegalArgumentException("unknown option constant: " + name);
		}
		return PackFunctionConstants.specialised(options, isEyeInWater, rainStrengthZero, hardVariantCap);
	}

	public boolean canCompile(int specialisedVariants) {
		return specialisedVariants >= 0 && specialisedVariants < hardVariantCap;
	}

	public Selection select(PackFunctionConstants requested, Optional<PackFunctionConstants> compiled) {
		Objects.requireNonNull(requested, "requested");
		Objects.requireNonNull(compiled, "compiled");
		if (requested.hardVariantCap() != hardVariantCap) throw new IllegalArgumentException("variant cap mismatch");
		PackFunctionConstants active = compiled.filter(value -> value.equals(requested)).orElse(genericFallback());
		return new Selection(active, requested, !active.equals(requested));
	}

	public record Selection(PackFunctionConstants active, PackFunctionConstants requested, boolean compiling) {
		public Selection {
			Objects.requireNonNull(active, "active");
			Objects.requireNonNull(requested, "requested");
		}
	}
}
