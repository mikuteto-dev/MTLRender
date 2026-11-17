package dev.mikuteto.mtlrender.terrain;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

final class SortWriter {
	static final int RECORD_STRIDE = 56;
	private static final int EXPECTED_STRIDE = Integer.BYTES;
	private static final int INITIAL_RECORDS = 128;
	private static final int INITIAL_EXPECTED = 1024;

	private MemorySegment recordMemory = Arena.ofAuto().allocate((long) INITIAL_RECORDS * RECORD_STRIDE, 16);
	private MemorySegment records = MemorySegment.ofAddress(recordMemory.address()).reinterpret(recordMemory.byteSize());
	private MemorySegment expectedMemory = Arena.ofAuto().allocate((long) INITIAL_EXPECTED * EXPECTED_STRIDE, 16);
	private MemorySegment expected = MemorySegment.ofAddress(expectedMemory.address()).reinterpret(expectedMemory.byteSize());
	private int count;
	private int recordBytes;
	private int expectedCount;
	private int maxPadded;
	private int itemCount;
	private int indexCount;

	void reset() {
		count = 0;
		recordBytes = 0;
		expectedCount = 0;
		maxPadded = 0;
		itemCount = 0;
		indexCount = 0;
	}

	int count() {
		return count;
	}

	int maxPadded() {
		return maxPadded;
	}

	int items() {
		return itemCount;
	}

	int indexCount() {
		return indexCount;
	}

	ByteBuffer bytes() {
		return records.asSlice(0, recordBytes).asByteBuffer();
	}

	ByteBuffer expectedBytes() {
		return expected.asSlice(0, (long) expectedCount * EXPECTED_STRIDE).asByteBuffer();
	}

	void add(long vertices, long indices, int quads, int indexType, int vertexFormat, int x, int y, int z, int[] order) {
		if (quads <= 0) {
			return;
		}
		int padded = 1;
		while (padded < quads && padded <= Integer.MAX_VALUE / 2) {
			padded <<= 1;
		}
		if (padded < quads) {
			return;
		}
		if ((long) count * RECORD_STRIDE + RECORD_STRIDE > records.byteSize()) {
			growRecords();
		}
		int idsOffset = itemCount;
		int indicesOffset = indexCount * 6;
		int expectedOffset = -1;
		if (order != null) {
			expectedOffset = expectedCount;
			ensureExpected(expectedCount + order.length);
			for (int value : order) {
				expected.set(ValueLayout.JAVA_INT_UNALIGNED, (long) expectedCount * EXPECTED_STRIDE, value);
				expectedCount++;
			}
		}
		long offset = (long) count * RECORD_STRIDE;
		records.set(ValueLayout.JAVA_LONG_UNALIGNED, offset, vertices);
		records.set(ValueLayout.JAVA_LONG_UNALIGNED, offset + 8, indices);
		records.set(ValueLayout.JAVA_INT_UNALIGNED, offset + 16, quads);
		records.set(ValueLayout.JAVA_INT_UNALIGNED, offset + 20, padded);
		records.set(ValueLayout.JAVA_INT_UNALIGNED, offset + 24, indexType);
		records.set(ValueLayout.JAVA_INT_UNALIGNED, offset + 28, vertexFormat);
		records.set(ValueLayout.JAVA_INT_UNALIGNED, offset + 32, idsOffset);
		records.set(ValueLayout.JAVA_INT_UNALIGNED, offset + 36, indicesOffset);
		records.set(ValueLayout.JAVA_INT_UNALIGNED, offset + 40, expectedOffset);
		records.set(ValueLayout.JAVA_INT_UNALIGNED, offset + 44, x);
		records.set(ValueLayout.JAVA_INT_UNALIGNED, offset + 48, y);
		records.set(ValueLayout.JAVA_INT_UNALIGNED, offset + 52, z);
		count++;
		recordBytes = count * RECORD_STRIDE;
		itemCount += padded;
		maxPadded = Math.max(maxPadded, padded);
		indexCount += quads;
	}

	private void ensureExpected(int needed) {
		if ((long) needed * EXPECTED_STRIDE <= expected.byteSize()) {
			return;
		}
		int size = (int) Math.max((long) expected.byteSize() * 2 / EXPECTED_STRIDE, needed);
		MemorySegment larger = Arena.ofAuto().allocate((long) size * EXPECTED_STRIDE, 16);
		MemorySegment.copy(expected, 0, larger, 0, (long) expectedCount * EXPECTED_STRIDE);
		expectedMemory = larger;
		expected = MemorySegment.ofAddress(larger.address()).reinterpret(larger.byteSize());
	}

	private void growRecords() {
		long size = records.byteSize() * 2;
		MemorySegment larger = Arena.ofAuto().allocate(size, 16);
		MemorySegment.copy(records, 0, larger, 0, recordBytes);
		recordMemory = larger;
		records = MemorySegment.ofAddress(larger.address()).reinterpret(larger.byteSize());
	}
}
