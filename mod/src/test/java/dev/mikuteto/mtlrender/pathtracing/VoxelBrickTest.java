package dev.mikuteto.mtlrender.pathtracing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.junit.jupiter.api.Test;

class VoxelBrickTest {
	@Test
	void directAndHeapInputsProduceIdenticalBricks() {
		VoxelStateAccess states = new TestStates();
		VoxelBrick heap = VoxelBrick.from(source(false), ChunkSectionLayer.CUTOUT, states);
		VoxelBrick direct = VoxelBrick.from(source(true), ChunkSectionLayer.CUTOUT, states);
		ByteBuffer heapData = heap.data();
		ByteBuffer directData = direct.data();
		for (int offset = 0; offset < VoxelBrick.BYTES; offset += Integer.BYTES) {
			assertEquals(heapData.getInt(offset), directData.getInt(offset));
		}
	}

	@Test
	void packsLinearMaterialWithoutSrgbConversion() {
		int packed = VoxelBrick.pack(new BlockStateAlbedo.Material(0.5F, 0.25F, 1.0F, 11), 3);
		assertEquals(0x3BFF4080, packed);
	}

	@Test
	void clampsVoxelCoordinatesToBrick() {
		assertEquals(0, VoxelBrick.cell(-1.0F));
		assertEquals(15, VoxelBrick.cell(16.0F));
		assertEquals(15, VoxelBrick.cell(15.99F));
	}

	private static ByteBuffer source(boolean direct) {
		ByteBuffer data = (direct ? ByteBuffer.allocateDirect(28 * 4) : ByteBuffer.allocate(28 * 4))
			.order(ByteOrder.nativeOrder());
		for (int vertex = 0; vertex < 4; vertex++) {
			int offset = vertex * 28;
			data.putFloat(offset, vertex == 1 || vertex == 2 ? 2.0F : 1.0F);
			data.putFloat(offset + 4, vertex >= 2 ? 3.0F : 1.0F);
			data.putFloat(offset + 8, 1.0F);
			data.putInt(offset + 12, 0xFF204060);
			data.putShort(offset + 24, (short) 0x0030);
			data.putShort(offset + 26, (short) 0x00A0);
		}
		return data;
	}

	private static final class TestStates implements VoxelStateAccess {
		@Override
		public int[] mtlrender$stateIdsXPlus16YPlus256Z() {
			return new int[VoxelBrick.VOXELS];
		}

		@Override
		public void mtlrender$setStateIdsXPlus16YPlus256Z(int[] stateIds) {
		}
	}
}
