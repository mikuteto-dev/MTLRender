package dev.mikuteto.mtlrender.lod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class LodSaveManager {
	private static final LodSaveManager INSTANCE = new LodSaveManager();
	private static final int MAX_CANDIDATES = 4096;
	private static final int SCAN_PER_UPDATE = 64;
	private static final int REQUESTS_PER_UPDATE = 4;
	private static final int COMPLETIONS_PER_UPDATE = 4;
	private static final int MAX_DECODED_CHUNKS = 128;
	private static final long DECODED_BUDGET = 32L * 1024L * 1024L;
	private static final long STORE_BUDGET = 64L * 1024L * 1024L;

	private final LodSaveSource source = new LodSaveSource();
	private final LodChunkDecoder decoder = new LodChunkDecoder();
	private final LodAssembler assembler = new LodAssembler();
	private final LodStore store = new LodStore(STORE_BUDGET);
	private final ConcurrentLinkedQueue<DecodedResult> decoded = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<AggregateResult> aggregates = new ConcurrentLinkedQueue<>();
	private final Set<CompletableFuture<?>> requests = ConcurrentHashMap.newKeySet();
	private final Map<LodData.ChunkKey, Map<Integer, LodData.DecodedSection>> chunks = new ConcurrentHashMap<>();
	private final Set<LodData.LodKey> aggregateInFlight = ConcurrentHashMap.newKeySet();
	private long generation;
	private ClientLevel level;
	private ServerLevel serverLevel;
	private ResourceKey<Level> dimension;
	private List<ChunkPos> candidates = List.of();
	private int candidateIndex;
	private long decodedBytes;
	private long requestCount;
	private long decodedCount;
	private long discardedCount;

	private LodSaveManager() {
	}

	public static LodSaveManager get() {
		return INSTANCE;
	}

	public void update(ClientLevel currentLevel) {
		if (currentLevel == null) {
			reset();
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		IntegratedServer server = minecraft.getSingleplayerServer();
		ServerLevel currentServerLevel = server == null ? null : server.getLevel(currentLevel.dimension());
		if (server == null || currentServerLevel == null || !server.isRunning()) {
			reset();
			return;
		}
		if (level != currentLevel || dimension != currentLevel.dimension() || serverLevel != currentServerLevel
			|| candidates.isEmpty() && minecraft.player != null) {
			reset();
			level = currentLevel;
			serverLevel = currentServerLevel;
			dimension = currentLevel.dimension();
			candidates = buildCandidates(minecraft, currentLevel);
		}
		publishDecoded();
		publishAggregates();
		scan(currentLevel);
	}

	public LodStore store() {
		return store;
	}

	public Optional<LodData.LodSection> section(LodData.LodKey key) {
		return store.get(key);
	}

	public Map<Integer, LodData.DecodedSection> decodedSections(LodData.ChunkKey key) {
		Map<Integer, LodData.DecodedSection> value = chunks.get(Objects.requireNonNull(key, "key"));
		return value == null ? Map.of() : Map.copyOf(value);
	}

	void generated(ChunkPos pos) {
		if (pos != null && requestedPositions.remove(pos)) {
			candidateIndex = 0;
		}
	}

	public Counters counters() {
		return new Counters(generation, requestCount, decodedCount, discardedCount, requests.size(), decodedBytes,
			store.currentBytes(), store.entries(), store.evictions());
	}

	static List<ChunkPos> buildCandidates(Minecraft minecraft, ClientLevel level) {
		if (minecraft == null || level == null || minecraft.player == null) {
			return List.of();
		}
		int inner = Math.max(2, minecraft.options.renderDistance().get());
		return candidatePositions(minecraft.player.chunkPosition(), inner, inner + 8, MAX_CANDIDATES);
	}

	static List<ChunkPos> candidatePositions(ChunkPos center, int inner, int outer, int limit) {
		if (center == null || inner < 0 || outer < inner || limit <= 0) {
			return List.of();
		}
		List<ChunkPos> result = new ArrayList<>(Math.min(limit, MAX_CANDIDATES));
		for (int radius = inner + 1; radius <= outer && result.size() < limit; radius++) {
			for (int dx = -radius; dx <= radius && result.size() < limit; dx++) {
				addCandidate(result, center, dx, -radius, limit);
				addCandidate(result, center, dx, radius, limit);
			}
			for (int dz = -radius + 1; dz < radius && result.size() < limit; dz++) {
				addCandidate(result, center, -radius, dz, limit);
				addCandidate(result, center, radius, dz, limit);
			}
		}
		return List.copyOf(result);
	}

	static boolean tokenValid(long expected, long current) {
		return expected == current;
	}

	private static void addCandidate(List<ChunkPos> result, ChunkPos center, int dx, int dz, int limit) {
		if (result.size() < limit) {
			result.add(new ChunkPos(center.x() + dx, center.z() + dz));
		}
	}

	private void scan(ClientLevel currentLevel) {
		int scanned = 0;
		int requested = 0;
		while (scanned++ < SCAN_PER_UPDATE && candidateIndex < candidates.size() && requested < REQUESTS_PER_UPDATE) {
			ChunkPos pos = candidates.get(candidateIndex++);
			if (currentLevel.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false) != null) {
				continue;
			}
			LodData.ChunkKey key = new LodData.ChunkKey(dimension.identifier().toString(), pos.x(), pos.z());
			if (chunks.containsKey(key) || !requestedPositions.add(pos)) {
				continue;
			}
			long token = generation;
			CompletableFuture<?> future = source.read(Minecraft.getInstance().getSingleplayerServer(), serverLevel, dimension, pos)
				.thenApplyAsync(value -> value.flatMap(tag -> decoder.decodeChunk(pos, tag, currentLevel, currentLevel.registryAccess())))
				.whenComplete((value, failure) -> decoded.add(new DecodedResult(token, key, value == null ? Optional.empty() : value)));
			requests.add(future);
			requestCount++;
			requested++;
		}
	}

	private final Set<ChunkPos> requestedPositions = new HashSet<>();

	private void publishDecoded() {
		int count = 0;
		DecodedResult result;
		while (count++ < COMPLETIONS_PER_UPDATE && (result = decoded.poll()) != null) {
			requests.removeIf(CompletableFuture::isDone);
			if (!tokenValid(result.token(), generation) || result.sections().isEmpty()) {
				discardedCount++;
				continue;
			}
			Map<Integer, LodData.DecodedSection> byY = new HashMap<>();
			long bytes = 0;
			for (LodData.DecodedSection section : result.sections().get()) {
				byY.put(section.sectionY(), section);
				bytes += section.estimatedBytes();
			}
			if (bytes > DECODED_BUDGET) {
				discardedCount++;
				continue;
			}
			Map<Integer, LodData.DecodedSection> old = chunks.put(result.key(), Map.copyOf(byY));
			if (old != null) {
				decodedBytes -= old.values().stream().mapToLong(LodData.DecodedSection::estimatedBytes).sum();
			}
			decodedBytes += bytes;
			decodedCount++;
			while (chunks.size() > MAX_DECODED_CHUNKS || decodedBytes > DECODED_BUDGET) {
				LodData.ChunkKey oldest = chunks.keySet().stream().findFirst().orElse(null);
				if (oldest == null) {
					break;
				}
				Map<Integer, LodData.DecodedSection> removed = chunks.remove(oldest);
				if (removed != null) {
					decodedBytes -= removed.values().stream().mapToLong(LodData.DecodedSection::estimatedBytes).sum();
				}
			}
			for (LodData.DecodedSection section : byY.values()) {
				int originX = Math.floorDiv(result.key().chunkX() * 16, 32) * 32;
				int originY = Math.floorDiv(section.sectionY() * 16, 32) * 32;
				int originZ = Math.floorDiv(result.key().chunkZ() * 16, 32) * 32;
				schedule(new LodData.LodKey(dimension.identifier().toString(), 0, originX, originY, originZ));
			}
		}
	}

	private void publishAggregates() {
		int count = 0;
		AggregateResult result;
		while (count++ < COMPLETIONS_PER_UPDATE && (result = aggregates.poll()) != null) {
			aggregateInFlight.remove(result.key());
			if (!tokenValid(result.token(), generation) || result.section().isEmpty()) {
				continue;
			}
			LodData.LodSection section = result.section().get();
			store.put(section);
			if (result.key().level() < LodData.MAX_LEVEL) {
				schedule(parent(result.key()));
			}
		}
	}

	private void schedule(LodData.LodKey key) {
		if (!aggregateInFlight.add(key)) {
			return;
		}
		long token = generation;
		CompletableFuture.supplyAsync(() -> assemble(key))
			.whenComplete((value, failure) -> aggregates.add(new AggregateResult(token, key, failure == null ? value : Optional.empty())));
	}

	private Optional<LodData.LodSection> assemble(LodData.LodKey key) {
		if (key.level() == 0) {
			return assembler.assembleLevel0Data(key, chunks, Optional.empty());
		}
		Map<LodData.LodKey, LodData.LodSection> children = new HashMap<>();
		int span = LodData.LOD_SIZE << (key.level() - 1);
		for (int z = 0; z < 2; z++) {
			for (int y = 0; y < 2; y++) {
				for (int x = 0; x < 2; x++) {
					LodData.LodKey child = new LodData.LodKey(key.dimension(), key.level() - 1,
						key.originX() + x * span, key.originY() + y * span, key.originZ() + z * span);
					Optional<LodData.LodSection> section = store.get(child);
					if (section.isEmpty()) {
						return Optional.empty();
					}
					children.put(child, section.get());
				}
			}
		}
		return assembler.reduceData(key, children, Optional.empty());
	}

	private static LodData.LodKey parent(LodData.LodKey child) {
		int level = child.level() + 1;
		int span = LodData.LOD_SIZE << level;
		return new LodData.LodKey(child.dimension(), level,
			Math.floorDiv(child.originX(), span) * span,
			Math.floorDiv(child.originY(), span) * span,
			Math.floorDiv(child.originZ(), span) * span);
	}

	private void reset() {
		generation++;
		requests.forEach(future -> future.cancel(true));
		requests.clear();
		decoded.clear();
		aggregates.clear();
		aggregateInFlight.clear();
		chunks.clear();
		requestedPositions.clear();
		store.clear();
		decodedBytes = 0;
		candidateIndex = 0;
		level = null;
		serverLevel = null;
		dimension = null;
		candidates = List.of();
	}

	public record Counters(long generation, long requests, long decoded, long discarded, int inFlight,
		long decodedBytes, long storeBytes, int storeEntries, long evictions) {
	}

	private record DecodedResult(long token, LodData.ChunkKey key, Optional<List<LodData.DecodedSection>> sections) {
	}

	private record AggregateResult(long token, LodData.LodKey key, Optional<LodData.LodSection> section) {
	}
}
