package dev.mikuteto.mtlrender.pack.runtime;

import java.util.Objects;

public record RuntimeProgram(String dimension, String sourceName, String variantName, long renderPipeline) {
	public RuntimeProgram {
		Objects.requireNonNull(dimension, "dimension");
		sourceName = requireName(sourceName, "source name");
		variantName = requireName(variantName, "variant name");
		if (renderPipeline <= 0) {
			throw new IllegalArgumentException("render pipeline handle must be positive");
		}
	}

	private static String requireName(String value, String label) {
		Objects.requireNonNull(value, label);
		if (value.isBlank()) {
			throw new IllegalArgumentException(label + " must not be blank");
		}
		return value;
	}
}
