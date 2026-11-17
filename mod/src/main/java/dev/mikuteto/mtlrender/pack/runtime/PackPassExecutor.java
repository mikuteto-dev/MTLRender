package dev.mikuteto.mtlrender.pack.runtime;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.dh.DhProgramKind;
import dev.mikuteto.mtlrender.pack.dh.DhShadowFrame;
import dev.mikuteto.mtlrender.pack.dh.DhUniformState;
import dev.mikuteto.mtlrender.pack.shadow.ShadowTargetPlan;
import dev.mikuteto.mtlrender.backend.PackPipelineDesc;
import org.joml.Vector4f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PackPassExecutor {
	private static final Logger LOGGER = LogUtils.getLogger();
	private final PackPassFailurePolicy failurePolicy;
	private final CommandEncoder suppliedEncoder;
	private final SequenceRecorder suppliedRecorder;
	private final FailureReporter failureReporter;
	private final GeometryScheduler geometryScheduler;

	public PackPassExecutor() {
		this(new PackPassFailurePolicy(), null, null, PackPassExecutor::logFailure, null);
	}

	PackPassExecutor(CommandEncoder encoder) {
		this(new PackPassFailurePolicy(), Objects.requireNonNull(encoder, "encoder"), null, PackPassExecutor::logFailure, null);
	}

	public PackPassExecutor(PackPassFailurePolicy failurePolicy) {
		this(failurePolicy, null, null, PackPassExecutor::logFailure, null);
	}

	PackPassExecutor(SequenceRecorder recorder) {
		this(new PackPassFailurePolicy(), null, Objects.requireNonNull(recorder, "sequence recorder"), PackPassExecutor::logFailure, null);
	}

	PackPassExecutor(PackPassFailurePolicy failurePolicy, SequenceRecorder recorder, FailureReporter failureReporter) {
		this(failurePolicy, null, Objects.requireNonNull(recorder, "sequence recorder"), failureReporter, null);
	}

	PackPassExecutor(PackPassFailurePolicy failurePolicy, SequenceRecorder recorder, FailureReporter failureReporter,
		GeometryScheduler geometryScheduler) {
		this(failurePolicy, null, Objects.requireNonNull(recorder, "sequence recorder"), failureReporter, geometryScheduler);
	}

	private PackPassExecutor(PackPassFailurePolicy failurePolicy, CommandEncoder encoder, SequenceRecorder recorder,
		FailureReporter failureReporter, GeometryScheduler geometryScheduler) {
		this.failurePolicy = Objects.requireNonNull(failurePolicy, "failure policy");
		suppliedEncoder = encoder;
		suppliedRecorder = recorder;
		this.failureReporter = Objects.requireNonNull(failureReporter, "failure reporter");
		this.geometryScheduler = geometryScheduler;
	}

	public interface ShadowStage {
		ShadowInput capture(PackRuntimeService.PackSnapshot pack, long generation, PackTargetSet targets);

		void render(DhShadowFrame frame, PackTargetSet targets, CommandEncoder encoder);

		void finalizeDepth(DhShadowFrame frame, PackTargetSet targets, CommandEncoder encoder);
	}

	public record ShadowInput(DhUniformState uniforms, boolean shadowTerrain, boolean shadowTranslucent) {
		public ShadowInput {
			Objects.requireNonNull(uniforms, "uniforms");
		}
	}

	public boolean executeShadow(DhShadowFrame frame, PackTargetSet targets, ShadowStage stage) {
		Objects.requireNonNull(frame, "shadow frame");
		Objects.requireNonNull(targets, "targets");
		Objects.requireNonNull(stage, "shadow stage");
		if (!frame.enabled()) return false;
		if (frame.targetGeneration() != targets.planVersion() || frame.depthGeneration() != targets.planVersion()) {
			throw new IllegalArgumentException("DH shadow resources changed during frame");
		}
		CommandEncoder encoder = suppliedEncoder != null ? suppliedEncoder : RenderSystem.getDevice().createCommandEncoder();
		stage.render(frame, targets, encoder);
		stage.finalizeDepth(frame, targets, encoder);
		return true;
	}

	public void copyShadowTargets(ShadowTargetPlan plan, PackTargetSet targets) {
		Objects.requireNonNull(plan, "shadow target plan");
		Objects.requireNonNull(targets, "target set");
		if (plan.generation() != targets.planVersion()) throw new IllegalArgumentException("shadow target generation mismatch");
		CommandEncoder encoder = suppliedEncoder != null ? suppliedEncoder : RenderSystem.getDevice().createCommandEncoder();
		for (ShadowTargetPlan.CopyOperation operation : plan.operations()) {
			GpuTextureView sourceView = targets.optionalShadow(operation.source()).orElse(null);
			GpuTextureView destinationView = targets.optionalShadow(operation.destination()).orElse(null);
			if (sourceView == null || destinationView == null) continue;
			GpuTexture source = sourceView.texture();
			GpuTexture destination = destinationView.texture();
			if (source.getWidth(operation.mipLevel()) < operation.sourceX() + operation.width()
				|| source.getHeight(operation.mipLevel()) < operation.sourceY() + operation.height()
				|| destination.getWidth(operation.mipLevel()) < operation.destX() + operation.width()
				|| destination.getHeight(operation.mipLevel()) < operation.destY() + operation.height()) {
				throw new IllegalArgumentException("shadow copy exceeds target extent");
			}
			encoder.copyTextureToTexture(source, destination, operation.mipLevel(), operation.destX(), operation.destY(),
				operation.sourceX(), operation.sourceY(), operation.width(), operation.height());
		}
	}

	public PackPassResult execute(FullscreenPass preparedPass, PackProgramFailure failure) {
		Objects.requireNonNull(preparedPass, "prepared pass");
		PackPassResult result = failurePolicy.classifyFailure(preparedPass.kind(), preparedPass.program(),
			Objects.requireNonNull(failure, "failure"));
		report(result);
		return result;
	}

	public PackPassResult execute(FullscreenPass preparedPass, PackPassPlan plan, GpuBufferSlice uniformSlice) {
		return execute(preparedPass, plan, uniformSlice, Optional.empty());
	}

	PackPassResult execute(FullscreenPass preparedPass, PackPassPlan plan, GpuBufferSlice uniformSlice,
		Optional<GeometryExecution> geometry) {
		Objects.requireNonNull(preparedPass, "prepared pass");
		Objects.requireNonNull(plan, "pass plan");
		Objects.requireNonNull(uniformSlice, "uniform slice");
		validateMatch(preparedPass, plan);
		SequenceRecorder recorder = suppliedRecorder;
		if (recorder == null) {
			CommandEncoder encoder = suppliedEncoder != null ? suppliedEncoder : RenderSystem.getDevice().createCommandEncoder();
			recorder = backendRecorder(encoder);
		}
		try {
			executeGeometry(recorder, preparedPass, plan, uniformSlice, geometry);
		} catch (RuntimeException exception) {
			PackPassResult result = classifyGeometryFailure(preparedPass, geometry, exception);
			report(result);
			return result;
		}
		return failurePolicy.classifySuccess(plan.kind(), preparedPass);
	}

	public List<PackPassResult> executeSequence(List<RequestedPass> requested) {
		List<RequestedPass> sequence = List.copyOf(Objects.requireNonNull(requested, "requested passes"));
		if (sequence.isEmpty()) return List.of();
		for (RequestedPass pass : sequence) Objects.requireNonNull(pass, "requested pass");

		SequenceRecorder recorder = suppliedRecorder;
		if (recorder == null) {
			CommandEncoder encoder = suppliedEncoder != null ? suppliedEncoder : RenderSystem.getDevice().createCommandEncoder();
			recorder = backendRecorder(encoder);
		}
		List<PendingMipmap> pending = new ArrayList<>();
		List<PackPassResult> results = new ArrayList<>(sequence.size());
		for (RequestedPass requestedPass : sequence) {
			if (requestedPass.failed()) {
				PackPassResult result = execute(requestedPass.preparedPass(), requestedPass.failure());
				results.add(result);
				continue;
			}
			FullscreenPass preparedPass = requestedPass.preparedPass();
			PackPassPlan plan = requestedPass.plan();
			validateMatch(preparedPass, plan);
			generateBeforeSample(recorder, pending, plan);
			try {
				executeGeometry(recorder, preparedPass, plan, requestedPass.uniformSlice(), requestedPass.geometry());
				rememberProducers(pending, plan);
				results.add(failurePolicy.classifySuccess(plan.kind(), preparedPass));
			} catch (RuntimeException exception) {
				PackPassResult result = classifyGeometryFailure(preparedPass, requestedPass.geometry(), exception);
				report(result);
				results.add(result);
			}
		}
		return List.copyOf(results);
	}

	private void report(PackPassResult result) {
		if (!result.succeeded()) failureReporter.report(result);
	}

	private static void logFailure(PackPassResult result) {
		PackProgramFailure failure = result.failure().orElseThrow();
		if (result.surfacedFailure()) {
			LOGGER.error("Shader pack final program failed: pack={} dimension={} program={} stage={} error={}",
				failure.pack(), failure.dimension(), failure.program(), failure.stage(), failure.firstError());
		} else {
			LOGGER.warn("Skipping optional shader pack program: pack={} dimension={} program={} stage={} error={}",
				failure.pack(), failure.dimension(), failure.program(), failure.stage(), failure.firstError());
		}
	}

	private static void validateMatch(FullscreenPass preparedPass, PackPassPlan plan) {
		if (preparedPass.kind() != plan.kind() || !preparedPass.program().equals(plan.program())) {
			throw new IllegalArgumentException("prepared pass and plan do not match");
		}
	}

	private static void generateBeforeSample(SequenceRecorder recorder, List<PendingMipmap> pending, PackPassPlan plan) {
		for (int index = 0; index < pending.size();) {
			PendingMipmap mipmap = pending.get(index);
			if (!samples(plan, mipmap.target())) {
				index++;
				continue;
			}
			recorder.generateMipmaps(mipmap.target(), 0, mipmap.mipLevels());
			pending.remove(index);
		}
	}

	private static boolean samples(PackPassPlan plan, GpuTextureView target) {
		for (PackPassPlan.Sampler sampler : plan.samplers().values()) {
			if (sampler.view() == target) return true;
		}
		return false;
	}

	private static void rememberProducers(List<PendingMipmap> pending, PackPassPlan plan) {
		for (FullscreenPass.Attachment attachment : plan.attachments()) {
			if (!plan.mipBeforeSample().contains(attachment.index())) continue;
			FullscreenPass.TargetView target = attachment.target();
			if (target.mipLevels() <= 1) continue;
			pending.removeIf(value -> value.target() == target.view());
			pending.add(new PendingMipmap(target.view(), target.mipLevels()));
		}
	}

	private PackPassResult classifyGeometryFailure(FullscreenPass preparedPass, Optional<GeometryExecution> geometry,
		RuntimeException exception) {
		String message = exception.getMessage();
		if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
		String dimension = preparedPass.program().dimension();
		String program = preparedPass.program().variantName();
		String pack = geometry.map(value -> value.artifact().request().pack()).orElse("");
		Program.Stage stage = geometry.map(GeometryExecution::path).filter(path -> path == GeometryPathSelector.Path.COMPUTE)
			.isPresent() ? Program.Stage.COMPUTE : Program.Stage.GEOMETRY;
		return failurePolicy.classifyFailure(preparedPass.kind(), preparedPass.program(),
			new PackProgramFailure(pack, dimension, program, stage, message));
	}

	private void executeGeometry(SequenceRecorder recorder, FullscreenPass preparedPass, PackPassPlan plan,
		GpuBufferSlice uniformSlice, Optional<GeometryExecution> geometry) {
		Objects.requireNonNull(geometry, "geometry execution");
		if (geometry.isEmpty()) {
			recorder.render(preparedPass, plan, uniformSlice);
			return;
		}
		GeometryExecution execution = geometry.orElseThrow();
		if (!execution.artifact().runtimeProgram().equals(preparedPass.program())) {
			throw new IllegalArgumentException("geometry artifact does not match prepared pass");
		}
		Optional<GeometryPathSelector.Path> selected = execution.selectedPath();
		if (selected.isEmpty() && execution.artifact().geometrySelection().isPresent()) return;
		GeometryPathSelector.Path path = selected.orElse(GeometryPathSelector.Path.PASS_THROUGH);
		if (path == GeometryPathSelector.Path.PASS_THROUGH || path == GeometryPathSelector.Path.LAYER_INSTANCED) {
			recorder.render(preparedPass, plan, uniformSlice);
		} else if (path == GeometryPathSelector.Path.MESH) {
			PackProgramArtifact.MeshMetadata mesh = execution.artifact().mesh().orElseThrow(
				() -> new IllegalArgumentException("mesh path has no mesh artifact"));
			recorder.mesh(preparedPass, plan, uniformSlice, mesh);
		} else {
			GeometryComputeSeam.Execution compute = execution.compute().orElseThrow(
				() -> new IllegalArgumentException("compute path has no compute execution"));
			if (geometryScheduler == null) throw new IllegalStateException("geometry compute scheduler is unavailable");
			geometryScheduler.compute(compute);
			recorder.compute(preparedPass, plan, uniformSlice, compute);
		}
	}

	private static SequenceRecorder backendRecorder(CommandEncoder encoder) {
		return new SequenceRecorder() {
			@Override
			public void render(FullscreenPass preparedPass, PackPassPlan plan, GpuBufferSlice uniformSlice) {
				PackPassExecutor.render(encoder, plan, uniformSlice);
			}

			@Override
			public void mesh(FullscreenPass preparedPass, PackPassPlan plan, GpuBufferSlice uniformSlice,
				PackProgramArtifact.MeshMetadata mesh) {
				if (!MetalAccess.dispatchMesh(MetalAccess.enableMeshFeature(), mesh.dispatch())) {
					throw new IllegalStateException("mesh dispatch was rejected");
				}
			}

			@Override
			public void generateMipmaps(GpuTextureView target, int firstMip, int mipCount) {
				MetalAccess.generatePackMipmaps(target, firstMip, mipCount);
			}
		};
	}

	private static void render(CommandEncoder encoder, PackPassPlan plan, GpuBufferSlice uniformSlice) {
		RenderPassDescriptor descriptor = descriptor(plan);
		try (RenderPass pass = encoder.createRenderPass(descriptor)) {
			MetalAccess.usePackPipeline(plan.program().renderPipeline());
			bindUniforms(pass, plan, uniformSlice);
			bindSamplers(pass, plan);
			if (plan.scissor().enabled()) {
				pass.enableScissor(plan.scissor().x(), plan.scissor().y(), plan.scissor().width(), plan.scissor().height());
			} else {
				pass.disableScissor();
			}
			PackPipelineDesc.LayerInstancing layers = plan.pipeline().layerInstancing().orElse(null);
			pass.draw(3, layers == null ? 1 : layers.instanceCount(), 0, layers == null ? 0 : layers.baseInstance());
		}
	}

	private static RenderPassDescriptor descriptor(PackPassPlan plan) {
		RenderPassDescriptor result = RenderPassDescriptor.create(() -> "MTLRender pack " + plan.program().variantName());
		Map<Integer, FullscreenPass.Attachment> attachments = new java.util.HashMap<>();
		for (FullscreenPass.Attachment attachment : plan.attachments()) {
			attachments.put(attachment.index(), attachment);
		}
		for (int location = 0; location < plan.pipeline().colorAttachments().size(); location++) {
			FullscreenPass.Attachment attachment = attachments.get(location);
			if (attachment == null) {
				result.withUnusedColorAttachment();
				continue;
			}
			if (attachment.load() == FullscreenPass.LoadAction.CLEAR) {
				Vector4f clear = attachment.clearColor().map(value -> new Vector4f(value.red(), value.green(), value.blue(), value.alpha())).orElseGet(Vector4f::new);
				result.withColorAttachment(attachment.target().view(), Optional.of(clear));
			} else {
				result.withColorAttachment(attachment.target().view());
			}
		}
		result.withRenderArea(new RenderPass.RenderArea(0, 0, plan.viewport().width(), plan.viewport().height()));
		return result;
	}

	private static void bindUniforms(RenderPass pass, PackPassPlan plan, GpuBufferSlice uniformSlice) {
		for (PackPipelineDesc.Binding binding : plan.pipeline().bindings()) {
			if (binding.kind() == PackPipelineDesc.Kind.BUFFER) {
				pass.setUniform(binding.name(), uniformSlice);
			}
		}
	}

	private static void bindSamplers(RenderPass pass, PackPassPlan plan) {
		boolean dhProgram = isDhProgram(plan.program().sourceName());
		for (String name : DhSamplerNames.ALL) pass.bindTexture(name, null, null);
		GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		GpuSampler depth = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		for (PackPassPlan.Sampler binding : plan.samplers().values()) {
			GpuSampler sampler = dhProgram && DhSamplerNames.isDepth(binding.name()) ? depth : linear;
			pass.bindTexture(binding.name(), binding.view(), sampler);
		}
	}

	private static boolean isDhProgram(String sourceName) {
		for (DhProgramKind kind : DhProgramKind.values()) {
			if (kind.sourceName().equals(sourceName)) return true;
		}
		return false;
	}

	private static final class DhSamplerNames {
		private static final List<String> ALL = List.of("dhDepthTex", "dhDepthTex0", "dhDepthTex1");

		private static boolean isDepth(String name) {
			return ALL.contains(name);
		}
	}

	public record RequestedPass(FullscreenPass preparedPass, PackPassPlan plan, GpuBufferSlice uniformSlice,
		PackProgramFailure failure, Optional<GeometryExecution> geometry) {
		public RequestedPass {
			Objects.requireNonNull(preparedPass, "prepared pass");
			geometry = Objects.requireNonNull(geometry, "geometry");
			if (failure == null) {
				Objects.requireNonNull(plan, "pass plan");
				Objects.requireNonNull(uniformSlice, "uniform slice");
			} else if (plan != null || uniformSlice != null) {
				throw new IllegalArgumentException("failed pass cannot have an execution plan");
			}
		}

		public static RequestedPass success(FullscreenPass preparedPass, PackPassPlan plan, GpuBufferSlice uniformSlice) {
			return new RequestedPass(preparedPass, plan, uniformSlice, null, Optional.empty());
		}

		static RequestedPass success(FullscreenPass preparedPass, PackPassPlan plan, GpuBufferSlice uniformSlice,
			GeometryExecution geometry) {
			return new RequestedPass(preparedPass, plan, uniformSlice, null, Optional.of(Objects.requireNonNull(geometry, "geometry")));
		}

		public static RequestedPass failed(FullscreenPass preparedPass, PackProgramFailure failure) {
			return new RequestedPass(preparedPass, null, null, failure, Optional.empty());
		}

		public boolean failed() {
			return failure != null;
		}
	}

	interface SequenceRecorder {
		void render(FullscreenPass preparedPass, PackPassPlan plan, GpuBufferSlice uniformSlice);
		default void mesh(FullscreenPass preparedPass, PackPassPlan plan, GpuBufferSlice uniformSlice,
			PackProgramArtifact.MeshMetadata mesh) { }
		default void compute(FullscreenPass preparedPass, PackPassPlan plan, GpuBufferSlice uniformSlice,
			GeometryComputeSeam.Execution execution) { }
		void generateMipmaps(GpuTextureView target, int firstMip, int mipCount);
	}

	@FunctionalInterface
	interface GeometryScheduler {
		void compute(GeometryComputeSeam.Execution execution);
	}

	public record GeometryExecution(PackProgramArtifact artifact, Optional<GeometryComputeSeam.Execution> compute) {
		public GeometryExecution {
			artifact = Objects.requireNonNull(artifact, "geometry artifact");
			compute = Objects.requireNonNull(compute, "geometry compute execution");
		}

		Optional<GeometryPathSelector.Path> selectedPath() {
			return artifact.geometrySelection().flatMap(GeometryPathSelector.Result::selectedPath);
		}

		GeometryPathSelector.Path path() {
			return selectedPath().orElse(GeometryPathSelector.Path.PASS_THROUGH);
		}
	}

	@FunctionalInterface
	interface FailureReporter {
		void report(PackPassResult result);
	}

	private record PendingMipmap(GpuTextureView target, int mipLevels) {
	}
}
