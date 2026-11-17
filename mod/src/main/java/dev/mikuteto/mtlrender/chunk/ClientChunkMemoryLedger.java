package dev.mikuteto.mtlrender.chunk;

import dev.mikuteto.mtlrender.debug.MemoryAccount;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ClientChunkMemoryLedger {
	public record Identity(long key, long generation, long bytes, long count) {
		public Identity {
			if (generation < 0L || bytes < 0L || count < 0L) {
				throw new IllegalArgumentException("ledger values must be non-negative");
			}
		}
	}

	public record SectionIdentity(long key, long chunkKey, long generation, long bytes, long count) {
		public SectionIdentity {
			if (generation < 0L || bytes < 0L || count < 0L) {
				throw new IllegalArgumentException("ledger values must be non-negative");
			}
		}
	}

	public record Snapshot(List<Identity> chunks, List<SectionIdentity> sections) {
		public Snapshot {
			chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
			sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
		}
	}

	private final MemoryAccount account;
	private final Map<Long, Entry> chunks = new HashMap<>();
	private final Map<Long, SectionEntry> sections = new HashMap<>();
	private long nextGeneration;

	public ClientChunkMemoryLedger() {
		this(MemoryAccount.global());
	}

	public ClientChunkMemoryLedger(MemoryAccount account) {
		this.account = Objects.requireNonNull(account, "account");
	}

	public synchronized long replaceChunk(long chunkKey) {
		return replaceChunk(chunkKey, 0L, 0L);
	}

	public synchronized long replaceChunk(long chunkKey, long bytes, long count) {
		Entry old = chunks.remove(chunkKey);
		if (old != null) {
			old.allocation.release();
		}
		long generation = generation();
		chunks.put(chunkKey, new Entry(generation, account.retain(MemoryAccount.Subsystem.TERRAIN, bytes, count)));
		return generation;
	}

	public synchronized long replaceSection(long sectionKey, long chunkKey) {
		return replaceSection(sectionKey, chunkKey, 0L, 0L);
	}

	public synchronized long replaceSection(long sectionKey, long chunkKey, long bytes, long count) {
		SectionEntry old = sections.remove(sectionKey);
		if (old != null) {
			old.allocation.release();
		}
		long generation = generation();
		sections.put(sectionKey, new SectionEntry(chunkKey, generation,
			account.retain(MemoryAccount.Subsystem.TERRAIN, bytes, count)));
		return generation;
	}

	public synchronized boolean releaseChunk(long chunkKey, long generation) {
		Entry entry = chunks.get(chunkKey);
		if (entry == null || entry.generation != generation) {
			return false;
		}
		chunks.remove(chunkKey);
		entry.allocation.release();
		return true;
	}

	public synchronized boolean releaseSection(long sectionKey, long generation) {
		SectionEntry entry = sections.get(sectionKey);
		if (entry == null || entry.generation != generation) {
			return false;
		}
		sections.remove(sectionKey);
		entry.allocation.release();
		return true;
	}

	public synchronized void removeChunk(long chunkKey) {
		Entry entry = chunks.remove(chunkKey);
		if (entry != null) {
			entry.allocation.release();
		}
		List<Long> removedSections = new ArrayList<>();
		for (Map.Entry<Long, SectionEntry> candidate : sections.entrySet()) {
			if (candidate.getValue().chunkKey == chunkKey) {
				candidate.getValue().allocation.release();
				removedSections.add(candidate.getKey());
			}
		}
		for (Long sectionKey : removedSections) {
			sections.remove(sectionKey);
		}
	}

	public synchronized int evictOutside(int centerChunkX, int centerChunkZ, int radius) {
		if (radius < 0) {
			throw new IllegalArgumentException("radius must be non-negative");
		}
		List<Long> removed = new ArrayList<>();
		for (Long key : chunks.keySet()) {
			int x = (int)(key >>> 32);
			int z = (int)(long)key;
			if (Math.abs((long)x - centerChunkX) > radius || Math.abs((long)z - centerChunkZ) > radius) {
				removed.add(key);
			}
		}
		for (Long key : removed) {
			removeChunk(key);
		}
		return removed.size();
	}

	public synchronized Snapshot snapshot() {
		List<Identity> chunkSnapshot = new ArrayList<>(chunks.size());
		for (Map.Entry<Long, Entry> entry : chunks.entrySet()) {
			chunkSnapshot.add(new Identity(entry.getKey(), entry.getValue().generation,
				entry.getValue().allocation.bytes(), entry.getValue().allocation.count()));
		}
		List<SectionIdentity> sectionSnapshot = new ArrayList<>(sections.size());
		for (Map.Entry<Long, SectionEntry> entry : sections.entrySet()) {
			SectionEntry value = entry.getValue();
			sectionSnapshot.add(new SectionIdentity(entry.getKey(), value.chunkKey, value.generation,
				value.allocation.bytes(), value.allocation.count()));
		}
		return new Snapshot(chunkSnapshot, sectionSnapshot);
	}

	private long generation() {
		if (nextGeneration == Long.MAX_VALUE) {
			throw new IllegalStateException("ledger generation overflow");
		}
		return nextGeneration++;
	}

	private record Entry(long generation, MemoryAccount.Allocation allocation) {
	}

	private record SectionEntry(long chunkKey, long generation, MemoryAccount.Allocation allocation) {
	}
}
