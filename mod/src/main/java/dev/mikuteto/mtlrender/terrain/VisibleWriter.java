package dev.mikuteto.mtlrender.terrain;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class VisibleWriter {
	private static final int ENTRY_STRIDE = TerrainPreparation.VISIBLE_ENTRY_STRIDE;
	private static final int INITIAL_ENTRIES = 4096;
	private static final int MAX_ENTRIES = 1 << 18;

	private MemorySegment memory = Arena.global().allocate((long) INITIAL_ENTRIES * ENTRY_STRIDE, 16);
	private MemorySegment raw = MemorySegment.ofAddress(memory.address()).reinterpret(memory.byteSize());
	private int count;

	int count() {
		return count;
	}

	void reset() {
		count = 0;
	}

	void entry(int index, float visibility) {
		long offset = (long) count * ENTRY_STRIDE;
		if (offset + ENTRY_STRIDE > raw.byteSize()) {
			grow();
		}
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.VISIBLE_ENTRY_INDEX_OFFSET, index);
		raw.set(ValueLayout.JAVA_FLOAT_UNALIGNED, offset + TerrainPreparation.VISIBLE_ENTRY_VISIBILITY_OFFSET, visibility);
		count++;
	}

	void load(byte[] values) {
		int loaded = values.length / ENTRY_STRIDE;
		ensureCapacity(loaded);
		if (loaded > 0) {
			MemorySegment.copy(MemorySegment.ofArray(values), 0, raw, 0, (long) loaded * ENTRY_STRIDE);
		}
		count = loaded;
	}

	ByteBuffer bytes() {
		return raw.asSlice(0, (long) count * ENTRY_STRIDE).asByteBuffer().order(ByteOrder.nativeOrder());
	}

	byte[] snapshot() {
		ByteBuffer source = bytes();
		byte[] result = new byte[source.remaining()];
		source.get(result);
		return result;
	}

	private void ensureCapacity(int required) {
		while (raw.byteSize() / ENTRY_STRIDE < required) {
			grow();
		}
	}

	private void grow() {
		long currentEntries = raw.byteSize() / ENTRY_STRIDE;
		if (currentEntries >= MAX_ENTRIES) {
			throw new IllegalStateException("visible terrain frame exceeds capacity");
		}
		long largerBytes = Math.min((long) MAX_ENTRIES * ENTRY_STRIDE, raw.byteSize() * 2);
		MemorySegment larger = Arena.global().allocate(largerBytes, 16);
		MemorySegment.copy(raw, 0, larger, 0, raw.byteSize());
		memory = larger;
		raw = MemorySegment.ofAddress(larger.address()).reinterpret(larger.byteSize());
	}
}
