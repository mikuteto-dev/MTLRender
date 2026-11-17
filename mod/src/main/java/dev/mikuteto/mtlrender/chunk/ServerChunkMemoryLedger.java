package dev.mikuteto.mtlrender.chunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.server.level.FullChunkStatus;

public final class ServerChunkMemoryLedger {
	private static final ServerChunkMemoryLedger GLOBAL = new ServerChunkMemoryLedger();

	public static ServerChunkMemoryLedger global() {
		return GLOBAL;
	}
	public record ChunkIdentity(String dimension, long chunkKey) {
		public ChunkIdentity {
			if (dimension == null || dimension.isBlank()) {
				throw new IllegalArgumentException("dimension must not be blank");
			}
		}
	}

	public record SectionIdentity(String dimension, long sectionKey) {
		public SectionIdentity {
			if (dimension == null || dimension.isBlank()) {
				throw new IllegalArgumentException("dimension must not be blank");
			}
		}
	}

	public record ChunkSnapshot(ChunkIdentity identity, long generation, FullChunkStatus status, boolean loaded,
		boolean ticking, boolean saving, int pendingSend, int pendingSave, int pendingTask) {
	}

	public record SectionSnapshot(SectionIdentity identity, long generation, int pendingTask) {
	}

	public record Snapshot(List<ChunkSnapshot> chunks, List<SectionSnapshot> sections) {
		public Snapshot {
			chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
			sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
		}
	}

	public final class Handle {
		private final ChunkIdentity identity;
		private final long generation;

		private Handle(ChunkIdentity identity, long generation) {
			this.identity = identity;
			this.generation = generation;
		}

		public ChunkIdentity identity() {
			return identity;
		}

		public long generation() {
			return generation;
		}
	}

	public final class SectionHandle {
		private final SectionIdentity identity;
		private final long generation;

		private SectionHandle(SectionIdentity identity, long generation) {
			this.identity = identity;
			this.generation = generation;
		}

		public SectionIdentity identity() {
			return identity;
		}

		public long generation() {
			return generation;
		}
	}

	public enum Pending {
		SEND,
		SAVE,
		TASK
	}

	public final class Claim {
		private final ChunkIdentity identity;
		private final long generation;
		private final Pending pending;
		private boolean released;

		private Claim(ChunkIdentity identity, long generation, Pending pending) {
			this.identity = identity;
			this.generation = generation;
			this.pending = pending;
		}
	}

	private final Map<ChunkIdentity, List<ChunkEntry>> chunks = new HashMap<>();
	private final Map<SectionIdentity, SectionEntry> sections = new HashMap<>();
	private long nextGeneration;

	public synchronized Handle load(String dimension, long chunkKey) {
		return load(new ChunkIdentity(dimension, chunkKey));
	}

	public synchronized Handle load(ChunkIdentity identity) {
		Objects.requireNonNull(identity, "identity");
		long generation = generation();
		chunks.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(new ChunkEntry(generation));
		return new Handle(identity, generation);
	}

	public synchronized SectionHandle loadSection(String dimension, long sectionKey) {
		return loadSection(new SectionIdentity(dimension, sectionKey));
	}

	public synchronized SectionHandle loadSection(SectionIdentity identity) {
		Objects.requireNonNull(identity, "identity");
		long generation = generation();
		sections.put(identity, new SectionEntry(generation));
		return new SectionHandle(identity, generation);
	}

	public synchronized boolean observeStatus(String dimension, long chunkKey, FullChunkStatus status) {
		ChunkIdentity identity = new ChunkIdentity(dimension, chunkKey);
		List<ChunkEntry> entries = chunks.get(identity);
		Handle handle;
		if (entries == null || entries.isEmpty()) {
			handle = load(identity);
		} else {
			handle = new Handle(identity, entries.get(entries.size() - 1).generation);
		}
		return transition(handle, status);
	}

	public synchronized boolean unloadCurrent(String dimension, long chunkKey) {
		ChunkIdentity identity = new ChunkIdentity(dimension, chunkKey);
		List<ChunkEntry> entries = chunks.get(identity);
		if (entries == null || entries.isEmpty()) {
			return false;
		}
		ChunkEntry entry = entries.get(entries.size() - 1);
		Handle handle = new Handle(identity, entry.generation);
		return unload(handle);
	}

	public synchronized boolean transition(Handle handle, FullChunkStatus status) {
		Objects.requireNonNull(handle, "handle");
		Objects.requireNonNull(status, "status");
		ChunkEntry entry = current(handle);
		if (entry == null) {
			return false;
		}
		entry.status = status;
		entry.loaded = status != FullChunkStatus.INACCESSIBLE;
		entry.ticking = status == FullChunkStatus.BLOCK_TICKING || status == FullChunkStatus.ENTITY_TICKING;
		return true;
	}

