package dev.mikuteto.mtlrender.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class ServerChunkPrefetch {
	private final ServerChunkCache cache;
	private final ChunkLoadParallelism parallelism;

	public ServerChunkPrefetch(ServerChunkCache cache, ChunkLoadParallelism parallelism) {
		this.cache = Objects.requireNonNull(cache, "cache");
		this.parallelism = Objects.requireNonNull(parallelism, "parallelism");
	}

	public CompletableFuture<List<ChunkAccess>> prefetch(
		List<ChunkPos> positions,
		ChunkStatus status,
		boolean loadOrGenerate,
		Executor publicationExecutor
	) {
		List<ChunkPos> orderedPositions = List.copyOf(Objects.requireNonNull(positions, "positions"));
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(publicationExecutor, "publicationExecutor");
		ChunkLoadMarkers.Marker marker = ChunkLoadMarkers.begin(
			ChunkLoadMarkers.Phase.SERVER_PREFETCH,
			orderedPositions.size()
		);
		if (orderedPositions.isEmpty()) {
			CompletableFuture<List<ChunkAccess>> empty = CompletableFuture.completedFuture(List.of());
			marker.finish(null);
			return empty;
		}

		List<Callable<CompletableFuture<ChunkResult<ChunkAccess>>>> requests = new ArrayList<>(orderedPositions.size());
		for (ChunkPos position : orderedPositions) {
			requests.add(() -> cache.getChunkFuture(position.x(), position.z(), status, loadOrGenerate));
		}

		CompletableFuture<List<CompletableFuture<ChunkResult<ChunkAccess>>>> submitted;
		try {
			submitted = parallelism.submitBatch(requests);
		} catch (Throwable failure) {
			marker.finish(failure);
			return CompletableFuture.failedFuture(failure);
		}

		CompletableFuture<List<ChunkAccess>> result = submitted.thenComposeAsync(
			futures -> flatten(orderedPositions, futures, publicationExecutor),
			publicationExecutor
		);
		result.whenComplete((value, failure) -> marker.finish(failure));
		return result;
	}

	static <T> CompletableFuture<List<T>> flatten(
		List<ChunkPos> positions,
		List<CompletableFuture<ChunkResult<T>>> futures,
		Executor executor
	) {
		if (positions.size() != futures.size()) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("position and future counts differ"));
		}
		CompletableFuture<List<T>> result = CompletableFuture.completedFuture(List.of());
		for (int index = 0; index < positions.size(); index++) {
			ChunkPos position = positions.get(index);
			CompletableFuture<Completion<T>> completion = futures.get(index).handle(Completion::new);
			result = result.thenCombineAsync(completion, (values, item) -> append(values, position, item), executor);
		}
		return result;
	}

	private static <T> List<T> append(List<T> values, ChunkPos position, Completion<T> completion) {
		if (completion.failure() != null) {
			throw new ChunkPrefetchException(position, completion.failure().getMessage(), completion.failure());
		}
		ChunkResult<T> result = completion.result();
		if (result == null || !result.isSuccess()) {
			String error = result == null ? "null chunk result" : result.getError();
			throw new ChunkPrefetchException(position, error, null);
		}
		List<T> next = new ArrayList<>(values.size() + 1);
		next.addAll(values);
		next.add(result.orElse(null));
		return List.copyOf(next);
	}

	private record Completion<T>(ChunkResult<T> result, Throwable failure) {
	}

	public static final class ChunkPrefetchException extends RuntimeException {
		private final ChunkPos position;

		private ChunkPrefetchException(ChunkPos position, String error, Throwable cause) {
			super("Chunk " + position + " failed: " + String.valueOf(error), cause);
			this.position = position;
		}

		public ChunkPos position() {
			return position;
		}
	}
}
