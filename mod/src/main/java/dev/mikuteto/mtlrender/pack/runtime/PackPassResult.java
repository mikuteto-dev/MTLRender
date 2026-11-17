package dev.mikuteto.mtlrender.pack.runtime;

import java.util.Objects;
import java.util.Optional;

public record PackPassResult(Action action, PassKind kind, RuntimeProgram program,
	Optional<FullscreenPass> pass, Optional<PackProgramFailure> failure) {
	public enum Action {
		SUCCESS,
		OPTIONAL_SKIP,
		FINAL_FAILURE
	}

	public PackPassResult {
		action = Objects.requireNonNull(action, "action");
		kind = Objects.requireNonNull(kind, "pass kind");
		program = Objects.requireNonNull(program, "program");
		pass = Objects.requireNonNull(pass, "pass");
		failure = Objects.requireNonNull(failure, "failure");
		if (action == Action.SUCCESS && (pass.isEmpty() || failure.isPresent())) {
			throw new IllegalArgumentException("successful pass must have a pass and no failure");
		}
		if (action != Action.SUCCESS && (pass.isPresent() || failure.isEmpty())) {
			throw new IllegalArgumentException("failed pass must have a failure and no pass");
		}
		if (pass.isPresent() && !pass.get().program().equals(program)) {
			throw new IllegalArgumentException("result program does not match full-screen pass");
		}
	}

	public static PackPassResult success(PassKind kind, FullscreenPass pass) {
		Objects.requireNonNull(pass, "pass");
		return new PackPassResult(Action.SUCCESS, kind, pass.program(), Optional.of(pass), Optional.empty());
	}

	public static PackPassResult optionalSkip(PassKind kind, RuntimeProgram program, PackProgramFailure failure) {
		return failed(Action.OPTIONAL_SKIP, kind, program, failure);
	}

	public static PackPassResult finalFailure(PassKind kind, RuntimeProgram program, PackProgramFailure failure) {
		return failed(Action.FINAL_FAILURE, kind, program, failure);
	}

	public boolean succeeded() {
		return action == Action.SUCCESS;
	}

	public boolean skipped() {
		return action == Action.OPTIONAL_SKIP;
	}

	public boolean surfacedFailure() {
		return action == Action.FINAL_FAILURE;
	}

	private static PackPassResult failed(Action action, PassKind kind, RuntimeProgram program,
		PackProgramFailure failure) {
		return new PackPassResult(action, kind, program, Optional.empty(), Optional.of(failure));
	}
}
