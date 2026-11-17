package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.pack.PackManager;
import dev.mikuteto.mtlrender.pack.PackOption;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ProgramSet;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.compiler.CompiledShader;
import dev.mikuteto.mtlrender.pack.compiler.GlslCompiler;
import dev.mikuteto.mtlrender.pack.compiler.Reflection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ComputePipelineSet implements AutoCloseable {
	public static final String ENTRY_POINT = "mtlrender_compute_main";

	@FunctionalInterface
	public interface Compiler {
		CompiledShader compile(Program.Stage stage, String source, List<GlslCompiler.Define> defines) throws Exception;
	}

	@FunctionalInterface
	public interface PipelineCreator {
		long create(String msl, String entry, int x, int y, int z) throws Exception;
	}

	@FunctionalInterface
	public interface PipelineDestroyer {
		void destroy(long handle);
	}

	public record Binding(String name, int kind, int slot, boolean writable, String addressSpace) {
	}

	public record Pipeline(String dimension, String program, String source, String msl, String entryPoint,
		Reflection reflection, Reflection.ComputeLocalSize localSize, List<Binding> bindings, long handle) {
		public Pipeline {
			Objects.requireNonNull(dimension);
			Objects.requireNonNull(program);
			Objects.requireNonNull(source);
			Objects.requireNonNull(msl);
			Objects.requireNonNull(entryPoint);
			Objects.requireNonNull(reflection);
			Objects.requireNonNull(localSize);
			bindings = List.copyOf(bindings);
			if (handle <= 0) throw new IllegalArgumentException("compute pipeline handle must be positive");
		}
	}

	public record GeometryStageMetadata(GeometryComputeSeam.StageKind stage, String entryPoint,
		List<GeometryPlan.SideEffect> sideEffects) {
		public GeometryStageMetadata {
			Objects.requireNonNull(stage, "stage");
			Objects.requireNonNull(entryPoint, "entry point");
			if (entryPoint.isBlank()) throw new IllegalArgumentException("entry point must not be blank");
			sideEffects = List.copyOf(Objects.requireNonNull(sideEffects, "side effects"));
		}
	}

	public static List<GeometryStageMetadata> geometryStages(GeometryPlan plan) {
		Objects.requireNonNull(plan, "geometry plan");
		if (plan.path() != GeometryPlan.GeometryPath.COMPUTE) return List.of();
		String entry = plan.entryPoints().compute();
		List<GeometryPlan.SideEffect> effects = plan.sideEffects().stream().sorted().toList();
		return List.of(
			new GeometryStageMetadata(GeometryComputeSeam.StageKind.VS, entry + "_vs", effects),
			new GeometryStageMetadata(GeometryComputeSeam.StageKind.COUNT, entry + "_count", effects),
			new GeometryStageMetadata(GeometryComputeSeam.StageKind.SCAN, entry + "_scan", List.of()),
			new GeometryStageMetadata(GeometryComputeSeam.StageKind.GS, entry + "_gs", effects),
			new GeometryStageMetadata(GeometryComputeSeam.StageKind.COMPACT, entry + "_compact", effects),
			new GeometryStageMetadata(GeometryComputeSeam.StageKind.INDIRECT, entry + "_indirect", List.of()));
	}

	public record Failure(String dimension, String program, String message) {
		public Failure {
			Objects.requireNonNull(dimension);
			Objects.requireNonNull(program);
			Objects.requireNonNull(message);
		}
	}

	public record Result(long identity, List<Pipeline> pipelines, List<Failure> failures) {
		public Result {
			pipelines = List.copyOf(pipelines);
			failures = List.copyOf(failures);
		}
		public int attempted() { return pipelines.size() + failures.size(); }
	}

	private final PackManager manager;
	private final Compiler compiler;
	private final PipelineCreator creator;
	private final PipelineDestroyer destroyer;
	private final ExecutorService executor;
	private final int parallelism;
	private Result cached;
	private long cachedIdentity = Long.MIN_VALUE;
	private boolean closed;

	public ComputePipelineSet() {
		this(PackManager.get(), (stage, source, defines) -> new GlslCompiler().compile(toCompilerStage(stage),
			List.of(new GlslCompiler.Source(stage.extension(), source)), defines),
			(msl, entry, x, y, z) -> MetalAccess.createPackComputePipeline(msl, entry, x, y, z), availableParallelism());
	}

	public ComputePipelineSet(Compiler compiler, PipelineCreator creator, int parallelism) {
		this(null, compiler, creator, MetalAccess::destroyPackComputePipeline, parallelism);
	}

	public ComputePipelineSet(PackManager manager, Compiler compiler, PipelineCreator creator, int parallelism) {
		this(manager, compiler, creator, MetalAccess::destroyPackComputePipeline, parallelism);
	}

	public ComputePipelineSet(PackManager manager, Compiler compiler, PipelineCreator creator,
		PipelineDestroyer destroyer, int parallelism) {
		this.manager = manager;
		this.compiler = Objects.requireNonNull(compiler);
		this.creator = Objects.requireNonNull(creator);
		this.destroyer = Objects.requireNonNull(destroyer);
		if (parallelism < 1) throw new IllegalArgumentException("parallelism must be positive");
		this.parallelism = parallelism;
		this.executor = Executors.newFixedThreadPool(parallelism);
	}

	public synchronized Result compile() {
		if (manager == null) throw new IllegalStateException("a pack manager is required for compile()");
		if (closed) throw new IllegalStateException("compute pipeline set is closed");
		long identity = manager.version();
		if (cachedIdentity == identity && cached != null) return cached;
		cachedIdentity = identity;
		closePipelines(cached);
		Optional<ShaderPack> active = manager.active();
		if (active.isEmpty()) {
			cached = new Result(identity, List.of(), List.of());
			return cached;
		}
		List<Job> jobs = jobs(active.get());
		List<Future<Outcome>> futures = new ArrayList<>(jobs.size());
		for (Job job : jobs) futures.add(executor.submit(() -> compile(job, active.get())));
		List<Pipeline> pipelines = new ArrayList<>();
		List<Failure> failures = new ArrayList<>();
		for (int i = 0; i < futures.size(); i++) {
			try {
				Outcome outcome = futures.get(i).get();
				outcome.pipeline().ifPresent(pipelines::add);
				outcome.failure().ifPresent(failures::add);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				failures.add(failure(jobs.get(i), exception));
			} catch (ExecutionException exception) {
				failures.add(failure(jobs.get(i), exception.getCause() == null ? exception : exception.getCause()));
			}
		}
		pipelines.sort(Comparator.comparing(Pipeline::dimension).thenComparing(Pipeline::program));
		failures.sort(Comparator.comparing(Failure::dimension).thenComparing(Failure::program));
		cached = new Result(identity, pipelines, failures);
		return cached;
	}

	public synchronized Result compile(ShaderPack pack, long identity) {
		Objects.requireNonNull(pack, "pack");
		if (closed) throw new IllegalStateException("compute pipeline set is closed");
		if (cachedIdentity == identity && cached != null) return cached;
		cachedIdentity = identity;
		closePipelines(cached);
		cached = compilePack(pack, identity);
		return cached;
	}

	public int parallelism() { return parallelism; }

	private Result compilePack(ShaderPack pack, long identity) {
		List<Job> jobs = jobs(pack);
		List<Future<Outcome>> futures = new ArrayList<>(jobs.size());
		for (Job job : jobs) futures.add(executor.submit(() -> compile(job, pack)));
		List<Pipeline> pipelines = new ArrayList<>();
		List<Failure> failures = new ArrayList<>();
		for (int i = 0; i < futures.size(); i++) {
			try { Outcome o = futures.get(i).get(); o.pipeline().ifPresent(pipelines::add); o.failure().ifPresent(failures::add); }
			catch (InterruptedException e) { Thread.currentThread().interrupt(); failures.add(failure(jobs.get(i), e)); }
			catch (ExecutionException e) { failures.add(failure(jobs.get(i), e.getCause() == null ? e : e.getCause())); }
		}
		pipelines.sort(Comparator.comparing(Pipeline::dimension).thenComparing(Pipeline::program));
		failures.sort(Comparator.comparing(Failure::dimension).thenComparing(Failure::program));
		return new Result(identity, pipelines, failures);
	}

	private Outcome compile(Job job, ShaderPack pack) {
		try {
			CompiledShader shader = compiler.compile(Program.Stage.COMPUTE, job.source(), defines(pack));
			if (shader.reflection().stage() != Program.Stage.COMPUTE.ordinal()) throw new IllegalArgumentException("compute reflection has wrong stage");
			Reflection.ComputeLocalSize size = shader.reflection().computeLocalSize();
			if (size == null) size = new Reflection.ComputeLocalSize(1, 1, 1);
			List<Binding> bindings = new ArrayList<>();
			for (Reflection.Resource resource : shader.reflection().resources()) {
				bindings.add(new Binding(resource.name(), resource.kind(), resource.binding(), resource.writable(), resource.addressSpace()));
			}
			long handle = creator.create(shader.msl(), ENTRY_POINT, size.x(), size.y(), size.z());
			if (handle <= 0) throw new IllegalStateException("compute pipeline creation returned an invalid handle");
			return new Outcome(Optional.of(new Pipeline(job.dimension(), job.program(), job.source(), shader.msl(), ENTRY_POINT,
				shader.reflection(), size, bindings, handle)), Optional.empty());
		} catch (Exception exception) {
			return new Outcome(Optional.empty(), Optional.of(failure(job, exception)));
		}
	}

	private List<Job> jobs(ShaderPack pack) {
		List<Job> result = new ArrayList<>();
		for (Map.Entry<String, ProgramSet> entry : pack.programSets().entrySet()) {
			for (Program program : entry.getValue().programs().values()) {
				program.source(Program.Stage.COMPUTE).ifPresent(source -> result.add(new Job(entry.getKey(), program.name(), source)));
			}
		}
		result.sort(Comparator.comparing(Job::dimension).thenComparing(Job::program));
		return result;
	}

	private static List<GlslCompiler.Define> defines(ShaderPack pack) {
		List<GlslCompiler.Define> result = new ArrayList<>();
		for (Map.Entry<String, String> entry : pack.standardMacros().entrySet()) result.add(new GlslCompiler.Define(entry.getKey(), entry.getValue()));
		for (Map.Entry<String, PackOption> entry : pack.options().allOptions().entrySet()) {
			PackOption option = entry.getValue();
			String value = pack.optionValues().getOrDefault(entry.getKey(), option.isBoolean() ? Boolean.toString(option.booleanDefaultValue()) : option.defaultValue());
			if (option.isBoolean()) { if ("true".equals(value)) result.add(new GlslCompiler.Define(entry.getKey(), null)); }
			else if (value != null) result.add(new GlslCompiler.Define(entry.getKey(), value));
		}
		return List.copyOf(result);
	}

	private static Failure failure(Job job, Throwable exception) {
		String message = exception.getMessage();
		return new Failure(job.dimension(), job.program(), message == null || message.isBlank() ? exception.getClass().getSimpleName() : message);
	}

	private void closePipelines(Result result) {
		if (result == null) return;
		for (Pipeline pipeline : result.pipelines()) destroyer.destroy(pipeline.handle());
	}

	@Override public synchronized void close() {
		if (closed) return;
		closed = true;
		closePipelines(cached);
		cached = null;
		executor.shutdownNow();
	}

	private static GlslCompiler.Stage toCompilerStage(Program.Stage stage) {
		return switch (stage) {
			case VERTEX -> GlslCompiler.Stage.VERTEX;
			case FRAGMENT -> GlslCompiler.Stage.FRAGMENT;
			case COMPUTE -> GlslCompiler.Stage.COMPUTE;
			case GEOMETRY -> GlslCompiler.Stage.GEOMETRY;
			case TESS_CONTROL -> GlslCompiler.Stage.TESSELLATION_CONTROL;
			case TESS_EVALUATION -> GlslCompiler.Stage.TESSELLATION_EVALUATION;
		};
	}

	private static int availableParallelism() { return Math.max(1, Runtime.getRuntime().availableProcessors() - 1); }
	private record Job(String dimension, String program, String source) {}
	private record Outcome(Optional<Pipeline> pipeline, Optional<Failure> failure) {}
}
