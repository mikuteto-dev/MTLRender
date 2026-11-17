package dev.mikuteto.mtlrender.pack.precision;

import java.util.List;

public final class ReflectedOutputAdapter {
	private ReflectedOutputAdapter() {
	}

	public static NumericUsage numericUsage(ReflectedOutput output) {
		if (output.scalarType() != ReflectedOutput.ScalarType.FLOAT) {
			return new NumericUsage(signedness(output.scalarType()), NumericUsage.PrecisionEvidence.REQUIRED);
		}
		if (output.scalarWidth() == 0) {
			return NumericUsage.unknown();
		}
		NumericUsage.PrecisionEvidence precision = output.scalarWidth() <= 16
			? NumericUsage.PrecisionEvidence.PROVEN_SAFE
			: NumericUsage.PrecisionEvidence.REQUIRED;
		return new NumericUsage(NumericUsage.Signedness.FLOAT, precision);
	}

	public static boolean hasKnownLocation(List<ReflectedOutput> outputs, int location) {
		return outputs.stream().anyMatch(output -> output.locationKnown() && output.location() == location);
	}

	private static NumericUsage.Signedness signedness(ReflectedOutput.ScalarType type) {
		return switch (type) {
			case SIGNED -> NumericUsage.Signedness.SIGNED;
			case UNSIGNED -> NumericUsage.Signedness.UNSIGNED;
			case FLOAT -> NumericUsage.Signedness.FLOAT;
			case BOOLEAN, UNKNOWN -> NumericUsage.Signedness.UNKNOWN;
		};
	}
}
