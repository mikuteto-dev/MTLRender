package dev.mikuteto.mtlrender.chunk;

import dev.mikuteto.mtlrender.sys.Qos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChunkLoadParallelism implements AutoCloseable {
	private final Executor executor;
	private final Executor workerExecutor;
	private final boolean ownsExecutor;
	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicInteger activeBatches = new AtomicInteger();

	public ChunkLoadParallelism() {
		this(createExecutor(), true);
	}

	public ChunkLoadParallelism(Executor executor) {
		this(executor, false);
	}

	public static ChunkLoadParallelism shared() {
		return Holder.INSTANCE;
	}

	public <T> CompletableFuture<List<T>> submitBatch(List<? extends Callable<T>> jobs) {
		List<? extends Callable<T>> batch = List.copyOf(Objects.requireNonNull(jobs, "jobs"));
		if (!beginBatch()) {
			return CompletableFuture.failedFuture(new RejectedExecutionException("chunk load parallelism is shut down"));
		}
		if (batch.isEmpty()) {
			finishBatch();
			return CompletableFuture.completedFuture(List.of());
		}
		List<CompletableFuture<Outcome<T>>> tasks = new ArrayList<>(batch.size());
		for (Callable<T> job : batch) {
			tasks.add(start(job));
		}
		CompletableFuture<?>[] taskArray = tasks.toArray(CompletableFuture<?>[]::new);
		CompletableFuture<List<T>> result;
		try {
			result = CompletableFuture.allOf(taskArray)
				.thenApplyAsync(ignored -> collect(tasks), workerExecutor);
		} catch (Throwable failure) {
			finishBatch();
			return CompletableFuture.failedFuture(unwrap(failure));
		}
		result.whenComplete((ignored, failure) -> finishBatch());
		return result;
	}

	public boolean isShutdown() {
		return closed.get();
	}

	public void shutdown() {
		if (closed.compareAndSet(false, true) && activeBatches.get() == 0) {
			shutdownExecutor();
		}
	}

	@Override
	public void close() {
		shutdown();
	}

	private boolean beginBatch() {
		synchronized (this) {
			if (closed.get()) {
				return false;
			}
			activeBatches.incrementAndGet();
			return true;
		}
	}

	private void finishBatch() {
		if (activeBatches.decrementAndGet() == 0 && closed.get()) {
			shutdownExecutor();
		}
	}

	private void shutdownExecutor() {
		if (ownsExecutor && executor instanceof ExecutorService service) {
			service.shutdown();
		}
	}

	private <T> CompletableFuture<Outcome<T>> start(Callable<T> job) {
		CompletableFuture<Outcome<T>> task;
		try {
			task = CompletableFuture.supplyAsync(() -> run(job), workerExecutor);
		} catch (Throwable failure) {
			task = CompletableFuture.completedFuture(new Outcome<>(null, failure));
		}
		return task.handle((outcome, failure) -> failure == null
			? outcome
			: new Outcome<>(null, unwrap(failure)));
	}

	private static <T> Outcome<T> run(Callable<T> job) {
		try {
			return new Outcome<>(job.call(), null);
		} catch (Throwable failure) {
			return new Outcome<>(null, failure);
		}
	}

	private static <T> List<T> collect(List<CompletableFuture<Outcome<T>>> tasks) {
		List<T> results = new ArrayList<>(tasks.size());
		for (CompletableFuture<Outcome<T>> task : tasks) {
			Outcome<T> outcome = task.join();
			if (outcome.failure() != null) {
				throw new java.util.concurrent.CompletionException(outcome.failure());
			}
			results.add(outcome.value());
		}
		return Collections.unmodifiableList(results);
	}

	private static Throwable unwrap(Throwable failure) {
		Throwable current = failure;
		while ((current instanceof java.util.concurrent.CompletionException
			|| current instanceof java.util.concurrent.ExecutionException)
			&& current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	private static ExecutorService createExecutor() {
		int workers = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
		return Executors.newFixedThreadPool(
			workers,
			Thread.ofPlatform().daemon(true).name("MTLRender-ChunkLoad-", 0).factory()
		);
	}

	private ChunkLoadParallelism(Executor executor, boolean ownsExecutor) {
		this.executor = Objects.requireNonNull(executor, "executor");
		this.ownsExecutor = ownsExecutor;
		this.workerExecutor = command -> executor.execute(() -> {
			Qos.setWorkerThread();
			command.run();
		});
	}

	private record Outcome<T>(T value, Throwable failure) {
	}

	private static final class Holder {
		private static final ChunkLoadParallelism INSTANCE = new ChunkLoadParallelism();
	}
}
