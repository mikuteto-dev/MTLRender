package dev.mikuteto.mtlrender.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class PropertiesPreprocessorTest {
	@Test
	void expandsDefinedConditions() {
		String source = """
			#if defined(A) && !defined(B)
			value=1
			#else
			value=0
			#endif
			""";

		assertEquals("value=1\n", PropertiesPreprocessor.preprocess(source, Map.of("A", "")));
		assertEquals("value=0\n", PropertiesPreprocessor.preprocess(source, Map.of("B", "")));
	}

	@Test
	void expandsMacrosInOutput() {
		assertEquals("value=2\n", PropertiesPreprocessor.preprocess("#define FOO 2\nvalue=FOO", Map.of()));
	}

	@Test
	void supportsIfdefAndIfndef() {
		String source = """
			#ifdef A
			a
			#endif
			#ifndef A
			not-a
			#endif
			""";

		assertEquals("a\n", PropertiesPreprocessor.preprocess(source, Map.of("A", "")));
		assertEquals("not-a\n", PropertiesPreprocessor.preprocess(source, Map.of()));
	}

	@Test
	void supportsElifAndUndef() {
		String source = """
			#define X 1
			#if X == 2
			two
			#elif X == 1
			one
			#else
			other
			#endif
			#undef X
			#if defined(X)
			still
			#endif
			""";

		assertEquals("one\n", PropertiesPreprocessor.preprocess(source, Map.of()));
	}

	@Test
	void evaluatesExpressions() {
		assertTrue(PropertiesPreprocessor.evaluate("1 && (2 > 1) || 0", Map.of()));
		assertTrue(PropertiesPreprocessor.evaluate("!defined(X)", Map.of()));
		assertTrue(PropertiesPreprocessor.evaluate("defined(X)", Map.of("X", "")));
		assertTrue(PropertiesPreprocessor.evaluate("MC_VERSION >= 260200", Map.of("MC_VERSION", "260200")));
		assertFalse(PropertiesPreprocessor.evaluate("MC_VERSION > 260200", Map.of("MC_VERSION", "260200")));
	}

	@Test
	void joinsContinuedLines() {
		String source = "#if defined(A) && \\\n    defined(B)\nyes\n#endif\n";
		assertEquals("yes\n", PropertiesPreprocessor.preprocess(source, Map.of("A", "", "B", "")));
	}
}
