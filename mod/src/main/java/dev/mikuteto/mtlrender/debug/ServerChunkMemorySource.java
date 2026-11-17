package dev.mikuteto.mtlrender.debug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import dev.mikuteto.mtlrender.chunk.ServerChunkMemoryLedger;
import dev.mikuteto.mtlrender.config.Config;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;

public final class ServerChunkMemorySource implements MemorySource {
	private static final long INTERVAL_NANOS = Long.getLong("mtlrender.memory.integratedServer.intervalNanos", 1_000_000_000L);
	private static final int DIMENSION_LIMIT = 64;
	private static final ServerChunkMemorySource GLOBAL = new ServerChunkMemorySource(
		System::nanoTime,
		() -> snapshotFromLedger(System.nanoTime())
	);
	private static final MemoryDiagnostics.Registration REGISTRATION = MemoryDiagnostics.global().register("java.chunk.server", GLOBAL);

	private final LongSupplier clock;
	private final Supplier<ServerChunkMemorySnapshot> sampler;
	private volatile ServerChunkMemorySnapshot snapshot;
	private long lastSampleNanos = Long.MIN_VALUE;

	public ServerChunkMemorySource() {
		this(System::nanoTime, () -> snapshotFromLedger(System.nanoTime()));
	}

	public ServerChunkMemorySource(LongSupplier clock, Supplier<ServerChunkMemorySnapshot> sampler) {
		this.clock = clock;
		this.sampler = sampler;
	}

	public static ServerChunkMemorySource global() {
		return GLOBAL;
	}

	public void maybeSample(ServerLevel level) {
		if (!enabled() || level == null || !(level.getServer() instanceof IntegratedServer)) {
			snapshot = null;
			return;
		}
		long now = clock.getAsLong();
		if (lastSampleNanos != Long.MIN_VALUE && now >= lastSampleNanos && now - lastSampleNanos < INTERVAL_NANOS) {
			return;
		}
		try {
			ServerChunkMemorySnapshot next = sampler.get();
			if (next != null) {
				snapshot = next;
			}
		} catch (Throwable failure) {
			snapshot = null;
		}
		lastSampleNanos = now;
	}

	@Override
	public Values sample() {
		if (!enabled()) {
			return Values.unavailable();
		}
		ServerChunkMemorySnapshot current = snapshot;
		if (current == null) {
			return Values.unavailable();
		}
		OptionalLong total = current.aggregate().totalBytes();
		return new Values(total, OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty());
	}

	@Override
	public List<String> diagnosticLines() {
		if (!enabled()) {
			return List.of();
		}
		ServerChunkMemorySnapshot current = snapshot;
		if (current == null) {
			return List.of("memory_source.java.chunk.server: unavailable");
		}
		List<String> lines = new ArrayList<>();
		lines.add("memory_source.java.chunk.server: available");
		for (ServerChunkMemorySnapshot.Dimension dimension : current.dimensions()) {
			String prefix = "java.chunk.server." + dimension.dimensionId() + ".";
			lines.add(prefix + "loaded_chunks: " + value(dimension.loadedChunkCount()));
			lines.add(prefix + "ticking_chunks: " + value(dimension.tickingChunkCount()));
			lines.add(prefix + "saving_chunks: " + value(dimension.savingChunkCount()));
			lines.add(prefix + "section_palette_bytes: " + value(dimension.sectionPaletteBytes()));
			lines.add(prefix + "section_light_bytes: " + value(dimension.sectionLightBytes()));
			lines.add(prefix + "heightmap_bytes: " + value(dimension.heightmapBytes()));
			lines.add(prefix + "poi_records: " + value(dimension.poiCount()));
			lines.add(prefix + "block_entities: " + value(dimension.blockEntityCount()));
			lines.add(prefix + "pending_chunk_tasks: " + value(dimension.pendingChunkTasks()));
			lines.add(prefix + "pending_light_tasks: " + value(dimension.pendingLightTasks()));
			lines.add(prefix + "pending_generation_tasks: " + value(dimension.pendingGenerationTasks()));
			lines.add(prefix + "packet_buffer_bytes: " + value(dimension.packetBufferBytes()));
			lines.add(prefix + "serialization_buffer_bytes: " + value(dimension.serializationBufferBytes()));
			lines.add(prefix + "compression_buffer_bytes: " + value(dimension.compressionBufferBytes()));
		}
		return List.copyOf(lines);
	}

	private boolean enabled() {
		return Config.get().debugMemory && Config.get().integratedServer;
	}

	private static ServerChunkMemorySnapshot snapshotFromLedger(long now) {
		ServerChunkMemoryLedger.Snapshot source = ServerChunkMemoryLedger.global().snapshot();
		Map<String, Counts> counts = new HashMap<>();
		for (ServerChunkMemoryLedger.ChunkSnapshot chunk : source.chunks()) {
			Counts count = counts.computeIfAbsent(chunk.identity().dimension(), ignored -> new Counts());
			count.loaded += chunk.loaded() ? 1L : 0L;
			count.ticking += chunk.ticking() ? 1L : 0L;
			count.saving += chunk.saving() ? 1L : 0L;
			count.pending += (long) chunk.pendingSend() + chunk.pendingSave() + chunk.pendingTask();
		}
		for (ServerChunkMemoryLedger.SectionSnapshot section : source.sections()) {
			Counts count = counts.computeIfAbsent(section.identity().dimension(), ignored -> new Counts());
			count.pending += section.pendingTask();
		}
		List<ServerChunkMemorySnapshot.Dimension> dimensions = new ArrayList<>();
		for (Map.Entry<String, Counts> entry : counts.entrySet()) {
			Counts count = entry.getValue();
			dimensions.add(new ServerChunkMemorySnapshot.Dimension(entry.getKey(), OptionalLong.empty(), OptionalLong.of(count.loaded),
				OptionalLong.of(count.ticking), OptionalLong.of(count.saving), OptionalLong.empty(), OptionalLong.empty(),
				OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), OptionalLong.of(count.pending), OptionalLong.empty(),
				OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty()));
		}
		return new ServerChunkMemorySnapshot(now, DIMENSION_LIMIT, ServerChunkMemorySnapshot.ServerIdentity.unknown(), dimensions);
	}

	private static String value(OptionalLong value) {
		return value.isPresent() ? Long.toString(value.getAsLong()) : "unavailable";
	}

	private static final class Counts {
		private long loaded;
		private long ticking;
		private long saving;
		private long pending;
	}
}
