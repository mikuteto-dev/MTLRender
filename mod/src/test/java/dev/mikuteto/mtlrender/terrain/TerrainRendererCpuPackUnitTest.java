package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

class TerrainRendererCpuPackUnitTest {
	@Test
	void keepsNoPackUnitLayoutLegacy() {
		UnitWriter units = new UnitWriter();
		units.unit(0x1000L, 0L, 1, 2, 3, 0.5F, 4, 0, TerrainVertex.BLOCK.ordinal(), 9);
		ByteBuffer bytes = units.bytes();
		assertEquals(PackTerrainUnits.LEGACY_UNIT_STRIDE, bytes.remaining());
		assertEquals(9, bytes.getInt(TerrainPreparation.UNIT_SLOT_OFFSET));
		assertEquals(4, bytes.getInt(TerrainPreparation.UNIT_QUADS_OFFSET));
	}

	@Test
	void writesPackMetadataWithoutChangingUnitGeometryFields() {
		UnitWriter units = new UnitWriter(true);
		units.packUnit(0x1000L, 0x2000L, 0x3000L, PackTerrainUnits.METADATA_STRIDE, 32,
			64, -16, 80, 0.75F, 32, 1, TerrainVertex.COMPACT.ordinal() | PackTerrainUnits.PACK_FLAG, 7);

		ByteBuffer bytes = units.bytes();
		assertEquals(PackTerrainUnits.PACK_UNIT_STRIDE, bytes.remaining());
		assertEquals(0x1000L, bytes.getLong(TerrainPreparation.UNIT_VERTICES_OFFSET));
		assertEquals(0x2000L, bytes.getLong(TerrainPreparation.UNIT_INDICES_OFFSET));
		assertEquals(64, bytes.getInt(TerrainPreparation.UNIT_ORIGIN_X_OFFSET));
		assertEquals(-16, bytes.getInt(TerrainPreparation.UNIT_ORIGIN_Y_OFFSET));
		assertEquals(80, bytes.getInt(TerrainPreparation.UNIT_ORIGIN_Z_OFFSET));
		assertEquals(0.75F, bytes.getFloat(TerrainPreparation.UNIT_VISIBILITY_OFFSET));
		assertEquals(32, bytes.getInt(TerrainPreparation.UNIT_QUADS_OFFSET));
		assertEquals(1, bytes.getInt(TerrainPreparation.UNIT_INDEX_FORMAT_OFFSET));
		assertEquals(TerrainVertex.COMPACT.ordinal() | PackTerrainUnits.PACK_FLAG,
			bytes.getInt(TerrainPreparation.UNIT_VERTEX_FORMAT_OFFSET));
		assertEquals(7, bytes.getInt(TerrainPreparation.UNIT_SLOT_OFFSET));
		assertEquals(0x3000L, bytes.getLong(TerrainPreparation.PACK_UNIT_METADATA_OFFSET));
		assertEquals(PackTerrainUnits.METADATA_STRIDE, bytes.getInt(TerrainPreparation.PACK_UNIT_METADATA_STRIDE_OFFSET));
		assertEquals(32, bytes.getInt(TerrainPreparation.PACK_UNIT_QUAD_INDEX_OFFSET));
	}

	@Test
	void splitsMetadataRunsAtTheSameThirtyTwoQuadBoundaries() {
		UnitWriter units = new UnitWriter(true);
		for (int start = 0; start < 65; start += TerrainPreparation.QUADS_PER_UNIT) {
			int count = Math.min(TerrainPreparation.QUADS_PER_UNIT, 65 - start);
			units.packUnit(0x1000L + (long) start * 80L, 0L, 0x3000L, PackTerrainUnits.METADATA_STRIDE, start,
				16, 32, 48, 1.0F, count, 0, TerrainVertex.BLOCK.ordinal() | PackTerrainUnits.PACK_FLAG, 3);
		}

		ByteBuffer bytes = units.bytes();
		assertEquals(3, units.count());
		assertEquals(0, bytes.getInt(TerrainPreparation.PACK_UNIT_QUAD_INDEX_OFFSET));
		assertEquals(32, bytes.getInt(PackTerrainUnits.PACK_UNIT_STRIDE + TerrainPreparation.PACK_UNIT_QUAD_INDEX_OFFSET));
		assertEquals(64, bytes.getInt(2 * PackTerrainUnits.PACK_UNIT_STRIDE + TerrainPreparation.PACK_UNIT_QUAD_INDEX_OFFSET));
		assertEquals(1, bytes.getInt(2 * PackTerrainUnits.PACK_UNIT_STRIDE + TerrainPreparation.UNIT_QUADS_OFFSET));
	}
}
