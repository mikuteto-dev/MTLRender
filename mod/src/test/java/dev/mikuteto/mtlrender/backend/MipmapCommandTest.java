package dev.mikuteto.mtlrender.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.foreign.ValueLayout;

import org.junit.jupiter.api.Test;

final class MipmapCommandTest {
	@Test
	void writesViewRelativeMipRange() {
		CommandStream stream = new CommandStream();
		stream.generateMipmaps(0x1122_3344_5566_7788L, 3, 5);

		assertEquals(5, stream.size());
		assertEquals(42, word(stream, 0));
		assertEquals(0x5566_7788, word(stream, 1));
		assertEquals(0x1122_3344, word(stream, 2));
		assertEquals(3, word(stream, 3));
		assertEquals(5, word(stream, 4));
	}

	private static int word(CommandStream stream, long index) {
		return stream.words().getAtIndex(ValueLayout.JAVA_INT, index);
	}
}
