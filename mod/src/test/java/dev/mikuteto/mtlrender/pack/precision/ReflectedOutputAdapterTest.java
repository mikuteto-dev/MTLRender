package dev.mikuteto.mtlrender.pack.precision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReflectedOutputAdapterTest {
	@Test
	void preservesLocationAndShapeContract() {
		ReflectedOutput output = new ReflectedOutput("albedo", 3, true, 4, 32,
			ReflectedOutput.ScalarType.FLOAT, NumericUsage.unknown(), false, false, 0, true);
		assertEquals(3, output.location());
		assertEquals(4, output.vectorWidth());
		assertTrue(output.shapeKnown());
		assertEquals(NumericUsage.PrecisionEvidence.REQUIRED, ReflectedOutputAdapter.numericUsage(output).precision());
	}

	@Test
	void rejectsIntegerAndUnknownMetadata() {
		ReflectedOutput integer = new ReflectedOutput("id", 1, true, 1, 32,
			ReflectedOutput.ScalarType.SIGNED, NumericUsage.unknown(), false, false, 0, true);
		ReflectedOutput unknown = new ReflectedOutput("unknown", -1, false, 0, 0,
			ReflectedOutput.ScalarType.UNKNOWN, NumericUsage.unknown(), false, false, 1, false);
		assertEquals(NumericUsage.Signedness.SIGNED, ReflectedOutputAdapter.numericUsage(integer).signedness());
		assertFalse(unknown.shapeKnown());
		assertEquals(NumericUsage.Signedness.UNKNOWN, ReflectedOutputAdapter.numericUsage(unknown).signedness());
	}

	@Test
	void locationLookupDoesNotUseListIndex() {
		List<ReflectedOutput> outputs = List.of(
			new ReflectedOutput("a", 4, true, 4, 16, ReflectedOutput.ScalarType.FLOAT, NumericUsage.unknown(), false, false, 0, true),
			new ReflectedOutput("b", -1, false, 4, 16, ReflectedOutput.ScalarType.FLOAT, NumericUsage.unknown(), false, false, 0, true));
		assertTrue(ReflectedOutputAdapter.hasKnownLocation(outputs, 4));
		assertFalse(ReflectedOutputAdapter.hasKnownLocation(outputs, 1));
	}
}