	public synchronized Claim begin(Handle handle, Pending pending) {
		Objects.requireNonNull(handle, "handle");
		Objects.requireNonNull(pending, "pending");
		ChunkEntry entry = current(handle);
		if (entry == null) {
			return null;
		}
		increment(entry, pending);
		return new Claim(handle.identity, handle.generation, pending);
	}

	public synchronized boolean release(Claim claim) {
		Objects.requireNonNull(claim, "claim");
		if (claim.released) {
			return false;
		}
		claim.released = true;
		ChunkEntry entry = find(claim.identity, claim.generation);
		if (entry == null) {
			return false;
		}
		boolean current = currentGeneration(claim.identity) == claim.generation;
		decrement(entry, claim.pending);
		removeIfFinished(claim.identity, entry);
		return current;
	}

	public synchronized boolean unload(Handle handle) {
		Objects.requireNonNull(handle, "handle");
		ChunkEntry entry = current(handle);
		if (entry == null) {
			return false;
		}
		entry.loaded = false;
		entry.ticking = false;
		entry.status = FullChunkStatus.INACCESSIBLE;
		removeIfFinished(handle.identity, entry);
		return true;
	}

	public synchronized boolean releaseSection(SectionHandle handle) {
		Objects.requireNonNull(handle, "handle");
		SectionEntry entry = sections.get(handle.identity);
		if (entry == null || entry.generation != handle.generation) {
			return false;
		}
		sections.remove(handle.identity);
		return true;
	}

	public synchronized Snapshot snapshot() {
		List<ChunkSnapshot> chunkSnapshots = new ArrayList<>(chunks.size());
		for (Map.Entry<ChunkIdentity, List<ChunkEntry>> item : chunks.entrySet()) {
			for (ChunkEntry entry : item.getValue()) {
				chunkSnapshots.add(new ChunkSnapshot(item.getKey(), entry.generation, entry.status, entry.loaded, entry.ticking,
					entry.pendingSave > 0, entry.pendingSend, entry.pendingSave, entry.pendingTask));
			}
		}
		List<SectionSnapshot> sectionSnapshots = new ArrayList<>(sections.size());
		for (Map.Entry<SectionIdentity, SectionEntry> item : sections.entrySet()) {
			sectionSnapshots.add(new SectionSnapshot(item.getKey(), item.getValue().generation, item.getValue().pendingTask()));
		}
		return new Snapshot(chunkSnapshots, sectionSnapshots);
	}

	private ChunkEntry current(Handle handle) {
		List<ChunkEntry> entries = chunks.get(handle.identity);
		if (entries == null || entries.isEmpty()) {
			return null;
		}
		ChunkEntry entry = entries.get(entries.size() - 1);
		return entry.generation == handle.generation ? entry : null;
	}

	private long currentGeneration(ChunkIdentity identity) {
		List<ChunkEntry> entries = chunks.get(identity);
		if (entries == null || entries.isEmpty()) {
			return -1L;
		}
		return entries.get(entries.size() - 1).generation;
	}

	private ChunkEntry find(ChunkIdentity identity, long generation) {
		List<ChunkEntry> entries = chunks.get(identity);
		if (entries == null) {
			return null;
		}
		for (ChunkEntry entry : entries) {
			if (entry.generation == generation) {
				return entry;
			}
		}
		return null;
	}

	private long generation() {
		if (nextGeneration == Long.MAX_VALUE) {
			throw new IllegalStateException("ledger generation overflow");
		}
		return nextGeneration++;
	}

	private static void increment(ChunkEntry entry, Pending pending) {
		switch (pending) {
			case SEND -> entry.pendingSend++;
			case SAVE -> entry.pendingSave++;
			case TASK -> entry.pendingTask++;
		}
	}

	private static void decrement(ChunkEntry entry, Pending pending) {
		switch (pending) {
			case SEND -> entry.pendingSend--;
			case SAVE -> entry.pendingSave--;
			case TASK -> entry.pendingTask--;
		}
	}

	private void removeIfFinished(ChunkIdentity identity, ChunkEntry entry) {
		if (!entry.loaded && entry.pendingSend == 0 && entry.pendingSave == 0 && entry.pendingTask == 0) {
			List<ChunkEntry> entries = chunks.get(identity);
			if (entries != null) {
				entries.remove(entry);
				if (entries.isEmpty()) {
					chunks.remove(identity);
				}
			}
		}
	}

	private static final class ChunkEntry {
		private final long generation;
		private FullChunkStatus status = FullChunkStatus.INACCESSIBLE;
		private boolean loaded;
		private boolean ticking;
		private int pendingSend;
		private int pendingSave;
		private int pendingTask;

		private ChunkEntry(long generation) {
			this.generation = generation;
			this.loaded = true;
		}
	}

	private record SectionEntry(long generation) {
		private int pendingTask() {
			return 0;
		}
	}
}
