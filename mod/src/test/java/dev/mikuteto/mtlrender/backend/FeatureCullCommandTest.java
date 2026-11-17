package dev.mikuteto.mtlrender.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.ValueLayout;

import org.junit.jupiter.api.Test;

final class FeatureCullCommandTest {
	@Test
	void writesOneBatchedRequestForAllFeatures() {
		CommandStream stream = new CommandStream();
		float[] clip = new float[16];
		float[] previous = new float[16];
		stream.featureCull(11L, 24L, 17, 6, 22L, 28L, 33L, clip, previous);

		assertEquals(45, stream.size());
		assertEquals(39, word(stream, 0));
		assertEquals(17, word(stream, 5));
		assertEquals(6, word(stream, 6));
		assertEquals(33, word(stream, 11));
	}

	@Test
	void rejectsInvalidStrideAndMatrices() {
		CommandStream stream = new CommandStream();
		float[] matrix = new float[16];
		assertThrows(IllegalArgumentException.class, () -> stream.featureCull(1L, 0L, 1, 5, 2L, 0L, 3L, matrix, matrix));
		assertThrows(IllegalArgumentException.class, () -> stream.featureCull(1L, 0L, 1, 6, 2L, 0L, 3L, new float[15], matrix));
	}

	private static int word(CommandStream stream, long index) {
		return stream.words().getAtIndex(ValueLayout.JAVA_INT, index);
	}
}
