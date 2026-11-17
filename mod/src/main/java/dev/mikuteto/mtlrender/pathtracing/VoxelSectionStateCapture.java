package dev.mikuteto.mtlrender.pathtracing;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Block;

public final class VoxelSectionStateCapture {
	private static final int SIZE = 16;
	private static final int VOLUME = SIZE * SIZE * SIZE;
	private static final Map<SectionCompiler.Results, int[]> PENDING = new WeakHashMap<>();

	private VoxelSectionStateCapture() {
	}

	public static void capture(
		SectionCompiler.Results results,
		SectionPos sectionPos,
		RenderSectionRegion region
	) {
		int[] stateIds = new int[VOLUME];
		BlockPos origin = sectionPos.origin();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int z = 0; z < SIZE; z++) {
			for (int y = 0; y < SIZE; y++) {
				for (int x = 0; x < SIZE; x++) {
					pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
					stateIds[index(x, y, z)] = Block.BLOCK_STATE_REGISTRY.getId(region.getBlockState(pos));
				}
			}
		}
		synchronized (PENDING) {
			PENDING.put(results, stateIds);
		}
	}

	public static int[] consume(SectionCompiler.Results results) {
		synchronized (PENDING) {
			return PENDING.remove(results);
		}
	}

	static int index(int x, int y, int z) {
		return x + SIZE * (y + SIZE * z);
	}
}
