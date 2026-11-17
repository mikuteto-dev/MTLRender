package dev.mikuteto.mtlrender.debug;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public record ServerChunkMemorySnapshot(
	long sampledAtNanos,
	int dimensionRecordLimit,
	ServerIdentity server,
	List<Dimension> dimensions
) {
	public static final long UNKNOWN = -1L;
	public ServerChunkMemorySnapshot {
		if (sampledAtNanos < 0L) {
			throw new IllegalArgumentException("sample time must be non-negative");
		}
		if (dimensionRecordLimit < 0) {
			throw new IllegalArgumentException("dimension record limit must be non-negative");
		}
		server = Objects.requireNonNull(server, "server");
		Objects.requireNonNull(dimensions, "dimensions");
		List<Dimension> ordered = new ArrayList<>(dimensions);
		ordered.sort(Comparator.comparing(Dimension::dimensionId));
		if (ordered.size() > dimensionRecordLimit) {
			throw new IllegalArgumentException("snapshot record limit exceeded");
		}
		for (int index = 1; index < ordered.size(); index++) {
			if (ordered.get(index - 1).dimensionId().equals(ordered.get(index).dimensionId())) {
				throw new IllegalArgumentException("duplicate dimension id");
			}
		}
		dimensions = List.copyOf(ordered);
	}

	public Totals aggregate() {
		return Totals.sum(dimensions);
	}

	public record ServerIdentity(Optional<String> instanceId, OptionalLong lifecycleGeneration) {
		public ServerIdentity {
			instanceId = instanceId == null ? Optional.empty() : instanceId;
			lifecycleGeneration = value(lifecycleGeneration);
			instanceId.ifPresent(id -> {
				if (id.isBlank()) {
					throw new IllegalArgumentException("server instance id must not be blank");
				}
			});
		}

		public static ServerIdentity unknown() {
			return new ServerIdentity(Optional.empty(), OptionalLong.empty());
		}
	}

	public record Dimension(
		String dimensionId,
		OptionalLong totalBytes,
		OptionalLong loadedChunkCount,
		OptionalLong tickingChunkCount,
		OptionalLong savingChunkCount,
		OptionalLong sectionPaletteBytes,
		OptionalLong sectionLightBytes,
		OptionalLong heightmapBytes,
		OptionalLong poiCount,
		OptionalLong blockEntityCount,
		OptionalLong pendingChunkTasks,
		OptionalLong pendingLightTasks,
		OptionalLong pendingGenerationTasks,
		OptionalLong packetBufferBytes,
		OptionalLong serializationBufferBytes,
		OptionalLong compressionBufferBytes
	) {
		public Dimension {
			if (dimensionId == null || dimensionId.isBlank()) {
				throw new IllegalArgumentException("dimension id must not be blank");
			}
			totalBytes = value(totalBytes);
			loadedChunkCount = value(loadedChunkCount);
			tickingChunkCount = value(tickingChunkCount);
			savingChunkCount = value(savingChunkCount);
			sectionPaletteBytes = value(sectionPaletteBytes);
			sectionLightBytes = value(sectionLightBytes);
			heightmapBytes = value(heightmapBytes);
			poiCount = value(poiCount);
			blockEntityCount = value(blockEntityCount);
			pendingChunkTasks = value(pendingChunkTasks);
			pendingLightTasks = value(pendingLightTasks);
			pendingGenerationTasks = value(pendingGenerationTasks);
			packetBufferBytes = value(packetBufferBytes);
			serializationBufferBytes = value(serializationBufferBytes);
			compressionBufferBytes = value(compressionBufferBytes);
		}

		public static Dimension unknown(String dimensionId) {
			return new Dimension(dimensionId, OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(),
				OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(),
				OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty());
		}
	}

	public record Totals(
		OptionalLong totalBytes,
		OptionalLong loadedChunkCount,
		OptionalLong tickingChunkCount,
		OptionalLong savingChunkCount,
		OptionalLong sectionPaletteBytes,
		OptionalLong sectionLightBytes,
		OptionalLong heightmapBytes,
		OptionalLong poiCount,
		OptionalLong blockEntityCount,
		OptionalLong pendingChunkTasks,
		OptionalLong pendingLightTasks,
		OptionalLong pendingGenerationTasks,
		OptionalLong packetBufferBytes,
		OptionalLong serializationBufferBytes,
		OptionalLong compressionBufferBytes
	) {
		private static Totals sum(List<Dimension> dimensions) {
			OptionalLong[] values = new OptionalLong[15];
			for (int index = 0; index < values.length; index++) {
				values[index] = OptionalLong.of(0L);
			}
			for (Dimension dimension : dimensions) {
				OptionalLong[] current = values(dimension);
				for (int index = 0; index < values.length; index++) {
					values[index] = add(values[index], current[index]);
				}
			}
			return new Totals(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7],
				values[8], values[9], values[10], values[11], values[12], values[13], values[14]);
		}

		private static OptionalLong[] values(Dimension dimension) {
			return new OptionalLong[] { dimension.totalBytes(), dimension.loadedChunkCount(), dimension.tickingChunkCount(),
				dimension.savingChunkCount(), dimension.sectionPaletteBytes(), dimension.sectionLightBytes(), dimension.heightmapBytes(),
				dimension.poiCount(), dimension.blockEntityCount(), dimension.pendingChunkTasks(), dimension.pendingLightTasks(),
				dimension.pendingGenerationTasks(), dimension.packetBufferBytes(), dimension.serializationBufferBytes(),
				dimension.compressionBufferBytes() };
		}

		private static OptionalLong add(OptionalLong left, OptionalLong right) {
			if (left.isEmpty() || right.isEmpty()) {
				return OptionalLong.empty();
			}
			long value = left.getAsLong();
			long addition = right.getAsLong();
			if (Long.MAX_VALUE - value < addition) {
				throw new IllegalArgumentException("snapshot total overflow");
			}
			return OptionalLong.of(value + addition);
		}
	}

	private static OptionalLong value(OptionalLong value) {
		if (value == null || value.isEmpty()) {
			return OptionalLong.empty();
		}
		if (value.getAsLong() < 0L) {
			throw new IllegalArgumentException("snapshot values must be non-negative");
		}
		return value;
	}
}
