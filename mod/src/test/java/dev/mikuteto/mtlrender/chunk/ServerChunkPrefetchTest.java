package dev.mikuteto.mtlrender.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.ChunkPos;

class ServerChunkPrefetchTest {
	@Test
	void flattensEmptyInputImmediately() {
		CompletableFuture<List<String>> result = ServerChunkPrefetch.flatten(
			List.of(),
			List.of(),
			Runnable::run
		);

		assertEquals(List.of(), result.join());
		assertEquals(true, result.isDone());
	}

	@Test
	void preservesInputOrderWhenRequestsCompleteOutOfOrder() {
		CompletableFuture<ChunkResult<String>> first = new CompletableFuture<>();
		CompletableFuture<ChunkResult<String>> second = new CompletableFuture<>();
		CompletableFuture<List<String>> result = ServerChunkPrefetch.flatten(
			List.of(new ChunkPos(1, 2), new ChunkPos(3, 4)),
			List.of(first, second),
			Runnable::run
		);

		second.complete(ChunkResult.of("second"));
		first.complete(ChunkResult.of("first"));

		assertEquals(List.of("first", "second"), result.join());
	}

	@Test
	void reportsTheFirstPositionSpecificChunkFailure() {
		CompletableFuture<ChunkResult<String>> first = CompletableFuture.completedFuture(
			ChunkResult.error("first position is unloaded")
		);
		CompletableFuture<ChunkResult<String>> second = CompletableFuture.completedFuture(
			ChunkResult.error("second position is unloaded")
		);
		CompletableFuture<List<String>> result = ServerChunkPrefetch.flatten(
			List.of(new ChunkPos(5, 6), new ChunkPos(7, 8)),
			List.of(first, second),
			Runnable::run
		);

		CompletionException exception = assertThrows(CompletionException.class, result::join);
		ServerChunkPrefetch.ChunkPrefetchException failure = assertInstanceOf(
			ServerChunkPrefetch.ChunkPrefetchException.class,
			exception.getCause()
		);
		assertEquals(new ChunkPos(5, 6), failure.position());
		assertEquals(true, failure.getMessage().contains("first position is unloaded"));
		assertEquals(false, failure.getMessage().contains("second position is unloaded"));
	}

	@Test
	void retainsExceptionalRequestCauseAtItsInputPosition() {
		IllegalStateException cause = new IllegalStateException("request failed");
		CompletableFuture<ChunkResult<String>> first = CompletableFuture.failedFuture(cause);
		CompletableFuture<List<String>> result = ServerChunkPrefetch.flatten(
			List.of(new ChunkPos(9, 10)),
			List.of(first),
			Runnable::run
		);

		CompletionException exception = assertThrows(CompletionException.class, result::join);
		ServerChunkPrefetch.ChunkPrefetchException failure = assertInstanceOf(
			ServerChunkPrefetch.ChunkPrefetchException.class,
			exception.getCause()
		);
		assertEquals(new ChunkPos(9, 10), failure.position());
		assertSame(cause, failure.getCause());
	}
}
