package dev.mikuteto.mtlrender.lod;

import java.util.Arrays;
import java.util.Objects;

public final class LodData {
	public static final int MIN_LEVEL = 0;
	public static final int MAX_LEVEL = 4;
	public static final int SOURCE_SIZE = 16;
	public static final int SOURCE_VOXELS = SOURCE_SIZE * SOURCE_SIZE * SOURCE_SIZE;
	public static final int LOD_SIZE = 32;
	public static final int LOD_VOXELS = LOD_SIZE * LOD_SIZE * LOD_SIZE;
	private LodData() {
	}

	public record ChunkKey(String dimension, int chunkX, int chunkZ) {
		public ChunkKey {
			dimension = requireDimension(dimension);
		}
	}

	public record LodKey(String dimension, int level, int originX, int originY, int originZ) {
		public LodKey {
			dimension = requireDimension(dimension);
			if (level < MIN_LEVEL || level > MAX_LEVEL) {
				throw new IllegalArgumentException("level must be between " + MIN_LEVEL + " and " + MAX_LEVEL);
			}
			int span = LOD_SIZE << level;
			if (originX % span != 0 || originY % span != 0 || originZ % span != 0) {
				throw new IllegalArgumentException("LOD origin is not aligned to its level");
			}
		}
	}

	public static final class Heightmap {
		private final int size;
		private final int[] values;

		public Heightmap(int size, int[] values) {
			if (size != SOURCE_SIZE && size != LOD_SIZE) {
				throw new IllegalArgumentException("heightmap size must be 16 or 32");
			}
			Objects.requireNonNull(values, "values");
			int expected = size * size;
			if (values.length != expected) {
				throw new IllegalArgumentException("heightmap length must be " + expected);
			}
			this.size = size;
			this.values = Arrays.copyOf(values, values.length);
		}

		public static Heightmap chunk(int[] values) {
			return new Heightmap(SOURCE_SIZE, values);
		}

		public static Heightmap lod(int[] values) {
			return new Heightmap(LOD_SIZE, values);
		}

		public int size() {
			return size;
		}

		public int[] values() {
			return Arrays.copyOf(values, values.length);
		}

		int[] valuesRaw() {
			return values;
		}

		public long estimatedBytes() {
			return (long) values.length * Integer.BYTES;
		}
	}

	public static final class DecodedSection {
		private final ChunkKey chunk;
		private final int sectionY;
		private final int[] blockStateIds;
		private final int[] biomeIds;
		private final byte[] skyLight;
		private final byte[] blockLight;
		private final Heightmap heightmap;

		public DecodedSection(
			ChunkKey chunk,
			int sectionY,
			int[] blockStateIds,
			int[] biomeIds,
			byte[] skyLight,
			byte[] blockLight,
			Heightmap heightmap
		) {
			this.chunk = Objects.requireNonNull(chunk, "chunk");
			this.sectionY = sectionY;
			this.blockStateIds = copy(blockStateIds, SOURCE_VOXELS, "blockStateIds");
			this.biomeIds = copy(biomeIds, SOURCE_VOXELS, "biomeIds");
			this.skyLight = copy(skyLight, SOURCE_VOXELS, "skyLight");
			this.blockLight = copy(blockLight, SOURCE_VOXELS, "blockLight");
			this.heightmap = requireHeightmap(heightmap, SOURCE_SIZE, "source heightmap");
		}

		public ChunkKey chunk() {
			return chunk;
		}

		public int sectionY() {
			return sectionY;
		}

		public int[] blockStateIds() {
			return Arrays.copyOf(blockStateIds, blockStateIds.length);
		}

		public int[] biomeIds() {
			return Arrays.copyOf(biomeIds, biomeIds.length);
		}

		public byte[] skyLight() {
			return Arrays.copyOf(skyLight, skyLight.length);
		}

		public byte[] blockLight() {
			return Arrays.copyOf(blockLight, blockLight.length);
		}

		public Heightmap heightmap() {
			return heightmap;
		}

		int[] statesRaw() {
			return blockStateIds;
		}

		int[] biomesRaw() {
			return biomeIds;
		}

		byte[] skyRaw() {
			return skyLight;
		}

		byte[] blockRaw() {
			return blockLight;
		}

		public long estimatedBytes() {
			return (long) blockStateIds.length * Integer.BYTES
				+ (long) biomeIds.length * Integer.BYTES
				+ skyLight.length
				+ blockLight.length
				+ heightmap.estimatedBytes();
		}
	}

	public static final class LodSection {
		private final LodKey key;
		private final int[] voxelValues;
		private final int[] biomeIds;
		private final byte[] skyLight;
		private final byte[] blockLight;
		private final Heightmap heightmap;

		public LodSection(LodKey key, int[] voxelValues, Heightmap heightmap) {
			this(key, voxelValues, new int[LOD_VOXELS], new byte[LOD_VOXELS], new byte[LOD_VOXELS], heightmap);
		}

		public LodSection(LodKey key, int[] voxelValues, int[] biomeIds, byte[] skyLight, byte[] blockLight, Heightmap heightmap) {
			this.key = Objects.requireNonNull(key, "key");
			this.voxelValues = copy(voxelValues, LOD_VOXELS, "voxelValues");
			this.biomeIds = copy(biomeIds, LOD_VOXELS, "biomeIds");
			this.skyLight = copy(skyLight, LOD_VOXELS, "skyLight");
			this.blockLight = copy(blockLight, LOD_VOXELS, "blockLight");
			this.heightmap = requireHeightmap(heightmap, LOD_SIZE, "LOD heightmap");
		}

		public LodKey key() {
			return key;
		}

		public int[] voxelValues() {
			return Arrays.copyOf(voxelValues, voxelValues.length);
		}

		public int[] biomeIds() {
			return Arrays.copyOf(biomeIds, biomeIds.length);
		}

		public byte[] skyLight() {
			return Arrays.copyOf(skyLight, skyLight.length);
		}

		public byte[] blockLight() {
			return Arrays.copyOf(blockLight, blockLight.length);
		}

		public Heightmap heightmap() {
			return heightmap;
		}

		int[] statesRaw() {
			return voxelValues;
		}

		int[] biomesRaw() {
			return biomeIds;
		}

		byte[] skyRaw() {
			return skyLight;
		}

		byte[] blockRaw() {
			return blockLight;
		}

		public long estimatedBytes() {
			return (long) voxelValues.length * Integer.BYTES
				+ (long) biomeIds.length * Integer.BYTES
				+ skyLight.length + blockLight.length + heightmap.estimatedBytes();
		}
	}

	private static Heightmap requireHeightmap(Heightmap heightmap, int expectedSize, String name) {
		Objects.requireNonNull(heightmap, name);
		if (heightmap.size() != expectedSize) {
			throw new IllegalArgumentException(name + " must be " + expectedSize + " by " + expectedSize);
		}
		return heightmap;
	}

	private static String requireDimension(String dimension) {
		Objects.requireNonNull(dimension, "dimension");
		if (dimension.isBlank()) {
			throw new IllegalArgumentException("dimension must not be blank");
		}
		return dimension;
	}

	private static int[] copy(int[] values, int expected, String name) {
		Objects.requireNonNull(values, name);
		if (values.length != expected) {
			throw new IllegalArgumentException(name + " length must be " + expected);
		}
		return Arrays.copyOf(values, values.length);
	}

	private static byte[] copy(byte[] values, int expected, String name) {
		Objects.requireNonNull(values, name);
		if (values.length != expected) {
			throw new IllegalArgumentException(name + " length must be " + expected);
		}
		return Arrays.copyOf(values, values.length);
	}
}
