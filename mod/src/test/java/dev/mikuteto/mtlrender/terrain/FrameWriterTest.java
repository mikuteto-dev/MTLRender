package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

class FrameWriterTest {
	@Test
	void unitWriterResetsAndGrowsWithoutChangingLayout() {
		UnitWriter writer = new UnitWriter();
		for (int i = 0; i < 4097; i++) {
			writer.unit(11L, 22L, i, 2, 3, 0.5F, 7, 1, 2, 9);
		}
		assertEquals(4097, writer.count());
		ByteBuffer bytes = writer.bytes().order(ByteOrder.nativeOrder());
		assertEquals(11L, bytes.getLong(0));
		assertEquals(7, bytes.getInt(TerrainPreparation.UNIT_QUADS_OFFSET));
		writer.reset();
		assertEquals(0, writer.count());
		assertEquals(0, writer.bytes().remaining());
	}

	@Test
	void packWritersUseSeparateLayouts() {
		UnitWriter units = new UnitWriter(true);
		units.packUnit(16L, 0L, 32L, PackTerrainUnits.METADATA_STRIDE, 0, 0, 0, 0, 1.0F, 1, 0, 1, 2);
		assertEquals(PackTerrainUnits.PACK_UNIT_STRIDE, units.bytes().remaining());
		RecordWriter records = new RecordWriter(true);
		records.update(0, 16L, 0L, 0, 0, 0, 1, PackTerrainUnits.PACK_FLAG, 0, new int[7], 32L, PackTerrainUnits.METADATA_STRIDE);
		assertEquals(PackTerrainUnits.PACK_RECORD_UPDATE_STRIDE, records.bytes().remaining());
	}

	@Test
	void recordAndVisibleLoadsPreserveBytes() {
		RecordWriter records = new RecordWriter();
		int[] faces = {1, 2, 3, 4, 5, 6, 7};
		records.update(1, 2L, 3L, 4, 5, 6, 7, 8, 9, faces);
		byte[] recordBytes = records.snapshot();
		RecordWriter loadedRecords = new RecordWriter();
		loadedRecords.load(recordBytes);
		assertArrayEquals(recordBytes, loadedRecords.snapshot());

		VisibleWriter visible = new VisibleWriter();
		visible.entry(12, 0.25F);
		byte[] visibleBytes = visible.snapshot();
		VisibleWriter loadedVisible = new VisibleWriter();
		loadedVisible.load(visibleBytes);
		assertArrayEquals(visibleBytes, loadedVisible.snapshot());
	}
}
