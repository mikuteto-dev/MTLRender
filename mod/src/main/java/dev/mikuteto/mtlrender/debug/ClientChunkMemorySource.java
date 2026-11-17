package dev.mikuteto.mtlrender.debug;

import dev.mikuteto.mtlrender.chunk.ClientChunkMemoryLedger;
import dev.mikuteto.mtlrender.mixin.ClientChunkMemoryLedgerAccess;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class ClientChunkMemorySource implements MemorySource {
	private static final long DEFAULT_INTERVAL_NANOS = 1_000_000_000L;
	private static final int DEFAULT_CHUNK_RECORD_LIMIT = 64;
	private static final int DEFAULT_SECTION_RECORD_LIMIT = 256;
	private static final ClientChunkMemorySource GLOBAL = new ClientChunkMemorySource(
		MemoryDiagnostics.global(),
		Long.getLong("mtlrender.memory.clientSections.intervalNanos", DEFAULT_INTERVAL_NANOS),
		Integer.getInteger("mtlrender.memory.clientSections.chunkRecordLimit", DEFAULT_CHUNK_RECORD_LIMIT),
		Integer.getInteger("mtlrender.memory.clientSections.sectionRecordLimit", DEFAULT_SECTION_RECORD_LIMIT)
	);

	private final MemoryDiagnostics diagnostics;
	private final long intervalNanos;
	private final int chunkRecordLimit;
	private final int sectionRecordLimit;
	private long lastSampleNanos = Long.MIN_VALUE;
	private volatile ClientChunkMemorySnapshot latest;
	private long transientPacketBytes;
	private long transientHeightmapBytes;

	static {
		registerGlobal();
	}

	public ClientChunkMemorySource(MemoryDiagnostics diagnostics, long intervalNanos, int chunkRecordLimit, int sectionRecordLimit) {
		this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
		if (intervalNanos < 0L || chunkRecordLimit < 0 || sectionRecordLimit < 0) {
			throw new IllegalArgumentException("invalid client memory source limits");
		}
		this.intervalNanos = intervalNanos;
		this.chunkRecordLimit = chunkRecordLimit;
		this.sectionRecordLimit = sectionRecordLimit;
	}

	public static ClientChunkMemorySource global() {
		return GLOBAL;
	}

	public static void registerGlobal() {
		MemoryDiagnostics.global().register("client_chunks", GLOBAL);
	}

	@Override
	public MemorySource.Values sample() {
		return MemorySource.Values.unavailable();
	}

	public ClientChunkMemorySnapshot latestSnapshot() {
		return latest;
	}

	public void maybeSample(ClientLevel level) {
		if (!diagnostics.enabled() || !clientSectionsEnabled() || level == null) {
			return;
		}
		long now = System.nanoTime();
		if (lastSampleNanos != Long.MIN_VALUE && now >= lastSampleNanos && now - lastSampleNanos < intervalNanos) {
			return;
		}
		try {
			latest = sample(level, now);
			lastSampleNanos = now;
		} catch (Throwable failure) {
			lastSampleNanos = now;
		}
	}

	public synchronized void recordPacketTransient(long packetBytes, long heightmapBytes) {
		if (!diagnostics.enabled() || !clientSectionsEnabled() || packetBytes < 0L || heightmapBytes < 0L) {
			return;
		}
		transientPacketBytes = add(transientPacketBytes, packetBytes);
		transientHeightmapBytes = add(transientHeightmapBytes, heightmapBytes);
	}

	private ClientChunkMemorySnapshot sample(ClientLevel level, long now) {
		ClientChunkCache cache = level.getChunkSource();
		ClientChunkMemoryLedger ledger = ((ClientChunkMemoryLedgerAccess)(Object)cache).mtlrender$clientChunkMemoryLedger();
		ClientChunkMemoryLedger.Snapshot identities = ledger.snapshot();
		Map<Long, ClientChunkMemoryLedger.Identity> chunksByKey = new HashMap<>();
		for (ClientChunkMemoryLedger.Identity identity : identities.chunks()) {
			chunksByKey.put(identity.key(), identity);
		}
		List<ClientChunkMemorySnapshot.Chunk> chunks = new ArrayList<>(Math.min(chunkRecordLimit, identities.chunks().size()));
		List<ClientChunkMemorySnapshot.Section> sections = new ArrayList<>(Math.min(sectionRecordLimit, identities.sections().size()));
		List<ClientChunkMemoryLedger.Identity> orderedChunks = new ArrayList<>(identities.chunks());
		orderedChunks.sort(Comparator.comparingLong(ClientChunkMemoryLedger.Identity::key));
		for (ClientChunkMemoryLedger.Identity identity : orderedChunks) {
			if (chunks.size() >= chunkRecordLimit) {
				break;
			}
			LevelChunk chunk = cache.getChunk(chunkX(identity.key()), chunkZ(identity.key()), ChunkStatus.FULL, false);
			if (chunk != null) {
				chunks.add(ClientChunkMemoryAccounting.chunk(chunk, identity.generation()));
			}
		}
		List<ClientChunkMemoryLedger.SectionIdentity> orderedSections = new ArrayList<>(identities.sections());
		orderedSections.sort(Comparator.comparingLong(ClientChunkMemoryLedger.SectionIdentity::key));
		for (ClientChunkMemoryLedger.SectionIdentity identity : orderedSections) {
			if (sections.size() >= sectionRecordLimit) {
				break;
			}
			ClientChunkMemoryLedger.Identity chunkIdentity = chunksByKey.get(identity.chunkKey());
			if (chunkIdentity == null) {
				continue;
			}
			LevelChunk chunk = cache.getChunk(chunkX(identity.chunkKey()), chunkZ(identity.chunkKey()), ChunkStatus.FULL, false);
			if (chunk == null || chunkIdentity.generation() < 0L) {
				continue;
			}
			SectionPos position = SectionPos.of(identity.key());
			int sectionIndex = chunk.getSectionIndexFromSectionY(position.y());
			if (sectionIndex >= 0 && sectionIndex < chunk.getSections().length) {
				sections.add(ClientChunkMemoryAccounting.section(chunk, sectionIndex, identity.generation()));
			}
		}
		long packetBytes;
		long heightmapBytes;
		synchronized (this) {
			packetBytes = transientPacketBytes;
			heightmapBytes = transientHeightmapBytes;
			transientPacketBytes = 0L;
			transientHeightmapBytes = 0L;
		}
		return new ClientChunkMemorySnapshot(now, chunkRecordLimit, sectionRecordLimit,
			identities.chunks().size(), identities.sections().size(), chunks, sections, packetBytes, heightmapBytes);
	}

	private static boolean clientSectionsEnabled() {
		return Boolean.getBoolean("mtlrender.memory.clientSections");
	}

	private static int chunkX(long key) {
		return (int)(key >> 32);
	}

	private static int chunkZ(long key) {
		return (int)key;
	}

	private static long add(long left, long right) {
		return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
	}
}
