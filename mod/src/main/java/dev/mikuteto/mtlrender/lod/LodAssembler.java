package dev.mikuteto.mtlrender.lod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class LodAssembler {
	private static final int CHILDREN = 8;
	private static final int HEIGHTMAP_AREA = LodData.LOD_SIZE * LodData.LOD_SIZE;

	public LodAssembler() {
	}

	public Optional<LodData.LodSection> assembleLevel0Data(
		LodData.LodKey key,
		Map<LodData.ChunkKey, Map<Integer, LodData.DecodedSection>> sources,
		Optional<LodData.LodSection> prior
	) {
		return assembleLevel0(key, sources, prior);
	}

	public Optional<LodData.LodSection> reduceData(
		LodData.LodKey key,
		Map<LodData.LodKey, LodData.LodSection> children,
		Optional<LodData.LodSection> prior
	) {
		return reduce(key, children, stateId -> stateId == 0 ? 0 : 1, prior);
	}

	@FunctionalInterface
	public interface StateInfo {
		int opacity(int stateId);

		default boolean isAir(int stateId) {
			return opacity(stateId) == 0;
		}
	}

	public static Optional<LodData.LodSection> assembleLevel0(
		LodData.LodKey key,
		Map<LodData.ChunkKey, Map<Integer, LodData.DecodedSection>> sources,
		Optional<LodData.LodSection> prior
	) {
		return assembleLevel0(key, sources, stateId -> stateId == 0 ? 0 : 1, prior);
	}

	public static Optional<LodData.LodSection> assembleLevel0(
		LodData.LodKey key,
		Map<LodData.ChunkKey, Map<Integer, LodData.DecodedSection>> sources,
		StateInfo stateInfo,
		Optional<LodData.LodSection> prior
	) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(sources, "sources");
		Objects.requireNonNull(stateInfo, "stateInfo");
		Objects.requireNonNull(prior, "prior");
		if (key.level() != LodData.MIN_LEVEL) {
			throw new IllegalArgumentException("level 0 key required");
		}
		int chunkX = Math.floorDiv(key.originX(), LodData.SOURCE_SIZE);
		int chunkZ = Math.floorDiv(key.originZ(), LodData.SOURCE_SIZE);
		int sectionY = Math.floorDiv(key.originY(), LodData.SOURCE_SIZE);
		List<LodData.DecodedSection> sections = new ArrayList<>(4);
		for (int z = 0; z < 2; z++) {
			for (int x = 0; x < 2; x++) {
				LodData.ChunkKey chunk = new LodData.ChunkKey(key.dimension(), chunkX + x, chunkZ + z);
				Map<Integer, LodData.DecodedSection> byY = sources.get(chunk);
				if (byY == null) {
					return prior;
				}
				LodData.DecodedSection lower = byY.get(sectionY);
				LodData.DecodedSection upper = byY.get(sectionY + 1);
				if (lower == null || upper == null) {
					return prior;
				}
				sections.add(lower);
				sections.add(upper);
			}
		}
		int[] states = new int[LodData.LOD_VOXELS];
		int[] biomes = new int[LodData.LOD_VOXELS];
		byte[] sky = new byte[LodData.LOD_VOXELS];
		byte[] block = new byte[LodData.LOD_VOXELS];
		int[] heights = new int[HEIGHTMAP_AREA];
		for (int z = 0; z < LodData.LOD_SIZE; z++) {
			for (int x = 0; x < LodData.LOD_SIZE; x++) {
				int chunk = (x >> 4) | (z >> 4) * 2;
				int local = (x & 15) | (z & 15) * 16;
				LodData.DecodedSection section = sections.get(chunk * 2);
				heights[x | z * LodData.LOD_SIZE] = section.heightmap().valuesRaw()[local];
			}
		}
		for (int y = 0; y < LodData.LOD_SIZE; y++) {
			int source = y >> 4;
			for (int z = 0; z < LodData.LOD_SIZE; z++) {
				for (int x = 0; x < LodData.LOD_SIZE; x++) {
					int chunk = (x >> 4) | (z >> 4) * 2;
					LodData.DecodedSection section = sections.get(chunk * 2 + source);
					int from = (x & 15) | (z & 15) * 16 | (y & 15) * 256;
					int to = x | z * 32 | y * 1024;
					states[to] = section.statesRaw()[from];
					biomes[to] = section.biomesRaw()[from];
					sky[to] = section.skyRaw()[from];
					block[to] = section.blockRaw()[from];
				}
			}
		}
		return Optional.of(new LodData.LodSection(key, states, biomes, sky, block, LodData.Heightmap.lod(heights)));
	}

	public static Optional<LodData.LodSection> reduce(
		LodData.LodKey key,
		Map<LodData.LodKey, LodData.LodSection> children,
		StateInfo stateInfo,
		Optional<LodData.LodSection> prior
	) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(children, "children");
		Objects.requireNonNull(stateInfo, "stateInfo");
		Objects.requireNonNull(prior, "prior");
		if (key.level() <= LodData.MIN_LEVEL || key.level() > LodData.MAX_LEVEL) {
			throw new IllegalArgumentException("reduction level must be between 1 and 4");
		}
		int childSpan = LodData.LOD_SIZE << (key.level() - 1);
		List<LodData.LodSection> values = new ArrayList<>(CHILDREN);
		for (int z = 0; z < 2; z++) {
			for (int y = 0; y < 2; y++) {
				for (int x = 0; x < 2; x++) {
					LodData.LodKey child = new LodData.LodKey(key.dimension(), key.level() - 1,
						key.originX() + x * childSpan, key.originY() + y * childSpan, key.originZ() + z * childSpan);
					LodData.LodSection value = children.get(child);
					if (value == null) {
						return prior;
					}
					values.add(value);
				}
			}
		}
		int[] states = new int[LodData.LOD_VOXELS];
		int[] biomes = new int[LodData.LOD_VOXELS];
		byte[] sky = new byte[LodData.LOD_VOXELS];
		byte[] block = new byte[LodData.LOD_VOXELS];
		for (int y = 0; y < 32; y++) {
			for (int z = 0; z < 32; z++) {
				for (int x = 0; x < 32; x++) {
					int[] selected = select(values, x, y, z, stateInfo);
					int to = x | z * 32 | y * 1024;
					states[to] = selected[0];
					biomes[to] = selected[1];
					sky[to] = (byte) selected[2];
					block[to] = (byte) selected[3];
				}
			}
		}
		int[] heights = new int[HEIGHTMAP_AREA];
		for (int z = 0; z < 32; z++) {
			for (int x = 0; x < 32; x++) {
				int maximum = Integer.MIN_VALUE;
				for (int dz = 0; dz < 2; dz++) {
					for (int dx = 0; dx < 2; dx++) {
						for (int dy = 0; dy < 2; dy++) {
							int child = dx | (dy << 1) | (dz << 2);
							int value = values.get(child).heightmap().valuesRaw()[x | z * 32];
							maximum = Math.max(maximum, value);
						}
					}
				}
				heights[x | z * 32] = maximum;
			}
		}
		return Optional.of(new LodData.LodSection(key, states, biomes, sky, block, LodData.Heightmap.lod(heights)));
	}

	private static int[] select(List<LodData.LodSection> values, int x, int y, int z, StateInfo info) {
		int best = -1;
		int bestChild = 0;
		int bestOpacity = -1;
		int skySum = 0;
		int blockSum = 0;
		int source = (x >> 1) | (z >> 1) * 32 | (y >> 1) * 1024;
		for (int child = 0; child < CHILDREN; child++) {
			LodData.LodSection section = values.get(child);
			int state = section.statesRaw()[source];
			int opacity = info.opacity(state);
			if (opacity > bestOpacity || opacity == bestOpacity && (child & 2) != 0 && (bestChild & 2) == 0) {
				best = state;
				bestChild = child;
				bestOpacity = opacity;
			}
			skySum += Byte.toUnsignedInt(section.skyRaw()[source]);
			blockSum += Byte.toUnsignedInt(section.blockRaw()[source]);
		}
		LodData.LodSection chosen = values.get(bestChild);
		boolean air = info.isAir(best);
		return new int[]{best, chosen.biomesRaw()[source], air ? (skySum + 4) / 8 : Byte.toUnsignedInt(chosen.skyRaw()[source]), air ? (blockSum + 4) / 8 : Byte.toUnsignedInt(chosen.blockRaw()[source])};
	}
}
