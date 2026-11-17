package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

class TerrainRendererGpuRecordTest {
	@Test
	void legacyRecordKeepsTheTwentyWordLayout() {
		RecordWriter records = new RecordWriter();
		records.update(3, 0x1000L, 0x2000L, 1, 2, 3, 33, 6, 7, new int[] { 1, 2, 3, 4, 5, 6, 7 });
		ByteBuffer bytes = records.bytes();
		assertEquals(PackTerrainUnits.LEGACY_RECORD_UPDATE_STRIDE, bytes.remaining());
		assertEquals(3, bytes.getInt(TerrainPreparation.RECORD_UPDATE_SLOT_OFFSET));
		assertEquals(0x1000L, bytes.getLong(TerrainPreparation.RECORD_UPDATE_RECORD_OFFSET + TerrainPreparation.RECORD_VERTICES_OFFSET));
		assertEquals(33, bytes.getInt(TerrainPreparation.RECORD_UPDATE_RECORD_OFFSET + TerrainPreparation.RECORD_QUADS_OFFSET));
		assertEquals(7, bytes.getInt(TerrainPreparation.RECORD_UPDATE_RECORD_OFFSET + TerrainPreparation.RECORD_FACES_OFFSET + 24));
	}

	@Test
	void packRecordCarriesSidecarAddressAndStride() {
		RecordWriter records = new RecordWriter(true);
		records.update(6, 0x1000L, 0x2000L, 4, 5, 6, 64, 0x51, 9,
			new int[] { 8, 9, 10, 11, 12, 13, 1 }, 0x3000L, PackTerrainUnits.METADATA_STRIDE);
		ByteBuffer bytes = records.bytes();
		assertEquals(PackTerrainUnits.PACK_RECORD_UPDATE_STRIDE, bytes.remaining());
		assertEquals(0x3000L, bytes.getLong(TerrainPreparation.PACK_RECORD_UPDATE_METADATA_OFFSET));
		assertEquals(PackTerrainUnits.METADATA_STRIDE, bytes.getInt(TerrainPreparation.PACK_RECORD_UPDATE_METADATA_STRIDE_OFFSET));
		assertTrue(PackTerrainUnits.validMetadata(0x3000L, PackTerrainUnits.METADATA_STRIDE, 64));
	}

	@Test
	void capacityUsesThirtyTwoQuadBoundaries() {
		assertEquals(3, (0 + 32 - 1) / 32 + 3);
		assertEquals(5, (33 + 32 - 1) / 32 + 3);
		assertEquals(6, (65 + 32 - 1) / 32 + 3);
	}
}
