package dev.mikuteto.mtlrender.lod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;

public final class LodChunkDecoder {
	private static final int MAX_SECTIONS = 4096;
	private static final int MAX_PALETTE_ENTRIES = 4096;
	private static final int MAX_STORAGE_LONGS = 4096;
	private static final String SAVED_DIMENSION = "saved";

	public LodChunkDecoder() {
	}

	public Optional<List<LodData.DecodedSection>> decodeChunk(
		ChunkPos expectedPos,
		CompoundTag chunkTag,
		LevelHeightAccessor levelHeight,
		RegistryAccess registryAccess
	) {
		return decode(expectedPos, chunkTag, levelHeight, registryAccess);
	}

	public static Optional<List<LodData.DecodedSection>> decode(
		ChunkPos expectedPos,
		CompoundTag chunkTag,
		LevelHeightAccessor levelHeight,
		RegistryAccess registryAccess
	) {
		if (expectedPos == null || chunkTag == null || levelHeight == null || registryAccess == null) {
			return Optional.empty();
		}
		try {
			if (chunkTag.getString("Status").isEmpty()) {
				return Optional.empty();
			}
			int savedX = chunkTag.getIntOr("xPos", expectedPos.x());
			int savedZ = chunkTag.getIntOr("zPos", expectedPos.z());
			if (savedX != expectedPos.x() || savedZ != expectedPos.z() || !isBounded(levelHeight)) {
				return Optional.empty();
			}
			if (!hasBoundedShape(chunkTag, levelHeight)) {
				return Optional.empty();
			}
			PalettedContainerFactory factory = PalettedContainerFactory.create(registryAccess);
			SerializableChunkData parsed = SerializableChunkData.parse(levelHeight, factory, chunkTag);
			if (parsed == null || parsed.sectionData().size() > MAX_SECTIONS) {
				return Optional.empty();
			}
			int[] heights = decodeHeightmap(parsed.heightmaps(), levelHeight);
			if (heights == null) {
				return Optional.empty();
			}
			Registry<Biome> biomes = registryAccess.lookupOrThrow(Registries.BIOME);
			List<LodData.DecodedSection> sections = new ArrayList<>(parsed.sectionData().size());
			LodData.ChunkKey key = new LodData.ChunkKey(SAVED_DIMENSION, expectedPos.x(), expectedPos.z());
			for (SerializableChunkData.SectionData sectionData : parsed.sectionData()) {
				LevelChunkSection section = sectionData.chunkSection();
				if (section == null) {
					continue;
				}
				int[] states = new int[LodData.SOURCE_VOXELS];
				int[] biomeIds = new int[LodData.SOURCE_VOXELS];
				byte[] sky = new byte[LodData.SOURCE_VOXELS];
				byte[] block = new byte[LodData.SOURCE_VOXELS];
				DataLayer skyLayer = sectionData.skyLight();
				DataLayer blockLayer = sectionData.blockLight();
				for (int y = 0; y < LodData.SOURCE_SIZE; y++) {
					for (int z = 0; z < LodData.SOURCE_SIZE; z++) {
						for (int x = 0; x < LodData.SOURCE_SIZE; x++) {
							int index = x | z << 4 | y << 8;
							BlockState state = section.getBlockState(x, y, z);
							states[index] = Block.BLOCK_STATE_REGISTRY.getId(state);
							Holder<Biome> biome = section.getBiomes().get(x >> 2, y >> 2, z >> 2);
							biomeIds[index] = biomes.getId(biome.value());
							if (skyLayer != null) {
								sky[index] = (byte)skyLayer.get(x, y, z);
							}
							if (blockLayer != null) {
								block[index] = (byte)blockLayer.get(x, y, z);
							}
						}
					}
				}
				sections.add(new LodData.DecodedSection(key, sectionData.y(), states, biomeIds, sky, block, LodData.Heightmap.chunk(heights)));
			}
			return Optional.of(List.copyOf(sections));
		} catch (RuntimeException | LinkageError exception) {
			return Optional.empty();
		}
	}

	private static boolean isBounded(LevelHeightAccessor levelHeight) {
		return levelHeight.getHeight() > 0 && levelHeight.getHeight() <= 4096;
	}

	private static boolean hasBoundedShape(CompoundTag chunkTag, LevelHeightAccessor levelHeight) {
		ListTag sections = chunkTag.getListOrEmpty(SerializableChunkData.SECTIONS_TAG);
		if (sections.size() > MAX_SECTIONS) {
			return false;
		}
		for (int i = 0; i < sections.size(); i++) {
			CompoundTag section = sections.getCompound(i).orElse(null);
			if (section == null) {
				return false;
			}
			if (!boundedContainer(section.getCompound("block_states").orElse(null))
				|| !boundedContainer(section.getCompound("biomes").orElse(null))) {
				return false;
			}
			if (!boundedLight(section, SerializableChunkData.BLOCK_LIGHT_TAG)
				|| !boundedLight(section, SerializableChunkData.SKY_LIGHT_TAG)) {
				return false;
			}
		}
		CompoundTag heightmaps = chunkTag.getCompound(SerializableChunkData.HEIGHTMAPS_TAG).orElse(null);
		if (heightmaps == null) {
			return false;
		}
		int bits = ceilLog2(levelHeight.getHeight() + 1);
		int expectedLongs = (256 + 64 / bits - 1) / (64 / bits);
		boolean found = false;
		for (Heightmap.Types type : Heightmap.Types.values()) {
			String name = type.getSerializationKey();
			if (heightmaps.getLongArray(name).isPresent()) {
				found = true;
				long[] values = heightmaps.getLongArray(name).orElse(null);
				if (values == null || values.length != expectedLongs) {
					return false;
				}
			}
		}
		return found;
	}

	private static boolean boundedContainer(CompoundTag container) {
		if (container == null) {
			return true;
		}
		ListTag palette = container.getListOrEmpty("palette");
		if (palette.isEmpty() || palette.size() > MAX_PALETTE_ENTRIES) {
			return false;
		}
		long[] data = container.getLongArray("data").orElse(null);
		return data == null || data.length <= MAX_STORAGE_LONGS;
	}

	private static boolean boundedLight(CompoundTag section, String name) {
		byte[] light = section.getByteArray(name).orElse(null);
		return light == null || light.length == DataLayer.SIZE;
	}

	private static int[] decodeHeightmap(Map<Heightmap.Types, long[]> heightmaps, LevelHeightAccessor levelHeight) {
		Heightmap.Types type = null;
		for (Heightmap.Types candidate : new Heightmap.Types[]{Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES}) {
			if (heightmaps.containsKey(candidate)) {
				type = candidate;
				break;
			}
		}
		if (type == null) {
			return null;
		}
		int bits = ceilLog2(levelHeight.getHeight() + 1);
		int valuesPerLong = 64 / bits;
		int[] result = new int[256];
		long[] raw = heightmaps.get(type);
		if (raw == null || raw.length != (256 + valuesPerLong - 1) / valuesPerLong) {
			return null;
		}
		long mask = (1L << bits) - 1L;
		for (int index = 0; index < result.length; index++) {
			int word = index / valuesPerLong;
			int shift = index % valuesPerLong * bits;
			result[index] = (int)((raw[word] >>> shift) & mask) + levelHeight.getMinY();
		}
		return result;
	}

	private static int ceilLog2(int value) {
		return 32 - Integer.numberOfLeadingZeros(value - 1);
	}
}
