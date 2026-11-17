package dev.mikuteto.mtlrender.lod;

import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.mixin.ServerChunkCacheAccess;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class LodMissingTerrainGenerator implements AutoCloseable {
	public static final int MAX_QUEUE = 32;
	public static final int MAX_PER_TICK = 2;
	public static final int OUTER_DISTANCE = LodRenderManager.OUTER_RING;

	private final LodSaveManager saves;
	private final ArrayDeque<Request> queue = new ArrayDeque<>();
	private final Map<RequestKey, Request> queued = new HashMap<>();
	private final Map<RequestKey, CompletableFuture<?>> active = new HashMap<>();
	private final ConcurrentLinkedQueue<Completion> completions = new ConcurrentLinkedQueue<>();
	private int submitted;

	public LodMissingTerrainGenerator() {
		this(LodSaveManager.get());
	}

	LodMissingTerrainGenerator(LodSaveManager saves) {
		this.saves = Objects.requireNonNull(saves, "saves");
	}

	public void tick() {
		submitted = 0;
		while (submitted < MAX_PER_TICK) {
			Request request;
			synchronized (this) {
				request = queue.pollFirst();
				if (request == null) {
					return;
				}
				queued.remove(request.key());
			}
			if (submit(request)) {
				submitted++;
			}
		}
	}

	public void request(ClientLevel clientLevel, ChunkPos pos, long generation) {
		if (!accepted(clientLevel, pos)) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		IntegratedServer server = minecraft.getSingleplayerServer();
		ServerLevel serverLevel = server == null ? null : server.getLevel(clientLevel.dimension());
		if (serverLevel == null || !server.isRunning()) {
			return;
		}
		Request request = new Request(new RequestKey(generation, clientLevel.dimension(), pos), server,
			serverLevel, clientLevel.dimension(), pos);
		synchronized (this) {
			if (queued.containsKey(request.key()) || active.containsKey(request.key()) || queue.size() >= MAX_QUEUE) {
				return;
			}
			queue.addLast(request);
			queued.put(request.key(), request);
		}
	}

	public void drainCompletions(long generation) {
		Completion completion;
		while ((completion = completions.poll()) != null) {
			if (completion.generation() == generation) {
				saves.generated(completion.pos());
			}
		}
	}

	public synchronized int queuedCount() {
		return queue.size();
	}

	public synchronized int activeCount() {
		return active.size();
	}

	public synchronized void invalidateBefore(long generation) {
		queue.removeIf(request -> request.key().generation() < generation);
		queued.entrySet().removeIf(entry -> entry.getKey().generation() < generation);
		for (Map.Entry<RequestKey, CompletableFuture<?>> entry : active.entrySet()) {
			if (entry.getKey().generation() < generation) {
				entry.getValue().cancel(true);
			}
		}
	}

	@Override
	public void close() {
		synchronized (this) {
			queue.clear();
			queued.clear();
			for (CompletableFuture<?> future : active.values()) {
				future.cancel(true);
			}
			active.clear();
		}
	}

	static boolean canRequest(boolean enabled, boolean integratedServer, boolean matchingDimension,
		boolean loadedClientChunk, ChunkPos center, ChunkPos pos, int renderDistance) {
		return enabled && integratedServer && matchingDimension && !loadedClientChunk
			&& withinOuterDistance(center, pos, renderDistance);
	}

	static boolean withinOuterDistance(ChunkPos center, ChunkPos pos, int renderDistance) {
		if (center == null || pos == null || renderDistance < 0) {
			return false;
		}
		long dx = Math.abs((long) pos.x() - center.x());
		long dz = Math.abs((long) pos.z() - center.z());
		long distance = Math.max(dx, dz);
		return distance > renderDistance && distance <= (long) renderDistance + OUTER_DISTANCE;
	}

	private boolean accepted(ClientLevel level, ChunkPos pos) {
		if (!Config.get().terrain.lodGenerateMissing || level == null || pos == null) {
			return false;
		}
		Minecraft minecraft = Minecraft.getInstance();
		IntegratedServer server = minecraft.getSingleplayerServer();
		ServerLevel serverLevel = server == null ? null : server.getLevel(level.dimension());
		boolean loaded = level.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false) != null;
		return server != null && serverLevel != null && server.isRunning()
			&& canRequest(Config.get().terrain.lodGenerateMissing, server != null,
				server.getLevel(level.dimension()) == serverLevel, loaded, minecraft.player == null ? null
					: minecraft.player.chunkPosition(), pos,
				Math.max(0, minecraft.options.renderDistance().get()));
	}

	private boolean submit(Request request) {
		CompletableFuture<ChunkResult<ChunkAccess>> result = new CompletableFuture<>();
		synchronized (this) {
			if (request.key().generation() < 0 || active.size() >= MAX_QUEUE) {
				return false;
			}
			active.put(request.key(), result);
		}
		try {
			request.server().execute(() -> start(request, result));
		} catch (Throwable failure) {
			finish(request, result, failure);
			return false;
		}
		return true;
	}

	private void start(Request request, CompletableFuture<ChunkResult<ChunkAccess>> result) {
		if (request.server().getLevel(request.dimension()) != request.level() || !request.server().isRunning()) {
			finish(request, result, null);
			return;
		}
		ServerChunkCache cache = request.level().getChunkSource();
		Ticket ticket = new Ticket(new TicketType(1L, 2), ChunkLevel.byStatus(ChunkStatus.NOISE));
		cache.addTicket(ticket, request.pos());
		((ServerChunkCacheAccess) cache).mtlrender$runDistanceManagerUpdates();
		cache.getChunkFuture(request.pos().x(), request.pos().z(), ChunkStatus.NOISE, false)
			.whenComplete((value, failure) -> request.server().execute(() -> {
				TicketStorage storage = ((ServerChunkCacheAccess) cache).mtlrender$ticketStorage();
				storage.removeTicket(ticket, request.pos());
				((ServerChunkCacheAccess) cache).mtlrender$runDistanceManagerUpdates();
				if (failure == null && value != null && value.isSuccess()) {
					completions.add(new Completion(request.key().generation(), request.pos()));
				}
				finish(request, result, failure);
			}));
	}

	private synchronized void finish(Request request, CompletableFuture<?> future, Throwable failure) {
		active.remove(request.key(), future);
		if (failure != null) {
			future.completeExceptionally(failure);
		} else if (!future.isDone()) {
			future.complete(null);
		}
	}

	private record Request(RequestKey key, IntegratedServer server, ServerLevel level,
		ResourceKey<Level> dimension, ChunkPos pos) {
	}

	private record RequestKey(long generation, ResourceKey<Level> dimension, ChunkPos pos) {
	}

	private record Completion(long generation, ChunkPos pos) {
	}
}
