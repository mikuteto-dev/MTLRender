package dev.mikuteto.mtlrender.pack.precision;

import dev.mikuteto.mtlrender.pack.Program;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BufferUsageAnalyzer {
	private static final Pattern DIRECTIVE = Pattern.compile("/\\*\\s*(DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([^*]*?)\\s*\\*/");
	private static final Pattern FRAG_DATA = Pattern.compile("\\bgl_FragData\\s*\\[\\s*([^]]+)\\s*]\\s*(?:\\.([rgba]{1,4}))?\\s*=\\s*([^;]+)");
	private static final Pattern FRAG_COLOR = Pattern.compile("\\bgl_FragColor\\s*(?:\\.([rgba]{1,4}))?\\s*=\\s*([^;]+)");
	private static final Pattern ASSIGNMENT = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*(?:\\.([rgba]{1,4}))?\\s*=\\s*([^;]+)");
	private static final Pattern SAMPLE = Pattern.compile("\\b(?:texture|texture2D|textureProj|textureLod|textureGrad|texelFetch)\\s*\\(\\s*([A-Za-z_]\\w*)\\s*,[^;()]*?\\)\\s*(?:\\.([rgba]{1,4}))?");

	private BufferUsageAnalyzer() {
	}

	public static List<BufferUsage> analyze(Program program, TranslatedOutputReflection reflection) {
		return analyze(program, reflection, Map.of());
	}

	public static List<BufferUsage> analyze(Program program, Map<String, Integer> outputLocations) {
		return analyze(program, new TranslatedOutputReflection(outputLocations));
	}

	public static List<BufferUsage> analyze(Program program, TranslatedOutputReflection reflection,
		Map<String, BufferSpec> specifications) {
		String source = clean(program.sources().values().stream().reduce("", (a, b) -> a + "\n" + b));
		String rawFragment = program.source(Program.Stage.FRAGMENT).orElse("");
		String fragment = clean(rawFragment);
		Map<Integer, String> targets = outputTargets(rawFragment);
		Map<String, Evidence> evidence = new TreeMap<>();
		for (Map.Entry<Integer, String> entry : targets.entrySet()) {
			evidence.computeIfAbsent(entry.getValue(), ignored -> new Evidence()).dynamic |= targets.isEmpty();
		}
		for (Map.Entry<String, Integer> entry : reflection.locations().entrySet()) {
			String target = targets.getOrDefault(entry.getValue(), target(entry.getValue()));
			evidence.computeIfAbsent(target, ignored -> new Evidence()).outputs.put(entry.getKey(), entry.getValue());
		}
		boolean directiveMissing = !hasDirective(rawFragment);
		Map<String, String> aliases = aliases();
		for (Matcher matcher = FRAG_DATA.matcher(fragment); matcher.find();) {
			String target = targets.getOrDefault(constant(matcher.group(1)), target(constant(matcher.group(1))));
			Evidence item = evidence.computeIfAbsent(target, ignored -> new Evidence());
			item.writes |= channels(matcher.group(2));
			item.sawWrite = true;
			item.numericSafe &= safeExpression(matcher.group(3));
			item.dynamic |= constant(matcher.group(1)) < 0;
			item.unknown |= constant(matcher.group(1)) < 0;
		}
		for (Matcher matcher = FRAG_COLOR.matcher(fragment); matcher.find();) {
			Evidence item = evidence.computeIfAbsent(targets.getOrDefault(0, "colortex0"), ignored -> new Evidence());
			item.writes |= channels(matcher.group(1));
			item.sawWrite = true;
			item.numericSafe &= safeExpression(matcher.group(2));
		}
		for (Matcher matcher = ASSIGNMENT.matcher(fragment); matcher.find();) {
			String name = matcher.group(1);
			if (reflection.locations().containsKey(name)) {
				String target = targets.getOrDefault(reflection.locations().get(name), target(reflection.locations().get(name)));
				Evidence item = evidence.computeIfAbsent(target, ignored -> new Evidence());
				item.writes |= channels(matcher.group(2));
				item.sawWrite = true;
				item.numericSafe &= safeExpression(matcher.group(3));
			}
		}
		for (Matcher matcher = SAMPLE.matcher(fragment); matcher.find();) {
			String sampler = aliases.getOrDefault(matcher.group(1), matcher.group(1));
			String target = samplerTarget(sampler);
			if (target != null) {
				Evidence item = evidence.computeIfAbsent(target, ignored -> new Evidence());
				item.reads |= channels(matcher.group(2));
			}
		}
		if (source.contains("imageStore") || source.contains("imageLoad")) {
			for (Evidence item : evidence.values()) item.resourceType = BufferUsage.ResourceType.IMAGE;
		}
		if (source.contains("buffer") || source.contains("atomic")) {
			for (Evidence item : evidence.values()) item.resourceType = BufferUsage.ResourceType.SSBO;
		}
		List<BufferUsage> result = new ArrayList<>();
		for (Map.Entry<String, Evidence> entry : evidence.entrySet()) {
			Evidence item = entry.getValue();
			item.dynamic |= directiveMissing && reflection.locations().isEmpty();
			item.unknown |= item.dynamic;
			if (hasIndirectAccess(fragment, entry.getKey())) item.unknown = true;
			BufferSpec spec = specifications.getOrDefault(entry.getKey(), BufferSpec.defaultFor(entry.getKey()));
			ChannelMask reads = item.unknown ? ChannelMask.unknownMask() : ChannelMask.known(item.reads);
			ChannelMask writes = item.unknown ? ChannelMask.unknownMask() : ChannelMask.known(item.writes);
			NumericUsage numeric = item.sawWrite && item.numericSafe && !item.unknown
				? new NumericUsage(NumericUsage.Signedness.FLOAT, precision(fragment)) : NumericUsage.unknown();
			BufferUsage.ResourceType resourceType = item.resourceType == BufferUsage.ResourceType.COLOR_ATTACHMENT
				? spec.resourceType() : item.resourceType;
			result.add(new BufferUsage(entry.getKey(), spec.requestedFormat(), spec.effectiveMetalFormat(), reads, writes,
				numeric, resourceType, item.dynamic));
		}
		return List.copyOf(result);
	}

	public record BufferSpec(String requestedFormat, String effectiveMetalFormat, BufferUsage.ResourceType resourceType) {
		static BufferSpec defaultFor(String id) {
			return new BufferSpec("RGBA16F", "RGBA16_FLOAT", id.startsWith("shadowcolor") || id.startsWith("colortex")
				? BufferUsage.ResourceType.COLOR_ATTACHMENT : BufferUsage.ResourceType.UNKNOWN);
		}
	}

	private static String clean(String source) {
		return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
	}

	private static boolean hasDirective(String source) {
		return DIRECTIVE.matcher(source).find();
	}

	private static Map<Integer, String> outputTargets(String source) {
		Map<Integer, String> result = new LinkedHashMap<>();
		Matcher matcher = DIRECTIVE.matcher(source);
		String kind = null;
		String value = null;
		while (matcher.find()) { kind = matcher.group(1); value = matcher.group(2).trim(); }
		if (kind == null) { result.put(0, "colortex0"); return result; }
		if (kind.equals("DRAWBUFFERS")) {
			for (int i = 0; i < value.length(); i++) {
				int index = Character.digit(value.charAt(i), 10);
				if (index >= 0) result.put(i, target(index));
			}
		} else {
			String[] values = value.split(",");
			for (int i = 0; i < values.length; i++) { int index = constant(values[i].trim()); if (index >= 0) result.put(i, target(index)); }
		}
		return result;
	}

	private static Map<String, String> aliases() {
		Map<String, String> result = new LinkedHashMap<>();
		String[] names = {"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"};
		for (int i = 0; i < names.length; i++) result.put(names[i], target(i));
		return result;
	}

	private static String samplerTarget(String name) {
		if (name.matches("colortex(?:1[0-5]|[0-9])")) return name;
		if (name.matches("shadowcolor[0-7]")) return name;
		if (name.equals("gcolor") || name.equals("gdepth") || name.equals("gnormal") || name.equals("composite") || name.matches("gaux[1-4]")) return aliases().get(name);
		if (name.matches("colorimg(?:1[0-5]|[0-9])")) return "colortex" + name.substring(8);
		return null;
	}

	private static String target(int index) {
		return index >= 0 ? "colortex" + index : "colortex0";
	}

	private static int constant(String value) {
		try { return Integer.parseInt(value.trim()); } catch (NumberFormatException ignored) { return -1; }
	}

	private static int channels(String swizzle) {
		if (swizzle == null || swizzle.isEmpty()) return ChannelMask.RGBA;
		int bits = 0;
		for (int i = 0; i < swizzle.length(); i++) bits |= switch (swizzle.charAt(i)) { case 'r' -> ChannelMask.RED; case 'g' -> ChannelMask.GREEN; case 'b' -> ChannelMask.BLUE; case 'a' -> ChannelMask.ALPHA; default -> ChannelMask.RGBA; };
		return bits;
	}

	private static boolean hasIndirectAccess(String source, String target) {
		return source.contains("imageStore") || source.contains("imageLoad") || source.matches("(?s).*\\b(?:texture|texelFetch)\\s*\\([^,]+\\[.*?\\].*") || source.contains("buffer");
	}

	private static boolean safeExpression(String expression) {
		return !expression.matches("(?s).*[-]\\s*[0-9].*")
			&& expression.matches("(?s)\\s*(?:vec[234]\\s*\\([^;]*\\)|[0-9]+(?:\\.[0-9]*)?(?:[eE][+]?[0-9]+)?|[+*/(),.\\s]+)\\s*");
	}

	private static NumericUsage.PrecisionEvidence precision(String source) {
		return source.contains("double") || source.contains("dFdx") || source.contains("dFdy") ? NumericUsage.PrecisionEvidence.REQUIRED : NumericUsage.PrecisionEvidence.PROVEN_SAFE;
	}

	private static final class Evidence {
		int reads;
		int writes;
		boolean unknown;
		boolean dynamic;
		boolean sawWrite;
		boolean numericSafe = true;
		BufferUsage.ResourceType resourceType = BufferUsage.ResourceType.COLOR_ATTACHMENT;
		Map<String, Integer> outputs = new LinkedHashMap<>();
	}
}
