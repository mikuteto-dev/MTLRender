package dev.mikuteto.mtlrender.lod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;

import dev.mikuteto.mtlrender.lod.LodData.DecodedSection;

public final class LodRenderManager implements AutoCloseable {
	public static final int MAX_CANDIDATES = 1024;
	public static final int MAX_IN_FLIGHT = 32;
	public static final int MAX_COMPLETIONS = 32;
	public static final int OUTER_RING = 8;

	@FunctionalInterface
	public interface LoadedSource {
		Map<ChunkPos, Map<Integer, DecodedSection>> sections(ClientLevel level);
	}

	public record RenderInput(
		Object world,
		String dimension,
		int centerChunkX,
		int centerChunkZ,
		int renderDistance,
		boolean singleplayer,
		Map<ChunkPos, Map<Integer, DecodedSection>> loaded,
		LodMesh.Material[] materials,
		long resourceGeneration
	) {
		public RenderInput {
			if (world != null && (dimension == null || dimension.isBlank())) {
				throw new IllegalArgumentException("dimension is required for a world");
			}
			if (renderDistance < 0) {
				throw new IllegalArgumentException("renderDistance must be non-negative");
			}
			if (resourceGeneration < 0) {
				throw new IllegalArgumentException("resourceGeneration must be non-negative");
			}
			loaded = copyLoaded(loaded);
			materials = materials == null ? new LodMesh.Material[0] : materials.clone();
		}

		private static Map<ChunkPos, Map<Integer, DecodedSection>> copyLoaded(
			Map<ChunkPos, Map<Integer, DecodedSection>> source) {
			if (source == null || source.isEmpty()) {
				return Map.of();
			}
			Map<ChunkPos, Map<Integer, DecodedSection>> copy = new HashMap<>();
			for (Map.Entry<ChunkPos, Map<Integer, DecodedSection>> entry : source.entrySet()) {
				copy.put(Objects.requireNonNull(entry.getKey(), "loaded chunk"),
					Map.copyOf(entry.getValue()));
			}
			return Map.copyOf(copy);
		}
	}

	public record Snapshot(List<LodData.LodKey> candidates, Counters counters) {
		public Snapshot {
			candidates = List.copyOf(candidates);
		}
	}

	public record Counters(long generation, int candidates, int inFlight, int completions,
		long outputBytes, int aggregates, long published, long discarded) {
	}

	private final LodSaveManager saves;
	private final LodMeshGenerator generator;
	private final LodGpuStore gpu;
	private final LodMissingTerrainGenerator missingTerrain;
	private final LodAssembler assembler = new LodAssembler();
	private final LodStore aggregates = new LodStore(64L * 1024L * 1024L);
	private final Set<LodData.LodKey> aggregateInFlight = ConcurrentHashMap.newKeySet();
	private final Map<LodData.LodKey, Integer> signatures = new HashMap<>();
	private final Set<LodData.LodKey> meshInFlight = ConcurrentHashMap.newKeySet();
	private final ConcurrentLinkedQueue<AggregateResult> aggregateResults = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<MeshResult> meshResults = new ConcurrentLinkedQueue<>();
	private Map<LodData.ChunkKey, Map<Integer, DecodedSection>> sources = Map.of();
	private List<LodData.LodKey> candidates = List.of();
	private Object world;
	private String dimension;
	private long resourceGeneration;
	private long generation;
	private long outputBytes;
	private long published;
	private long discarded;

	public LodRenderManager() {
		this(LodSaveManager.get(), new LodMeshGenerator(), new LodGpuStore(64L * 1024L * 1024L, 2048));
	}

	LodRenderManager(LodSaveManager saves, LodMeshGenerator generator, LodGpuStore gpu) {
		this.saves = Objects.requireNonNull(saves, "saves");
		this.generator = Objects.requireNonNull(generator, "generator");
		this.gpu = Objects.requireNonNull(gpu, "gpu");
		this.missingTerrain = new LodMissingTerrainGenerator(this.saves);
	}

