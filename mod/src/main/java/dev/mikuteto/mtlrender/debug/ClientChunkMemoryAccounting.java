package dev.mikuteto.mtlrender.debug;

import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;

public final class ClientChunkMemoryAccounting {
	private static final int BLOCK_ENTRY_COUNT = 4096;
	private static final int BIOME_ENTRY_COUNT = 64;
	private static final long UNKNOWN = ClientChunkMemorySnapshot.UNKNOWN;

	private ClientChunkMemoryAccounting() {
	}

	public static ClientChunkMemorySnapshot.Chunk chunk(LevelChunk chunk, long generation) {
		Objects.requireNonNull(chunk, "chunk");
		long heightmapBytes = 0L;
		for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
			heightmapBytes = add(heightmapBytes, heightmapBytes(entry.getValue()));
		}
		return new ClientChunkMemorySnapshot.Chunk(
			chunk.getPos().x(),
			chunk.getPos().z(),
			generation,
			chunk.getSections().length,
			chunk.getBlockEntities().size(),
			UNKNOWN,
			heightmapBytes
		);
	}

	public static ClientChunkMemorySnapshot.Section section(LevelChunk chunk, int sectionIndex, long generation) {
		Objects.requireNonNull(chunk, "chunk");
		LevelChunkSection[] sections = chunk.getSections();
		if (sectionIndex < 0 || sectionIndex >= sections.length) {
			throw new IllegalArgumentException("section index is outside the chunk");
		}
		int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
		LevelLightEngine lightEngine = chunk.getLevel().getLightEngine();
		SectionPos position = SectionPos.of(chunk.getPos(), sectionY);
		return new ClientChunkMemorySnapshot.Section(
			chunk.getPos().x(),
			sectionY,
			chunk.getPos().z(),
			generation,
			palette(sections[sectionIndex].getStates(), true),
			palette(sections[sectionIndex].getBiomes(), false),
			lightBytes(lightEngine, LightLayer.BLOCK, position),
			lightBytes(lightEngine, LightLayer.SKY, position),
			blockEntityCount(chunk.getBlockEntities(), sectionY)
		);
	}

	public static ClientChunkMemorySnapshot.Palette palette(PalettedContainerRO<?> palette, boolean blockStates) {
		Objects.requireNonNull(palette, "palette");
		final long[] entries = {0L};
		palette.forEachInPalette(value -> entries[0]++);
		int bits = palette.bitsPerEntry();
		ClientChunkMemorySnapshot.Mode mode = mode(entries[0], bits, blockStates);
		int entryCount = blockStates ? BLOCK_ENTRY_COUNT : BIOME_ENTRY_COUNT;
		long storageBytes = storageBytes(bits, entryCount);
		return new ClientChunkMemorySnapshot.Palette(mode, entries[0], storageBytes);
	}

	public static long lightBytes(LevelLightEngine lightEngine, LightLayer layer, SectionPos position) {
		Objects.requireNonNull(lightEngine, "lightEngine");
		Objects.requireNonNull(layer, "layer");
		Objects.requireNonNull(position, "position");
		DataLayer data = lightEngine.getLayerListener(layer).getDataLayerData(position);
		return data == null ? 0L : dataLayerBytes(data);
	}

	public static long dataLayerBytes(DataLayer data) {
		Objects.requireNonNull(data, "data");
		return data.isDefinitelyHomogenous() ? UNKNOWN : DataLayer.SIZE;
	}

	public static long heightmapBytes(Heightmap heightmap) {
		Objects.requireNonNull(heightmap, "heightmap");
		return rawArrayBytes(heightmap.getRawData());
	}

	public static long rawArrayBytes(long[] data) {
		Objects.requireNonNull(data, "data");
		return Math.multiplyExact((long)data.length, Long.BYTES);
	}

	public static long blockEntityCount(Map<BlockPos, ?> blockEntities, int sectionY) {
		Objects.requireNonNull(blockEntities, "blockEntities");
		long count = 0L;
		for (BlockPos position : blockEntities.keySet()) {
			if (SectionPos.blockToSectionCoord(position.getY()) == sectionY) {
				count++;
			}
		}
		return count;
	}

	private static ClientChunkMemorySnapshot.Mode mode(long entries, int bits, boolean blockStates) {
		if (entries == 1L && bits == 0) {
			return ClientChunkMemorySnapshot.Mode.SINGLE_VALUE;
		}
		if (bits < 0) {
			return ClientChunkMemorySnapshot.Mode.UNKNOWN;
		}
		if (blockStates) {
			return bits <= 4 ? ClientChunkMemorySnapshot.Mode.LINEAR
				: bits <= 8 ? ClientChunkMemorySnapshot.Mode.HASH_MAP : ClientChunkMemorySnapshot.Mode.GLOBAL;
		}
		return bits <= 3 ? ClientChunkMemorySnapshot.Mode.LINEAR : ClientChunkMemorySnapshot.Mode.GLOBAL;
	}

	private static long storageBytes(int bits, int entryCount) {
		if (bits < 0) {
			return UNKNOWN;
		}
		long words = ((long)bits * entryCount + Long.SIZE - 1L) / Long.SIZE;
		return Math.multiplyExact(words, Long.BYTES);
	}

	private static long add(long left, long right) {
		if (left == UNKNOWN || right == UNKNOWN) {
			return UNKNOWN;
		}
		return Math.addExact(left, right);
	}
}
