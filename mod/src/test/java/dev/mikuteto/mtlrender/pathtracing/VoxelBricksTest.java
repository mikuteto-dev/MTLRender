package dev.mikuteto.mtlrender.pathtracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import dev.mikuteto.mtlrender.terrain.DirectUberBuffer;
import dev.mikuteto.mtlrender.terrain.MeshEntries;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.junit.jupiter.api.Test;

class VoxelBricksTest {
	@Test
	void layerReplacementUsesNewestGeneration() {
		Entries entries = new Entries();
		VoxelBricks.update(entries, ChunkSectionLayer.SOLID, quad(), 2L);
		VoxelBricks.update(entries, ChunkSectionLayer.SOLID, ByteBuffer.allocate(0), 1L);
		assertNotNull(entries.mtlrender$voxelBricks().value(ChunkSectionLayer.SOLID));
	}

	@Test
	void retainedBytesFollowLayerOwnership() {
		long before = VoxelBrickSet.retainedBytes();
		Entries entries = new Entries();
		VoxelBricks.update(entries, ChunkSectionLayer.SOLID, quad(), 1L);
		VoxelBricks.update(entries, ChunkSectionLayer.CUTOUT, quad(), 1L);
		assertEquals(before + 2L * VoxelBrick.BYTES, VoxelBrickSet.retainedBytes());

		VoxelBricks.update(entries, ChunkSectionLayer.SOLID, ByteBuffer.allocate(0), 2L);
		assertEquals(before + VoxelBrick.BYTES, VoxelBrickSet.retainedBytes());
		entries.mtlrender$voxelBricks().release();
		assertEquals(before, VoxelBrickSet.retainedBytes());
	}

	@Test
	void emptyUploadClearsLayerBrick() {
		Entries entries = new Entries();
		VoxelBricks.update(entries, ChunkSectionLayer.SOLID, quad());
		assertNotNull(entries.mtlrender$voxelBricks().value(ChunkSectionLayer.SOLID));

		VoxelBricks.update(entries, ChunkSectionLayer.SOLID, ByteBuffer.allocate(0));
		assertNull(entries.mtlrender$voxelBricks().value(ChunkSectionLayer.SOLID));
	}

	private static ByteBuffer quad() {
		ByteBuffer data = ByteBuffer.allocate(28 * 4).order(ByteOrder.nativeOrder());
		for (int vertex = 0; vertex < 4; vertex++) {
			int offset = vertex * 28;
			data.putFloat(offset, 1.0F);
			data.putFloat(offset + 4, 1.0F);
			data.putFloat(offset + 8, 1.0F);
		}
		return data;
	}

	private static final class Entries implements MeshEntries, VoxelStateAccess {
		private VoxelBrickSet bricks;

		@Override
		public DirectUberBuffer.Entry mtlrender$entry(int layer) {
			return null;
		}

		@Override
		public void mtlrender$setEntry(int layer, DirectUberBuffer.Entry entry) {
		}

		@Override
		public VoxelBrickSet mtlrender$voxelBricks() {
			return bricks;
		}

		@Override
		public void mtlrender$setVoxelBricks(VoxelBrickSet bricks) {
			this.bricks = bricks;
		}

		@Override
		public int mtlrender$section() {
			return -1;
		}

		@Override
		public void mtlrender$setSection(int index) {
		}

		@Override
		public boolean mtlrender$acceptUploadCallback(boolean sortedIndexBuffer) {
			return true;
		}

		@Override
		public boolean mtlrender$isCurrent() {
			return true;
		}

		@Override
		public int[] mtlrender$stateIdsXPlus16YPlus256Z() {
			return new int[VoxelBrick.VOXELS];
		}

		@Override
		public void mtlrender$setStateIdsXPlus16YPlus256Z(int[] stateIds) {
		}
	}
}