	public void update(ClientLevel level, LoadedSource loadedSource, LodMesh.Material[] materials,
		long resourceGeneration) {
		missingTerrain.tick();
		if (level == null || loadedSource == null) {
			reset();
			return;
		}
		saves.update(level);
		MinecraftContext context = MinecraftContext.read(level);
		if (context == null) {
			reset();
			return;
		}
		update(new RenderInput(level, context.dimension(), context.chunkX(), context.chunkZ(),
			context.renderDistance(), context.singleplayer(), loadedSource.sections(level), materials, resourceGeneration));
		missingTerrain.drainCompletions(generation);
		if (context.singleplayer()) {
			requestMissing(level);
		}
	}

	public void update(RenderInput input) {
		Objects.requireNonNull(input, "input");
		if (input.world() == null) {
			reset();
			return;
		}
		if (world != input.world() || !Objects.equals(dimension, input.dimension())
			|| resourceGeneration != input.resourceGeneration()) {
			beginGeneration(input);
		}
		drain(input.materials());
		Map<LodData.ChunkKey, Map<Integer, DecodedSection>> next = sources(input);
		sources = next;
		candidates = candidateKeys(input);
		Set<LodData.LodKey> keys = new HashSet<>(candidates);
		for (LodData.LodKey key : keys) {
			scheduleAggregate(key, input.materials());
		}
	}

	public Snapshot snapshot() {
		return new Snapshot(candidates, new Counters(generation, candidates.size(),
			meshInFlight.size() + aggregateInFlight.size(), meshResults.size() + aggregateResults.size(),
			outputBytes, aggregates.entries(), published, discarded));
	}

	public Collection<LodGpuMesh> meshes() {
		return gpu.entries();
	}

	public long generation() {
		return generation;
	}

	public void reset() {
		generation++;
		generator.invalidateBefore(generation);
		missingTerrain.invalidateBefore(generation);
		aggregateInFlight.clear();
		meshInFlight.clear();
		aggregateResults.clear();
		meshResults.clear();
		aggregates.clear();
		signatures.clear();
		sources = Map.of();
		candidates = List.of();
		world = null;
		dimension = null;
		resourceGeneration = 0;
		outputBytes = 0;
	}

	@Override
	public void close() {
		generator.close();
		missingTerrain.close();
	}

	private void beginGeneration(RenderInput input) {
		reset();
		world = input.world();
		dimension = input.dimension();
		resourceGeneration = input.resourceGeneration();
	}

	private Map<LodData.ChunkKey, Map<Integer, DecodedSection>> sources(RenderInput input) {
		Map<LodData.ChunkKey, Map<Integer, DecodedSection>> result = new HashMap<>();
		for (Map.Entry<ChunkPos, Map<Integer, DecodedSection>> entry : input.loaded().entrySet()) {
			ChunkPos pos = entry.getKey();
			result.put(new LodData.ChunkKey(input.dimension(), pos.x(), pos.z()), entry.getValue());
		}
		if (!input.singleplayer()) {
			return Map.copyOf(result);
		}
		for (ChunkPos pos : outerPositions(input)) {
			if (input.loaded().containsKey(pos)) {
				continue;
			}
			LodData.ChunkKey key = new LodData.ChunkKey(input.dimension(), pos.x(), pos.z());
			Map<Integer, DecodedSection> saved = saves.decodedSections(key);
			if (!saved.isEmpty()) {
				result.put(key, saved);
			}
		}
		return Map.copyOf(result);
	}

	private List<LodData.LodKey> candidateKeys(RenderInput input) {
		Set<LodData.LodKey> keys = new HashSet<>();
		for (ChunkPos pos : outerPositions(input)) {
			int originX = Math.floorDiv(pos.x() * LodData.SOURCE_SIZE, LodData.LOD_SIZE) * LodData.LOD_SIZE;
			int originZ = Math.floorDiv(pos.z() * LodData.SOURCE_SIZE, LodData.LOD_SIZE) * LodData.LOD_SIZE;
			for (int sectionY : sectionYs(input, pos)) {
				int originY = Math.floorDiv(sectionY * LodData.SOURCE_SIZE, LodData.LOD_SIZE) * LodData.LOD_SIZE;
				keys.add(new LodData.LodKey(input.dimension(), 0, originX, originY, originZ));
			}
		}
		return List.copyOf(keys);
	}

