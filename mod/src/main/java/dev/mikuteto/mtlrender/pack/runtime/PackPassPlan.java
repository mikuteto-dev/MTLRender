package dev.mikuteto.mtlrender.pack.runtime;

import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.backend.PackPipelineDesc;
import dev.mikuteto.mtlrender.pack.dh.DhDepthResources;
import dev.mikuteto.mtlrender.pack.dh.DhProgramKind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PackPassPlan {
	private final PassKind kind;
	private final RuntimeProgram program;
	private final PackPipelineDesc pipeline;
	private final PackUniformBindings uniforms;
	private final java.util.List<FullscreenPass.Attachment> attachments;
	private final Map<String, Sampler> samplers;
	private final FullscreenPass.Dimensions viewport;
	private final FullscreenPass.Scissor scissor;
	private final RuntimeFrame.FlipSnapshot flips;
	private final Set<Integer> mipBeforeSample;

	private PackPassPlan(PassKind kind, RuntimeProgram program, PackPipelineDesc pipeline,
		PackUniformBindings uniforms, java.util.List<FullscreenPass.Attachment> attachments,
		Map<String, Sampler> samplers, FullscreenPass.Dimensions viewport,
		FullscreenPass.Scissor scissor, RuntimeFrame.FlipSnapshot flips, Set<Integer> mipBeforeSample) {
		this.kind = Objects.requireNonNull(kind, "pass kind");
		this.program = Objects.requireNonNull(program, "program");
		this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
		this.uniforms = Objects.requireNonNull(uniforms, "uniforms");
		this.attachments = java.util.List.copyOf(attachments);
		this.samplers = Map.copyOf(samplers);
		this.viewport = Objects.requireNonNull(viewport, "viewport");
		this.scissor = Objects.requireNonNull(scissor, "scissor");
		this.flips = copyFlip(flips);
		this.mipBeforeSample = Set.copyOf(mipBeforeSample);
	}

	public static PackPassPlan capture(FullscreenPass pass, PackPipelineDesc pipeline,
		Map<String, GpuTextureView> samplerViews) {
		Objects.requireNonNull(pass, "pass");
		Objects.requireNonNull(pipeline, "pipeline");
		Map<String, GpuTextureView> resolved = resolve(pass, samplerViews);
		rejectReadWriteAlias(pass);
		Map<String, Sampler> samplers = samplers(pass, pipeline, resolved);
		int physicalWidth = pass.attachments().stream().mapToInt(value -> value.target().width()).min().orElse(pass.dimensions().width());
		int physicalHeight = pass.attachments().stream().mapToInt(value -> value.target().height()).min().orElse(pass.dimensions().height());
		FullscreenPass.Dimensions physical = new FullscreenPass.Dimensions(physicalWidth, physicalHeight);
		return new PackPassPlan(pass.kind(), pass.program(), pipeline, pass.uniformBindings(),
			pass.attachments(), samplers, pass.dimensions(), boundedScissor(pass.scissor(), physical),
			pass.flips(), pass.requestedMipmaps());
	}

	public static PackPassPlan capture(FullscreenPass pass, PackPipelineDesc pipeline, PackTargetSet targets) {
		Objects.requireNonNull(targets, "target set");
		Map<String, GpuTextureView> views = new LinkedHashMap<>(targets.depthViews());
		views.putAll(targets.shadowViews());
		if (isDhProgram(pass.program())) {
			DhDepthResources.from(targets).resources().forEach((name, resource) -> views.put(name, resource.view()));
		}
		for (Map.Entry<Integer, FullscreenPass.TargetView> entry : pass.reads().entrySet()) {
			views.put("colortex" + entry.getKey(), entry.getValue().view());
		}
		for (Map.Entry<Integer, FullscreenPass.TargetView> entry : pass.writes().entrySet()) {
			views.putIfAbsent("colortex" + entry.getKey(), entry.getValue().view());
		}
		return capture(pass, pipeline, views);
	}

	public PassKind kind() { return kind; }
	public RuntimeProgram program() { return program; }
	public PackPipelineDesc pipeline() { return pipeline; }
	public PackUniformBindings uniforms() { return uniforms; }
	public PackUniformBindings uniformBindings() { return uniforms; }
	public java.util.List<FullscreenPass.Attachment> attachments() { return attachments; }
	public Map<String, Sampler> samplers() { return samplers; }
	public FullscreenPass.Dimensions viewport() { return viewport; }
	public FullscreenPass.Scissor scissor() { return scissor; }
	public RuntimeFrame.FlipSnapshot flips() { return flips; }
	public Set<Integer> mipBeforeSample() { return mipBeforeSample; }
	public Set<Integer> mipmappedBuffers() { return mipBeforeSample; }

	private static Map<String, GpuTextureView> resolve(FullscreenPass pass, Map<String, GpuTextureView> source) {
		Objects.requireNonNull(source, "sampler views");
		Map<String, GpuTextureView> result = new LinkedHashMap<>();
		for (String name : pass.targetPlan().samplers()) {
			String canonical = canonical(name);
			GpuTextureView view = source.get(canonical);
			if (view == null && isDhSampler(name) && isDhProgram(pass.program())) continue;
			if (view == null && canonical.startsWith("colortex")) {
				try {
					int index = Integer.parseInt(canonical.substring(8));
					FullscreenPass.TargetView target = pass.reads().get(index);
					if (target != null) view = target.view();
				} catch (NumberFormatException exception) {
					throw new IllegalArgumentException("invalid sampler: " + name);
				}
			}
			if (view == null) throw new IllegalArgumentException("missing sampler view: " + name);
			result.put(name, view);
		}
		return result;
	}

	private static Map<String, Sampler> samplers(FullscreenPass pass, PackPipelineDesc pipeline,
		Map<String, GpuTextureView> views) {
		Map<String, PackPipelineDesc.Binding> bindings = new LinkedHashMap<>();
		for (PackPipelineDesc.Binding binding : pipeline.bindings()) {
			if (binding.kind() == PackPipelineDesc.Kind.TEXTURE) bindings.put(binding.name(), binding);
		}
		Map<String, Sampler> result = new LinkedHashMap<>();
		for (String name : pass.targetPlan().samplers()) {
			GpuTextureView view = views.get(name);
			if (view == null && isDhSampler(name) && isDhProgram(pass.program())) continue;
			PackPipelineDesc.Binding binding = bindings.get(name);
			if (binding == null) binding = bindings.get(canonical(name));
			if (binding == null) throw new IllegalArgumentException("sampler is absent from pipeline: " + name);
			result.put(name, new Sampler(name, view, binding.slot(), binding.stages()));
		}
		return result;
	}

	private static void rejectReadWriteAlias(FullscreenPass pass) {
		for (FullscreenPass.TargetView read : pass.reads().values()) {
			for (FullscreenPass.TargetView write : pass.writes().values()) {
				if (read.view() == write.view()) throw new IllegalArgumentException("pass samples its color attachment");
			}
		}
	}

	private static boolean isDhProgram(RuntimeProgram program) {
		for (DhProgramKind kind : DhProgramKind.values()) {
			if (kind.sourceName().equals(program.sourceName())) return true;
		}
		return false;
	}

	private static boolean isDhSampler(String name) {
		return name.equals("dhDepthTex") || name.equals("dhDepthTex0") || name.equals("dhDepthTex1");
	}

	private static String canonical(String name) {
		return switch (name) {
			case "gcolor" -> "colortex0";
			case "gdepth" -> "colortex1";
			case "gnormal" -> "colortex2";
			case "composite" -> "colortex3";
			case "gaux1" -> "colortex4";
			case "gaux2" -> "colortex5";
			case "gaux3" -> "colortex6";
			case "gaux4" -> "colortex7";
			case "shadowColor0", "shadowColor1", "shadowColor2", "shadowColor3", "shadowColor4", "shadowColor5", "shadowColor6", "shadowColor7" -> "shadowcolor" + name.substring(10);
			case "shadowtex" -> "shadowtex0";
			default -> name;
		};
	}

	private static FullscreenPass.Scissor boundedScissor(FullscreenPass.Scissor scissor, FullscreenPass.Dimensions size) {
		if (!scissor.enabled()) return FullscreenPass.Scissor.disabled();
		int x = Math.max(0, scissor.x());
		int y = Math.max(0, scissor.y());
		int right = Math.min(size.width(), Math.addExact(scissor.x(), scissor.width()));
		int bottom = Math.min(size.height(), Math.addExact(scissor.y(), scissor.height()));
		if (right <= x || bottom <= y) throw new IllegalArgumentException("scissor is outside the pass viewport");
		return new FullscreenPass.Scissor(true, x, y, right - x, bottom - y);
	}

	private static RuntimeFrame.FlipSnapshot copyFlip(RuntimeFrame.FlipSnapshot flip) {
		Objects.requireNonNull(flip, "flip snapshot");
		return new RuntimeFrame.FlipSnapshot(flip.dimension(), flip.passName(), flip.before(), flip.after());
	}

	public record Sampler(String name, GpuTextureView view, int textureSlot, int stages) {
		public Sampler {
			Objects.requireNonNull(name, "sampler name");
			Objects.requireNonNull(view, "sampler view");
			if (textureSlot < 0 || stages <= 0) throw new IllegalArgumentException("invalid sampler binding");
		}
	}
}
