package dev.mikuteto.mtlrender.debug;

import java.util.List;
import java.util.Objects;

public record ClientChunkMemorySnapshot(
	long sampledAtNanos,
	int chunkRecordLimit,
	int sectionRecordLimit,
	long loadedChunkCount,
	long loadedSectionCount,
	List<Chunk> chunks,
	List<Section> sections,
	long transientPacketBytes,
	long transientHeightmapBufferBytes
) {
	public static final long UNKNOWN = -1L;

	public ClientChunkMemorySnapshot {
		if (sampledAtNanos < 0L) {
			throw new IllegalArgumentException("sample time must be non-negative");
		}
		if (chunkRecordLimit < 0 || sectionRecordLimit < 0) {
			throw new IllegalArgumentException("record limits must be non-negative");
		}
		if (loadedChunkCount < 0L || loadedSectionCount < 0L) {
			throw new IllegalArgumentException("loaded counts must be non-negative");
		}
		if (!validMeasured(transientPacketBytes) || !validMeasured(transientHeightmapBufferBytes)) {
			throw new IllegalArgumentException("transient bytes must be non-negative or unknown");
		}
		chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
		sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
		if (chunks.size() > chunkRecordLimit || sections.size() > sectionRecordLimit) {
			throw new IllegalArgumentException("snapshot record limit exceeded");
		}
	}

	public record Chunk(
		int chunkX,
		int chunkZ,
		long generation,
		long sectionCount,
		long blockEntityCount,
		long pendingBlockEntityCount,
		long heightmapRawArrayBytes
	) {
		public Chunk {
			if (generation < 0L || sectionCount < 0L || !validMeasured(blockEntityCount)
				|| !validMeasured(pendingBlockEntityCount) || !validMeasured(heightmapRawArrayBytes)) {
				throw new IllegalArgumentException("chunk memory values must be non-negative or unknown");
			}
		}
	}

	public record Section(
		int chunkX,
		int sectionY,
		int chunkZ,
		long generation,
		Palette blockPalette,
		Palette biomePalette,
		long blockLightBytes,
		long skyLightBytes,
		long blockEntityCount
	) {
		public Section {
			if (generation < 0L || !validMeasured(blockLightBytes) || !validMeasured(skyLightBytes)
				|| !validMeasured(blockEntityCount)) {
				throw new IllegalArgumentException("section memory values must be non-negative or unknown");
			}
			blockPalette = Objects.requireNonNull(blockPalette, "blockPalette");
			biomePalette = Objects.requireNonNull(biomePalette, "biomePalette");
		}
	}

	public record Palette(Mode mode, long entryCount, long storageBytes) {
		public Palette {
			mode = Objects.requireNonNull(mode, "mode");
			if (entryCount < 0L || storageBytes < 0L) {
				throw new IllegalArgumentException("palette memory values must be non-negative");
			}
		}
	}

	private static boolean validMeasured(long value) {
		return value >= 0L || value == UNKNOWN;
	}

	public enum Mode {
		SINGLE_VALUE,
		LINEAR,
		HASH_MAP,
		GLOBAL,
		UNKNOWN
	}
}
