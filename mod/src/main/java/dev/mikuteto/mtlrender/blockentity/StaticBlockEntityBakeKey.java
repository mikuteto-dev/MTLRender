package dev.mikuteto.mtlrender.blockentity;

import java.util.Objects;

public record StaticBlockEntityBakeKey(long resourceGeneration, String typeKey, String variantKey) {
	public StaticBlockEntityBakeKey {
		if (resourceGeneration < 0) {
			throw new IllegalArgumentException("resourceGeneration must not be negative");
		}
		typeKey = requireKey(typeKey, "typeKey");
		variantKey = requireKey(variantKey, "variantKey");
	}

	private static String requireKey(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