	private Set<Integer> sectionYs(RenderInput input, ChunkPos pos) {
		Set<Integer> values = new HashSet<>();
		Map<Integer, DecodedSection> loaded = input.loaded().get(pos);
		if (loaded != null) {
			values.addAll(loaded.keySet());
		}
		if (input.singleplayer()) {
			values.addAll(saves.decodedSections(new LodData.ChunkKey(input.dimension(), pos.x(), pos.z())).keySet());
		}
		return values;
	}

	private void scheduleAggregate(LodData.LodKey key, LodMesh.Material[] materials) {
		int signature = sourceSignature(key);
		if (signatures.getOrDefault(key, Integer.MIN_VALUE) == signature || aggregateInFlight.contains(key)
			|| meshInFlight.contains(key) || aggregateInFlight.size() >= MAX_IN_FLIGHT) {
			return;
		}
		aggregateInFlight.add(key);
		long token = generation;
		Map<LodData.ChunkKey, Map<Integer, DecodedSection>> snapshot = sources;
		CompletableFuture.supplyAsync(() -> assemble(key, snapshot))
			.whenComplete((value, failure) -> aggregateResults.add(new AggregateResult(token, key,
				failure == null ? value : Optional.empty(), materials.clone())));
	}

	private Optional<LodData.LodSection> assemble(LodData.LodKey key,
		Map<LodData.ChunkKey, Map<Integer, DecodedSection>> sourceSnapshot) {
		if (key.level() == 0) {
			return assembler.assembleLevel0Data(key, sourceSnapshot, Optional.empty());
		}
		Map<LodData.LodKey, LodData.LodSection> children = new HashMap<>();
		int span = LodData.LOD_SIZE << (key.level() - 1);
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
			LodData.LodKey child = new LodData.LodKey(key.dimension(), key.level() - 1,
				key.originX() + x * span, key.originY() + y * span, key.originZ() + z * span);
			Optional<LodData.LodSection> section = aggregates.get(child);
			if (section.isEmpty()) return Optional.empty();
			children.put(child, section.get());
		}
		return assembler.reduceData(key, children, Optional.empty());
	}

	private void drain(LodMesh.Material[] materials) {
		int count = 0;
		AggregateResult aggregate;
		while (count++ < MAX_COMPLETIONS && (aggregate = aggregateResults.poll()) != null) {
			aggregateInFlight.remove(aggregate.key());
			if (aggregate.token() != generation || aggregate.section().isEmpty()) {
				discarded++;
				continue;
			}
			LodData.LodSection section = aggregate.section().get();
			aggregates.put(section);
			signatures.put(section.key(), sourceSignature(section.key()));
			if (section.key().level() < LodData.MAX_LEVEL) scheduleAggregate(parent(section.key()), materials);
			if (meshInFlight.size() >= MAX_IN_FLIGHT) continue;
			meshInFlight.add(section.key());
			long token = generation;
			try {
				generator.request(section, aggregate.materials(), token)
					.whenComplete((mesh, failure) -> meshResults.add(new MeshResult(token, section.key(), mesh, failure)));
			} catch (RejectedExecutionException error) {
				meshInFlight.remove(section.key());
				discarded++;
			}
		}
		count = 0;
		MeshResult mesh;
		while (count++ < MAX_COMPLETIONS && (mesh = meshResults.poll()) != null) {
			meshInFlight.remove(mesh.key());
			if (mesh.token() != generation || mesh.mesh() == null || mesh.failure() != null) {
				discarded++;
				continue;
			}
			try {
				Optional<LodGpuMesh> uploaded = gpu.upload(mesh.mesh(), generation);
				if (uploaded.isPresent()) {
					outputBytes = gpu.residentBytes();
					published++;
				}
			} catch (IllegalStateException error) {
				discarded++;
			}
		}
	}

	private int sourceSignature(LodData.LodKey key) {
		if (key.level() > 0) {
			int hash = key.hashCode();
			int span = LodData.LOD_SIZE << (key.level() - 1);
			for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
				LodData.LodKey child = new LodData.LodKey(key.dimension(), key.level() - 1,
					key.originX() + x * span, key.originY() + y * span, key.originZ() + z * span);
				Optional<LodData.LodSection> section = aggregates.get(child);
				if (section.isEmpty()) return Integer.MIN_VALUE;
				hash = 31 * hash + Arrays.hashCode(section.get().statesRaw());
			}
			return hash;
		}
		int hash = key.hashCode();
		for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
			LodData.ChunkKey chunk = new LodData.ChunkKey(key.dimension(), key.originX() / 16 + x, key.originZ() / 16 + z);
			Map<Integer, DecodedSection> values = sources.get(chunk);
			if (values == null) return Integer.MIN_VALUE;
			for (int y = 0; y < 2; y++) {
				DecodedSection section = values.get(key.originY() / 16 + y);
				if (section == null) return Integer.MIN_VALUE;
				hash = 31 * hash + Arrays.hashCode(section.statesRaw());
				hash = 31 * hash + Arrays.hashCode(section.biomesRaw());
				hash = 31 * hash + Arrays.hashCode(section.skyRaw());
				hash = 31 * hash + Arrays.hashCode(section.blockRaw());
			}
		}
		return hash;
	}

	private void requestMissing(ClientLevel level) {
		for (LodData.LodKey key : candidates) {
			if (key.level() != 0) {
				continue;
			}
			for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
				ChunkPos pos = new ChunkPos(Math.floorDiv(key.originX(), 16) + x,
					Math.floorDiv(key.originZ(), 16) + z);
				LodData.ChunkKey source = new LodData.ChunkKey(dimension, pos.x(), pos.z());
				if (!sources.containsKey(source)) {
					missingTerrain.request(level, pos, generation);
				}
			}
		}
	}

	private List<ChunkPos> outerPositions(RenderInput input) {
		return ring(new ChunkPos(input.centerChunkX(), input.centerChunkZ()), input.renderDistance(),
			input.renderDistance() + OUTER_RING, MAX_CANDIDATES);
	}

	static List<ChunkPos> ring(ChunkPos center, int inner, int outer, int limit) {
		if (center == null || inner < 0 || outer < inner || limit <= 0) return List.of();
		List<ChunkPos> result = new ArrayList<>(Math.min(limit, MAX_CANDIDATES));
		for (int radius = inner + 1; radius <= outer && result.size() < limit; radius++) {
			for (int dx = -radius; dx <= radius && result.size() < limit; dx++) {
				add(result, center, dx, -radius, limit);
				add(result, center, dx, radius, limit);
			}
			for (int dz = -radius + 1; dz < radius && result.size() < limit; dz++) {
				add(result, center, -radius, dz, limit);
				add(result, center, radius, dz, limit);
			}
		}
		return List.copyOf(result);
	}

	private static void add(List<ChunkPos> result, ChunkPos center, int dx, int dz, int limit) {
		ChunkPos value = new ChunkPos(center.x() + dx, center.z() + dz);
		if (result.size() < limit && !result.contains(value)) result.add(value);
	}

	private static LodData.LodKey parent(LodData.LodKey child) {
		int level = child.level() + 1;
		int span = LodData.LOD_SIZE << level;
		return new LodData.LodKey(child.dimension(), level, Math.floorDiv(child.originX(), span) * span,
			Math.floorDiv(child.originY(), span) * span, Math.floorDiv(child.originZ(), span) * span);
	}

	private record AggregateResult(long token, LodData.LodKey key, Optional<LodData.LodSection> section,
		LodMesh.Material[] materials) {}
	private record MeshResult(long token, LodData.LodKey key, LodMesh mesh, Throwable failure) {}
	private record MinecraftContext(String dimension, int chunkX, int chunkZ, int renderDistance, boolean singleplayer) {
		static MinecraftContext read(ClientLevel level) {
			net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
			if (minecraft.player == null) return null;
			return new MinecraftContext(level.dimension().identifier().toString(),
				minecraft.player.chunkPosition().x(), minecraft.player.chunkPosition().z(),
				Math.max(0, minecraft.options.renderDistance().get()), minecraft.getSingleplayerServer() != null);
		}
	}
}
