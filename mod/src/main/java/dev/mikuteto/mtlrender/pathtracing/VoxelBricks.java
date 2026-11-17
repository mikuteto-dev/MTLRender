package dev.mikuteto.mtlrender.pathtracing;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import dev.mikuteto.mtlrender.terrain.MeshEntries;
import dev.mikuteto.mtlrender.terrain.SectionCpuRetention;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

public final class VoxelBricks {
	private static final AtomicLong DEFAULT_GENERATION = new AtomicLong();

	private VoxelBricks() {
	}

	public static boolean enabled() {
		return PathTracer.get().active() || SectionCpuRetention.required();
	}

	public static void update(Object key, ChunkSectionLayer layer, ByteBuffer source) {
		update(key, layer, source, DEFAULT_GENERATION.incrementAndGet());
	}

	public static void update(Object key, ChunkSectionLayer layer, ByteBuffer source, long generation) {
		if (!(key instanceof MeshEntries entries)) {
			return;
		}
		VoxelBrick brick;
		if (source.remaining() == 0) {
			brick = null;
		} else if (key instanceof VoxelStateAccess states) {
			brick = VoxelBrick.from(source, layer, states);
		} else {
			return;
		}
		synchronized (entries) {
			VoxelBrickSet set = entries.mtlrender$voxelBricks();
			if (set == null) {
				set = new VoxelBrickSet();
				entries.mtlrender$setVoxelBricks(set);
			}
			set.replace(layer, generation, brick);
		}
	}

	public static VoxelBrickSet get(Object key) {
		return key instanceof MeshEntries entries ? entries.mtlrender$voxelBricks() : null;
	}

	public static void release(Object key) {
		if (!(key instanceof MeshEntries entries)) {
			return;
		}
		synchronized (entries) {
			VoxelBrickSet set = entries.mtlrender$voxelBricks();
			if (set != null && !PathTracer.get().owns(set)) {
				entries.mtlrender$setVoxelBricks(null);
				set.release();
			}
		}
	}

	public static long retainedBytes() {
		return VoxelBrickSet.retainedBytes();
	}
}
