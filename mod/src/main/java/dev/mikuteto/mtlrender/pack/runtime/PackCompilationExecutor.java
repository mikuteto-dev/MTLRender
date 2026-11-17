package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.sys.Qos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class PackCompilationExecutor implements AutoCloseable {
	@FunctionalInterface
	public interface Operation {
		Outcome compile(PackProgramRequest request) throws Exception;
	}

	@FunctionalInterface
	public interface QosSetter {
		void setUtility();
	}

	public record Outcome(PackProgramArtifact artifact, PackProgramFailure failure) {
		public Outcome {
			if ((artifact == null) == (failure == null)) {
				throw new IllegalArgumentException("compilation outcome must contain exactly one result");
			}
		}

		public static Outcome success(PackProgramArtifact artifact) {
			return new Outcome(Objects.requireNonNull(artifact), null);
		}

		public static Outcome failure(PackProgramFailure failure) {
			return new Outcome(null, Objects.requireNonNull(failure));
		}
	}

	public record Result(int attempted, List<PackProgramArtifact> artifacts, List<PackProgramFailure> failures) {
		public Result {
			artifacts = List.copyOf(artifacts);
			failures = List.copyOf(failures);
		}
	}

	private static final int MAX_WORKERS = 64;
	private final ExecutorService workers;
	private final Semaphore submissions;
	private final QosSetter qos;
	private boolean closed;

	public PackCompilationExecutor(int performanceCores, int queuedWork) {
		this(performanceCores, queuedWork, namedFactory(), Qos::setUtilityThread);
	}

	public PackCompilationExecutor(int performanceCores, int queuedWork, ThreadFactory factory,
		QosSetter qos) {
		Objects.requireNonNull(factory, "thread factory");
		this.qos = Objects.requireNonNull(qos, "qos setter");
		int work = Math.max(0, queuedWork);
		int workerCount = Math.max(1, Math.min(MAX_WORKERS, Math.min(Math.max(1, performanceCores), Math.max(1, work))));
		int queueCapacity = Math.max(1, workerCount);
		this.submissions = new Semaphore(workerCount + queueCapacity);
		this.workers = new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
			new ArrayBlockingQueue<>(queueCapacity), workerFactory(factory), new ThreadPoolExecutor.AbortPolicy());
	}

	public synchronized Result compile(List<PackProgramRequest> requests, Operation operation) {
		Objects.requireNonNull(requests, "requests");
		Objects.requireNonNull(operation, "operation");
		if (closed) {
			throw new IllegalStateException("pack compilation executor is closed");
		}
		List<Future<Outcome>> futures = new ArrayList<>(requests.size());
		List<PackProgramFailure> failures = new ArrayList<>();
		boolean interrupted = false;
		for (PackProgramRequest request : requests) {
			boolean submitted = false;
			while (!submitted) {
				try {
					submissions.acquire();
					futures.add(workers.submit(() -> {
						try {
							return operation.compile(request);
						} catch (Exception exception) {
							return Outcome.failure(failure(request, exception));
						} finally {
							submissions.release();
						}
					}));
					submitted = true;
				} catch (InterruptedException exception) {
					interrupted = true;
					failures.add(failure(request, exception));
					break;
				} catch (RejectedExecutionException exception) {
					submissions.release();
				}
			}
		}
		List<PackProgramArtifact> artifacts = new ArrayList<>();
		for (int index = 0; index < futures.size(); index++) {
			Future<Outcome> future = futures.get(index);
			try {
				Outcome outcome = future.get();
				if (outcome.artifact() != null) {
					artifacts.add(outcome.artifact());
				} else {
					failures.add(outcome.failure());
				}
			} catch (InterruptedException exception) {
				interrupted = true;
				failures.add(failure(requests.get(index), exception));
				index--;
				Thread.interrupted();
			} catch (ExecutionException exception) {
				Throwable cause = exception.getCause() == null ? exception : exception.getCause();
				failures.add(failure(requests.get(index), cause));
			}
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
		artifacts.sort(Comparator.comparing((PackProgramArtifact value) -> value.request().dimension())
			.thenComparing(value -> value.request().program()));
		failures.sort(Comparator.comparing(PackProgramFailure::pack)
			.thenComparing(PackProgramFailure::dimension)
			.thenComparing(PackProgramFailure::program)
			.thenComparing(value -> value.stage() == null ? "" : value.stage().name())
			.thenComparing(PackProgramFailure::normalizedFirstError));
		return new Result(requests.size(), artifacts, failures);
	}

	public synchronized void retire() {
		if (!closed) {
			closed = true;
			workers.shutdownNow();
		}
	}

	@Override
	public void close() {
		retire();
	}

	private ThreadFactory workerFactory(ThreadFactory factory) {
		AtomicInteger sequence = new AtomicInteger();
		return runnable -> {
			Thread thread = factory.newThread(() -> {
				qos.setUtility();
				runnable.run();
			});
			if (thread == null) {
				throw new IllegalStateException("pack compilation thread factory returned null");
			}
			thread.setName("MTLRender-PackCompile-" + sequence.incrementAndGet());
			return thread;
		};
	}

	private static PackProgramFailure failure(PackProgramRequest request, Throwable exception) {
		String message = exception.getMessage();
		if (message == null || message.isBlank()) {
			message = exception.getClass().getSimpleName();
		}
		return new PackProgramFailure(request.pack(), request.dimension(), request.program(),
			Program.Stage.FRAGMENT, message);
	}

	private static ThreadFactory namedFactory() {
		AtomicInteger sequence = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, "MTLRender-PackCompile-" + sequence.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}
}
