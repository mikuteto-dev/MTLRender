package dev.mikuteto.mtlrender.pathtracing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

public final class VoxelBrick {
	public static final int SIZE = 16;
	public static final int VOXELS = SIZE * SIZE * SIZE;
	public static final int BYTES = VOXELS * Integer.BYTES;
	private static final int VERTEX_BYTES = 28;
	private static final int LAYER_SHIFT = 28;
	private final ByteBuffer data;

	private VoxelBrick(ByteBuffer data) {
		this.data = data;
	}

	static VoxelBrick fromData(ByteBuffer data) {
		return new VoxelBrick(data);
	}

	public static VoxelBrick from(ByteBuffer source, ChunkSectionLayer layer, VoxelStateAccess states) {
		ByteBuffer input = source.slice().order(ByteOrder.nativeOrder());
		ByteBuffer output = ByteBuffer.allocate(BYTES).order(ByteOrder.nativeOrder());
		int quads = input.remaining() / (VERTEX_BYTES * 4);
		int layerFlags = layer == ChunkSectionLayer.TRANSLUCENT ? 3 : layer == ChunkSectionLayer.CUTOUT ? 2 : 1;
		int[] stateIds = states.mtlrender$stateIdsXPlus16YPlus256Z();
		for (int quad = 0; quad < quads; quad++) {
			int base = quad * VERTEX_BYTES * 4;
			for (int vertex = 0; vertex < 4; vertex++) {
				int at = base + vertex * VERTEX_BYTES;
				int ix = cell(input.getFloat(at));
				int iy = cell(input.getFloat(at + 4));
				int iz = cell(input.getFloat(at + 8));
				int stateId = stateIds == null ? -1 : stateIds[ix | iy * SIZE | iz * SIZE * SIZE];
				BlockStateAlbedo.Material material = BlockStateAlbedo.lookup(stateId);
				output.putInt((ix | iy * SIZE | iz * SIZE * SIZE) * Integer.BYTES,
					pack(material, layerFlags));
			}
		}
		return new VoxelBrick(output);
	}

	static int cell(float coordinate) {
		return Math.clamp((int) Math.floor(coordinate), 0, SIZE - 1);
	}

	static int pack(BlockStateAlbedo.Material material, int layerFlags) {
		return quantize(material.red()) | (quantize(material.green()) << 8) | (quantize(material.blue()) << 16)
			| (Math.clamp(material.emission(), 0, 15) << 24) | (layerFlags << LAYER_SHIFT);
	}

	private static int quantize(float value) {
		return Math.clamp(Math.round(Math.clamp(value, 0.0F, 1.0F) * 255.0F), 0, 255);
	}

	public static VoxelBrick combine(VoxelBrick[] bricks) {
		if (bricks == null) {
			return null;
		}
		ByteBuffer output = ByteBuffer.allocate(BYTES).order(ByteOrder.nativeOrder());
		boolean occupied = false;
		for (int i = 0; i < VOXELS; i++) {
			int material = 0;
			for (VoxelBrick brick : bricks) {
				if (brick != null) {
					int candidate = brick.data.getInt(i * Integer.BYTES);
					if (candidate != 0) {
						material = candidate;
						break;
					}
				}
			}
			if (material != 0) {
				occupied = true;
			}
			output.putInt(i * Integer.BYTES, material);
		}
		return occupied ? new VoxelBrick(output) : null;
	}

	public ByteBuffer data() {
		return data.duplicate().order(ByteOrder.nativeOrder());
	}
}
