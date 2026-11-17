package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.PackManager;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.tune.Hardware;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class PackProgramCompiler implements AutoCloseable {
	private final PackManager manager;
	private final PackProgramPipelineBuilder builder;
	private final PackProgramCache<PackProgramPipelineBuilder.Result> cache;
	private final int parallelism;
	private PackCompilationExecutor executor;
	private long executorVersion = Long.MIN_VALUE;
	private long compiledVersion = Long.MIN_VALUE;
	private Result compiled;

	public PackProgramCompiler(PackManager manager, Object inventory, Object builder, Object cache) {
		this(manager, (PackProgramPipelineBuilder) builder,
			(PackProgramCache<PackProgramPipelineBuilder.Result>) cache, availableParallelism());
	}

	public PackProgramCompiler(PackManager manager, PackProgramPipelineBuilder builder,
		PackProgramCache<PackProgramPipelineBuilder.Result> cache) {
		this(manager, builder, cache, availableParallelism());
	}

	public PackProgramCompiler(PackManager manager, PackProgramPipelineBuilder builder,
		PackProgramCache<PackProgramPipelineBuilder.Result> cache, int parallelism) {
		if (parallelism < 1) {
			throw new IllegalArgumentException("parallelism must be positive");
		}
		this.manager = manager;
		this.builder = builder;
		this.cache = cache;
		this.parallelism = parallelism;
	}

	public synchronized Result compile() {
		long version = manager.version();
		if (version == compiledVersion && compiled != null) {
			return compiled;
		}
		if (version != executorVersion) {
			retireExecutor();
			executorVersion = version;
		}
		compiledVersion = version;
		Optional<ShaderPack> active = manager.active();
		if (active.isEmpty()) {
			if (cache != null) {
				cache.generation("");
			}
			compiled = Result.empty(version);
			return compiled;
		}
		if (isRenderThread() || builder == null || cache == null) {
			compiled = new Result(version, 0, 0, List.of(), List.of());
			return compiled;
		}
		List<Job> jobs = jobs(active.get());
		if (jobs.isEmpty()) {
			compiled = new Result(version, 0, 0, List.of(), List.of());
			return compiled;
		}
		if (executor == null) {
			executor = new PackCompilationExecutor(parallelism, jobs.size());
		}
		List<PackProgramRequest> requests = jobs.stream().map(Job::request).toList();
		PackCompilationExecutor.Result result = executor.compile(requests,
			request -> compile(new Job(request), active.get()));
		compiled = new Result(version, result.attempted(), result.artifacts().size(), result.failures(), result.artifacts());
		return compiled;
	}

	public Result compileOnLoad() {
		return compile();
	}

	public int parallelism() {
		return parallelism;
	}

	private PackCompilationExecutor.Outcome compile(Job job, ShaderPack pack) {
		if (job.request().program().equals("final")
			&& job.request().source(Program.Stage.VERTEX).orElse("").isEmpty()) {
			return PackCompilationExecutor.Outcome.failure(new PackProgramFailure(job.request().pack(), job.request().dimension(),
				"final", null, "missing required render final"));
		}
		if (isRenderThread()) {
			return PackCompilationExecutor.Outcome.failure(exceptionFailure(job, "compile: render-thread invocation"));
		}
		try {
			PackProgramCache.ProgramResult<PackProgramPipelineBuilder.Result> cached = cache.pipeline(
				job.request().dimension(), job.request().program(),
				(dimension, program) -> builder.build(job.request(), pack, pass(job.request().program())));
			PackProgramPipelineBuilder.Result result = cached.pipeline().orElse(null);
			if (result != null && result.successful()) {
				return PackCompilationExecutor.Outcome.success(result.artifact().orElseThrow());
			}
			if (result != null) {
				return PackCompilationExecutor.Outcome.failure(result.failure().orElseGet(() -> exceptionFailure(job, "pipeline: failed")));
			}
			return PackCompilationExecutor.Outcome.failure(exceptionFailure(job, "pipeline: "
				+ cached.failure().map(PackProgramCompiler::message).orElse("failed")));
		} catch (Exception exception) {
			return PackCompilationExecutor.Outcome.failure(exceptionFailure(job, "pipeline: " + message(exception)));
		}
	}

	private List<Job> jobs(ShaderPack pack) {
		String packName = manager.activeName();
		PackProgramInventory inventory = PackProgramInventory.from(packName.isEmpty() ? "shaderpack" : packName, pack);
		List<Job> jobs = new ArrayList<>();
		for (PackProgramRequest request : inventory.requests()) {
			jobs.add(new Job(request));
		}
		for (Map.Entry<String, dev.mikuteto.mtlrender.pack.ProgramSet> entry : pack.programSets().entrySet()) {
			if (!entry.getValue().hasRenderProgram("final")) {
				jobs.add(new Job(new PackProgramRequest(packName.isEmpty() ? "shaderpack" : packName,
					entry.getKey(), "final", Map.of(Program.Stage.VERTEX, "", Program.Stage.FRAGMENT, ""))));
			}
		}
		jobs.sort(Comparator.comparing((Job value) -> value.request().dimension())
			.thenComparing(value -> value.request().program()));
		return jobs;
	}

	private static PassKind pass(String name) {
		for (PassKind candidate : PassKind.executionOrder()) {
			if (name.equals(candidate.sourceName()) || name.startsWith(candidate.sourceName())) {
				return candidate;
			}
		}
		return PassKind.BEGIN;
	}

	private PackProgramFailure exceptionFailure(Job job, String detail) {
		return new PackProgramFailure(job.request().pack(), job.request().dimension(), job.request().program(),
			job.request().program().equals("final") && job.request().source(Program.Stage.VERTEX).orElse("").isEmpty()
				? null : Program.Stage.FRAGMENT, detail);
	}

	private static String message(Throwable exception) {
		String message = exception.getMessage();
		return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
	}

	private static boolean isRenderThread() {
		return Thread.currentThread().getName().toLowerCase().contains("render");
	}

	private static int availableParallelism() {
		int cores = Hardware.detect(Hardware.NativeCaps.missing()).performanceCores();
		return Math.max(1, cores);
	}

	private void retireExecutor() {
		if (executor != null) {
			executor.close();
			executor = null;
		}
	}

	@Override
	public void close() {
		retireExecutor();
	}

	private record Job(PackProgramRequest request) {
	}


	public record Result(long version, int attempted, int succeeded, List<PackProgramFailure> failures,
		List<PackProgramArtifact> artifacts) {
		public Result {
			failures = List.copyOf(failures);
			artifacts = List.copyOf(artifacts);
		}

		public int failed() {
			return failures.size();
		}

		public boolean requiredFinalMissing() {
			return failures.stream().anyMatch(failure -> failure.program().equals("final") && failure.stage() == null);
		}

		public Result(long version, int attempted, int succeeded, List<PackProgramFailure> failures) {
			this(version, attempted, succeeded, failures, List.of());
		}

		private static Result empty(long version) {
			return new Result(version, 0, 0, List.of(), List.of());
		}
	}
}
