package dev.mikuteto.mtlrender.pack.runtime;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferDrawBridge;
import dev.mikuteto.mtlrender.pack.shadow.ShadowFeatureBridge;
import dev.mikuteto.mtlrender.pack.shadow.ShadowMatrices;
import dev.mikuteto.mtlrender.pack.shadow.ShadowFrameSnapshot;
import dev.mikuteto.mtlrender.pack.shadow.ShadowPassExecutor;
import dev.mikuteto.mtlrender.pack.shadow.ShadowPassPlan;
import dev.mikuteto.mtlrender.pack.shadow.ShadowProgramKind;
import dev.mikuteto.mtlrender.pack.shadow.ShadowProgramResolver;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferRuntime;
import dev.mikuteto.mtlrender.pack.PackManager;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ProgramSet;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.ShaderProperties;
import dev.mikuteto.mtlrender.pack.compiler.Reflection;
import dev.mikuteto.mtlrender.pack.dh.DhDepthResources;
import dev.mikuteto.mtlrender.pack.dh.DhProgramKind;
import dev.mikuteto.mtlrender.pack.dh.DhShadowFrame;
import dev.mikuteto.mtlrender.pack.shadow.ShadowTargetPlan;
import dev.mikuteto.mtlrender.pack.shadow.ShadowTargetRuntime;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PackRuntimeService implements AutoCloseable {
	private static final Logger LOGGER = LogUtils.getLogger();

	public interface PackSource {
		Optional<ShaderPack> active();

		long version();

		default String name() {
			return "shaderpack";
		}

		default boolean configured() {
			return true;
		}

		default Optional<PackSnapshot> snapshot() {
			if (!configured()) return Optional.empty();
			Optional<ShaderPack> pack = Objects.requireNonNull(active(), "active pack");
			return pack.map(value -> new PackSnapshot(version(), value));
		}
	}

	@FunctionalInterface
	public interface RuntimeFactory {
		RuntimeBundle create(PackSnapshot pack, int width, int height);
	}

	@FunctionalInterface
	public interface GbufferFactory {
		GbufferRuntime.Generation create(PackSnapshot pack, String dimension, RuntimeResources resources);
	}

	public record PackSnapshot(long version, ShaderPack pack) {
		public PackSnapshot {
			Objects.requireNonNull(pack, "pack");
		}
	}

	public record FrameInput(
		Minecraft minecraft,
		GameRenderer gameRenderer,
		RenderTarget mainRenderTarget,
		LevelRenderState levelRenderState,
		CameraRenderState cameraRenderState,
		DeltaTracker deltaTracker) {
		public FrameInput {
			Objects.requireNonNull(minecraft, "minecraft");
			Objects.requireNonNull(gameRenderer, "game renderer");
			Objects.requireNonNull(mainRenderTarget, "main render target");
			Objects.requireNonNull(levelRenderState, "level render state");
			Objects.requireNonNull(cameraRenderState, "camera render state");
			Objects.requireNonNull(deltaTracker, "delta tracker");
		}
	}

	@FunctionalInterface
	public interface TessellationDelegate {
		PackPassResult execute(TessellationRequest request);
	}

	public record TessellationRequest(PackProgramRequest program, PassKind kind,
		dev.mikuteto.mtlrender.pack.target.RenderTargetPlan.Pass target, RuntimeFrame frame,
		PackTargetSet targets, PackUniformState uniforms, CommandEncoder encoder) {
		public TessellationRequest {
			Objects.requireNonNull(program, "program");
			Objects.requireNonNull(kind, "pass kind");
			Objects.requireNonNull(target, "target");
			Objects.requireNonNull(frame, "frame");
			Objects.requireNonNull(targets, "targets");
			Objects.requireNonNull(uniforms, "uniforms");
			Objects.requireNonNull(encoder, "encoder");
		}
	}

	public record RuntimeResources(
		Optional<PackTargetSet> targets,
		Optional<PackProgramCache<?>> programs,
		Optional<PackUniformState> uniforms,
		Optional<PackPassExecutor> executor,
		Optional<TessellationDelegate> tessellation,
		Optional<PackPassExecutor.ShadowStage> shadowStage,
		Optional<ShadowPassExecutor> shadowExecutor) {
		public RuntimeResources {
			targets = clean(targets, "targets");
			programs = clean(programs, "programs");
			uniforms = clean(uniforms, "uniforms");
			executor = clean(executor, "executor");
			tessellation = clean(tessellation, "tessellation");
			shadowStage = clean(shadowStage, "shadow stage");
			shadowExecutor = clean(shadowExecutor, "shadow executor");
		}

		public RuntimeResources(Optional<PackTargetSet> targets, Optional<PackProgramCache<?>> programs,
			Optional<PackUniformState> uniforms, Optional<PackPassExecutor> executor) {
			this(targets, programs, uniforms, executor, Optional.empty(), Optional.empty(), Optional.empty());
		}

		public RuntimeResources(Optional<PackTargetSet> targets, Optional<PackProgramCache<?>> programs,
			Optional<PackUniformState> uniforms, Optional<PackPassExecutor> executor,
			Optional<TessellationDelegate> tessellation) {
			this(targets, programs, uniforms, executor, tessellation, Optional.empty(), Optional.empty());
		}

		public RuntimeResources(Optional<PackTargetSet> targets, Optional<PackProgramCache<?>> programs,
			Optional<PackUniformState> uniforms, Optional<PackPassExecutor> executor,
			Optional<TessellationDelegate> tessellation, Optional<PackPassExecutor.ShadowStage> shadowStage) {
			this(targets, programs, uniforms, executor, tessellation, shadowStage, Optional.empty());
		}

		public static RuntimeResources empty() {
			return new RuntimeResources(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
		}

		public Optional<ShadowPassExecutor> shadowExecutor() {
			return shadowExecutor;
		}

		private static <T> Optional<T> clean(Optional<T> value, String name) {
			return Objects.requireNonNull(value, name);
		}
	}

	public record PassInput(PassKind kind, RuntimeProgram program) {
		public PassInput {
			Objects.requireNonNull(kind, "pass kind");
			Objects.requireNonNull(program, "runtime program");
		}
	}

	static List<PassKind> scheduledKinds(boolean setup) {
		List<PassKind> result = new ArrayList<>();
		for (PassKind kind : PassKind.executionOrder()) {
			if (kind != PassKind.SETUP || setup) result.add(kind);
		}
		return List.copyOf(result);
	}

	public record RuntimeBundle(PackRuntime runtime, RuntimeResources resources, ComputeRuntime compute) {
		public RuntimeBundle(PackRuntime runtime, RuntimeResources resources) {
			this(runtime, resources, ComputeRuntime.inactive());
		}

		public RuntimeBundle {
			Objects.requireNonNull(runtime, "runtime");
			Objects.requireNonNull(resources, "runtime resources");
			Objects.requireNonNull(compute, "compute runtime");
		}
	}

	private static PackRuntimeService instance;

	private final PackSource packs;
	private final RuntimeFactory factory;
	private PackRuntime activeRuntime = PackRuntime.inactive();
	private ComputeRuntime activeCompute = ComputeRuntime.inactive();
	private RuntimeResources activeResources = RuntimeResources.empty();
	private long activeVersion = Long.MIN_VALUE;
	private Object activeLevel;
	private int width;
	private int height;
	private boolean hasSize;
	private boolean setupPending;
	private boolean closed;
	private String activeDimension;
	private ShadowFrameSnapshot shadowFrame = ShadowFrameSnapshot.noPack();
	private final GbufferRuntime gbuffer;
	private final GbufferFactory gbufferFactory;

	public PackRuntimeService(PackSource packs, PackRuntime runtime) {
		this(packs, (RuntimeFactory) (pack, width, height) -> new RuntimeBundle(runtime,
			createResources(pack, width, height)));
	}

	public PackRuntimeService(PackSource packs, PackRuntime runtime, RuntimeResources resources) {
		this(packs, (RuntimeFactory) (ignored, width, height) -> new RuntimeBundle(runtime, resources));
	}

	public PackRuntimeService(PackSource packs, RuntimeFactory factory) {
		this(packs, factory, new GbufferRuntime((generation, transition) -> { }), PackRuntimeService::defaultGbuffer);
	}

	public PackRuntimeService(PackSource packs, RuntimeFactory factory, GbufferRuntime gbuffer,
		GbufferFactory gbufferFactory) {
		this.packs = Objects.requireNonNull(packs, "pack source");
		this.factory = Objects.requireNonNull(factory, "runtime factory");
		this.gbuffer = Objects.requireNonNull(gbuffer, "gbuffer runtime");
		this.gbufferFactory = Objects.requireNonNull(gbufferFactory, "gbuffer factory");
	}

	public static synchronized PackRuntimeService get() {
		if (instance == null) instance = create(PackManager.get(), PackRuntime.inactive());
		return instance;
	}

	public static synchronized void closeIfPresent() {
		if (instance == null) return;
		instance.close();
		instance = null;
	}

	public static PackRuntimeService create(PackManager manager, PackRuntime runtime) {
		Objects.requireNonNull(manager, "pack manager");
		return new PackRuntimeService(new PackSource() {
			@Override
			public boolean configured() {
				String name = Config.get().shaderPack;
				return name != null && !name.isEmpty();
			}

			@Override
			public Optional<ShaderPack> active() {
				return manager.active();
			}

			@Override
			public long version() {
				return manager.version();
			}

			@Override
			public String name() {
				String name = manager.activeName();
				return name.isEmpty() ? "shaderpack" : name;
			}
		}, runtime);
	}

	public PackSource packs() {
		return packs;
	}

	public RuntimeResources resources() {
		return activeResources;
	}

	public Optional<PackSnapshot> activePack() {
		return packs.snapshot();
	}

	public Optional<PackRuntime> runtime() {
		return activeVersion != Long.MIN_VALUE && activeRuntime.active() ? Optional.of(activeRuntime) : Optional.empty();
	}

	public long generation() {
		return activeVersion;
	}

	public ShadowFrameSnapshot shadowFrame() {
		return shadowFrame;
	}

	public void resize(int nextWidth, int nextHeight) {
		ensureOpen();
		if (nextWidth <= 0 || nextHeight <= 0) throw new IllegalArgumentException("dimensions must be positive");
		if (hasSize && width == nextWidth && height == nextHeight) return;
		width = nextWidth;
		height = nextHeight;
		hasSize = true;
		if (activeVersion != Long.MIN_VALUE) {
			shadowFrame = ShadowFrameSnapshot.noPack();
			gbuffer.publish(GbufferRuntime.Transition.RESIZE, Optional.empty());
			activeRuntime.resize(nextWidth, nextHeight);
			if (activeCompute.active()) {
				int shadow = activeResources.targets().map(value -> value.plan().shadowMapResolution()).orElse(nextWidth);
				activeCompute.resize(new ComputeResources.TargetSize(nextWidth, nextHeight, shadow, shadow));
			}
			setupPending = true;
		}
	}

	public Optional<PackPassResult> render(FrameInput input, RuntimeFrame frame) {
		ensureOpen();
		if (!packs.configured()) {
			retire();
			return Optional.empty();
		}
		Objects.requireNonNull(input, "frame input");
		Objects.requireNonNull(frame, "runtime frame");
		Optional<PackSnapshot> snapshot = packs.snapshot();
		if (snapshot.isEmpty()) {
			retire();
			return Optional.empty();
		}
		PackSnapshot pack = snapshot.orElseThrow();
		return renderActive(input, frame, pack);
	}

	private Optional<PackPassResult> renderActive(FrameInput input, RuntimeFrame frame, PackSnapshot pack) {
		int nextWidth = input.mainRenderTarget().width;
		int nextHeight = input.mainRenderTarget().height;
		if (nextWidth <= 0 || nextHeight <= 0) return Optional.empty();
		if (activeLevel != input.minecraft().level) {
			if (activeLevel != null) retire();
			activeLevel = input.minecraft().level;
		}
		String nextDimension = computeDimension(pack, frame);
		if (activeVersion != pack.version()) {
			retire();
			RuntimeBundle bundle = Objects.requireNonNull(factory.create(pack, nextWidth, nextHeight), "runtime bundle");
			activeRuntime = bundle.runtime();
			activeCompute = bundle.compute();
			activeResources = bundle.resources();
			activeVersion = pack.version();
			width = nextWidth;
			height = nextHeight;
			hasSize = true;
			setupPending = true;
			activeRuntime.reload(pack.version(), Optional.of(pack.pack()));
			loadCompute(pack, computeDimension(pack, frame), computeTargets(frame, pack));
			activeLevel = input.minecraft().level;
			activeDimension = nextDimension;
			compilePrograms(pack, frame, activeResources.programs());
			publishGbuffer(pack, nextDimension, GbufferRuntime.Transition.PACK_RELOAD);
		} else if (!Objects.equals(activeDimension, nextDimension)) {
			publishGbuffer(pack, nextDimension, GbufferRuntime.Transition.DIMENSION_CHANGE);
			activeDimension = nextDimension;
			compilePrograms(pack, frame, activeResources.programs());
		} else if (!hasSize || width != nextWidth || height != nextHeight) {
			gbuffer.publish(GbufferRuntime.Transition.RESIZE, Optional.empty());
			width = nextWidth;
			height = nextHeight;
			hasSize = true;
			activeRuntime.resize(nextWidth, nextHeight);
			resizeTargets(pack, nextWidth, nextHeight);
			resizeCompute(frame, pack);
			setupPending = true;
			publishGbuffer(pack, nextDimension, GbufferRuntime.Transition.RESIZE);
		}
		shadowFrame = ShadowFrameSnapshot.noPack();
		GbufferRuntime.FrameSnapshot gbufferFrame = gbuffer.beginFrame();
		try {
			Optional<PackPassResult> finalFailure = executeFrame(input, frame, pack, gbufferFrame);
			if (activeVersion != Long.MIN_VALUE) activeRuntime.afterLevelRender(frame);
			return finalFailure;
		} finally {
			gbuffer.endFrame(gbufferFrame);
		}
	}

	@Override
	public void close() {
		if (closed) return;
		closed = true;
		retire();
		gbuffer.close();
	}

	private void resizeTargets(PackSnapshot pack, int nextWidth, int nextHeight) {
		if (activeResources.targets().isEmpty()) return;
		PackTargetSet current = activeResources.targets().orElseThrow();
		PackTargetSet next = current.reallocate(activeVersion, pack.pack().targetPlan(),
			Optional.of(ShadowTargetPlan.build(pack.pack(), activeVersion)), nextWidth, nextHeight);
		if (next == current) return;
		activeResources = new RuntimeResources(Optional.of(next), activeResources.programs(), activeResources.uniforms(),
			activeResources.executor(), activeResources.tessellation(), activeResources.shadowStage(), activeResources.shadowExecutor());
	}

	private Optional<PackPassResult> executeFrame(FrameInput input, RuntimeFrame frame, PackSnapshot pack,
		GbufferRuntime.FrameSnapshot gbufferFrame) {
		Optional<GbufferRuntime.Generation> captured = gbufferFrame.generation();
		if (captured.isPresent() && (captured.orElseThrow().identity().packVersion() != activeVersion
			|| !captured.orElseThrow().identity().dimension().equals(activeDimension))) return Optional.empty();
		Optional<PackTargetSet> targetValue = activeResources.targets();
		Optional<PackProgramCache<?>> programValue = activeResources.programs();
		Optional<PackUniformState> uniformValue = activeResources.uniforms();
		if (targetValue.isEmpty() || programValue.isEmpty() || uniformValue.isEmpty()) return Optional.empty();
		PackTargetSet targets = targetValue.orElseThrow();
		if (!targets.same(activeVersion, pack.pack().targetPlan(), input.mainRenderTarget().width,
			input.mainRenderTarget().height) || frame.targetPlan() != pack.pack().targetPlan()) return Optional.empty();
		PackProgramCache<?> programs = programValue.orElseThrow();
		PackUniformState uniforms = uniformValue.orElseThrow();
		uniforms.collect(input.minecraft(), input.gameRenderer().mainCamera(), input.levelRenderState(), input.deltaTracker());
		captureShadowFrame(pack, frame, gbufferFrame, targets, uniforms);
		prepareCompute(pack, frame, targetsForCompute(frame, pack), uniforms);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		GpuTexture color = Objects.requireNonNull(input.gameRenderer().mainRenderTarget().getColorTexture(), "vanilla color texture");
		GpuTexture depth = Objects.requireNonNull(input.gameRenderer().mainRenderTarget().getDepthTexture(), "vanilla depth texture");
		targets.seed(encoder, color, depth);
		RequestedFramePasses requested = requestedPasses(pack, frame, targets, programs, uniforms, encoder);
		PackPassExecutor executor = activeResources.executor().orElseGet(() -> new PackPassExecutor(encoder));
		List<PackPassResult> results = executeCombined(requested, executor, pack, frame, targets);
		if (requested.empty()) {
			encoder.submit();
			return missingFinal(pack, frame);
		}
		if (activeResources.executor().isEmpty()
			&& results.stream().anyMatch(value -> value.succeeded() && value.kind() == PassKind.FINAL)) {
			GpuTexture output = targets.main().texture();
			encoder.copyTextureToTexture(output, color, 0, 0, 0, 0, 0,
				Math.min(output.getWidth(0), color.getWidth(0)), Math.min(output.getHeight(0), color.getHeight(0)));
		}
		if (activeResources.executor().isEmpty()) encoder.submit();
		setupPending = false;
		Optional<PackPassResult> finalFailure = results.stream().filter(PackPassResult::surfacedFailure).findFirst();
		if (finalFailure.isPresent()) return finalFailure;
		if (results.stream().noneMatch(value -> value.kind() == PassKind.FINAL)) return missingFinal(pack, frame);
		return Optional.empty();
	}

	private void captureShadowFrame(PackSnapshot pack, RuntimeFrame frame,
		GbufferRuntime.FrameSnapshot gbufferFrame, PackTargetSet targets, PackUniformState uniforms) {
		if (activeResources.shadowExecutor().isEmpty() || gbufferFrame.generation().isEmpty()) {
			shadowFrame = ShadowFrameSnapshot.noPack();
			return;
		}
		GbufferRuntime.Identity generation = gbufferFrame.generation().orElseThrow().identity();
		String dimension = computeDimension(pack, frame);
		ShaderProperties properties = pack.pack().properties();
		ShadowPassPlan plan = new ShadowPassPlan(pack.pack().targetPlan().shadowMapResolution(),
			pack.pack().targetPlan().shadowDistance(), Optional.empty(), 1.0F,
			pack.pack().targetPlan().shadowDistance(), 0.0F, shadowCulling(properties),
			ShadowMatrices.identity(), properties.shadowTerrain().orElse(true),
			properties.shadowTranslucent().orElse(false), properties.shadowEntities().orElse(true),
			properties.shadowBlockEntities().orElse(true));
		ShadowProgramResolver resolver = new ShadowProgramResolver(pack.pack().programSet(dimension), properties);
		List<dev.mikuteto.mtlrender.pack.shadow.ShadowProgramSelection> selections = List.of(
			resolver.resolve(ShadowProgramKind.TERRAIN_SOLID), resolver.resolve(ShadowProgramKind.TERRAIN_CUTOUT),
			resolver.resolve(ShadowProgramKind.TERRAIN_WATER, true), resolver.resolve(ShadowProgramKind.ENTITIES, true),
			resolver.resolve(ShadowProgramKind.LIGHTNING, true), resolver.resolve(ShadowProgramKind.BLOCK_ENTITIES, true));
		ShadowTargetRuntime.TargetSet target = new ShadowTargetSnapshot(targets.planVersion());
		shadowFrame = ShadowFrameSnapshot.enabled(pack, generation, plan, target, uniforms, selections,
			properties.prepareBeforeShadow().orElse(false));
		uniforms.gameState().publishShadow(shadowFrame);
	}

	private List<PackPassResult> executeCombined(RequestedFramePasses requested,
		PackPassExecutor executor, PackSnapshot pack, RuntimeFrame frame, PackTargetSet targets) {
		List<PackPassResult> results = new ArrayList<>();
		if (activeCompute.setupPending()) runCompute(ComputeRuntime.Phase.SETUP);
		for (PassKind kind : scheduledKinds(setupPending)) {
			if (kind == PassKind.BEGIN) {
				runCompute(ComputeRuntime.Phase.BEGIN);
				if (prepareBeforeShadow(pack)) {
					runCompute(ComputeRuntime.Phase.PREPARE);
					executePassGroup(requested, executor, PassKind.PREPARE, results);
				}
			}
			if (kind == PassKind.SHADOWCOMP) {
				runCompute(ComputeRuntime.Phase.SHADOW);
				if (executeShadow(executor, pack, frame, targets)
					&& activeResources.shadowExecutor().isEmpty()) {
					executor.copyShadowTargets(ShadowTargetPlan.build(pack.pack(), activeVersion), targets);
				}
				runCompute(ComputeRuntime.Phase.SHADOWCOMP);
			}
			if (kind == PassKind.PREPARE && !prepareBeforeShadow(pack)) runCompute(ComputeRuntime.Phase.PREPARE);
			if (kind == PassKind.DEFERRED) runCompute(ComputeRuntime.Phase.DEFERRED);
			if (kind == PassKind.COMPOSITE) runCompute(ComputeRuntime.Phase.COMPOSITE);
			if (kind == PassKind.FINAL) runCompute(ComputeRuntime.Phase.FINAL);
			if (kind != PassKind.PREPARE || !prepareBeforeShadow(pack)) {
				executePassGroup(requested, executor, kind, results);
			}
		}
		return List.copyOf(results);
	}

	private void executePassGroup(RequestedFramePasses requested, PackPassExecutor executor, PassKind kind,
		List<PackPassResult> results) {
		List<PackPassExecutor.RequestedPass> group = requested.ordinary().stream()
			.filter(value -> value.preparedPass().kind() == kind).toList();
		results.addAll(executor.executeSequence(group));
		for (TessellationRequest tessellation : requested.tessellation().stream()
			.filter(value -> value.kind() == kind).toList()) {
			activeResources.tessellation().ifPresent(delegate -> results.add(delegate.execute(tessellation)));
		}
	}

	private void runCompute(ComputeRuntime.Phase phase) {
		if (!activeCompute.active()) return;
		activeCompute.run(phase);
	}

	private boolean executeShadow(PackPassExecutor executor, PackSnapshot pack, RuntimeFrame frame, PackTargetSet targets) {
		if (activeResources.shadowExecutor().isPresent()) {
			ShadowFrameSnapshot frameSnapshot = shadowFrame;
			try {
				return activeResources.shadowExecutor().orElseThrow()
					.execute(frameSnapshot, frameSnapshot.generations()).rendered();
			} catch (RuntimeException exception) {
				return false;
			}
		}
		Optional<PackPassExecutor.ShadowStage> stageValue = activeResources.shadowStage();
		if (stageValue.isEmpty() || activeVersion < 0L) return false;
		if (pack.pack().programSet(computeDimension(pack, frame)).resolve(DhProgramKind.SHADOW).isEmpty()) return false;
		PackPassExecutor.ShadowStage stage = stageValue.orElseThrow();
		PackPassExecutor.ShadowInput input = stage.capture(pack, activeVersion, targets);
		if (input == null || input.uniforms().generation() != activeVersion) return false;
		DhDepthResources depth = DhDepthResources.from(targets);
		ShadowPassPlan plan = shadowPlan(pack, frame, input, targets);
		DhShadowFrame shadow = DhShadowFrame.enabled(new DhShadowFrame.PackIdentity(packs.name()), activeVersion,
			true, plan.shadowTerrain(), plan.shadowTranslucent(), input.uniforms(), activeVersion, targets.planVersion(), depth);
		ShadowProgramResolver resolver = new ShadowProgramResolver(pack.pack().programSet(computeDimension(pack, frame)), pack.pack().properties());
		try (ShadowFeatureBridge.Scope ignored = ShadowFeatureBridge.enter(plan, resolver)) {
			executor.executeShadow(shadow, targets, stage);
		}
		return true;
	}

	private ShadowPassPlan shadowPlan(PackSnapshot pack, RuntimeFrame frame, PackPassExecutor.ShadowInput input,
		PackTargetSet targets) {
		var uniforms = input.uniforms();
		ShadowMatrices matrices = new ShadowMatrices(uniforms.shadowModelViewMatrix(), uniforms.shadowModelViewInverseMatrix(),
			uniforms.shadowProjectionMatrix(), uniforms.shadowProjectionInverseMatrix(), 0.0F, 0.0F, 0.0F,
			pack.pack().targetPlan().shadowDistance());
		ShaderProperties properties = pack.pack().properties();
		return new ShadowPassPlan(pack.pack().targetPlan().shadowMapResolution(), pack.pack().targetPlan().shadowDistance(),
			Optional.empty(), 1.0F, pack.pack().targetPlan().shadowDistance(), 0.0F, shadowCulling(properties), matrices,
			input.shadowTerrain(), input.shadowTranslucent(), properties.shadowEntities().orElse(true),
			properties.shadowBlockEntities().orElse(true));
	}

	private static ShadowPassPlan.CullingMode shadowCulling(ShaderProperties properties) {
		return properties.shadowCulling().map(value -> switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "advanced" -> ShadowPassPlan.CullingMode.ADVANCED;
			case "safe", "safe_zone" -> ShadowPassPlan.CullingMode.SAFE_ZONE;
			case "distance" -> ShadowPassPlan.CullingMode.DISTANCE;
			default -> ShadowPassPlan.CullingMode.DEFAULT;
		}).orElse(ShadowPassPlan.CullingMode.DEFAULT);
	}

	private boolean prepareBeforeShadow(PackSnapshot pack) {
		return pack.pack().properties().prepareBeforeShadow().orElse(false);
	}

	private void prepareCompute(PackSnapshot pack, RuntimeFrame frame, ComputeResources.TargetSize targets,
		PackUniformState uniforms) {
		if (!activeCompute.active()) return;
		String dimension = computeDimension(pack, frame);
		try {
			if (activeCompute.generation() == null || !activeCompute.generation().dimension().equals(dimension)) {
				loadCompute(pack, dimension, targets);
			}
			ComputeDispatchPlanner.FrameSize render = new ComputeDispatchPlanner.FrameSize(
				Math.round(frame.viewport().width() * frame.scale().x()),
				Math.round(frame.viewport().height() * frame.scale().y()));
			activeCompute.beginFrame(new ComputeRuntime.FrameContext(dimension, pack.pack().properties(), targets,
				new ComputeDispatchPlanner.FrameSizeContext(render, render,
					new ComputeDispatchPlanner.FrameSize(targets.shadowWidth(), targets.shadowHeight())),
				pack.pack().properties().allowConcurrentCompute().orElse(false),
				uniforms.write(new PackUniformBindings(List.of()))));
		} catch (RuntimeException exception) {
			LOGGER.warn("Shader pack compute frame unavailable: pack={} dimension={} error={}", packs.name(), dimension,
				exception.getMessage());
		}
	}

	private void loadCompute(PackSnapshot pack, String dimension, ComputeResources.TargetSize targets) {
		try {
			activeCompute.load(pack.version(), pack.pack(), pack.pack().programSet(dimension), targets);
		} catch (RuntimeException exception) {
			LOGGER.warn("Shader pack compute generation unavailable: pack={} dimension={} error={}", packs.name(), dimension,
				exception.getMessage());
		}
	}

	private void resizeCompute(RuntimeFrame frame, PackSnapshot pack) {
		if (activeCompute.active()) activeCompute.resize(targetsForCompute(frame, pack));
	}

	private ComputeResources.TargetSize targetsForCompute(RuntimeFrame frame, PackSnapshot pack) {
		return new ComputeResources.TargetSize(Math.max(1, Math.round(frame.viewport().width() * frame.scale().x())),
			Math.max(1, Math.round(frame.viewport().height() * frame.scale().y())),
			pack.pack().targetPlan().shadowMapResolution(), pack.pack().targetPlan().shadowMapResolution());
	}

	private ComputeResources.TargetSize computeTargets(RuntimeFrame frame, PackSnapshot pack) {
		return targetsForCompute(frame, pack);
	}

	private String computeDimension(PackSnapshot pack, RuntimeFrame frame) {
		return frame.targetPlan().passes().stream().map(value -> value.dimension()).findFirst().orElse(pack.pack().baseFolder());
	}

	private void compilePrograms(PackSnapshot pack, RuntimeFrame frame, Optional<PackProgramCache<?>> value) {
		if (value.isEmpty()) return;
		PackProgramCache<?> programs = value.orElseThrow();
		var generation = programs.generation(computeDimension(pack, frame));
		if (generation.isEmpty() || generation.orElseThrow().identity().version() != activeVersion) return;
		PackProgramCache rawPrograms = programs;
		PackProgramPipelineBuilder builder = new PackProgramPipelineBuilder();
		for (dev.mikuteto.mtlrender.pack.target.RenderTargetPlan.Pass target : frame.targetPlan().passes()) {
			Optional<Program> source = pack.pack().programSet(target.dimension()).program(target.name())
				.filter(Program::isRenderProgram);
			Optional<PassKind> kind = passKind(target.name());
			if (source.isEmpty() || kind.isEmpty()) continue;
			PackProgramRequest request = request(pack, target, source.orElseThrow());
			try {
				PackProgramCache.ProgramResult<?> cached = rawPrograms.result(target.dimension(),
					new GeometryPathSelector.ProgramIdentity(GeometryPathSelector.ProgramKind.RENDER, target.name()),
					() -> PackProgramCache.ProgramResult.success(builder.build(request, pack.pack(), kind.orElseThrow())));
				cached.pipeline().filter(PackProgramPipelineBuilder.Result.class::isInstance)
					.map(PackProgramPipelineBuilder.Result.class::cast).flatMap(PackProgramPipelineBuilder.Result::artifact)
					.ifPresent(this::reportGeometrySelection);
			} catch (RuntimeException exception) {
				LOGGER.warn("Shader pack program setup unavailable: pack={} dimension={} program={} error={}",
					packs.name(), target.dimension(), target.name(), exception.getMessage());
			}
		}
	}

	private PackProgramCache.ProgramResult<?> cachedProgram(PackProgramCache<?> programs, String dimension,
		String name) {
		var generation = programs.generation(dimension);
		if (generation.isEmpty() || generation.orElseThrow().identity().version() != activeVersion) {
			return PackProgramCache.ProgramResult.failure(new IllegalStateException("pack program generation is unavailable"));
		}
		Map results = generation.orElseThrow().results();
		PackProgramCache.ProgramIdentity identity = new PackProgramCache.ProgramIdentity(
			GeometryPathSelector.ProgramKind.RENDER, name);
		PackProgramCache.ProgramResult<?> result = (PackProgramCache.ProgramResult<?>) results.get(identity);
		return result == null ? PackProgramCache.ProgramResult.failure(
			new IllegalStateException("pack program was not prepared")) : result;
	}

	private void reportGeometrySelection(PackProgramArtifact artifact) {
		artifact.geometrySelection().ifPresent(selection -> {
			String reason = selection.diagnostics().stream().map(value -> value.reason().name()).findFirst().orElse("NONE");
			String selected = selection.selectedPath().map(Enum::name).orElse("REJECTED");
			LOGGER.info("Shader pack geometry selection: pack={} dimension={} program={} gsPattern={} selectedPath={} rejectionReason={} outputVertices={} outputPrimitives={} sideEffects={}",
				selection.pack(), selection.dimension(), selection.program().name(), selection.pattern().name(), selected,
				reason, selection.outputBudget().vertices(), selection.outputBudget().primitives(), selection.sideEffects());
		});
	}

	private PackProgramRequest request(PackSnapshot pack,
		dev.mikuteto.mtlrender.pack.target.RenderTargetPlan.Pass target, Program source) {
		return new PackProgramRequest(packs.name(), target.dimension(), target.name(),
			source.sources().entrySet().stream().filter(entry -> entry.getKey() != Program.Stage.COMPUTE)
				.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
					(left, right) -> left, java.util.LinkedHashMap::new)));
	}

	private static Optional<PassKind> passKind(String name) {
		return PassKind.executionOrder().stream().filter(kind -> matches(kind, name)).findFirst();
	}

	private RequestedFramePasses requestedPasses(PackSnapshot pack, RuntimeFrame frame,
		PackTargetSet targets, PackProgramCache<?> programs, PackUniformState uniforms, CommandEncoder encoder) {
		List<PackPassExecutor.RequestedPass> result = new ArrayList<>();
		List<TessellationRequest> tessellation = new ArrayList<>();
		TransientMemory memory = encoder.transientMemory();
		for (PassKind kind : scheduledKinds(setupPending)) {
			for (dev.mikuteto.mtlrender.pack.target.RenderTargetPlan.Pass target : frame.targetPlan().passes()) {
				if (!matches(kind, target.name())) continue;
				ProgramSet set = pack.pack().programSet(target.dimension());
				Optional<Program> source = set.program(target.name()).filter(Program::isRenderProgram);
				if (source.isEmpty()) continue;
				PackProgramRequest request = new PackProgramRequest(packs.name(), target.dimension(), target.name(),
					source.orElseThrow().sources().entrySet().stream().filter(entry -> entry.getKey() != Program.Stage.COMPUTE)
						.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
							(left, right) -> left, java.util.LinkedHashMap::new)));
				if (source.orElseThrow().isTessellatedRenderProgram()) {
					if (activeResources.tessellation().isPresent()) {
						tessellation.add(new TessellationRequest(request, kind, target, frame, targets, uniforms, encoder));
					}
					continue;
				}
				PackProgramCache.ProgramResult<?> cached;
				try {
					cached = cachedProgram(programs, target.dimension(), target.name());
				} catch (Exception exception) {
					cached = PackProgramCache.ProgramResult.failure(exception);
				}
				PackProgramPipelineBuilder.Result built = cached.pipeline().filter(PackProgramPipelineBuilder.Result.class::isInstance)
					.map(PackProgramPipelineBuilder.Result.class::cast).orElse(null);
				PackProgramArtifact artifact = artifact(request, target, kind, built);
				uniforms.forPass(frame, kind);
				PackUniformBindings bindings = new PackUniformBindings(List.<Reflection.UniformMember>of());
				FullscreenPass pass = new FullscreenPass(kind, artifact, frame, targets, bindings);
				if (cached.successful() && built != null && built.successful()) {
					PackPassPlan plan = PackPassPlan.capture(pass, built.descriptor().orElseThrow(), targets);
					GpuBufferSlice slice = bindings.size() == 0 ? new GpuBufferSlice(null, 0, 0)
						: uniforms.write(bindings, memory);
					Optional<PackPassExecutor.GeometryExecution> geometry = built.artifact()
						.filter(value -> value.geometrySelection().isPresent())
						.map(value -> new PackPassExecutor.GeometryExecution(value, Optional.empty()));
					result.add(new PackPassExecutor.RequestedPass(pass, plan, slice, null, geometry));
				} else {
					PackProgramFailure failure = failure(request, cached, built);
					result.add(PackPassExecutor.RequestedPass.failed(pass, failure));
				}
			}
		}
		return new RequestedFramePasses(List.copyOf(result), List.copyOf(tessellation));
	}

	private record RequestedFramePasses(List<PackPassExecutor.RequestedPass> ordinary,
		List<TessellationRequest> tessellation) {
		private RequestedFramePasses {
			ordinary = List.copyOf(ordinary);
			tessellation = List.copyOf(tessellation);
		}

		private boolean empty() {
			return ordinary.isEmpty() && tessellation.isEmpty();
		}
	}

	private static PackProgramArtifact artifact(PackProgramRequest request,
		dev.mikuteto.mtlrender.pack.target.RenderTargetPlan.Pass target, PassKind kind,
		PackProgramPipelineBuilder.Result built) {
		if (built != null && built.artifact().isPresent()) return built.artifact().orElseThrow();
		RuntimeProgram program = new RuntimeProgram(request.dimension(), kind.sourceName(), target.name(), 1L);
		return new PackProgramArtifact(request, program,
			Map.of(Program.Stage.VERTEX, "", Program.Stage.FRAGMENT, ""));
	}

	private static PackProgramFailure failure(PackProgramRequest request,
		PackProgramCache.ProgramResult<?> cached, PackProgramPipelineBuilder.Result built) {
		if (built != null && built.failure().isPresent()) return built.failure().orElseThrow();
		String message = cached.failure().map(Throwable::getMessage).filter(value -> value != null && !value.isBlank())
			.orElse("pack program could not be produced");
		return new PackProgramFailure(request.pack(), request.dimension(), request.program(), Program.Stage.FRAGMENT, message);
	}

	private Optional<PackPassResult> missingFinal(PackSnapshot pack, RuntimeFrame frame) {
		String dimension = pack.pack().baseFolder();
		boolean hasFinal = false;
		for (dev.mikuteto.mtlrender.pack.target.RenderTargetPlan.Pass target : frame.targetPlan().passes()) {
			if (target.name().equals(PassKind.FINAL.sourceName())
				&& pack.pack().programSet(target.dimension()).program(target.name()).filter(Program::isRenderProgram).isPresent()) {
				dimension = target.dimension();
				hasFinal = true;
				break;
			}
		}
		if (hasFinal) return Optional.empty();
		RuntimeProgram program = new RuntimeProgram(dimension, PassKind.FINAL.sourceName(), PassKind.FINAL.sourceName(), 1L);
		PackProgramFailure failure = new PackProgramFailure(packs.name(), dimension, "final", null,
			"missing required render final");
		LOGGER.error("Shader pack final program failed: pack={} dimension={} program={} stage={} error={}",
			failure.pack(), failure.dimension(), failure.program(), failure.stage(), failure.firstError());
		return Optional.of(PackPassResult.finalFailure(PassKind.FINAL, program, failure));
	}

	private static boolean matches(PassKind kind, String name) {
		if (kind == PassKind.FINAL) return name.equals("final");
		return name.equals(kind.sourceName()) || name.startsWith(kind.sourceName());
	}

	private void retire() {
		gbuffer.disablePack();
		if (activeVersion == Long.MIN_VALUE) return;
		activeRuntime.reload(Long.MIN_VALUE, Optional.empty());
		activeCompute.close();
		activeResources.programs().ifPresent(PackProgramCache::close);
		activeResources.targets().ifPresent(PackTargetSet::close);
		activeRuntime = PackRuntime.inactive();
		activeCompute = ComputeRuntime.inactive();
		activeResources = RuntimeResources.empty();
		activeVersion = Long.MIN_VALUE;
		activeLevel = null;
		activeDimension = null;
		shadowFrame = ShadowFrameSnapshot.noPack();
		setupPending = false;
	}

	private void publishGbuffer(PackSnapshot pack, String dimension, GbufferRuntime.Transition transition) {
		gbuffer.publish(transition, Optional.empty());
		if (activeResources.targets().isEmpty() || activeResources.programs().isEmpty()
			|| activeResources.uniforms().isEmpty() || !activeRuntime.active()) return;
		GbufferRuntime.Generation generation = gbufferFactory.create(pack, dimension, activeResources);
		gbuffer.publish(transition, Optional.ofNullable(generation));
	}

	private static GbufferRuntime.Generation defaultGbuffer(PackSnapshot pack, String dimension,
		RuntimeResources resources) {
		GbufferDrawBridge.Provider bridge = Optional::<GbufferDrawBridge.Draw>empty;
		GbufferRuntime.Identity identity = new GbufferRuntime.Identity(pack.pack().baseFolder(), pack.version(), dimension,
			resources.targets().map(PackTargetSet::planVersion).orElse(pack.version()), pack.version(), pack.version(),
			resources.uniforms().map(PackUniformState::frame).orElse(0L));
		return new GbufferRuntime.Generation(identity, new GbufferRuntime.Providers() {
			@Override
			public void install() {
				MetalAccess.installGbufferDrawProvider(bridge);
			}

			@Override
			public void remove() {
				MetalAccess.removeGbufferDrawProvider(bridge);
			}
		});
	}

	private static RuntimeResources createResources(PackSnapshot pack, int width, int height) {
		ShadowTargetPlan shadowPlan = ShadowTargetPlan.build(pack.pack(), pack.version());
		PackTargetSet targets = PackTargetSet.allocate(RenderSystem.getDevice(), Optional.of(pack.pack().targetPlan()),
			Optional.of(shadowPlan), pack.version(), width, height).orElseThrow();
		PackProgramCache<PackProgramPipelineBuilder.Result> programs = new PackProgramCache<>(
			() -> new PackProgramCache.State(pack.version(), true), value -> value.artifact().ifPresent(artifact ->
				MetalAccess.destroyPackPipeline(artifact.runtime().renderPipeline())));
		return new RuntimeResources(Optional.of(targets), Optional.of(programs), Optional.of(PackUniformState.from(pack.pack())),
			Optional.empty());
	}

	private record ShadowTargetSnapshot(long generation) implements ShadowTargetRuntime.TargetSet {
		@Override
		public void close() {
		}
	}

	private void ensureOpen() {
		if (closed) throw new IllegalStateException("pack runtime service is closed");
	}
}
