package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.Program;

import java.util.Objects;

public record PackProgramFailure(String pack, String dimension, String program, Program.Stage stage,
		String normalizedFirstError) {
	public PackProgramFailure {
		pack = requireName(pack, "pack");
		dimension = Objects.requireNonNull(dimension, "dimension");
		program = requireName(program, "program");
		normalizedFirstError = normalize(normalizedFirstError);
	}

	public String firstError() {
		return normalizedFirstError;
	}

	private static String requireName(String value, String label) {
		Objects.requireNonNull(value, label);
		if (value.isBlank()) {
			throw new IllegalArgumentException(label + " must not be blank");
		}
		return value;
	}

	private static String normalize(String value) {
		Objects.requireNonNull(value, "first error");
		String firstLine = value.replace('\r', '\n').split("\\n", 2)[0].trim();
		if (firstLine.isEmpty()) {
			throw new IllegalArgumentException("first error must not be blank");
		}
		return firstLine.replaceAll("\\s+", " ");
	}
}
