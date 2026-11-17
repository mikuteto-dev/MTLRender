package dev.mikuteto.mtlrender.debug;

import java.util.Map;
import java.util.Collection;
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

public final class ServerChunkMemoryAccounting {
	private ServerChunkMemoryAccounting() {
	}

	public static long sectionPaletteBytes(LevelChunkSection section) {
		Objects.requireNonNull(section, "section");
		return add(section.getStates().getSerializedSize(), section.getBiomes().getSerializedSize());
	}

	public static long sectionSerializedBytes(LevelChunkSection section) {
		Objects.requireNonNull(section, "section");
		return section.getSerializedSize();
	}

	public static long paletteSerializedBytes(PalettedContainerRO<?> palette) {
		Objects.requireNonNull(palette, "palette");
		return palette.getSerializedSize();
	}

	public static int paletteBitsPerEntry(PalettedContainerRO<?> palette) {
		Objects.requireNonNull(palette, "palette");
		return palette.bitsPerEntry();
	}

	public static long paletteEntryCount(PalettedContainerRO<?> palette) {
		Objects.requireNonNull(palette, "palette");
		final long[] count = {0L};
		palette.forEachInPalette(value -> count[0]++);
		return count[0];
	}

	public static long chunkHeightmapBytes(LevelChunk chunk) {
		Objects.requireNonNull(chunk, "chunk");
		long bytes = 0L;
		for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
			bytes = add(bytes, heightmapBytes(entry.getValue()));
		}
		return bytes;
	}

	public static long heightmapBytes(Heightmap heightmap) {
		Objects.requireNonNull(heightmap, "heightmap");
		return rawArrayBytes(heightmap.getRawData());
	}

	public static long rawArrayBytes(long[] data) {
		Objects.requireNonNull(data, "data");
		return Math.multiplyExact((long)data.length, Long.BYTES);
	}

	public static long blockEntityCount(LevelChunk chunk) {
		Objects.requireNonNull(chunk, "chunk");
		return chunk.getBlockEntities().size();
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

	public static long lightBytes(LevelLightEngine lightEngine, LightLayer layer, SectionPos position) {
		Objects.requireNonNull(lightEngine, "lightEngine");
		Objects.requireNonNull(layer, "layer");
		Objects.requireNonNull(position, "position");
		DataLayer data = lightEngine.getLayerListener(layer).getDataLayerData(position);
		return data == null ? 0L : dataLayerBytes(data);
	}

	public static long dataLayerBytes(DataLayer data) {
		Objects.requireNonNull(data, "data");
		return data.isDefinitelyHomogenous() ? ServerChunkMemorySnapshot.UNKNOWN : DataLayer.SIZE;
	}

	public static long unknown() {
		return ServerChunkMemorySnapshot.UNKNOWN;
	}

	public static long poiRecordCount(Collection<?> records) {
		Objects.requireNonNull(records, "records");
		return records.size();
	}

	public static long poiCountUnavailable() {
		return ServerChunkMemorySnapshot.UNKNOWN;
	}

	private static long add(long left, long right) {
		if (left == ServerChunkMemorySnapshot.UNKNOWN || right == ServerChunkMemorySnapshot.UNKNOWN) {
			return ServerChunkMemorySnapshot.UNKNOWN;
		}
		return Math.addExact(left, right);
	}
}
