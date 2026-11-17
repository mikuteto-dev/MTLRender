package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.Program;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record PackProgramRequest(String pack, String dimension, String program,
		Map<Program.Stage, String> sources) {
	public PackProgramRequest {
		pack = requireName(pack, "pack");
		dimension = Objects.requireNonNull(dimension, "dimension");
		program = requireName(program, "program");
		Objects.requireNonNull(sources, "sources");
		if (!sources.containsKey(Program.Stage.VERTEX) || !sources.containsKey(Program.Stage.FRAGMENT)) {
			throw new IllegalArgumentException("render program requires vertex and fragment sources");
		}
		if (sources.containsKey(Program.Stage.COMPUTE)) {
			throw new IllegalArgumentException("compute source is not part of a render program request");
		}
		EnumMap<Program.Stage, String> copy = new EnumMap<>(Program.Stage.class);
		for (Map.Entry<Program.Stage, String> entry : sources.entrySet()) {
			Objects.requireNonNull(entry.getKey(), "source stage");
			copy.put(entry.getKey(), Objects.requireNonNull(entry.getValue(), "source"));
		}
		sources = Map.copyOf(copy);
	}

	public Map<Program.Stage, String> sourceMap() {
		return sources;
	}

	public Optional<String> source(Program.Stage stage) {
		return Optional.ofNullable(sources.get(Objects.requireNonNull(stage, "stage")));
	}

	private static String requireName(String value, String label) {
		Objects.requireNonNull(value, label);
		if (value.isBlank()) {
			throw new IllegalArgumentException(label + " must not be blank");
		}
		return value;
	}
}
