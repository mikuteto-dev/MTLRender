package dev.mikuteto.mtlrender.pack.shadow;

import com.mojang.blaze3d.GpuFormat;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ProgramSet;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.target.RenderTargetPlan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShadowTargetPlan {
	private static final int COLOR_COUNT = 8;
	private static final Pattern CONSTANT = Pattern.compile(
		"\\bconst\\s+(?:int|bool)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([^;]+)");
	private static final Pattern DRAW_BUFFERS = Pattern.compile(
		"/\\*\\s*(?:DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([^*]+)\\*/");
	private static final Pattern FRAG_DATA = Pattern.compile("\\bgl_FragData\\s*\\[\\s*([0-7])\\s*\\]");
	private static final Pattern SHADOW_SAMPLER = Pattern.compile(
		"(?<![A-Za-z0-9_])shadow(?:tex[01]|color[0-7]|Color[0-7])(?![A-Za-z0-9_])");

	private final List<ShadowTargetSpec> targets;
	private final List<CopyOperation> operations;
	private final int mapResolution;
	private final float shadowDistance;
	private final long generation;

	private ShadowTargetPlan(List<ShadowTargetSpec> targets, List<CopyOperation> operations,
		int mapResolution, float shadowDistance, long generation) {
		this.targets = List.copyOf(targets);
		this.operations = List.copyOf(operations);
		this.mapResolution = mapResolution;
		this.shadowDistance = shadowDistance;
		this.generation = generation;
	}

	public static ShadowTargetPlan build(ShaderPack pack) {
		return build(pack, 0L);
	}

	public static ShadowTargetPlan build(ShaderPack pack, ShadowPassPlan passPlan) {
		return build(pack, passPlan, 0L);
	}

	public static ShadowTargetPlan build(ShaderPack pack, ShadowPassPlan passPlan, long generation) {
		Objects.requireNonNull(passPlan, "passPlan");
		ShadowTargetPlan result = build(pack, generation);
		if (result.mapResolution != passPlan.mapResolution() || Float.compare(result.shadowDistance, passPlan.shadowDistance()) != 0) {
			throw new IllegalArgumentException("shadow pass and target plans disagree");
		}
		return result;
	}

	public static ShadowTargetPlan build(ShaderPack pack, long generation) {
		Objects.requireNonNull(pack, "pack");
		RenderTargetPlan renderTargets = pack.targetPlan();
		List<String> sources = sources(pack);
		boolean shadowPass = hasProgram(pack, "shadow");
		boolean shadowComposite = hasProgram(pack, "shadowcomp");
		Set<String> samplers = samplers(sources);
		Set<Integer> writtenColors = writtenColors(pack, shadowPass);
		List<ShadowTargetSpec> result = new ArrayList<>();

		RenderTargetPlan.ShadowTexture depth0 = renderTargets.shadowDepth(0);
		RenderTargetPlan.ShadowTexture depth1 = renderTargets.shadowDepth(1);
		boolean depth0Present = shadowPass || samplers.contains("shadowtex0");
		boolean depth1Present = shadowComposite || samplers.contains("shadowtex1");
		result.add(depthSpec(depth0, depth0Present, nearest(pack, "shadowtex0", "shadowtexNearest", "shadow0MinMagNearest"),
			hardwareFiltering(pack, "shadowHardwareFiltering", "shadowHardwareFiltering0")));
		result.add(depthSpec(depth1, depth1Present, nearest(pack, "shadowtex1", "shadowtex1Nearest", "shadow1MinMagNearest"),
			hardwareFiltering(pack, "shadowHardwareFiltering", "shadowHardwareFiltering1")));

		for (int index = 0; index < COLOR_COUNT; index++) {
			RenderTargetPlan.ShadowTexture target = renderTargets.shadowColor(index);
			String name = target.name();
			boolean present = writtenColors.contains(index) || samplers.contains(name)
				|| samplers.contains("shadowColor" + index);
			result.add(new ShadowTargetSpec(name, ShadowTargetSpec.Kind.COLOR, present, target.resolution(), target.format(),
				target.metalFormat(), target.mipmap(), nearest(pack, name, name + "Nearest", "shadowColor" + index + "Nearest",
					"shadowColor" + index + "MinMagNearest"), false, target.clear(),
				target.clearColor().map(value -> new ShadowTargetSpec.ClearColor(value.red(), value.green(), value.blue(), value.alpha()))));
		}

		List<CopyOperation> operations = new ArrayList<>();
		if (depth0Present && depth1Present) {
			operations.add(new CopyOperation("shadowtex0", "shadowtex1", 0, 0, 0, 0, 0,
				renderTargets.shadowMapResolution(), renderTargets.shadowMapResolution(), Ordering.AFTER_SHADOW_BEFORE_SHADOWCOMP));
		}
		return new ShadowTargetPlan(result, operations, renderTargets.shadowMapResolution(), renderTargets.shadowDistance(), generation);
	}

	public List<ShadowTargetSpec> targets() { return targets; }
	public List<ShadowTargetSpec> presentTargets() { return targets.stream().filter(ShadowTargetSpec::present).toList(); }
	public ShadowTargetSpec target(String name) { return targets.stream().filter(value -> value.name().equals(name)).findFirst().orElseThrow(); }
	public List<ShadowTargetSpec> depthTargets() { return targets.stream().filter(value -> value.kind() == ShadowTargetSpec.Kind.DEPTH).toList(); }
	public List<ShadowTargetSpec> colorTargets() { return targets.stream().filter(value -> value.kind() == ShadowTargetSpec.Kind.COLOR).toList(); }
	public List<CopyOperation> operations() { return operations; }
	public int mapResolution() { return mapResolution; }
	public float shadowDistance() { return shadowDistance; }
	public long generation() { return generation; }

	private static ShadowTargetSpec depthSpec(RenderTargetPlan.ShadowTexture target, boolean present, boolean nearest,
		boolean hardwareFiltering) {
		return new ShadowTargetSpec(target.name(), ShadowTargetSpec.Kind.DEPTH, present, target.resolution(), target.format(),
			target.metalFormat(), target.mipmap(), nearest, hardwareFiltering, target.clear(), java.util.Optional.empty());
	}

	private static List<String> sources(ShaderPack pack) {
		List<String> result = new ArrayList<>();
		for (ProgramSet set : pack.programSets().values()) {
			for (Program program : set.programs().values()) result.addAll(program.sources().values());
		}
		return result;
	}

	private static boolean hasProgram(ShaderPack pack, String name) {
		return pack.programSets().values().stream().flatMap(set -> set.programs().values().stream())
			.anyMatch(program -> program.name().equals(name) || program.name().startsWith(name + "_"));
	}

	private static Set<String> samplers(List<String> sources) {
		Set<String> result = new LinkedHashSet<>();
		for (String source : sources) {
			Matcher matcher = SHADOW_SAMPLER.matcher(source);
			while (matcher.find()) result.add(matcher.group());
		}
		return result;
	}

	private static Set<Integer> writtenColors(ShaderPack pack, boolean shadowPass) {
		Set<Integer> result = new LinkedHashSet<>();
		if (!shadowPass) return result;
		for (ProgramSet set : pack.programSets().values()) {
			for (Program program : set.programs().values()) {
				if (!(program.name().equals("shadow") || program.name().startsWith("shadow_"))) continue;
				for (String source : program.sources().values()) {
					Matcher buffers = DRAW_BUFFERS.matcher(source);
					while (buffers.find()) {
						String value = buffers.group(1).trim();
						if (!value.contains(",")) {
							for (int index = 0; index < value.length(); index++) addColor(result, value.substring(index, index + 1));
						} else {
							for (String part : value.split(",")) addColor(result, part.trim());
						}
					}
					Matcher fragData = FRAG_DATA.matcher(source);
					while (fragData.find()) result.add(Integer.parseInt(fragData.group(1)));
				}
			}
		}
		return result;
	}

	private static void addColor(Set<Integer> result, String value) {
		try {
			int index = Integer.parseInt(value);
			if (index >= 0 && index < COLOR_COUNT) result.add(index);
		} catch (NumberFormatException ignored) {
		}
	}

	private static boolean nearest(ShaderPack pack, String... names) {
		return booleanConstant(pack, names);
	}

	private static boolean hardwareFiltering(ShaderPack pack, String... names) {
		return booleanConstant(pack, names);
	}

	private static boolean booleanConstant(ShaderPack pack, String... names) {
		Set<String> wanted = Set.of(names);
		boolean value = false;
		for (String source : sources(pack)) {
			Matcher matcher = CONSTANT.matcher(source);
			while (matcher.find()) if (wanted.contains(matcher.group(1)) && matcher.group(2).trim().equals("true")) value = true;
		}
		return value;
	}

	public enum Ordering { AFTER_SHADOW_BEFORE_SHADOWCOMP }

	public record CopyOperation(String source, String destination, int mipLevel, int destX, int destY,
		int sourceX, int sourceY, int width, int height, Ordering ordering) {
		public CopyOperation {
			Objects.requireNonNull(source, "source");
			Objects.requireNonNull(destination, "destination");
			Objects.requireNonNull(ordering, "ordering");
			if (mipLevel < 0 || width <= 0 || height <= 0) throw new IllegalArgumentException("invalid copy extent");
		}
	}
}
