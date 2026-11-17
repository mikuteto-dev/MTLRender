package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

class PackTerrainJavaMeshTest {
	@Test
	void writesStateMidUvAndMidBlockWithSharedRecordStride() {
		PackTerrainJavaMesh mesh = new PackTerrainJavaMesh(PackTerrainLayout.ALL_FIELDS);
		float[] x = {0.0F, 1.0F, 1.0F, 0.0F};
		float[] y = {0.0F, 0.0F, 0.0F, 0.0F};
		float[] z = {0.0F, 0.0F, 1.0F, 1.0F};
		float[] u = {0.0F, 1.0F, 1.0F, 0.0F};
		float[] v = {0.0F, 0.0F, 1.0F, 1.0F};
		mesh.append(x, y, z, u, v, 0.0F, 0.0F, 0.0F, 37);
		ByteBuffer record = mesh.data().order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(PackTerrainLayout.RECORD_STRIDE, record.remaining());
		assertEquals(37, record.getInt(PackTerrainLayout.BLOCK_ID_OFFSET));
		assertEquals(0x40004000, record.getInt(PackTerrainLayout.MID_TEX_COORD_OFFSET));
		assertEquals(0x00002000, record.getInt(PackTerrainLayout.MID_BLOCK_OFFSET));
	}

	@Test
	void computesDistinctFaceOrientationForAllSixDirections() {
		for (int axis = 0; axis < 3; axis++) {
			for (int sign = -1; sign <= 1; sign += 2) {
				float[] x = {0.0F, 1.0F, 1.0F, 0.0F};
				float[] y = {0.0F, 0.0F, 1.0F, 1.0F};
				float[] z = {0.0F, 0.0F, 0.0F, 0.0F};
				if (axis == 0) { x = new float[] {sign, sign, sign, sign}; z = new float[] {0.0F, 1.0F, 1.0F, 0.0F}; }
				if (axis == 1) { y = new float[] {sign, sign, sign, sign}; z = new float[] {0.0F, 0.0F, 1.0F, 1.0F}; }
				if (axis == 2) { z = new float[] {sign, sign, sign, sign}; }
				PackTerrainJavaMesh mesh = new PackTerrainJavaMesh(PackTerrainLayout.Field.NORMAL_TANGENT.bit());
				mesh.append(x, y, z, new float[] {0.0F, 1.0F, 1.0F, 0.0F},
					new float[] {0.0F, 0.0F, 1.0F, 1.0F}, 0.0F, 0.0F, 0.0F, 1);
				assertNotEquals(0, mesh.data().order(ByteOrder.LITTLE_ENDIAN).getInt(PackTerrainLayout.NORMAL_TANGENT_OFFSET));
			}
		}
	}

	@Test
	void leavesUnrequestedFieldsZero() {
		PackTerrainJavaMesh mesh = new PackTerrainJavaMesh(PackTerrainLayout.Field.BLOCK_ID.bit());
		float[] values = {0.0F, 1.0F, 1.0F, 0.0F};
		mesh.append(values, values, values, values, values, 0.0F, 0.0F, 0.0F, 9);
		ByteBuffer record = mesh.data().order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(9, record.getInt(0));
		assertEquals(0, record.getInt(4));
		assertEquals(0, record.getInt(8));
		assertEquals(0, record.getInt(12));
	}
}
