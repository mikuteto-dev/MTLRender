package dev.mikuteto.mtlrender.pack.target;

import com.mojang.blaze3d.GpuFormat;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ProgramId;
import dev.mikuteto.mtlrender.pack.ProgramSet;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.ShaderProperties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RenderTargetPlan {
	private static final int COLOR_COUNT = 16;
	private static final int SHADOW_COLOR_COUNT = 8;
	private static final List<String> LEGACY_NAMES = List.of(
		"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"
	);
	private static final Pattern CONST = Pattern.compile(
		"^\\s*const\\s+(int|bool|vec4)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*?)\\s*;");
	private static final Pattern COMMENT_DIRECTIVE = Pattern.compile(
		"/\\*\\s*(DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([^*]*?)\\*/", Pattern.DOTALL);
	private static final Pattern PACK_COMMENT_DIRECTIVE = Pattern.compile(
		"/\\*\\s*(GAUX4FORMAT|SHADOWRES|SHADOWHPL)\\s*:\\s*([^*]*?)\\*/", Pattern.DOTALL);
	private static final Pattern SAMPLER = Pattern.compile(
		"(?<![A-Za-z0-9_])(colortex(?:[0-9]|1[0-5])|gcolor|gdepth|gnormal|composite|gaux[1-4]|"
			+ "shadowcolor[0-7]|shadowColor[0-7]|shadowtex[01]|shadowtex|depthtex[0-2]|noisetex)(?![A-Za-z0-9_])");
	private static final Pattern GDEPTH_UNIFORM = Pattern.compile(
		"\\buniform\\s+[A-Za-z_][A-Za-z0-9_]*\\s+gdepth\\b");
	private static final Set<String> FORMATS = Set.of(
		"RGBA", "R8", "RG8", "RGB8", "RGBA8", "R8_SNORM", "RG8_SNORM", "RGB8_SNORM", "RGBA8_SNORM",
		"R16", "RG16", "RGB16", "RGBA16", "R16_SNORM", "RG16_SNORM", "RGB16_SNORM", "RGBA16_SNORM",
		"R16F", "RG16F", "RGB16F", "RGBA16F", "R32F", "RG32F", "RGB32F", "RGBA32F",
		"R8I", "RG8I", "RGB8I", "RGBA8I", "R8UI", "RG8UI", "RGB8UI", "RGBA8UI",
		"R16I", "RG16I", "RGB16I", "RGBA16I", "R16UI", "RG16UI", "RGB16UI", "RGBA16UI",
		"R32I", "RG32I", "RGB32I", "RGBA32I", "R32UI", "RG32UI", "RGB32UI", "RGBA32UI",
		"RGBA2", "RGBA4", "R3_G3_B2", "RGB5_A1", "RGB565", "RGB10_A2", "RGB10_A2UI",
		"R11F_G11F_B10F", "RGB9_E5"
	);

	private final List<ColorTarget> colorTargets;
	private final List<ShadowTexture> shadowColors;
	private final List<ShadowTexture> shadowDepths;
	private final List<Pass> passes;
	private final int shadowMapResolution;
	private final float shadowDistance;

	private RenderTargetPlan(List<ColorTarget> colorTargets, List<ShadowTexture> shadowColors,
			List<ShadowTexture> shadowDepths, List<Pass> passes, int shadowMapResolution, float shadowDistance) {
		this.colorTargets = List.copyOf(colorTargets);
		this.shadowColors = List.copyOf(shadowColors);
		this.shadowDepths = List.copyOf(shadowDepths);
		this.passes = List.copyOf(passes);
		this.shadowMapResolution = shadowMapResolution;
		this.shadowDistance = shadowDistance;
	}

	public static RenderTargetPlan build(ShaderPack pack) {
		Settings settings = new Settings();
		List<ProgramEntry> entries = entries(pack);
		for (ProgramEntry entry : entries) {
			entry.program.source(Program.Stage.FRAGMENT).ifPresent(settings::acceptFragment);
		}

		List<ColorTarget> colors = new ArrayList<>();
		for (int index = 0; index < COLOR_COUNT; index++) {
			String format = settings.formats.get(index);
			colors.add(new ColorTarget(index, colorName(index), format, metalFormat(format), settings.clear.get(index),
				Optional.ofNullable(settings.clearColors.get(index)), settings.mipmap.get(index), bufferSize(pack.properties(), index)));
		}

		List<ShadowTexture> shadowColors = new ArrayList<>();
		for (int index = 0; index < SHADOW_COLOR_COUNT; index++) {
			String name = "shadowcolor" + index;
			String format = settings.shadowFormats.get(index);
			shadowColors.add(new ShadowTexture(name, format, metalFormat(format), settings.shadowMipmap.get(index),
				settings.shadowClear.get(index), Optional.ofNullable(settings.shadowClearColors.get(index)),
				settings.shadowMapResolution));
		}

		List<ShadowTexture> shadowDepths = List.of(
			new ShadowTexture("shadowtex0", "DEPTH32", GpuFormat.D32_FLOAT, settings.shadowTexMipmap.get(0), true,
				Optional.empty(), settings.shadowMapResolution),
			new ShadowTexture("shadowtex1", "DEPTH32", GpuFormat.D32_FLOAT, settings.shadowTexMipmap.get(1), true,
				Optional.empty(), settings.shadowMapResolution)
		);

		return new RenderTargetPlan(colors, shadowColors, shadowDepths, buildPasses(pack, entries),
			settings.shadowMapResolution, settings.shadowDistance);
	}

	public List<ColorTarget> colorTargets() {
		return colorTargets;
	}

	public ColorTarget color(int index) {
		return colorTargets.get(index);
	}

	public List<ShadowTexture> shadowColors() {
		return shadowColors;
	}

	public ShadowTexture shadowColor(int index) {
		return shadowColors.get(index);
	}

	public List<ShadowTexture> shadowDepths() {
		return shadowDepths;
	}

	public ShadowTexture shadowDepth(int index) {
		return shadowDepths.get(index);
	}

	public List<Pass> passes() {
		return passes;
	}

	public int shadowMapResolution() {
		return shadowMapResolution;
	}

	public float shadowDistance() {
		return shadowDistance;
	}

	private static List<ProgramEntry> entries(ShaderPack pack) {
		List<ProgramEntry> entries = new ArrayList<>();
		List<Map.Entry<String, ProgramSet>> sets = new ArrayList<>(pack.programSets().entrySet());
		sets.sort(Map.Entry.comparingByKey());
		for (Map.Entry<String, ProgramSet> set : sets) {
			List<Program> programs = new ArrayList<>(set.getValue().programs().values());
			programs.sort(Comparator.comparingInt(RenderTargetPlan::programOrder).thenComparing(Program::name));
			for (Program program : programs) {
				entries.add(new ProgramEntry(set.getKey(), program));
			}
		}
		return entries;
	}

	private static int programOrder(Program program) {
		String name = program.name();
		return phaseOrder(name) * 1000 + suffixNumber(name);
	}

	private static int phaseOrder(String name) {
		if (name.equals("setup")) return 0;
		if (name.equals("begin") || name.startsWith("begin")) return 10;
		if (name.equals("shadow") || name.startsWith("shadow_")) return 20;
		if (name.equals("shadowcomp") || name.startsWith("shadowcomp")) return 30;
		if (name.equals("prepare") || name.startsWith("prepare")) return 40;
		if (ProgramId.fromSourceName(name).isPresent()) return 50;
		if (name.equals("deferred") || name.startsWith("deferred")) return 60;
		if (name.equals("composite") || name.startsWith("composite")) return 70;
		if (name.equals("final")) return 80;
		return 90;
	}

	private static int suffixNumber(String name) {
		int index = name.length();
		while (index > 0 && Character.isDigit(name.charAt(index - 1))) {
			index--;
		}
		if (index == name.length()) return 0;
		try {
			return Integer.parseInt(name.substring(index));
		} catch (NumberFormatException exception) {
			return 0;
		}
	}

	private static List<Pass> buildPasses(ShaderPack pack, List<ProgramEntry> entries) {
		List<Pass> passes = new ArrayList<>();
		Map<String, Flipper> flippers = new HashMap<>();
		Map<String, String> previousPhase = new HashMap<>();
		for (ProgramEntry entry : entries) {
			String dimension = entry.dimension;
			Flipper flipper = flippers.computeIfAbsent(dimension, ignored -> new Flipper());
			String phase = phase(entry.program.name());
			String oldPhase = previousPhase.put(dimension, phase);
			if (!phase.equals(oldPhase)) {
				applyPreFlips(flipper, pack.properties(), preFlipName(phase));
			}

			SourceInfo source = sourceInfo(entry.program);
			Set<Integer> reads = new LinkedHashSet<>();
			Set<String> samplerNames = new LinkedHashSet<>();
			for (String text : entry.program.sources().values()) {
				for (String sampler : samplers(text)) {
					samplerNames.add(sampler);
					Integer index = colorIndex(sampler);
					if (index != null) reads.add(index);
				}
			}

			Set<Integer> writes = new LinkedHashSet<>(source.drawBuffers);
			if (entry.program.name().equals("final")) writes.clear();
			Set<Integer> flippedBefore = flipper.snapshot();
			Map<Integer, Boolean> explicitValues = explicitFlips(pack.properties(), entry.program.name());
			Set<Integer> writesToAlt = new LinkedHashSet<>(writes);
			writesToAlt.removeAll(flippedBefore);
			Pass pass = new Pass(dimension, entry.program.name(), Set.copyOf(reads), Set.copyOf(writes),
				Set.copyOf(samplerNames), flippedBefore, Set.copyOf(writesToAlt), Set.copyOf(source.mipmapBuffers),
				passScale(pack.properties(), entry.program.name()), flippedBefore, Set.of());
			passes.add(pass);

			if (flipAfterPass(phase)) {
				for (int index : writes) {
					if (!Boolean.FALSE.equals(explicitValues.get(index))) flipper.toggle(index);
				}
				for (Map.Entry<Integer, Boolean> explicit : explicitValues.entrySet()) {
					if (Boolean.TRUE.equals(explicit.getValue())) flipper.toggle(explicit.getKey());
				}
			}
			Set<Integer> flippedAfter = flipper.snapshot();
			passes.set(passes.size() - 1, pass.withFlippedAfter(flippedAfter));
		}
		return passes;
	}

	private static boolean flipAfterPass(String phase) {
		return phase.equals("begin") || phase.equals("prepare") || phase.equals("deferred")
			|| phase.equals("composite") || phase.equals("shadowcomp");
	}

	private static String phase(String name) {
		if (name.equals("setup")) return "setup";
		if (name.equals("begin") || name.startsWith("begin")) return "begin";
		if (name.equals("shadowcomp") || name.startsWith("shadowcomp")) return "shadowcomp";
		if (name.equals("shadow") || name.startsWith("shadow_")) return "shadow";
		if (name.equals("prepare") || name.startsWith("prepare")) return "prepare";
		if (name.equals("deferred") || name.startsWith("deferred")) return "deferred";
		if (name.equals("composite") || name.startsWith("composite")) return "composite";
		if (name.equals("final")) return "final";
		return "gbuffer";
	}

	private static String preFlipName(String phase) {
		return switch (phase) {
			case "begin", "prepare", "deferred", "composite", "shadowcomp" -> phase + "_pre";
			default -> "";
		};
	}

	private static void applyPreFlips(Flipper flipper, ShaderProperties properties, String pass) {
		if (pass.isEmpty()) return;
		for (Map.Entry<String, Boolean> entry : properties.flips().getOrDefault(pass, Map.of()).entrySet()) {
			if (Boolean.TRUE.equals(entry.getValue())) {
				Integer index = colorIndex(entry.getKey());
				if (index != null) flipper.toggle(index);
			}
		}
	}

	private static Map<Integer, Boolean> explicitFlips(ShaderProperties properties, String pass) {
		Map<Integer, Boolean> result = new LinkedHashMap<>();
		for (Map.Entry<String, Boolean> entry : properties.flips().getOrDefault(pass, Map.of()).entrySet()) {
			Integer index = colorIndex(entry.getKey());
			if (index != null) result.put(index, entry.getValue());
		}
		return result;
	}

	private static SourceInfo sourceInfo(Program program) {
		Optional<String> fragment = program.source(Program.Stage.FRAGMENT);
		if (fragment.isEmpty()) return new SourceInfo(Set.of(), Set.of());
		String source = fragment.get();
		Set<Integer> writes = drawBuffers(source);
		Map<Integer, Boolean> mipmapValues = new LinkedHashMap<>();
		for (Directive directive : constDirectives(source)) {
			Integer index = directiveColorIndex(directive.name);
			if (index != null && directive.name.endsWith("MipmapEnabled") && directive.type.equals("bool")) {
				parseBoolean(directive.value).ifPresent(value -> mipmapValues.put(index, value));
			}
		}
		Set<Integer> mipmaps = new LinkedHashSet<>();
		for (Map.Entry<Integer, Boolean> entry : mipmapValues.entrySet()) {
			if (entry.getValue()) mipmaps.add(entry.getKey());
		}
		return new SourceInfo(writes, mipmaps);
	}

	private static Set<Integer> drawBuffers(String source) {
		Matcher matcher = COMMENT_DIRECTIVE.matcher(source);
		String type = null;
		String value = null;
		while (matcher.find()) {
			type = matcher.group(1);
			value = matcher.group(2).trim();
		}
		if (type == null) return Set.of(0);
		Set<Integer> result = new LinkedHashSet<>();
		if (type.equals("DRAWBUFFERS")) {
			for (int index = 0; index < value.length(); index++) {
				int buffer = Character.digit(value.charAt(index), 10);
				if (buffer >= 0 && buffer < COLOR_COUNT) result.add(buffer);
			}
		} else {
			for (String part : value.split(",")) {
				try {
					int buffer = Integer.parseInt(part.trim());
					if (buffer >= 0 && buffer < COLOR_COUNT) result.add(buffer);
				} catch (NumberFormatException exception) {
					return Set.of(0);
				}
			}
		}
		return Set.copyOf(result);
	}

	private static Set<String> samplers(String source) {
		Set<String> result = new LinkedHashSet<>();
		Matcher matcher = SAMPLER.matcher(stripComments(source));
		while (matcher.find()) result.add(matcher.group(1));
		return result;
	}

	private static List<Directive> constDirectives(String source) {
		List<Directive> result = new ArrayList<>();
		for (String line : source.split("\\R", -1)) {
			Matcher matcher = CONST.matcher(line);
			if (matcher.find()) result.add(new Directive(matcher.group(1), matcher.group(2), matcher.group(3).trim()));
		}
		return result;
	}

	private static String stripComments(String source) {
		StringBuilder result = new StringBuilder(source.length());
		boolean block = false;
		boolean line = false;
		for (int index = 0; index < source.length(); index++) {
			char current = source.charAt(index);
			char next = index + 1 < source.length() ? source.charAt(index + 1) : 0;
			if (line) {
				if (current == '\n') {
					line = false;
					result.append(current);
				}
				continue;
			}
			if (block) {
				if (current == '*' && next == '/') {
					block = false;
					index++;
				} else if (current == '\n') {
					result.append(current);
				}
				continue;
			}
			if (current == '/' && next == '/') {
				line = true;
				index++;
			} else if (current == '/' && next == '*') {
				block = true;
				index++;
			} else {
				result.append(current);
			}
		}
		return result.toString();
	}

	private static Optional<Boolean> parseBoolean(String value) {
		if (value.equals("true")) return Optional.of(true);
		if (value.equals("false")) return Optional.of(false);
		return Optional.empty();
	}

	private static Optional<ClearColor> parseColor(String value) {
		if (!value.startsWith("vec4(") || !value.endsWith(")")) return Optional.empty();
		String[] parts = value.substring(5, value.length() - 1).split(",");
		if (parts.length != 4) return Optional.empty();
		try {
			return Optional.of(new ClearColor(Float.parseFloat(parts[0].trim()), Float.parseFloat(parts[1].trim()),
				Float.parseFloat(parts[2].trim()), Float.parseFloat(parts[3].trim())));
		} catch (NumberFormatException exception) {
			return Optional.empty();
		}
	}

	private static BufferSize bufferSize(ShaderProperties properties, int index) {
		String canonical = colorName(index);
		String value = null;
		if (index < LEGACY_NAMES.size()) value = properties.bufferScales().get(LEGACY_NAMES.get(index));
		if (value == null) value = properties.bufferScales().get(canonical);
		if (value == null) return BufferSize.defaultValue();
		String[] parts = value.trim().split("\\s+");
		if (parts.length != 2) return BufferSize.defaultValue();
		try {
			return new BufferSize(parts[0].contains("."), Float.parseFloat(parts[0]), parts[1].contains("."), Float.parseFloat(parts[1]));
		} catch (NumberFormatException exception) {
			return BufferSize.defaultValue();
		}
	}

	private static PassScale passScale(ShaderProperties properties, String pass) {
		String value = properties.scales().get(pass);
		if (value == null) return PassScale.defaultValue();
		String[] parts = value.trim().split("\\s+");
		try {
			float scale = Float.parseFloat(parts[0]);
			if (parts.length == 1) return new PassScale(scale, 0.0f, 0.0f);
			if (parts.length < 3) return PassScale.defaultValue();
			return new PassScale(scale, Float.parseFloat(parts[1]), Float.parseFloat(parts[2]));
		} catch (NumberFormatException exception) {
			return PassScale.defaultValue();
		}
	}

	private static Integer directiveColorIndex(String name) {
		for (String suffix : List.of("Format", "ClearColor", "Clear", "MipmapEnabled")) {
			if (name.endsWith(suffix)) return colorIndex(name.substring(0, name.length() - suffix.length()));
		}
		return null;
	}

	private static Integer colorIndex(String name) {
		for (int index = 0; index < LEGACY_NAMES.size(); index++) {
			if (LEGACY_NAMES.get(index).equals(name)) return index;
		}
		if (!name.startsWith("colortex")) return null;
		try {
			int index = Integer.parseInt(name.substring("colortex".length()));
			return index >= 0 && index < COLOR_COUNT ? index : null;
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private static String colorName(int index) {
		return "colortex" + index;
	}

	private static GpuFormat metalFormat(String name) {
		if (name.equals("RGBA")) return GpuFormat.RGBA8_UNORM;
		return switch (name) {
			case "RGB10_A2" -> GpuFormat.RGB10A2_UNORM;
			case "RGB10_A2UI" -> GpuFormat.RGB10A2_UINT;
			case "R11F_G11F_B10F" -> GpuFormat.RG11B10_FLOAT;
			case "R3_G3_B2", "RGB5_A1", "RGB9_E5" -> GpuFormat.RGBA8_UNORM;
			case "RGB565" -> GpuFormat.RGBA16_UNORM;
		default -> {
			String mapped = name.startsWith("RGB") && !name.startsWith("RGBA")
				? "RGBA" + name.substring(3) : name;
			if (mapped.endsWith("UI")) mapped = mapped.substring(0, mapped.length() - 2) + "_UINT";
			else if (mapped.endsWith("I")) mapped = mapped.substring(0, mapped.length() - 1) + "_SINT";
			else if (mapped.endsWith("F")) mapped = mapped.substring(0, mapped.length() - 1) + "_FLOAT";
			else if (!mapped.endsWith("_SNORM") && !mapped.endsWith("_UNORM")) mapped += "_UNORM";
			yield GpuFormat.valueOf(mapped);
		}
		};
	}

	public record ColorTarget(int index, String name, String format, GpuFormat metalFormat, boolean clear,
		Optional<ClearColor> clearColor, boolean mipmapEnabled, BufferSize size) {
		public GpuFormat requestedMetalFormat() {
			return metalFormat;
		}
	}

	public record ShadowTexture(String name, String format, GpuFormat metalFormat, boolean mipmap, boolean clear,
		Optional<ClearColor> clearColor, int resolution) {
		public GpuFormat requestedMetalFormat() {
			return metalFormat;
		}
	}

	public record Pass(String dimension, String name, Set<Integer> reads, Set<Integer> writes, Set<String> samplers,
		Set<Integer> readsFromAlt, Set<Integer> writesToAlt, Set<Integer> mipmappedBuffers, PassScale scale,
		Set<Integer> flippedBefore, Set<Integer> flippedAfter) {
		public boolean readsFromAlt(int index) {
			return readsFromAlt.contains(index);
		}

		public boolean writesToAlt(int index) {
			return writesToAlt.contains(index);
		}

		private Pass withFlippedAfter(Set<Integer> after) {
			return new Pass(dimension, name, reads, writes, samplers, readsFromAlt, writesToAlt, mipmappedBuffers,
				scale, flippedBefore, after);
		}
	}

	public record ClearColor(float red, float green, float blue, float alpha) {
	}

	public record BufferSize(boolean relativeWidth, float width, boolean relativeHeight, float height) {
		private static BufferSize defaultValue() {
			return new BufferSize(true, 1.0f, true, 1.0f);
		}
	}

	public record PassScale(float scale, float offsetX, float offsetY) {
		private static PassScale defaultValue() {
			return new PassScale(1.0f, 0.0f, 0.0f);
		}
	}

	private record ProgramEntry(String dimension, Program program) {
	}

	private record Directive(String type, String name, String value) {
	}

	private record SourceInfo(Set<Integer> drawBuffers, Set<Integer> mipmapBuffers) {
	}

	private static final class Flipper {
		private final Set<Integer> state = new LinkedHashSet<>();

		private void toggle(int index) {
			if (!state.remove(index)) state.add(index);
		}

		private Set<Integer> snapshot() {
			return Set.copyOf(state);
		}
	}

	private static final class Settings {
		private final Map<Integer, String> formats = new HashMap<>();
		private final Map<Integer, Boolean> clear = new HashMap<>();
		private final Map<Integer, ClearColor> clearColors = new HashMap<>();
		private final Map<Integer, Boolean> mipmap = new HashMap<>();
		private final Map<Integer, String> shadowFormats = new HashMap<>();
		private final Map<Integer, Boolean> shadowClear = new HashMap<>();
		private final Map<Integer, ClearColor> shadowClearColors = new HashMap<>();
		private final Map<Integer, Boolean> shadowMipmap = new HashMap<>();
		private final Map<Integer, Boolean> shadowTexMipmap = new HashMap<>();
		private int shadowMapResolution = 1024;
		private float shadowDistance = 160.0f;

		private Settings() {
			for (int index = 0; index < COLOR_COUNT; index++) {
				formats.put(index, "RGBA");
				clear.put(index, true);
				mipmap.put(index, false);
			}
			for (int index = 0; index < SHADOW_COLOR_COUNT; index++) {
				shadowFormats.put(index, "RGBA");
				shadowClear.put(index, true);
				shadowMipmap.put(index, false);
				shadowClearColors.put(index, new ClearColor(1.0f, 1.0f, 1.0f, 1.0f));
			}
			shadowTexMipmap.put(0, false);
			shadowTexMipmap.put(1, false);
		}

		private void acceptFragment(String source) {
			for (Directive directive : constDirectives(source)) accept(directive);
			Matcher matcher = PACK_COMMENT_DIRECTIVE.matcher(source);
			while (matcher.find()) {
				String value = matcher.group(2).trim();
				try {
					switch (matcher.group(1)) {
						case "GAUX4FORMAT" -> {
							if (value.equals("RGBA32F") || value.equals("RGB32F") || value.equals("RGB16")) {
								formats.put(7, value);
							}
						}
						case "SHADOWRES" -> shadowMapResolution = Integer.parseInt(value);
						case "SHADOWHPL" -> shadowDistance = Float.parseFloat(value);
						default -> {
						}
					}
				} catch (NumberFormatException ignored) {
				}
			}
			if (GDEPTH_UNIFORM.matcher(stripComments(source)).find() && formats.get(1).equals("RGBA")) {
				formats.put(1, "RGBA32F");
			}
		}

		private void accept(Directive directive) {
			Integer index = directiveColorIndex(directive.name);
			if (index != null) {
				if (directive.name.endsWith("Format") && directive.type.equals("int")) {
					validFormat(directive.value).ifPresent(value -> formats.put(index, value));
				}
				if (directive.name.endsWith("Clear") && directive.type.equals("bool")) {
					parseBoolean(directive.value).ifPresent(value -> clear.put(index, value));
				}
				if (directive.name.endsWith("ClearColor") && directive.type.equals("vec4")) {
					parseColor(directive.value).ifPresent(value -> clearColors.put(index, value));
				}
				if (directive.name.endsWith("MipmapEnabled") && directive.type.equals("bool")) {
					parseBoolean(directive.value).ifPresent(value -> mipmap.put(index, mipmap.get(index) || value));
				}
			}

			Integer shadowIndex = shadowColorIndex(directive.name);
			if (shadowIndex != null) {
				if (directive.name.endsWith("Format") && directive.type.equals("int")) {
					validFormat(directive.value).ifPresent(value -> shadowFormats.put(shadowIndex, value));
				}
				if (directive.name.endsWith("Clear") && directive.type.equals("bool")) {
					parseBoolean(directive.value).ifPresent(value -> shadowClear.put(shadowIndex, value));
				}
				if (directive.name.endsWith("ClearColor") && directive.type.equals("vec4")) {
					parseColor(directive.value).ifPresent(value -> shadowClearColors.put(shadowIndex, value));
				}
				if (directive.name.endsWith("Mipmap") && directive.type.equals("bool")) {
					parseBoolean(directive.value).ifPresent(value -> shadowMipmap.put(shadowIndex, value));
				}
			}

			if (directive.name.equals("generateShadowColorMipmap") && directive.type.equals("bool")) {
				parseBoolean(directive.value).ifPresent(value -> {
					for (int targetIndex = 0; targetIndex < SHADOW_COLOR_COUNT; targetIndex++) shadowMipmap.put(targetIndex, value);
				});
			}
			if (directive.name.equals("generateShadowMipmap") && directive.type.equals("bool")) {
				parseBoolean(directive.value).ifPresent(value -> {
					shadowTexMipmap.put(0, value);
					shadowTexMipmap.put(1, value);
				});
			}
			if (directive.name.equals("shadowtexMipmap") && directive.type.equals("bool")) {
				parseBoolean(directive.value).ifPresent(value -> shadowTexMipmap.put(0, value));
			}
			if (directive.name.matches("shadowtex[01]Mipmap") && directive.type.equals("bool")) {
				parseBoolean(directive.value).ifPresent(value -> shadowTexMipmap.put(directive.name.charAt(9) - '0', value));
			}
			if (directive.name.equals("shadowMapResolution") && directive.type.equals("int")) {
				try {
					shadowMapResolution = Integer.parseInt(directive.value);
				} catch (NumberFormatException ignored) {
				}
			}
			if (directive.name.equals("shadowDistance") && directive.type.equals("float")) {
				try {
					shadowDistance = Float.parseFloat(directive.value);
				} catch (NumberFormatException ignored) {
				}
			}
		}
	}

	private static Optional<String> validFormat(String value) {
		String format = value.trim().toUpperCase();
		return FORMATS.contains(format) ? Optional.of(format) : Optional.empty();
	}

	private static Integer shadowColorIndex(String name) {
		String prefix = name.startsWith("shadowColor") ? "shadowColor" : "shadowcolor";
		if (!name.startsWith(prefix)) return null;
		int start = prefix.length();
		if (name.length() <= start + 1 || !Character.isDigit(name.charAt(start))) return null;
		int index = Character.digit(name.charAt(start), 10);
		if (index < 0 || index >= SHADOW_COLOR_COUNT) return null;
		String remainder = name.substring(start + 1);
		return remainder.equals("Format") || remainder.equals("Clear") || remainder.equals("ClearColor")
			|| remainder.equals("Mipmap") ? index : null;
	}
}
