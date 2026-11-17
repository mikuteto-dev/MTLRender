package dev.mikuteto.mtlrender.pack.precision;

import com.mojang.blaze3d.GpuFormat;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ProgramSet;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.target.RenderTargetPlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PrecisionPlanner {
	private PrecisionPlanner() {
	}

	public static EffectivePlan plan(RenderTargetPlan targets, ShaderPack pack, PrecisionPolicy policy) {
		List<BufferUsage> usages = new ArrayList<>();
		List<Map.Entry<String, ProgramSet>> sets = new ArrayList<>(pack.programSets().entrySet());
		sets.sort(Map.Entry.comparingByKey());
		for (Map.Entry<String, ProgramSet> set : sets) {
			List<Program> programs = new ArrayList<>(set.getValue().programs().values());
			programs.sort(Comparator.comparing(Program::name));
			for (Program program : programs) usages.addAll(BufferUsageAnalyzer.analyze(program, TranslatedOutputReflection.empty()));
		}
		return plan(targets, usages, policy);
	}

	public static EffectivePlan plan(RenderTargetPlan targets, List<BufferUsage> usages, PrecisionPolicy policy) {
		Map<String, BufferUsage> evidence = aggregate(usages);
		List<EffectiveColorTarget> colors = new ArrayList<>();
		List<EffectiveShadowTexture> shadows = new ArrayList<>();
		List<PrecisionDecision> decisions = new ArrayList<>();
		for (RenderTargetPlan.ColorTarget target : targets.colorTargets()) {
			Decision decision = decide(target.name(), target.format(), target.metalFormat(), evidence, policy);
			colors.add(new EffectiveColorTarget(target, decision.metalFormat()));
			decisions.add(decision.precision());
		}
		for (RenderTargetPlan.ShadowTexture target : targets.shadowColors()) {
			Decision decision = decide(target.name(), target.format(), target.metalFormat(), evidence, policy);
			shadows.add(new EffectiveShadowTexture(target, decision.metalFormat()));
			decisions.add(decision.precision());
		}
		return new EffectivePlan(targets, colors, shadows, new PrecisionReport(decisions));
	}

	private static Decision decide(String id, String requested, GpuFormat metal, Map<String, BufferUsage> evidence,
		PrecisionPolicy policy) {
		BufferUsage usage = evidence.get(id);
		if (usage == null) usage = new BufferUsage(id, requested, metal.name(), ChannelMask.unknownMask(),
			ChannelMask.unknownMask(), NumericUsage.unknown(), BufferUsage.ResourceType.COLOR_ATTACHMENT, false);
		else usage = new BufferUsage(id, requested, metal.name(), usage.reads(), usage.writes(), usage.numericUsage(),
			usage.resourceType(), usage.dynamicOutputSelection());
		PrecisionDecision precision = policy.decide(usage);
		GpuFormat effective = precision.reduction() == ReductionKind.NONE ? metal
			: GpuFormat.valueOf(precision.effectiveMetalFormat());
		return new Decision(effective, precision);
	}

	private static Map<String, BufferUsage> aggregate(List<BufferUsage> usages) {
		Map<String, BufferUsage> result = new LinkedHashMap<>();
		for (BufferUsage usage : usages) {
			BufferUsage old = result.get(usage.id());
			if (old == null) {
				result.put(usage.id(), usage);
				continue;
			}
			result.put(usage.id(), merge(old, usage));
		}
		return result;
	}

	private static BufferUsage merge(BufferUsage left, BufferUsage right) {
		ChannelMask reads = merge(left.reads(), right.reads());
		ChannelMask writes = merge(left.writes(), right.writes());
		NumericUsage numeric = left.numericUsage().equals(right.numericUsage()) ? left.numericUsage() : NumericUsage.unknown();
		BufferUsage.ResourceType type = left.resourceType() == right.resourceType()
			? left.resourceType() : BufferUsage.ResourceType.UNKNOWN;
		return new BufferUsage(left.id(), left.requestedFormat(), left.effectiveMetalFormat(), reads, writes, numeric,
			type, left.dynamicOutputSelection() || right.dynamicOutputSelection());
	}

	private static ChannelMask merge(ChannelMask left, ChannelMask right) {
		return left.unknown() || right.unknown() ? ChannelMask.unknownMask() : ChannelMask.known(left.bits() | right.bits());
	}

	private record Decision(GpuFormat metalFormat, PrecisionDecision precision) {
	}

	public record EffectivePlan(RenderTargetPlan requestedPlan, List<EffectiveColorTarget> colors,
		List<EffectiveShadowTexture> shadowColors, PrecisionReport report) {
		public EffectivePlan {
			colors = List.copyOf(colors);
			shadowColors = List.copyOf(shadowColors);
		}

		public EffectiveColorTarget color(int index) {
			return colors.get(index);
		}

		public EffectiveShadowTexture shadowColor(int index) {
			return shadowColors.get(index);
		}

		public List<RenderTargetPlan.ShadowTexture> shadowDepths() {
			return requestedPlan.shadowDepths();
		}
	}

	public record EffectiveColorTarget(RenderTargetPlan.ColorTarget requested, GpuFormat effectiveMetalFormat) {
		public String name() { return requested.name(); }
		public String format() { return requested.format(); }
		public GpuFormat requestedMetalFormat() { return requested.metalFormat(); }
	}

	public record EffectiveShadowTexture(RenderTargetPlan.ShadowTexture requested, GpuFormat effectiveMetalFormat) {
		public String name() { return requested.name(); }
		public String format() { return requested.format(); }
		public GpuFormat requestedMetalFormat() { return requested.metalFormat(); }
	}
}
