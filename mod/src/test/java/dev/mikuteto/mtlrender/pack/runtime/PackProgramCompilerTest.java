package dev.mikuteto.mtlrender.pack.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PackProgramCompilerTest {
	@Test
	void emptyResultIsStable() {
		PackProgramCompiler.Result result = new PackProgramCompiler.Result(4, 0, 0, List.of());

		assertEquals(4, result.version());
		assertEquals(0, result.attempted());
		assertEquals(0, result.succeeded());
		assertEquals(0, result.failed());
	}

	@Test
	void failuresExposeNormalizedContext() {
		PackProgramFailure failure = new PackProgramFailure(
			"test-pack", "world0", "final", null, "missing required render final");
		PackProgramCompiler.Result result = new PackProgramCompiler.Result(7, 1, 0, List.of(failure));

		assertEquals("missing required render final", result.failures().getFirst().normalizedFirstError());
		assertTrue(result.requiredFinalMissing());
	}
}
