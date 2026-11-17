package dev.mikuteto.mtlrender.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LodChunkDecoderTest {
	@BeforeAll
	static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	private static final LevelHeightAccessor HEIGHT = new LevelHeightAccessor() {
		@Override
		public int getHeight() {
			return 384;
		}

		@Override
		public int getMinY() {
			return -64;
		}
	};

	@Test
	void decodesStatesSectionCoordinatesAndHeightmap() {
		CompoundTag chunk = chunkWithSection();
		Optional<List<LodData.DecodedSection>> decoded = decode(chunk);

		assertTrue(decoded.isPresent());
		LodData.DecodedSection section = decoded.orElseThrow().getFirst();
		int index = 1 | 3 << 4 | 2 << 8;
		assertEquals(Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState()), section.blockStateIds()[index]);
		assertEquals(5, section.sectionY());
		assertEquals(70, section.heightmap().values()[1]);
	}

	@Test
	void acceptsAirOnlyChunkWithoutSections() {
		CompoundTag chunk = baseChunk();
		assertTrue(decode(chunk).isPresent());
		assertTrue(decode(chunk).orElseThrow().isEmpty());
	}

	@Test
	void rejectsMalformedPaletteAndHeightmap() {
		CompoundTag palette = chunkWithSection();
		palette.getListOrEmpty("sections").getCompound(0).orElseThrow().getCompound("block_states").orElseThrow().put("palette", new ListTag());
		assertFalse(decode(palette).isPresent());

		CompoundTag heightmap = chunkWithSection();
		heightmap.getCompound("Heightmaps").orElseThrow().putLongArray("MOTION_BLOCKING", new long[1]);
		assertFalse(decode(heightmap).isPresent());
	}

	private static Optional<List<LodData.DecodedSection>> decode(CompoundTag tag) {
		return LodChunkDecoder.decode(new ChunkPos(2, -3), tag, HEIGHT, registryAccess());
	}

	private static RegistryAccess registryAccess() {
		MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
		Biome plains = new Biome.BiomeBuilder()
			.temperature(0.8F)
			.downfall(0.4F)
			.specialEffects(new BiomeSpecialEffects.Builder().waterColor(4159204).build())
			.mobSpawnSettings(MobSpawnSettings.EMPTY)
			.generationSettings(BiomeGenerationSettings.EMPTY)
			.build();
		biomes.register(Biomes.PLAINS, plains, RegistrationInfo.BUILT_IN);
		biomes.freeze();
		return new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
	}

	private static CompoundTag chunkWithSection() {
		CompoundTag chunk = baseChunk();
		CompoundTag section = new CompoundTag();
		section.putByte("Y", (byte)5);
		CompoundTag blocks = new CompoundTag();
		ListTag palette = new ListTag();
		CompoundTag air = new CompoundTag();
		air.putString("Name", "minecraft:air");
		CompoundTag stone = new CompoundTag();
		stone.putString("Name", "minecraft:stone");
		palette.add(air);
		palette.add(stone);
		blocks.put("palette", palette);
		long[] data = new long[256];
		int index = 1 | 3 << 4 | 2 << 8;
		data[index / 16] |= 1L << (index % 16 * 4);
		blocks.putLongArray("data", data);
		section.put("block_states", blocks);
		ListTag sections = new ListTag();
		sections.add(section);
		chunk.put("sections", sections);
		return chunk;
	}

	private static CompoundTag baseChunk() {
		CompoundTag chunk = new CompoundTag();
		chunk.putInt("xPos", 2);
		chunk.putInt("zPos", -3);
		chunk.putString("Status", "full");
		int bits = 9;
		int valuesPerLong = 64 / bits;
		long[] values = new long[(256 + valuesPerLong - 1) / valuesPerLong];
		putPacked(values, 0, 65, bits, valuesPerLong);
		putPacked(values, 1, 134, bits, valuesPerLong);
		CompoundTag heightmaps = new CompoundTag();
		heightmaps.putLongArray("MOTION_BLOCKING", values);
		chunk.put("Heightmaps", heightmaps);
		return chunk;
	}

	private static void putPacked(long[] values, int index, int value, int bits, int valuesPerLong) {
		values[index / valuesPerLong] |= (long)value << (index % valuesPerLong * bits);
	}
}
