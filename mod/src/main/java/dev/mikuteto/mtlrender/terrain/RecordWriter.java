package dev.mikuteto.mtlrender.terrain;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class RecordWriter {
	private static final int FACE_WORDS = TerrainPreparation.FACE_COUNT;
	private static final int INITIAL_RECORDS = 4096;
	private static final int MAX_RECORDS = 1 << 19;
	private final boolean pack;
	private final int updateStride;
	private MemorySegment memory;
	private MemorySegment raw;
	private int count;

	RecordWriter() {
		this(false);
	}

	RecordWriter(boolean pack) {
		this.pack = pack;
		updateStride = pack ? TerrainPreparation.PACK_RECORD_UPDATE_STRIDE : TerrainPreparation.RECORD_UPDATE_STRIDE;
		memory = Arena.global().allocate((long) INITIAL_RECORDS * updateStride, 16);
		raw = MemorySegment.ofAddress(memory.address()).reinterpret(memory.byteSize());
	}

	int count() {
		return count;
	}

	void reset() {
		count = 0;
	}

	void update(int slot, long vertices, long indices, int x, int y, int z, int quads, int flags, int recordSlot, int[] faces) {
		update(slot, vertices, indices, x, y, z, quads, flags, recordSlot, faces, 0L, 0);
	}

	void update(int slot, long vertices, long indices, int x, int y, int z, int quads, int flags, int recordSlot, int[] faces,
		long metadata, int metadataStride) {
		long offset = (long) count * updateStride;
		if (offset + updateStride > raw.byteSize()) {
			grow();
		}
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.RECORD_UPDATE_SLOT_OFFSET, slot);
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.RECORD_UPDATE_RESERVED_OFFSET, 0);
		raw.set(ValueLayout.JAVA_LONG_UNALIGNED, offset + TerrainPreparation.RECORD_UPDATE_RECORD_OFFSET + TerrainPreparation.RECORD_VERTICES_OFFSET, vertices);
		raw.set(ValueLayout.JAVA_LONG_UNALIGNED, offset + TerrainPreparation.RECORD_UPDATE_RECORD_OFFSET + TerrainPreparation.RECORD_INDICES_OFFSET, indices);
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.RECORD_UPDATE_RECORD_OFFSET + TerrainPreparation.RECORD_ORIGIN_X_OFFSET, x);
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.RECORD_UPDATE_RECORD_OFFSET + TerrainPreparation.RECORD_ORIGIN_Y_OFFSET, y);
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.RECORD_UPDATE_RECORD_OFFSET + TerrainPreparation.RECORD_ORIGIN_Z_OFFSET, z);
		raw.set(ValueLayout.JAVA_FLOAT_UNALIGNED, offset + TerrainPreparation.RECORD_UPDATE_RECORD_OFFSET + TerrainPreparation.RECORD_VISIBILITY_OFFSET, 0.0F);
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.RECORD_UPDATE_RECORD_OFFSET + TerrainPreparation.RECORD_QUADS_OFFSET, quads);
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.RECORD_UPDATE_RECORD_OFFSET + TerrainPreparation.RECORD_FLAGS_OFFSET, flags);
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.RECORD_UPDATE_RECORD_SLOT_OFFSET, recordSlot);
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.RECORD_UPDATE_RECORD_OFFSET + TerrainPreparation.RECORD_RESERVED_OFFSET, 0);
	for (int i = 0; i < FACE_WORDS; i++) {
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.RECORD_UPDATE_RECORD_OFFSET + TerrainPreparation.RECORD_FACES_OFFSET + 4L * i, faces == null ? 0 : faces[i]);
	}
	raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.RECORD_UPDATE_RECORD_OFFSET + TerrainPreparation.RECORD_PADDING_OFFSET, 0);
	if (pack) {
		raw.set(ValueLayout.JAVA_LONG_UNALIGNED, offset + TerrainPreparation.PACK_RECORD_UPDATE_METADATA_OFFSET, metadata);
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.PACK_RECORD_UPDATE_METADATA_STRIDE_OFFSET, metadataStride);
	}
	count++;
	}

	void load(byte[] values) {
		if (values.length % updateStride != 0) {
			throw new IllegalArgumentException("terrain record updates are not stride aligned");
		}
		int loaded = values.length / updateStride;
		ensureCapacity(loaded);
		if (loaded > 0) {
			MemorySegment.copy(MemorySegment.ofArray(values), 0, raw, 0, (long) loaded * updateStride);
		}
		count = loaded;
	}

	ByteBuffer bytes() {
		return raw.asSlice(0, (long) count * updateStride).asByteBuffer().order(ByteOrder.nativeOrder());
	}

	byte[] snapshot() {
		ByteBuffer source = bytes();
		byte[] result = new byte[source.remaining()];
		source.get(result);
		return result;
	}

	private void ensureCapacity(int required) {
		while (raw.byteSize() / updateStride < required) {
			grow();
		}
	}

	private void grow() {
		long currentRecords = raw.byteSize() / updateStride;
		if (currentRecords >= MAX_RECORDS) {
			throw new IllegalStateException("terrain record frame exceeds capacity");
		}
		long largerBytes = Math.min((long) MAX_RECORDS * updateStride, raw.byteSize() * 2);
		MemorySegment larger = Arena.global().allocate(largerBytes, 16);
		MemorySegment.copy(raw, 0, larger, 0, raw.byteSize());
		memory = larger;
		raw = MemorySegment.ofAddress(larger.address()).reinterpret(larger.byteSize());
	}
}
