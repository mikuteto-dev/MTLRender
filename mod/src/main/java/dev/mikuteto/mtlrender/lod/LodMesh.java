package dev.mikuteto.mtlrender.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

public final class LodMesh {
	public static final int INPUT_MAGIC = 0x4c4f4431;
	public static final int INPUT_VERSION = 1;
	public static final int INPUT_HEADER_BYTES = 64;
	public static final int VOXEL_BYTES = 12;
	public static final int MATERIAL_BYTES = 24;
	public static final int FLAG_COMPLETE = 1;

	public record Material(int stateId, int materialId, int layer, int flags, int color, int light) {
		public Material {
			if (stateId < 0 || materialId < 0 || layer < 0 || layer > 2 || flags < 0 || color < 0 || light < 0) {
				throw new IllegalArgumentException("material values must be non-negative");
			}
		}
	}

	public static final class Input {
		private final LodData.LodSection section;
		private final Material[] materials;
		private final boolean complete;

		public Input(LodData.LodSection section, Material[] materials, boolean complete) {
			this.section = Objects.requireNonNull(section, "section");
			this.materials = Objects.requireNonNull(materials, "materials").clone();
			this.complete = complete;
		}

		public LodData.LodSection section() { return section; }
		public Material[] materials() { return materials.clone(); }
		public boolean complete() { return complete; }
	}

	private final LodData.LodKey key;
	private final int vertexOffset;
	private final int vertexBytes;
	private final int quadOffset;
	private final int quadBytes;
	private final int[] faceBuckets;
	private final int materialVersion;
	private final int formatVersion;
	private final long estimatedBytes;
	private final byte[] bytes;

	public LodMesh(LodData.LodKey key, int vertexOffset, int vertexBytes, int quadOffset, int quadBytes,
		int[] faceBuckets, int materialVersion, int formatVersion, long estimatedBytes) {
		this(key, vertexOffset, vertexBytes, quadOffset, quadBytes, faceBuckets, materialVersion, formatVersion, estimatedBytes, new byte[0]);
	}

	private LodMesh(LodData.LodKey key, int vertexOffset, int vertexBytes, int quadOffset, int quadBytes,
		int[] faceBuckets, int materialVersion, int formatVersion, long estimatedBytes, byte[] bytes) {
		this.key = Objects.requireNonNull(key, "key");
		if (vertexOffset < 0 || vertexBytes < 0 || quadOffset < 0 || quadBytes < 0 || materialVersion < 0 || formatVersion < 0 || estimatedBytes < 0) {
			throw new IllegalArgumentException("mesh metadata must be non-negative");
		}
		if (faceBuckets == null || faceBuckets.length != 7) {
			throw new IllegalArgumentException("exactly seven face buckets are required");
		}
		this.vertexOffset = vertexOffset;
		this.vertexBytes = vertexBytes;
		this.quadOffset = quadOffset;
		this.quadBytes = quadBytes;
		this.faceBuckets = faceBuckets.clone();
		this.materialVersion = materialVersion;
		this.formatVersion = formatVersion;
		this.estimatedBytes = estimatedBytes;
		this.bytes = bytes.clone();
	}

	private LodMesh(LodData.LodKey key, int quadBytes, int[] faceBuckets, int formatVersion, byte[] bytes) {
		this(key, 0, 0, 64, quadBytes, faceBuckets, 0, formatVersion, bytes.length, bytes);
	}

	static LodMesh nativeResult(LodData.LodKey key, int quadBytes, int[] faceBuckets, int formatVersion, byte[] bytes) {
		return new LodMesh(key, quadBytes, faceBuckets, formatVersion, bytes.clone());
	}

	public LodData.LodKey key() { return key; }
	public int vertexOffset() { return vertexOffset; }
	public int vertexBytes() { return vertexBytes; }
	public int quadOffset() { return quadOffset; }
	public int quadBytes() { return quadBytes; }
	public int[] faceBuckets() { return faceBuckets.clone(); }
	public int materialVersion() { return materialVersion; }
	public int formatVersion() { return formatVersion; }
	public long estimatedBytes() { return estimatedBytes; }
	public byte[] bytes() { return bytes.clone(); }

	public static ByteBuffer encode(Input input) {
		Objects.requireNonNull(input, "input");
		LodData.LodSection section = input.section();
		LodData.LodKey key = section.key();
		if (!input.complete()) {
			throw new IllegalArgumentException("incomplete LOD sections cannot be meshed");
		}
		if (input.materials().length > 1_048_576) {
			throw new IllegalArgumentException("too many materials");
		}
		int voxelBytes = Math.multiplyExact(LodData.LOD_VOXELS, VOXEL_BYTES);
		int materialBytes = Math.multiplyExact(input.materials().length, MATERIAL_BYTES);
		int materialOffset = Math.addExact(INPUT_HEADER_BYTES, voxelBytes);
		int total = Math.addExact(materialOffset, materialBytes);
		ByteBuffer buffer = ByteBuffer.allocateDirect(total).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(INPUT_MAGIC).putInt(INPUT_VERSION).putInt(INPUT_HEADER_BYTES).putInt(FLAG_COMPLETE);
		buffer.putInt(key.level()).putInt(key.originX()).putInt(key.originY()).putInt(key.originZ());
		buffer.putInt(LodData.LOD_VOXELS).putInt(input.materials().length).putInt(INPUT_HEADER_BYTES).putInt(materialOffset).putInt(total).putInt(0);
		int[] values = section.voxelValues();
		int[] heights = section.heightmap().values();
		for (int i = 0; i < values.length; i++) {
			buffer.putInt(values[i]);
			buffer.putInt(heights[(i / LodData.LOD_SIZE) % heights.length]);
			buffer.putInt(0);
		}
		for (Material material : input.materials()) {
			buffer.putInt(material.stateId()).putInt(material.materialId()).putInt(material.layer()).putInt(material.flags());
			buffer.putInt(material.color()).putInt(material.light());
		}
		buffer.flip();
		return buffer;
	}
}
