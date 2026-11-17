package dev.mikuteto.mtlrender.pack.shadow;

import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ProgramId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ShadowProgramSelection(
	ShadowProgramKind requestedKind,
	Optional<Program> program,
	String sourceName,
	List<ProgramId> fallbackPath,
	boolean optional,
	boolean disabled) {
	public ShadowProgramSelection {
		requestedKind = Objects.requireNonNull(requestedKind, "requestedKind");
		program = Objects.requireNonNull(program, "program");
		sourceName = Objects.requireNonNull(sourceName, "sourceName");
		fallbackPath = List.copyOf(fallbackPath);
	}

	public boolean resolved() {
		return program.isPresent();
	}

	public boolean requiredFailure() {
		return !optional && !resolved() && !disabled;
	}
}
