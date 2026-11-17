package dev.mikuteto.mtlrender.pack.runtime;

import java.util.Objects;
import java.util.Optional;

public final class PackPassFailurePolicy {
	public PackPassResult classify(PassKind kind, RuntimeProgram program,
		Optional<FullscreenPass> pass, Optional<PackProgramFailure> failure) {
		Objects.requireNonNull(kind, "pass kind");
		Objects.requireNonNull(program, "program");
		Objects.requireNonNull(pass, "pass");
		Objects.requireNonNull(failure, "failure");
		if (pass.isPresent() == failure.isPresent()) {
			throw new IllegalArgumentException("pass result must contain exactly one outcome");
		}
		if (pass.isPresent()) {
			FullscreenPass fullScreenPass = pass.get();
			if (fullScreenPass.kind() != kind || !fullScreenPass.program().equals(program)) {
				throw new IllegalArgumentException("full-screen pass does not match runtime program");
			}
			return PackPassResult.success(kind, fullScreenPass);
		}
		PackProgramFailure programFailure = failure.get();
		if (!programFailure.dimension().equals(program.dimension())
			|| !programFailure.program().equals(program.variantName())) {
			throw new IllegalArgumentException("failure does not match runtime program");
		}
		if (kind == PassKind.FINAL) {
			return PackPassResult.finalFailure(kind, program, programFailure);
		}
		return PackPassResult.optionalSkip(kind, program, programFailure);
	}

	public PackPassResult classifySuccess(PassKind kind, FullscreenPass pass) {
		Objects.requireNonNull(pass, "pass");
		return classify(kind, pass.program(), Optional.of(pass), Optional.empty());
	}

	public PackPassResult classifyFailure(PassKind kind, RuntimeProgram program, PackProgramFailure failure) {
		return classify(kind, program, Optional.empty(), Optional.of(failure));
	}
}
