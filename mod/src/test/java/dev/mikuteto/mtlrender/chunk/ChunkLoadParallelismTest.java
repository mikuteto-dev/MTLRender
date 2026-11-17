package dev.mikuteto.mtlrender.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChunkLoadParallelismTest {
	@Test
	void completesEmptyBatch() {
		try (ChunkLoadParallelism parallelism = new ChunkLoadParallelism(Runnable::run)) {
			assertEquals(List.of(), parallelism.submitBatch(List.of()).join());
		}
	}

	@Test
	void collectsAllJobsInSubmissionOrder() {
		try (ChunkLoadParallelism parallelism = new ChunkLoadParallelism(Runnable::run)) {
			assertEquals(
				List.of(1, 2, 3),
				parallelism.submitBatch(List.of(() -> 1, () -> 2, () -> 3)).join()
			);
		}
	}

	@Test
	void propagatesTheFirstFailureInSubmissionOrder() {
		IllegalStateException failure = new IllegalStateException("section load failed");
		try (ChunkLoadParallelism parallelism = new ChunkLoadParallelism(Runnable::run)) {
			CompletableFuture<List<Integer>> batch = parallelism.submitBatch(List.of(
				() -> 1,
				() -> {
					throw failure;
				},
				() -> 3
			));

			CompletionException exception = assertThrows(CompletionException.class, batch::join);
			assertSame(failure, exception.getCause());
		}
	}

	@Test
	void rejectsBatchesAfterShutdownWithoutClosingSuppliedExecutor() {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (ChunkLoadParallelism parallelism = new ChunkLoadParallelism(executor)) {
			parallelism.shutdown();

			assertTrue(parallelism.isShutdown());
			assertTrue(parallelism.submitBatch(List.of(() -> 1)).isCompletedExceptionally());
			assertFalse(executor.isShutdown());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void runsJobsOnTheSuppliedExecutor() {
		String caller = Thread.currentThread().getName();
		AtomicReference<String> worker = new AtomicReference<>();
		try (ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "chunk-load-test");
			thread.setDaemon(true);
			return thread;
		}); ChunkLoadParallelism parallelism = new ChunkLoadParallelism(executor)) {
			Callable<String> job = () -> {
				worker.set(Thread.currentThread().getName());
				return "worker";
			};
			List<String> result = parallelism.submitBatch(List.of(job)).join();

			assertEquals(List.of("worker"), result);
			assertEquals("chunk-load-test", worker.get());
			assertNotEquals(caller, worker.get());
		}
	}
}
