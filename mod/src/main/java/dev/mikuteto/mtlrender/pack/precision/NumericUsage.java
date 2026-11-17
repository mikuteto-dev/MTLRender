package dev.mikuteto.mtlrender.pack.precision;

public record NumericUsage(Signedness signedness, PrecisionEvidence precision) {
	public enum Signedness {
		FLOAT,
		SIGNED,
		UNSIGNED,
		UNKNOWN
	}

	public enum PrecisionEvidence {
		PROVEN_SAFE,
		REQUIRED,
		UNKNOWN
	}

	public boolean safeForReduction() {
		return signedness == Signedness.FLOAT && precision == PrecisionEvidence.PROVEN_SAFE;
	}

	public static NumericUsage unknown() {
		return new NumericUsage(Signedness.UNKNOWN, PrecisionEvidence.UNKNOWN);
	}
}
