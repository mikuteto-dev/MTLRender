package dev.mikuteto.mtlrender.pack.precision;

import java.util.Objects;

public record ReflectedOutput(
	String name,
	int location,
	boolean locationKnown,
	int vectorWidth,
	int scalarWidth,
	ScalarType scalarType,
	NumericUsage numericUsage,
	boolean relaxedPrecision,
	boolean depth,
	int arrayDimensions,
	boolean arraySizeKnown
) {
	public ReflectedOutput {
		Objects.requireNonNull(name);
		Objects.requireNonNull(scalarType);
		Objects.requireNonNull(numericUsage);
		if (vectorWidth < 0 || scalarWidth < 0 || arrayDimensions < 0) {
			throw new IllegalArgumentException("negative reflected output dimension");
		}
	}

	public enum ScalarType {
		FLOAT,
		SIGNED,
		UNSIGNED,
		BOOLEAN,
		UNKNOWN
	}

	public boolean shapeKnown() {
		return vectorWidth > 0 && scalarWidth > 0 && (arrayDimensions == 0 || arraySizeKnown);
	}
}
