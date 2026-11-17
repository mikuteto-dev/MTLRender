package dev.mikuteto.mtlrender.pathtracing;

import java.util.concurrent.atomic.LongAdder;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

public final class VoxelBrickSet {
	private static final LongAdder RETAINED_BYTES = new LongAdder();
	private final VoxelBrick[] bricks = new VoxelBrick[ChunkSectionLayer.values().length];
	private final long[] generations = new long[ChunkSectionLayer.values().length];
	private boolean released;

	public VoxelBrickSet() {
		java.util.Arrays.fill(generations, -1L);
	}

	public synchronized boolean replace(ChunkSectionLayer layer, long generation, VoxelBrick brick) {
		if (released) {
			return false;
		}
		int index = layer.ordinal();
		if (generation <= generations[index]) {
			return false;
		}
		VoxelBrick previous = bricks[index];
		bricks[index] = brick;
		generations[index] = generation;
		if (previous == null && brick != null) {
			RETAINED_BYTES.add(VoxelBrick.BYTES);
		} else if (previous != null && brick == null) {
			RETAINED_BYTES.add(-VoxelBrick.BYTES);
		}
		return true;
	}

	public synchronized void release() {
		if (released) {
			return;
		}
		released = true;
		for (int index = 0; index < bricks.length; index++) {
			if (bricks[index] != null) {
				bricks[index] = null;
				RETAINED_BYTES.add(-VoxelBrick.BYTES);
			}
		}
	}

	public synchronized VoxelBrick value(ChunkSectionLayer layer) {
		return bricks[layer.ordinal()];
	}

	public synchronized VoxelBrick combine() {
		boolean occupied = false;
		java.nio.ByteBuffer output = java.nio.ByteBuffer.allocate(VoxelBrick.BYTES).order(java.nio.ByteOrder.nativeOrder());
		java.nio.ByteBuffer[] data = new java.nio.ByteBuffer[bricks.length];
		for (int index = 0; index < bricks.length; index++) {
			if (bricks[index] != null) {
				data[index] = bricks[index].data();
			}
		}
		for (int index = 0; index < VoxelBrick.VOXELS; index++) {
			int material = 0;
			for (java.nio.ByteBuffer brick : data) {
				if (brick != null) {
					int candidate = brick.getInt(index * Integer.BYTES);
					if (candidate != 0) {
						material = candidate;
						break;
					}
				}
			}
			if (material != 0) {
				occupied = true;
			}
			output.putInt(index * Integer.BYTES, material);
		}
		return occupied ? VoxelBrick.fromData(output) : null;
	}

	public static long retainedBytes() {
		return RETAINED_BYTES.sum();
	}
}
