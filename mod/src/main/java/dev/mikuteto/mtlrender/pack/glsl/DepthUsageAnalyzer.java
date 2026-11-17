package dev.mikuteto.mtlrender.pack.glsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DepthUsageAnalyzer {
	private static final Pattern TOKEN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|[{}();=,#]");
	private static final Set<String> WORLD = Set.of("depthtex0", "depthtex1", "depthtex2", "gdepthtex", "dhDepthTex1");
	private static final Set<String> SHADOW = Set.of("shadowtex0", "shadowtex1", "shadowtex0HW", "shadowtex1HW",
		"shadowtex0DH", "shadowtex1DH", "shadow", "watershadow");

	private DepthUsageAnalyzer() {
	}

	public static DepthUsage analyze(String source) {
		List<String> tokens = tokens(source);
		Map<String, DepthResource.SamplerKind> declarations = declarations(tokens);
		Map<String, String> aliases = aliases(tokens);
		Set<String> used = new LinkedHashSet<>();
		for (String token : tokens) {
			String resolved = resolve(token, aliases);
			if (WORLD.contains(resolved) || SHADOW.contains(resolved)) used.add(token);
		}
		for (Map.Entry<String, String> alias : aliases.entrySet()) {
			String resolved = resolve(alias.getKey(), aliases);
			if ((WORLD.contains(resolved) || SHADOW.contains(resolved)) && tokens.contains(alias.getKey())) used.add(alias.getKey());
		}
		List<DepthResource> resources = new ArrayList<>();
		for (String name : used) {
			String resolved = resolve(name, aliases);
			DepthResource.Family family = WORLD.contains(resolved) ? DepthResource.Family.WORLD : DepthResource.Family.SHADOW;
			DepthResource.SamplerKind kind = declarations.getOrDefault(name, defaultKind(name, declarations));
			resources.add(new DepthResource(name, family, kind));
		}
		return new DepthUsage(resources, contains(tokens, "gl_FragDepth"));
	}

	private static DepthResource.SamplerKind defaultKind(String name, Map<String, DepthResource.SamplerKind> declarations) {
		if (name.startsWith("shadowtex") || name.equals("shadow") || name.equals("watershadow")) {
			return declarations.getOrDefault(name, DepthResource.SamplerKind.SAMPLE);
		}
		return DepthResource.SamplerKind.SAMPLE;
	}

	private static Map<String, DepthResource.SamplerKind> declarations(List<String> tokens) {
		Map<String, DepthResource.SamplerKind> result = new LinkedHashMap<>();
		for (int i = 0; i + 1 < tokens.size(); i++) {
			String type = tokens.get(i);
			if (!type.startsWith("sampler") && !type.startsWith("image")) continue;
			String name = tokens.get(i + 1);
			if (!isIdentifier(name)) continue;
			DepthResource.SamplerKind kind = type.contains("Shadow") ? DepthResource.SamplerKind.COMPARE_SAMPLE
				: type.startsWith("image") ? DepthResource.SamplerKind.IMAGE : DepthResource.SamplerKind.SAMPLE;
			result.put(name, kind);
		}
		return result;
	}

	private static Map<String, String> aliases(List<String> tokens) {
		Map<String, String> result = new LinkedHashMap<>();
		for (int i = 0; i + 3 < tokens.size(); i++) {
			if (!tokens.get(i).equals("#") || !tokens.get(i + 1).equals("define")) continue;
			String alias = tokens.get(i + 2);
			String target = tokens.get(i + 3);
			if (isIdentifier(alias) && isIdentifier(target)) result.put(alias, target);
		}
		for (int i = 0; i + 3 < tokens.size(); i++) {
			String type = tokens.get(i);
			String name = tokens.get(i + 1);
			if (!type.startsWith("sampler") || !tokens.get(i + 2).equals("=")) continue;
			String target = tokens.get(i + 3);
			if (isIdentifier(name) && isIdentifier(target)) result.put(name, target);
		}
		return result;
	}

	private static String resolve(String name, Map<String, String> aliases) {
		Set<String> seen = new LinkedHashSet<>();
		String current = name;
		while (aliases.containsKey(current) && seen.add(current)) current = aliases.get(current);
		return current;
	}

	private static List<String> tokens(String source) {
		String withoutComments = source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
		Matcher matcher = TOKEN.matcher(withoutComments);
		List<String> result = new ArrayList<>();
		while (matcher.find()) result.add(matcher.group());
		return result;
	}

	private static boolean contains(List<String> tokens, String value) {
		return tokens.contains(value);
	}

	private static boolean isIdentifier(String value) {
		return value.matches("[A-Za-z_][A-Za-z0-9_]*");
	}
}
