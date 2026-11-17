package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import dev.mikuteto.mtlrender.Native;
import org.junit.jupiter.api.Test;

class LodMergeTest {
	@Test
	void directAndHeapInputsProduceIdenticalLodData() throws Exception {
		Native.load();
		LodMerge.Merged heap = LodMerge.merge(source(false), 7);
		LodMerge.Merged direct = LodMerge.merge(source(true), 7);

		assertArrayEquals(new int[] {1, 1, 1, 1, 1, 1, 1}, heap.faces());
		assertArrayEquals(heap.faces(), direct.faces());
		ByteBuffer heapData = heap.data().asByteBuffer();
		ByteBuffer directData = direct.data().asByteBuffer();
		assertEquals(heapData.remaining(), directData.remaining());
		for (int offset = 0; offset < heapData.remaining(); offset++) {
			assertEquals(heapData.get(offset), directData.get(offset), "byte " + offset);
		}
	}

	private static ByteBuffer source(boolean direct) {
		ByteBuffer data = (direct ? ByteBuffer.allocateDirect(7 * FaceBuckets.QUAD_SIZE) : ByteBuffer.allocate(7 * FaceBuckets.QUAD_SIZE))
			.order(ByteOrder.nativeOrder());
		for (int axis = 0; axis < 3; axis++) {
			quad(data, (axis * 2) * FaceBuckets.QUAD_SIZE, axis, 0.0F, 0.0F, false, 0xFF204060, 0x00100020);
			quad(data, (axis * 2 + 1) * FaceBuckets.QUAD_SIZE, axis, 2.0F, 0.0F, true, 0xFF204060, 0x00100020);
		}
		int base = 6 * FaceBuckets.QUAD_SIZE;
		for (int vertex = 0; vertex < 4; vertex++) {
			int offset = base + vertex * TerrainVertex.BLOCK.size;
			data.putFloat(offset, vertex & 1);
			data.putFloat(offset + 4, vertex >>> 1);
			data.putFloat(offset + 8, vertex & 1);
			data.putInt(offset + 12, 0xFF806040);
			data.putFloat(offset + 16, vertex & 1);
			data.putFloat(offset + 20, vertex >>> 1);
			data.putInt(offset + 24, 0x00200030);
		}
		return data;
	}

	private static void quad(ByteBuffer data, int base, int axis, float u, float v, boolean reverse, int color, int light) {
		int size = TerrainVertex.BLOCK.size;
		int uAxis = (axis + 1) % 3;
		int vAxis = (axis + 2) % 3;
		for (int vertex = 0; vertex < 4; vertex++) {
			int sourceVertex = reverse ? 3 - vertex : vertex;
			float[] position = {0.0F, 0.0F, 0.0F};
			position[uAxis] = u + (sourceVertex == 1 || sourceVertex == 2 ? 1.0F : 0.0F);
			position[vAxis] = v + (sourceVertex >= 2 ? 1.0F : 0.0F);
			int offset = base + vertex * size;
			data.putFloat(offset, position[0]);
			data.putFloat(offset + 4, position[1]);
			data.putFloat(offset + 8, position[2]);
			data.putInt(offset + 12, color);
			data.putFloat(offset + 16, sourceVertex == 0 || sourceVertex == 3 ? 0.0F : 1.0F);
			data.putFloat(offset + 20, sourceVertex < 2 ? 0.0F : 1.0F);
			data.putInt(offset + 24, light);
		}
	}
}
