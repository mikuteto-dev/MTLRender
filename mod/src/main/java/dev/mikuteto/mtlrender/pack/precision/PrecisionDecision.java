package dev.mikuteto.mtlrender.pack.precision;

import java.util.Objects;

public record PrecisionDecision(
	String bufferId,
	String requestedFormat,
	String effectiveMetalFormat,
	ReductionKind reduction,
	boolean eligible,
	String reason
) {
	public PrecisionDecision(String bufferId, String requestedFormat, String effectiveMetalFormat,
		ReductionKind reduction, String reason) {
		this(bufferId, requestedFormat, effectiveMetalFormat, reduction, reduction != ReductionKind.NONE, reason);
	}
	public PrecisionDecision {
		Objects.requireNonNull(bufferId);
		Objects.requireNonNull(requestedFormat);
		Objects.requireNonNull(effectiveMetalFormat);
		Objects.requireNonNull(reduction);
		Objects.requireNonNull(reason);
	}

	public boolean changed() {
		return reduction != ReductionKind.NONE;
	}
}
