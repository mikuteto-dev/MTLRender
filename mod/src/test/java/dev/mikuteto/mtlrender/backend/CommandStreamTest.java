package dev.mikuteto.mtlrender.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.ValueLayout;

import org.junit.jupiter.api.Test;

final class CommandStreamTest {
	private static final int OPCODE = 28;
	private static final int WORDS = 30;

	@Test
	void encodesMinimumInLittleEndianContractOrder() {
		CommandStream stream = new CommandStream();
		stream.meshDispatch(1, 2, 3, 4, 5, 6, 1, 1, 1, 0x1122_3344_5566_7788L, 0, 64, 0, 1, 1, 2, 1, 1, 1, 1);

		assertEquals(WORDS, stream.size());
		assertEquals((WORDS << 16) | OPCODE, word(stream, 0));
		assertEquals(1, word(stream, 1));
		assertEquals(2, word(stream, 2));
		assertEquals(3, word(stream, 3));
		assertEquals(4, word(stream, 4));
		assertEquals(5, word(stream, 5));
		assertEquals(6, word(stream, 6));
		assertEquals(1, word(stream, 7));
		assertEquals(1, word(stream, 8));
		assertEquals(1, word(stream, 9));
		assertEquals(0x5566_7788, word(stream, 10));
		assertEquals(0x1122_3344, word(stream, 11));
		assertEquals(0, word(stream, 12));
		assertEquals(0, word(stream, 13));
		assertEquals(64, word(stream, 14));
		assertEquals(0, word(stream, 15));
		assertEquals(0, word(stream, 16));
		assertEquals(0, word(stream, 17));
		assertEquals(1, word(stream, 18));
		assertEquals(0, word(stream, 19));
		assertEquals(1, word(stream, 20));
		assertEquals(0, word(stream, 21));
		assertEquals(2, word(stream, 22));
		assertEquals(0, word(stream, 23));
		assertEquals(1, word(stream, 24));
		assertEquals(0, word(stream, 25));
		assertEquals(1, word(stream, 26));
		assertEquals(1, word(stream, 27));
		assertEquals(1, word(stream, 28));
		assertEquals(0, word(stream, 29));
	}

	@Test
	void acceptsOutputBoundariesAndGrowsWithoutChangingEarlierWords() {
		CommandStream stream = new CommandStream();
		stream.meshDispatch(65535, 65535, 65535, 65535, 65535, 65535, 16384, 256, 512,
			0x1000, 0, 1L << 20, 0, 1, 1, 2, 1, 1, 1, 1);
		for (int index = 0; index < 2200; index++) {
			stream.meshDispatch(1, 1, 1, 1, 1, 1, 1, 1, 1, 0x2000, 0, 64, 0, 1, 1, 2, 1, 1, 1, 1);
		}

		assertEquals(WORDS * 2201L, stream.size());
		assertEquals((WORDS << 16) | OPCODE, word(stream, 0));
		assertEquals(65535, word(stream, 1));
		assertEquals((WORDS << 16) | OPCODE, word(stream, WORDS));
	}

	@Test
	void invalidArgumentsDoNotAppend() {
		int[][] invalid = {
			{0, 1, 1, 1, 1, 1, 1, 1, 1},
			{-1, 1, 1, 1, 1, 1, 1, 1, 1},
			{1, 1, 1, 0, 1, 1, 1, 1, 1},
			{1, 1, 1, -1, 1, 1, 1, 1, 1},
			{1, 1, 1, 1, 1, 1, 0, 1, 1},
			{1, 1, 1, 1, 1, 1, 16385, 1, 1},
			{1, 1, 1, 1, 1, 1, 1, 257, 1},
			{1, 1, 1, 1, 1, 1, 1, 1, 513}
		};
		for (int[] values : invalid) {
			CommandStream stream = new CommandStream();
			stream.meshDispatch(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8],
				0x1000, 0, 64, 0, 1, 1, 2, 1, 1, 1, 1);
			assertEquals(0, stream.size());
		}

		CommandStream ranges = new CommandStream();
		ranges.meshDispatch(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, 64, 0, 1, 1, 2, 1, 1, 1, 1);
		assertEquals(0, ranges.size());
		ranges.meshDispatch(1, 1, 1, 1, 1, 1, 1, 1, 1, Long.MAX_VALUE, 1, 64, 0, 1, 1, 2, 1, 1, 1, 1);
		assertEquals(0, ranges.size());
		ranges.meshDispatch(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 64, 64, 1, 1, 2, 1, 1, 1, 1);
		assertEquals(0, ranges.size());
	}

	@Test
	void malformedOrTruncatedFrameCannotResynchronizeInsidePayload() {
		int malformedHeader = ((WORDS - 2) << 16) | OPCODE;
		int validHeader = (WORDS << 16) | OPCODE;
		assertEquals(28, malformedHeader & 0xffff);
		assertEquals(WORDS - 2, malformedHeader >>> 16);
		assertEquals(WORDS, validHeader >>> 16);
		int[] truncated = new int[WORDS - 3];
		truncated[0] = malformedHeader;
		assertTrue(truncated.length < (malformedHeader >>> 16));
	}

	@Test
	void defaultStreamDoesNotEmitMeshDispatch() {
		assertEquals(0, new CommandStream().size());
	}

	private static int word(CommandStream stream, long index) {
		return stream.words().getAtIndex(ValueLayout.JAVA_INT, index);
	}
}
