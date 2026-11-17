package dev.mikuteto.mtlrender.pack.runtime;

import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.pack.target.RenderTargetPlan;
import dev.mikuteto.mtlrender.pack.uniform.UniformWriter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class FullscreenPass {
	private final PassKind kind;
	private final RuntimeProgram program;
	private final PlanSnapshot targetPlan;
	private final RuntimeFrame.FlipSnapshot flips;
	private final Map<Integer, TargetView> reads;
	private final Map<Integer, TargetView> writes;
	private final List<Attachment> attachments;
	private final Set<Integer> mipmaps;
	private final Dimensions dimensions;
	private final Scissor scissor;
	private final PackUniformBindings uniformBindings;
	private final List<UniformWriter.Member> uniformMembers;

	public FullscreenPass(PassKind kind, PackProgramArtifact artifact, RuntimeFrame frame,
		PackTargetSet targets, PackUniformBindings uniformBindings) {
		this(kind, artifact, frame, targets, uniformBindings, Scissor.disabled());
	}

	public FullscreenPass(PassKind kind, PackProgramArtifact artifact, RuntimeFrame frame,
		PackTargetSet targets, PackUniformBindings uniformBindings, Scissor scissor) {
		this(kind, artifact, frame, pass(frame.targetPlan(), artifact), flip(frame, artifact),
			TargetViews.from(targets, indices(pass(frame.targetPlan(), artifact))), uniformBindings,
			defaultActions(pass(frame.targetPlan(), artifact), frame.targetPlan()), scissor);
	}

	public FullscreenPass(PassKind kind, PackProgramArtifact artifact, RuntimeFrame frame,
		RenderTargetPlan.Pass pass, RuntimeFrame.FlipSnapshot flips, TargetViews views,
		PackUniformBindings uniformBindings, Scissor scissor) {
		this(kind, artifact, frame, pass, flips, views, uniformBindings, defaultActions(pass, frame.targetPlan()), scissor);
	}

	public FullscreenPass(PassKind kind, PackProgramArtifact artifact, RuntimeFrame frame,
		RenderTargetPlan.Pass pass, RuntimeFrame.FlipSnapshot flips, TargetViews views,
		PackUniformBindings uniformBindings, Map<Integer, AttachmentAction> actions, Scissor scissor) {
		this.kind = Objects.requireNonNull(kind, "pass kind");
		Objects.requireNonNull(artifact, "program artifact");
		Objects.requireNonNull(frame, "frame");
		Objects.requireNonNull(pass, "target pass");
		this.flips = copyFlip(flips);
		this.targetPlan = snapshot(pass);
		this.program = validateProgram(kind, artifact, pass, this.flips, frame.targetPlan());
		this.uniformBindings = Objects.requireNonNull(uniformBindings, "uniform bindings");
		this.uniformMembers = List.copyOf(uniformBindings.members());
		TargetViews snapshot = Objects.requireNonNull(views, "target views");
		Map<Integer, GpuTextureView> sourceReads = new LinkedHashMap<>();
		Map<Integer, GpuTextureView> sourceWrites = new LinkedHashMap<>();
		for (int index : sorted(pass.reads())) {
			sourceReads.put(index, snapshot.view(index, pass.readsFromAlt(index)));
		}
		for (int index : sorted(pass.writes())) {
			sourceWrites.put(index, snapshot.view(index, pass.writesToAlt(index)));
		}
		this.reads = targetViews(sourceReads, sideMap(pass.reads(), pass.readsFromAlt()));
		this.writes = targetViews(sourceWrites, sideMap(pass.writes(), pass.writesToAlt()));
		this.attachments = attachments(pass, actions, this.writes);
		this.mipmaps = Set.copyOf(pass.mipmappedBuffers());
		this.dimensions = dimensions(frame, pass.scale());
		this.scissor = Objects.requireNonNull(scissor, "scissor");
	}

	public PassKind kind() {
		return kind;
	}

	public PassKind passKind() {
		return kind;
	}

	public RuntimeProgram program() {
		return program;
	}

	public RuntimeProgram runtimeProgram() {
		return program;
	}

	public long pipelineIdentity() {
		return program.renderPipeline();
	}

	public long pipelineHandle() {
		return program.renderPipeline();
	}

	public PlanSnapshot targetPlan() {
		return targetPlan;
	}

	public RuntimeFrame.FlipSnapshot flips() {
		return flips;
	}

	public Set<Integer> logicalReads() {
		return targetPlan.reads();
	}

	public Set<Integer> logicalWrites() {
		return targetPlan.writes();
	}

	public Map<Integer, TargetView> reads() {
		return reads;
	}

	public Map<Integer, TargetView> writes() {
		return writes;
	}

	public List<Attachment> attachments() {
		return attachments;
	}

	public Set<Integer> requestedMipmaps() {
		return mipmaps;
	}

	public Dimensions dimensions() {
		return dimensions;
	}

	public Scissor scissor() {
		return scissor;
	}

	public PackUniformBindings uniformBindings() {
		return uniformBindings;
	}

	public List<UniformWriter.Member> uniformMembers() {
		return uniformMembers;
	}

	private static RenderTargetPlan.Pass pass(RenderTargetPlan plan, PackProgramArtifact artifact) {
		Objects.requireNonNull(plan, "target plan");
		Objects.requireNonNull(artifact, "program artifact");
		for (RenderTargetPlan.Pass candidate : plan.passes()) {
			if (candidate.dimension().equals(artifact.request().dimension())
				&& candidate.name().equals(artifact.request().program())) {
				return candidate;
			}
		}
		throw new IllegalArgumentException("target plan has no render program: " + artifact.request().program());
	}

	private static RuntimeFrame.FlipSnapshot flip(RuntimeFrame frame, PackProgramArtifact artifact) {
		String dimension = artifact.request().dimension();
		String name = artifact.request().program();
		return frame.flips().stream()
			.filter(value -> value.dimension().equals(dimension) && value.passName().equals(name))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("frame has no flip snapshot: " + name));
	}

	private static RuntimeProgram validateProgram(PassKind kind, PackProgramArtifact artifact,
		RenderTargetPlan.Pass pass, RuntimeFrame.FlipSnapshot flips, RenderTargetPlan plan) {
		RuntimeProgram program = artifact.runtimeProgram();
		if (!program.dimension().equals(artifact.request().dimension())
			|| !program.variantName().equals(artifact.request().program())
			|| !pass.dimension().equals(program.dimension())
			|| !pass.name().equals(program.variantName())) {
			throw new IllegalArgumentException("runtime program does not match its render pass");
		}
		if (!programName(kind, program.sourceName()) || !programName(kind, program.variantName())) {
			throw new IllegalArgumentException("program does not belong to pass kind: " + program.variantName());
		}
		if (!flips.before().equals(pass.flippedBefore()) || !flips.after().equals(pass.flippedAfter())) {
			throw new IllegalArgumentException("flip snapshot does not match target plan");
		}
		if (plan.passes().stream().noneMatch(value -> value == pass ||
			(value.dimension().equals(pass.dimension()) && value.name().equals(pass.name())))) {
			throw new IllegalArgumentException("target pass is not part of the target plan");
		}
		return program;
	}

	private static boolean programName(PassKind kind, String name) {
		String prefix = kind.sourceName();
		if (name.equals(prefix)) {
			return true;
		}
		if (!name.startsWith(prefix)) {
			return false;
		}
		String suffix = name.substring(prefix.length());
		if (suffix.startsWith("_")) {
			return suffix.length() == 2 && suffix.charAt(1) >= 'a' && suffix.charAt(1) <= 'z';
		}
		int underscore = suffix.indexOf('_');
		String number = underscore < 0 ? suffix : suffix.substring(0, underscore);
		if (number.isEmpty() || !number.chars().allMatch(Character::isDigit)) {
			return false;
		}
		return underscore < 0 || (suffix.length() == underscore + 2
			&& suffix.charAt(underscore + 1) >= 'a' && suffix.charAt(underscore + 1) <= 'z');
	}

	private static RuntimeFrame.FlipSnapshot copyFlip(RuntimeFrame.FlipSnapshot source) {
		Objects.requireNonNull(source, "flip snapshot");
		return new RuntimeFrame.FlipSnapshot(source.dimension(), source.passName(), source.before(), source.after());
	}

	private static PlanSnapshot snapshot(RenderTargetPlan.Pass pass) {
		return new PlanSnapshot(pass.dimension(), pass.name(), pass.reads(), pass.writes(), pass.samplers(),
			pass.readsFromAlt(), pass.writesToAlt(), pass.mipmappedBuffers(), pass.scale(),
			pass.flippedBefore(), pass.flippedAfter());
	}

	private static Set<Integer> indices(RenderTargetPlan.Pass pass) {
		Set<Integer> result = new LinkedHashSet<>(pass.reads());
		result.addAll(pass.writes());
		return Set.copyOf(result);
	}

	private static Map<Integer, Boolean> sideMap(Set<Integer> indices, Set<Integer> alternate) {
		Map<Integer, Boolean> result = new LinkedHashMap<>();
		for (int index : indices) {
			result.put(index, alternate.contains(index));
		}
		return Map.copyOf(result);
	}

	private static Map<Integer, TargetView> targetViews(Map<Integer, GpuTextureView> source,
		Map<Integer, Boolean> alternate) {
		Map<Integer, TargetView> result = new LinkedHashMap<>();
		for (Map.Entry<Integer, GpuTextureView> entry : source.entrySet()) {
			GpuTextureView view = Objects.requireNonNull(entry.getValue(), "target view");
			result.put(entry.getKey(), new TargetView(entry.getKey(), Boolean.TRUE.equals(alternate.get(entry.getKey())), view,
				view.getWidth(0), view.getHeight(0), view.baseMipLevel(), view.mipLevels()));
		}
		return Map.copyOf(result);
	}

	private static List<Attachment> attachments(RenderTargetPlan.Pass pass, Map<Integer, AttachmentAction> actions,
		Map<Integer, TargetView> writes) {
		Objects.requireNonNull(actions, "attachment actions");
		List<Attachment> result = new ArrayList<>();
		for (int index : sorted(pass.writes())) {
			AttachmentAction action = Objects.requireNonNull(actions.get(index), "missing attachment action");
			result.add(new Attachment(index, writes.get(index), action.load(), action.clearColor(), action.store()));
		}
		if (actions.keySet().stream().anyMatch(index -> !pass.writes().contains(index))) {
			throw new IllegalArgumentException("attachment action is not a pass write");
		}
		return List.copyOf(result);
	}

	private static Map<Integer, AttachmentAction> defaultActions(RenderTargetPlan.Pass pass, RenderTargetPlan plan) {
		Objects.requireNonNull(plan, "target plan");
		Map<Integer, AttachmentAction> result = new LinkedHashMap<>();
		for (int index : pass.writes()) {
			RenderTargetPlan.ColorTarget target = plan.color(index);
			if (pass.reads().contains(index)) {
				result.put(index, new AttachmentAction(LoadAction.LOAD, Optional.empty(), StoreAction.STORE));
			} else if (target.clear()) {
				result.put(index, new AttachmentAction(LoadAction.CLEAR, target.clearColor(), StoreAction.STORE));
			} else {
				result.put(index, new AttachmentAction(LoadAction.DONT_CARE, Optional.empty(), StoreAction.STORE));
			}
		}
		return Map.copyOf(result);
	}

	private static Dimensions dimensions(RuntimeFrame frame, RenderTargetPlan.PassScale scale) {
		int width = scaled(frame.width(), frame.scale().x(), scale.scale());
		int height = scaled(frame.height(), frame.scale().y(), scale.scale());
		return new Dimensions(width, height);
	}

	private static int scaled(int value, float frameScale, float passScale) {
		float scaled = value * frameScale * passScale;
		if (!Float.isFinite(scaled) || scaled <= 0.0f) {
			throw new IllegalArgumentException("pass dimensions are invalid");
		}
		return Math.max(1, Math.round(scaled));
	}

	private static List<Integer> sorted(Set<Integer> values) {
		return values.stream().sorted(Comparator.naturalOrder()).toList();
	}

	public enum LoadAction {
		LOAD, CLEAR, DONT_CARE
	}

	public enum StoreAction {
		STORE, DISCARD
	}

	public record Dimensions(int width, int height) {
		public Dimensions {
			if (width <= 0 || height <= 0) {
				throw new IllegalArgumentException("pass dimensions must be positive");
			}
		}
	}

	public record Scissor(boolean enabled, int x, int y, int width, int height) {
		public Scissor {
			if (enabled && (width <= 0 || height <= 0)) {
				throw new IllegalArgumentException("scissor dimensions must be positive");
			}
		}

		public static Scissor disabled() {
			return new Scissor(false, 0, 0, 0, 0);
		}
	}

	public record TargetView(int index, boolean alternate, GpuTextureView view,
		int width, int height, int baseMipLevel, int mipLevels) {
		public TargetView {
			Objects.requireNonNull(view, "target view");
			if (index < 0 || width <= 0 || height <= 0 || baseMipLevel < 0 || mipLevels <= 0) {
				throw new IllegalArgumentException("invalid target view snapshot");
			}
		}
	}

	public record TargetViews(Map<Integer, GpuTextureView> main, Map<Integer, GpuTextureView> alternate) {
		public TargetViews {
			main = copyViews(main);
			alternate = copyViews(alternate);
		}

		public static TargetViews from(PackTargetSet targets, Set<Integer> indices) {
			Objects.requireNonNull(targets, "target set");
			Objects.requireNonNull(indices, "target indices");
			Map<Integer, GpuTextureView> main = new LinkedHashMap<>();
			Map<Integer, GpuTextureView> alternate = new LinkedHashMap<>();
			for (int index : indices) {
				if (index < 0) {
					throw new IllegalArgumentException("target index must be non-negative");
				}
				main.put(index, targets.colortex(index));
				alternate.put(index, targets.colortexAlt(index));
			}
			return new TargetViews(main, alternate);
		}

		private GpuTextureView view(int index, boolean alternateSide) {
			GpuTextureView view = (alternateSide ? alternate : main).get(index);
			if (view == null) {
				throw new IllegalArgumentException("missing target view: colortex" + index);
			}
			return view;
		}

		private static Map<Integer, GpuTextureView> copyViews(Map<Integer, GpuTextureView> source) {
			Objects.requireNonNull(source, "target views");
			Map<Integer, GpuTextureView> result = new LinkedHashMap<>();
			for (Map.Entry<Integer, GpuTextureView> entry : source.entrySet()) {
				if (entry.getKey() < 0) {
					throw new IllegalArgumentException("target index must be non-negative");
				}
				result.put(entry.getKey(), Objects.requireNonNull(entry.getValue(), "target view"));
			}
			return Map.copyOf(result);
		}
	}

	public record AttachmentAction(LoadAction load, Optional<RenderTargetPlan.ClearColor> clearColor,
		StoreAction store) {
		public AttachmentAction {
			Objects.requireNonNull(load, "load action");
			clearColor = Objects.requireNonNull(clearColor, "clear color");
			Objects.requireNonNull(store, "store action");
			if (load != LoadAction.CLEAR && clearColor.isPresent()) {
				throw new IllegalArgumentException("only clear actions can carry a clear color");
			}
		}
	}

	public record Attachment(int index, TargetView target, LoadAction load, Optional<RenderTargetPlan.ClearColor> clearColor,
		StoreAction store) {
		public Attachment {
			if (index < 0) {
				throw new IllegalArgumentException("attachment index must be non-negative");
			}
			Objects.requireNonNull(target, "attachment target");
			Objects.requireNonNull(load, "load action");
			clearColor = Optional.ofNullable(Objects.requireNonNull(clearColor, "clear color").orElse(null));
			Objects.requireNonNull(store, "store action");
		}
	}

	public record PlanSnapshot(String dimension, String name, Set<Integer> reads, Set<Integer> writes,
		Set<String> samplers, Set<Integer> readsFromAlt, Set<Integer> writesToAlt,
		Set<Integer> mipmappedBuffers, RenderTargetPlan.PassScale scale, Set<Integer> flippedBefore,
		Set<Integer> flippedAfter) {
		public PlanSnapshot {
			dimension = Objects.requireNonNull(dimension, "dimension");
			name = Objects.requireNonNull(name, "name");
			reads = Set.copyOf(Objects.requireNonNull(reads, "reads"));
			writes = Set.copyOf(Objects.requireNonNull(writes, "writes"));
			samplers = Set.copyOf(Objects.requireNonNull(samplers, "samplers"));
			readsFromAlt = Set.copyOf(Objects.requireNonNull(readsFromAlt, "read side"));
			writesToAlt = Set.copyOf(Objects.requireNonNull(writesToAlt, "write side"));
			mipmappedBuffers = Set.copyOf(Objects.requireNonNull(mipmappedBuffers, "mipmaps"));
			scale = Objects.requireNonNull(scale, "scale");
			flippedBefore = Set.copyOf(Objects.requireNonNull(flippedBefore, "flipped before"));
			flippedAfter = Set.copyOf(Objects.requireNonNull(flippedAfter, "flipped after"));
		}
	}
}
