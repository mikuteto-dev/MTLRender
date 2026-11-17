package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ProgramSet;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.ShaderProperties;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.pack.texture.TextureSpec;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ComputeRuntime implements AutoCloseable {
	public interface Factory {
		ComputePipelineSet pipelines();
		ComputeResources resources(Map<Integer, ComputePlan.SsboSpec> ssbos,
			Map<String, ComputePlan.CustomImageSpec> images, ComputeResources.TargetSize targets);
		ComputeTextureBindings textures(List<TextureSpec> textures) throws Exception;
	}

	@FunctionalInterface
	public interface CommandSeam {
		void submit(AccessSet accessSet, boolean allowConcurrentCompute);

		default void begin(boolean allowConcurrentCompute) { if (MetalAccess.active()) MetalAccess.beginCompute(allowConcurrentCompute); }
		default void pipeline(long handle) { if (MetalAccess.active()) MetalAccess.usePackComputePipeline(handle); }
		default void storageBuffer(int slot, GpuBuffer buffer, ComputePlan.Access access) {
			if (MetalAccess.active()) MetalAccess.bindPackStorageBuffer(slot, buffer, 0, MetalAccess.Access.valueOf(access.name()));
		}
		default void image(int slot, GpuTextureView view, ComputePlan.Access access) {
			if (MetalAccess.active()) MetalAccess.bindPackImage(slot, view, MetalAccess.Access.valueOf(access.name()));
		}
		default void texture(int textureSlot, int samplerSlot, GpuTextureView view, GpuSampler sampler) {
			if (MetalAccess.active()) MetalAccess.bindPackTexture(textureSlot, samplerSlot, view, sampler, MetalAccess.Access.READ);
		}
		default void uniform(int slot, ByteBuffer bytes) { if (MetalAccess.active()) MetalAccess.setPackComputeBytes(slot, bytes); }
		default void direct(ComputeDispatchPlanner.Direct dispatch) {
			if (MetalAccess.active()) MetalAccess.dispatchPackCompute((int) dispatch.x(), (int) dispatch.y(), (int) dispatch.z());
		}
		default void indirect(GpuBuffer buffer, long offset) { if (MetalAccess.active()) MetalAccess.dispatchPackComputeIndirect(buffer, offset); }
		default void end() { if (MetalAccess.active()) MetalAccess.endCompute(); }
		default void dispatch(String program, ComputeDispatchPlanner.Result dispatch, AccessSet accessSet,
			boolean allowConcurrentCompute) {
			submit(accessSet, allowConcurrentCompute);
		}
		default void geometry(String program, GeometryComputeSeam.StageKind stage,
			ComputeDispatchPlanner.Direct dispatch, AccessSet accessSet, boolean allowConcurrentCompute) {
			submit(accessSet, allowConcurrentCompute);
		}
		default void geometryIndirect(String program, GeometryComputeSeam.IndirectDrawArguments arguments,
			AccessSet accessSet, boolean allowConcurrentCompute) {
			submit(accessSet, allowConcurrentCompute);
		}
	}

	public enum Access { READ, WRITE, READ_WRITE }

	public record AccessSet(List<ResourceAccess> resources) {
		public AccessSet {
			resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
		}
	}

	public record ResourceAccess(String name, Access access) {
		public ResourceAccess {
			if (name == null || name.isBlank()) throw new IllegalArgumentException("resource name must not be blank");
			Objects.requireNonNull(access, "access");
		}
	}

	public enum Phase { SETUP, BEGIN, SHADOW, SHADOWCOMP, PREPARE, DEFERRED, COMPOSITE, FINAL }

	public record Submission(Phase phase, String program, ComputeDispatchPlanner.Result dispatch) {
		public Submission {
			Objects.requireNonNull(phase, "phase");
			Objects.requireNonNull(program, "program");
			Objects.requireNonNull(dispatch, "dispatch");
		}
	}

	public record Failure(Phase phase, String program, String message) {
		public Failure {
			Objects.requireNonNull(phase, "phase");
			Objects.requireNonNull(program, "program");
			Objects.requireNonNull(message, "message");
		}
	}

	public record GeometryDispatch(GeometryComputeSeam.StageKind stage, ComputeDispatchPlanner.Direct dispatch,
		List<GeometryComputeSeam.PrefixBoundary> prefix, GeometryComputeSeam.IndirectDrawArguments indirect,
		AccessSet accessSet) {
		public GeometryDispatch {
			Objects.requireNonNull(stage, "stage");
			Objects.requireNonNull(dispatch, "dispatch");
			prefix = List.copyOf(Objects.requireNonNull(prefix, "prefix"));
			Objects.requireNonNull(accessSet, "access set");
		}
	}

	public record GeometrySubmission(GeometryComputeSeam.Context context, List<GeometryDispatch> dispatches,
		GeometryComputeSeam.Execution execution) {
		public GeometrySubmission {
			Objects.requireNonNull(context, "context");
			dispatches = List.copyOf(Objects.requireNonNull(dispatches, "dispatches"));
			Objects.requireNonNull(execution, "execution");
		}
	}

	public record FrameContext(String dimension, ShaderProperties properties,
		ComputeResources.TargetSize targets, ComputeDispatchPlanner.FrameSizeContext sizes,
		boolean allowConcurrentCompute, ByteBuffer uniformState) {
		public FrameContext(String dimension, ShaderProperties properties, ComputeResources.TargetSize targets,
			ComputeDispatchPlanner.FrameSizeContext sizes, boolean allowConcurrentCompute) {
			this(dimension, properties, targets, sizes, allowConcurrentCompute, null);
		}
		public FrameContext {
			dimension = Objects.requireNonNull(dimension, "dimension");
			properties = Objects.requireNonNull(properties, "properties");
			targets = Objects.requireNonNull(targets, "targets");
			sizes = Objects.requireNonNull(sizes, "sizes");
		}
	}

	public record Generation(long id, String dimension, List<String> programs) {
		public Generation {
			if (id < 0) throw new IllegalArgumentException("generation must be non-negative");
			dimension = Objects.requireNonNull(dimension, "dimension");
			programs = List.copyOf(Objects.requireNonNull(programs, "programs"));
		}
	}

	private final Factory factory;
	private final CommandSeam commands;
	private final boolean inert;
	private GenerationState active;
	private FrameContext frame;
	private boolean setupPending;
	private final List<Failure> failures = new ArrayList<>();
	private final List<GeometryComputeSeam.ProgramFailure> geometryFailures = new ArrayList<>();
	private boolean closed;

	public ComputeRuntime(Factory factory, CommandSeam commands) {
		this(factory, commands, false);
	}

	private ComputeRuntime(Factory factory, CommandSeam commands, boolean inert) {
		this.factory = Objects.requireNonNull(factory, "factory");
		this.commands = Objects.requireNonNull(commands, "commands");
		this.inert = inert;
	}

	public static ComputeRuntime inactive() {
		return new ComputeRuntime(new Factory() {
			@Override public ComputePipelineSet pipelines() { throw new IllegalStateException("inactive compute runtime"); }
			@Override public ComputeResources resources(Map<Integer, ComputePlan.SsboSpec> ssbos,
				Map<String, ComputePlan.CustomImageSpec> images, ComputeResources.TargetSize targets) {
				throw new IllegalStateException("inactive compute runtime");
			}
			@Override public ComputeTextureBindings textures(List<TextureSpec> textures) { throw new IllegalStateException("inactive compute runtime"); }
		}, (accessSet, allowConcurrentCompute) -> { }, true);
	}

	public synchronized void load(long generation, ShaderPack pack, ProgramSet programSet,
		ComputeResources.TargetSize targets) {
		ensureOpen();
		if (inert) return;
		Objects.requireNonNull(pack, "pack");
		Objects.requireNonNull(programSet, "program set");
		Objects.requireNonNull(targets, "targets");
		if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
		if (active != null && active.id == generation && active.targets.equals(targets)) return;
		GenerationState next = build(generation, pack, programSet, targets);
		retire();
		active = next;
		for (ComputePipelineSet.Failure failure : next.result.failures()) {
			Phase phase = phaseOf(failure.program());
			if (phase != null) failures.add(new Failure(phase, failure.program(),
				"compute pipeline " + failure.dimension() + "/" + failure.program() + ": " + failure.message()));
		}
		setupPending = true;
		frame = null;
	}

	public synchronized void resize(ComputeResources.TargetSize targets) {
		ensureOpen();
		if (inert) return;
		Objects.requireNonNull(targets, "targets");
		if (active == null || active.targets.equals(targets)) return;
		try {
			active.resources.reconcile(active.ssbos, active.images, targets);
			active.targets = targets;
			setupPending = true;
			frame = null;
		} catch (RuntimeException exception) {
			throw failure("resize", active.id, active.dimension, targets, exception);
		}
	}

	public synchronized void beginFrame(FrameContext context) {
		ensureOpen();
		if (inert) return;
		Objects.requireNonNull(context, "frame context");
		if (active == null) return;
		if (!active.dimension.equals(context.dimension())) throw new IllegalArgumentException("frame dimension does not match active compute generation");
		if (!active.targets.equals(context.targets())) throw new IllegalArgumentException("frame targets do not match active compute generation");
		frame = context;
	}

	public synchronized void markSetupExecuted() {
		ensureOpen();
		if (inert) return;
		if (active != null) setupPending = false;
	}

	public synchronized void submit(AccessSet accessSet) {
		ensureOpen();
		if (inert) return;
		Objects.requireNonNull(accessSet, "access set");
		if (active == null || frame == null) return;
		commands.submit(accessSet, frame.allowConcurrentCompute());
	}

	public synchronized boolean active() { return active != null && !closed; }
	public synchronized boolean setupPending() { return setupPending; }
	public synchronized Generation generation() { return active == null ? null : active.info(); }
	public synchronized FrameContext frame() { return frame; }
	 synchronized ComputePipelineSet pipelines() { return required().pipelines; }
	 synchronized ComputeResources resources() { return required().resources; }
	 synchronized ComputeTextureBindings textures() { return required().textures; }
	public synchronized List<Program> computePrograms() { return active == null ? List.of() : active.programs; }
	public synchronized List<Failure> failures() { return List.copyOf(failures); }
	public synchronized List<GeometryComputeSeam.ProgramFailure> geometryFailures() { return List.copyOf(geometryFailures); }

	public synchronized List<GeometrySubmission> runGeometry(List<GeometryComputeSeam.Execution> executions) {
		ensureOpen();
		Objects.requireNonNull(executions, "executions");
		if (inert || active == null || frame == null) return List.of();
		List<GeometrySubmission> result = new ArrayList<>();
		for (GeometryComputeSeam.Execution execution : executions) {
			try {
				result.add(runGeometry(execution));
			} catch (RuntimeException exception) {
				GeometryComputePlan.Context planContext = execution.plan().context();
				GeometryComputeSeam.Context context = geometryContext(planContext);
				String message = exception.getMessage();
				geometryFailures.add(new GeometryComputeSeam.ProgramFailure(context,
					message == null || message.isBlank() ? exception.getClass().getSimpleName() : message));
			}
		}
		return List.copyOf(result);
	}

	public synchronized GeometrySubmission runGeometry(GeometryComputeSeam.Execution execution) {
		ensureOpen();
		Objects.requireNonNull(execution, "execution");
		if (inert || active == null || frame == null) throw new IllegalStateException("compute runtime is inactive");
		if (execution.geometry().path() != GeometryPlan.GeometryPath.COMPUTE) {
			throw new IllegalArgumentException("geometry path is not compute emulation");
		}
		if (!execution.plan().context().dimension().equals(active.dimension)) {
			throw new IllegalArgumentException("geometry dimension does not match active generation");
		}
		active.resources.prepareGeometry(execution.plan());
		List<GeometryDispatch> dispatches = new ArrayList<>();
		long count = execution.plan().primitiveCount();
		for (GeometryComputeSeam.StageKind stage : execution.stages()) {
			if (stage == GeometryComputeSeam.StageKind.INDIRECT) {
				if (execution.indirect() != null) {
					AccessSet access = geometryAccess(execution, stage);
					commands.geometryIndirect(execution.plan().context().program(), execution.indirect(), access,
						execution.allowConcurrentCompute());
				}
				continue;
			}
			ComputeDispatchPlanner.Direct dispatch = new ComputeDispatchPlanner.Direct(count, 1, 1);
			AccessSet access = geometryAccess(execution, stage);
			commands.geometry(execution.plan().context().program(), stage, dispatch, access,
				execution.allowConcurrentCompute());
			dispatches.add(new GeometryDispatch(stage, dispatch, execution.prefix(), execution.indirect(), access));
		}
		GeometryComputePlan.Context planContext = execution.plan().context();
		return new GeometrySubmission(geometryContext(planContext), dispatches, execution);
	}

	private static GeometryComputeSeam.Context geometryContext(GeometryComputePlan.Context context) {
		String dimension = context.dimension().isBlank() ? "default" : context.dimension();
		return new GeometryComputeSeam.Context(context.pack(), dimension, context.program(), context.stage());
	}

	private static AccessSet geometryAccess(GeometryComputeSeam.Execution execution, GeometryComputeSeam.StageKind stage) {
		return new AccessSet(execution.accesses().stream().filter(value -> value.stage() == stage)
			.map(value -> new ResourceAccess(value.resource(), Access.valueOf(value.access().name()))).toList());
	}

	public synchronized List<Submission> runFrame() {
		ensureOpen();
		if (inert || active == null || frame == null) return List.of();
		List<Submission> result = new ArrayList<>();
		if (setupPending) result.addAll(run(Phase.SETUP));
		result.addAll(run(Phase.BEGIN));
		boolean before = frame.properties().prepareBeforeShadow().orElse(false);
		if (before) result.addAll(run(Phase.PREPARE));
		result.addAll(run(Phase.SHADOW));
		result.addAll(run(Phase.SHADOWCOMP));
		if (!before) result.addAll(run(Phase.PREPARE));
		result.addAll(run(Phase.DEFERRED));
		result.addAll(run(Phase.COMPOSITE));
		result.addAll(run(Phase.FINAL));
		return List.copyOf(result);
	}

	public synchronized List<Submission> run(Phase phase) {
		ensureOpen();
		if (inert || active == null || frame == null) return List.of();
		if (phase == Phase.SETUP && !setupPending) return List.of();
		List<Submission> result = new ArrayList<>();
		for (Program program : ordered(phase)) {
			try {
				ComputePipelineSet.Pipeline pipeline = active.pipeline(program.name());
				var local = new dev.mikuteto.mtlrender.pack.glsl.PackShaderReflection.ComputeLocalSize(
					pipeline.localSize().x(), pipeline.localSize().y(), pipeline.localSize().z());
				ComputePlan.ResolvedPassPlan plan = active.plans.get(program.name());
				ComputeDispatchPlanner.Result dispatch = ComputeDispatchPlanner.resolve(program, frame.properties(),
					ComputePlan.empty(), local, frame.sizes());
				AccessSet accessSet = bind(pipeline, plan, dispatch);
				commands.dispatch(program.name(), dispatch, accessSet, frame.allowConcurrentCompute());
				result.add(new Submission(phase, program.name(), dispatch));
			} catch (RuntimeException exception) {
				failures.add(new Failure(phase, program.name(), context(active.dimension, phase, program.name(), exception)));
			}
		}
		if (phase == Phase.SETUP && !result.isEmpty()) setupPending = false;
		return List.copyOf(result);
	}

	private AccessSet bind(ComputePipelineSet.Pipeline pipeline,
		ComputePlan.ResolvedPassPlan plan, ComputeDispatchPlanner.Result dispatch) {
		boolean begun = false;
		Map<String, Access> accesses = new LinkedHashMap<>();
		try {
			commands.begin(frame.allowConcurrentCompute());
			begun = true;
			commands.pipeline(pipeline.handle());
			for (ComputePipelineSet.Binding binding : pipeline.bindings()) {
				bindResource(plan, binding, accesses);
			}
			if (dispatch instanceof ComputeDispatchPlanner.Direct direct) {
				commands.direct(direct);
			} else if (dispatch instanceof ComputeDispatchPlanner.IndirectResult indirect) {
				GpuBuffer buffer = active.resources.reflectedSsbo(indirect.resource());
				long size = active.resources.ssboSize(indirect.resource()).orElseThrow();
				if (indirect.offset() > size - 12) {
					throw resourceError(indirect.resource(), "indirect dispatch arguments are out of bounds");
				}
				merge(accesses, indirect.resource(), Access.READ);
				commands.indirect(buffer, indirect.offset());
			}
			AccessSet result = new AccessSet(accesses.entrySet().stream()
				.map(entry -> new ResourceAccess(entry.getKey(), entry.getValue())).toList());
			commands.end();
			begun = false;
			return result;
		} finally {
			if (begun) commands.end();
		}
	}

	private void bindResource(ComputePlan.ResolvedPassPlan plan,
		ComputePipelineSet.Binding binding, Map<String, Access> accesses) {
		String name = binding.name();
		Access access = accessFor(plan, binding);
		switch (binding.kind()) {
			case 0 -> {
				ByteBuffer state = frame.uniformState();
				if (state == null) throw resourceError(name, "uniform/state buffer is missing");
				commands.uniform(binding.slot(), state);
				merge(accesses, name, Access.READ);
			}
			case 1 -> {
				if (!active.resources.hasSsbo(name)) throw resourceError(name, "SSBO is missing");
				commands.storageBuffer(binding.slot(), active.resources.reflectedSsbo(name), toPlan(access));
				merge(accesses, name, access);
			}
			case 2, 3 -> {
				ComputeTextureBindings.Binding texture = active.textures.find(name)
					.orElseThrow(() -> resourceError(name, "sampled texture is missing"));
				commands.texture(binding.slot(), binding.slot(), texture.view(), texture.sampler());
				merge(accesses, name, Access.READ);
			}
			case 5 -> {
				if (!active.resources.hasImage(name)) throw resourceError(name, "storage image is missing");
				commands.image(binding.slot(), active.resources.reflectedImage(name), toPlan(access));
				merge(accesses, name, access);
			}
			default -> throw resourceError(name, "unsupported compute resource kind " + binding.kind());
		}
	}

	private static Access accessFor(ComputePlan.ResolvedPassPlan plan, ComputePipelineSet.Binding binding) {
		if (binding.kind() == 1) {
			return plan.ssbos().stream().filter(value -> value.name().equals(binding.name())).map(value -> Access.valueOf(value.access().name()))
				.findFirst().orElseThrow(() -> resourceError(binding.name(), "SSBO declaration is missing"));
		}
		if (binding.kind() == 5) {
			return plan.images().stream().filter(value -> value.name().equals(binding.name())).map(value -> Access.valueOf(value.access().name()))
				.findFirst().orElseThrow(() -> resourceError(binding.name(), "image declaration is missing"));
		}
		return Access.READ;
	}

	private static void merge(Map<String, Access> accesses, String name, Access next) {
		Access old = accesses.get(name);
		if (old == null || old == next) { accesses.putIfAbsent(name, next); return; }
		if (old == Access.READ && next == Access.WRITE || old == Access.WRITE && next == Access.READ) accesses.put(name, Access.READ_WRITE);
		else if (old == Access.READ_WRITE || next == Access.READ_WRITE) accesses.put(name, Access.READ_WRITE);
	}

	private static ComputePlan.Access toPlan(Access access) { return ComputePlan.Access.valueOf(access.name()); }

	private static IllegalArgumentException resourceError(String name, String message) {
		return new IllegalArgumentException(message + ": " + name);
	}

	private List<Program> ordered(Phase phase) {
		if (phase == Phase.SETUP && !setupPending) return List.of();
		return active.programs.stream().filter(program -> phaseOf(program.name()) == phase)
			.sorted(Comparator.comparing(Program::name, ComputeRuntime::compareNames)).toList();
	}

	private static Phase phaseOf(String name) {
		if (name.equals("setup") || name.startsWith("setup_")) return Phase.SETUP;
		if (name.equals("begin") || name.startsWith("begin")) return Phase.BEGIN;
		if (name.equals("shadow") || name.startsWith("shadow_")) return Phase.SHADOW;
		if (name.equals("shadowcomp") || name.startsWith("shadowcomp")) return Phase.SHADOWCOMP;
		if (name.equals("prepare") || name.startsWith("prepare")) return Phase.PREPARE;
		if (name.equals("deferred") || name.startsWith("deferred")) return Phase.DEFERRED;
		if (name.equals("composite") || name.startsWith("composite")) return Phase.COMPOSITE;
		if (name.equals("final") || name.startsWith("final_")) return Phase.FINAL;
		return null;
	}

	private static int compareNames(String left, String right) {
		String lb = variantBase(left);
		String rb = variantBase(right);
		int base = comparePass(lb, rb);
		if (base != 0) return base;
		return Integer.compare(variant(left), variant(right));
	}

	private static int comparePass(String left, String right) {
		int li = numericSuffix(left), ri = numericSuffix(right);
		String lp = li < 0 ? left : left.substring(0, left.length() - Integer.toString(li).length());
		String rp = ri < 0 ? right : right.substring(0, right.length() - Integer.toString(ri).length());
		int prefix = lp.compareTo(rp);
		return prefix != 0 ? prefix : Integer.compare(li < 0 ? 0 : li, ri < 0 ? 0 : ri);
	}

	private static String variantBase(String name) { return name.matches(".+_[a-z]") ? name.substring(0, name.length() - 2) : name; }
	private static int variant(String name) { return name.matches(".+_[a-z]") ? name.charAt(name.length() - 1) - 'a' + 1 : 0; }
	private static int numericSuffix(String name) {
		String base = variantBase(name);
		int i = base.length() - 1;
		while (i >= 0 && Character.isDigit(base.charAt(i))) i--;
		if (i == base.length() - 1) return -1;
		try { return Integer.parseInt(base.substring(i + 1)); } catch (NumberFormatException exception) { return -1; }
	}
	private static String context(String dimension, Phase phase, String program, RuntimeException exception) {
		String message = exception.getMessage();
		return "dimension=" + dimension + " pass=" + phase + " variant=" + program + ": "
			+ (message == null ? exception.getClass().getSimpleName() : message);
	}

	public synchronized ComputeDispatchPlanner.Result dispatch(String programName) {
		ensureOpen();
		if (active == null || frame == null) throw new IllegalStateException("compute runtime has no active frame");
		Program program = active.programs.stream().filter(value -> value.name().equals(programName)).findFirst()
			.orElseThrow(() -> new IllegalArgumentException("missing compute program: " + programName));
		ComputePipelineSet.Pipeline pipeline = active.pipeline(programName);
		dev.mikuteto.mtlrender.pack.glsl.PackShaderReflection.ComputeLocalSize local =
			new dev.mikuteto.mtlrender.pack.glsl.PackShaderReflection.ComputeLocalSize(
				pipeline.localSize().x(), pipeline.localSize().y(), pipeline.localSize().z());
		return ComputeDispatchPlanner.resolve(program, frame.properties(), ComputePlan.empty(), local, frame.sizes());
	}

	@Override public synchronized void close() {
		if (closed) return;
		closed = true;
		retire();
		frame = null;
		setupPending = false;
	}

	private GenerationState build(long id, ShaderPack pack, ProgramSet set, ComputeResources.TargetSize targets) {
		List<Program> programs = set.programs().values().stream().filter(Program::isComputeProgram).toList();
		Map<Integer, ComputePlan.SsboSpec> ssbos = pack.properties().parsedBufferObjects();
		Map<String, ComputePlan.CustomImageSpec> images = pack.properties().parsedImages();
		ComputePipelineSet pipelines = null;
		ComputeResources resources = null;
		ComputeTextureBindings textures = null;
		try {
			pipelines = factory.pipelines();
			ComputePipelineSet.Result result = pipelines.compile(pack, id);
			if (result.pipelines().isEmpty() && !result.failures().isEmpty() && !programs.isEmpty()) {
				throw new IllegalStateException("compute pipeline failures: " + result.failures());
			}
			resources = factory.resources(ssbos, images, targets);
			textures = factory.textures(pack.textures());
			Map<String, ComputePlan.ResolvedPassPlan> plans = new LinkedHashMap<>();
			for (Program program : programs) {
				plans.put(program.name(), new ComputePlan.ResolvedPassPlan(
					ComputePlan.PassId.parse(program.name()), pack.properties().dispatch(), List.copyOf(ssbos.values()), List.copyOf(images.values())));
			}
			return new GenerationState(id, set.dimension(), targets, pipelines, result, resources, textures, programs, ssbos, images, plans);
		} catch (Exception exception) {
			if (textures != null) textures.close();
			if (resources != null) resources.close();
			if (pipelines != null) pipelines.close();
			throw failure("load", id, set.dimension(), targets, exception);
		}
	}

	private GenerationState required() {
		if (active == null) throw new IllegalStateException("compute runtime is inactive");
		return active;
	}

	private void retire() {
		if (active == null) return;
		active.close();
		active = null;
	}

	private void ensureOpen() { if (closed) throw new IllegalStateException("compute runtime is closed"); }

	private static IllegalStateException failure(String operation, long id, String dimension,
		ComputeResources.TargetSize targets, Exception exception) {
		return new IllegalStateException(operation + " compute generation " + id + " for dimension " + dimension
			+ " at " + targets, exception);
	}

	private static final class GenerationState implements AutoCloseable {
		private final long id;
		private final String dimension;
		private ComputeResources.TargetSize targets;
		private final ComputePipelineSet pipelines;
		private final ComputePipelineSet.Result result;
		private final ComputeResources resources;
		private final ComputeTextureBindings textures;
		private final List<Program> programs;
		private final Map<Integer, ComputePlan.SsboSpec> ssbos;
		private final Map<String, ComputePlan.CustomImageSpec> images;
		private final Map<String, ComputePlan.ResolvedPassPlan> plans;
		private boolean closed;

		private GenerationState(long id, String dimension, ComputeResources.TargetSize targets,
			ComputePipelineSet pipelines, ComputePipelineSet.Result result, ComputeResources resources, ComputeTextureBindings textures, List<Program> programs,
			Map<Integer, ComputePlan.SsboSpec> ssbos, Map<String, ComputePlan.CustomImageSpec> images,
			Map<String, ComputePlan.ResolvedPassPlan> plans) {
			this.id = id; this.dimension = dimension; this.targets = targets;
			this.pipelines = pipelines; this.result = result; this.resources = resources; this.textures = textures;
			this.programs = List.copyOf(programs); this.ssbos = Map.copyOf(ssbos); this.images = Map.copyOf(images); this.plans = Map.copyOf(plans);
		}
		private Generation info() { return new Generation(id, dimension, programs.stream().map(Program::name).toList()); }
		private ComputePipelineSet.Pipeline pipeline(String name) {
			return result.pipelines().stream().filter(value -> value.program().equals(name)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("missing compute pipeline: " + name));
		}
		@Override public void close() { if (closed) return; closed = true; textures.close(); resources.close(); pipelines.close(); }
	}
}
