package dev.mikuteto.mtlrender.terrain;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class UnitWriter {
	private static final int INITIAL_UNITS = 4096;
	private static final int MAX_UNITS = 1 << 20;
	private final boolean pack;
	private final int stride;

	record Result(ByteBuffer bytes, int count, long quads) {
		Result {
			bytes = bytes.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
		}
	}

	private MemorySegment memory;
	private MemorySegment raw;
	private int count;
	private long quads;

	UnitWriter() {
		this(false);
	}

	UnitWriter(boolean pack) {
		this.pack = pack;
		stride = pack ? TerrainPreparation.PACK_UNIT_STRIDE : TerrainPreparation.UNIT_STRIDE;
		memory = Arena.global().allocate((long) INITIAL_UNITS * stride, 16);
		raw = MemorySegment.ofAddress(memory.address()).reinterpret(memory.byteSize());
	}

	int count() {
		return count;
	}

	long quads() {
		return quads;
	}

	void reset() {
		count = 0;
		quads = 0;
	}

	void unit(long vertices, long indices, int x, int y, int z, float visibility, int quads, int indexType, int vertexFormat, int slot) {
		unit(vertices, indices, 0L, PackTerrainUnits.METADATA_STRIDE, 0, x, y, z, visibility, quads, indexType, vertexFormat, slot);
	}

	void packUnit(long vertices, long indices, long metadata, int metadataStride, int quadIndex, int x, int y, int z,
		float visibility, int quads, int indexType, int vertexFormat, int slot) {
		if (!pack || !PackTerrainUnits.validMetadata(metadata, metadataStride, Math.addExact(quadIndex, quads))) {
			throw new IllegalArgumentException("invalid pack terrain unit metadata");
		}
		unit(vertices, indices, metadata, metadataStride, quadIndex, x, y, z, visibility, quads, indexType, vertexFormat, slot);
	}

	private void unit(long vertices, long indices, long metadata, int metadataStride, int quadIndex, int x, int y, int z,
		float visibility, int quads, int indexType, int vertexFormat, int slot) {
		long offset = (long) count * stride;
		if (offset + stride > raw.byteSize()) {
			grow();
		}
		raw.set(ValueLayout.JAVA_LONG_UNALIGNED, offset + TerrainPreparation.UNIT_VERTICES_OFFSET, vertices);
		raw.set(ValueLayout.JAVA_LONG_UNALIGNED, offset + TerrainPreparation.UNIT_INDICES_OFFSET, indices);
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.UNIT_ORIGIN_X_OFFSET, x);
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.UNIT_ORIGIN_Y_OFFSET, y);
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.UNIT_ORIGIN_Z_OFFSET, z);
		raw.set(ValueLayout.JAVA_FLOAT_UNALIGNED, offset + TerrainPreparation.UNIT_VISIBILITY_OFFSET, visibility);
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.UNIT_QUADS_OFFSET, quads);
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.UNIT_INDEX_FORMAT_OFFSET, indexType);
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.UNIT_VERTEX_FORMAT_OFFSET, vertexFormat);
	if (pack) {
		raw.set(ValueLayout.JAVA_LONG_UNALIGNED, offset + TerrainPreparation.PACK_UNIT_METADATA_OFFSET, metadata);
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.PACK_UNIT_METADATA_STRIDE_OFFSET, metadataStride);
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.PACK_UNIT_QUAD_INDEX_OFFSET, quadIndex);
	} else {
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.UNIT_SLOT_OFFSET, slot);
	}
	if (pack) {
		raw.set(ValueLayout.JAVA_INT_UNALIGNED, offset + TerrainPreparation.UNIT_SLOT_OFFSET, slot);
	}
	this.quads += quads;
	count++;
	}

	ByteBuffer bytes() {
		return raw.asSlice(0, (long) count * stride).asByteBuffer().order(ByteOrder.nativeOrder());
	}

	Result result() {
		return new Result(bytes(), count, quads);
	}

	private void grow() {
		long currentUnits = raw.byteSize() / stride;
		if (currentUnits >= MAX_UNITS) {
			throw new IllegalStateException("terrain unit frame exceeds capacity");
		}
		long largerBytes = Math.min((long) MAX_UNITS * stride, raw.byteSize() * 2);
		MemorySegment larger = Arena.global().allocate(largerBytes, 16);
		MemorySegment.copy(raw, 0, larger, 0, raw.byteSize());
		memory = larger;
		raw = MemorySegment.ofAddress(larger.address()).reinterpret(larger.byteSize());
	}
}
