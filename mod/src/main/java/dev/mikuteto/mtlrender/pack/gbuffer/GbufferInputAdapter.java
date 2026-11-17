package dev.mikuteto.mtlrender.pack.gbuffer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GbufferInputAdapter {
	private static final Map<String, String> ALIASES = Map.of(
		"mtlr_Position", "Position",
		"mtlr_Color", "Color",
		"mtlr_UV0", "UV0",
		"mtlr_UV2", "UV2",
		"mtlr_Normal", "Normal"
	);
	private static final Set<String> OPTIONAL = Set.of("mc_Entity", "at_tangent", "at_midBlock");
	private static final Pattern INPUT_DECLARATION = Pattern.compile(
		"(?m)(?<![A-Za-z0-9_])(?:layout\\s*\\([^;{}]*\\)\\s*)?(?:(?:flat|smooth|noperspective|centroid|sample|patch|invariant)\\s+)*(?:in|attribute)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+%s(\\s*(?:\\[[^;\\]]*\\])?\\s*;)");
	private static final Map<GbufferPipelineSpec.InputProfile, Set<String>> ATTRIBUTES = createAttributes();

	private GbufferInputAdapter() {
	}

	static String adapt(String source, GbufferPipelineSpec.InputProfile profile) {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(profile, "profile");
		String adapted = source;
		for (String name : OPTIONAL) {
			if (containsIdentifier(adapted, name) && !ATTRIBUTES.get(profile).contains(name)) {
				adapted = defaultOptional(adapted, name);
			}
		}
		Map<String, String> replacements = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, String> alias : ALIASES.entrySet()) {
			if (ATTRIBUTES.get(profile).contains(alias.getValue()) && containsIdentifier(adapted, alias.getKey())) {
				replacements.put(alias.getKey(), alias.getValue());
			}
		}
		validateCollisions(adapted, replacements);
		return replaceIdentifiers(adapted, replacements);
	}

	private static Map<GbufferPipelineSpec.InputProfile, Set<String>> createAttributes() {
		Map<GbufferPipelineSpec.InputProfile, Set<String>> result = new EnumMap<>(GbufferPipelineSpec.InputProfile.class);
		result.put(GbufferPipelineSpec.InputProfile.Position, Set.of("Position"));
		result.put(GbufferPipelineSpec.InputProfile.PositionColor, Set.of("Position", "Color"));
		result.put(GbufferPipelineSpec.InputProfile.PositionTex, Set.of("Position", "UV0"));
		result.put(GbufferPipelineSpec.InputProfile.PositionTexColor, Set.of("Position", "UV0", "Color"));
		result.put(GbufferPipelineSpec.InputProfile.PositionTexLightmapColor,
			Set.of("Position", "UV0", "UV2", "Color"));
		result.put(GbufferPipelineSpec.InputProfile.PositionColorLightmap,
			Set.of("Position", "Color", "UV2"));
		result.put(GbufferPipelineSpec.InputProfile.Block,
			Set.of("Position", "Color", "UV0", "UV2"));
		result.put(GbufferPipelineSpec.InputProfile.Entity,
			Set.of("Position", "Color", "UV0", "UV1", "UV2", "Normal"));
		result.put(GbufferPipelineSpec.InputProfile.Particle,
			Set.of("Position", "UV0", "Color", "UV2"));
		result.put(GbufferPipelineSpec.InputProfile.Line,
			Set.of("Position", "Color", "Normal", "LineWidth"));
		result.put(GbufferPipelineSpec.InputProfile.PositionColorLineWidth,
			Set.of("Position", "Color", "LineWidth"));
		result.put(GbufferPipelineSpec.InputProfile.Cloud, Set.of());
		return Map.copyOf(result);
	}

	private static String defaultOptional(String source, String name) {
		Matcher matcher = Pattern.compile(String.format(INPUT_DECLARATION.pattern(), Pattern.quote(name))).matcher(source);
		StringBuffer result = new StringBuffer();
		boolean declared = false;
		while (matcher.find()) {
			declared = true;
			matcher.appendReplacement(result, Matcher.quoteReplacement("const " + matcher.group(1) + " " + name + " = "
				+ zero(matcher.group(1)) + ";"));
		}
		matcher.appendTail(result);
		if (declared) {
			return result.toString();
		}
		if (!containsIdentifier(source, name)) {
			return source;
		}
		String declaration = "const vec4 " + name + " = vec4(0.0);\n";
		int lineEnd = source.indexOf('\n');
		if (lineEnd >= 0 && source.substring(0, lineEnd).trim().startsWith("#version")) {
			return source.substring(0, lineEnd + 1) + declaration + source.substring(lineEnd + 1);
		}
		if (source.trim().startsWith("#version") && lineEnd < 0) {
			return source + "\n" + declaration;
		}
		return declaration + source;
	}

	private static String zero(String type) {
		if (type.equals("bool")) return "false";
		if (type.startsWith("bvec")) return type + "(false)";
		if (type.equals("float") || type.equals("double") || type.startsWith("vec") || type.startsWith("dvec")) {
			return type + "(0.0)";
		}
		if (type.equals("uint") || type.startsWith("uvec")) return type + "(0u)";
		return type + "(0)";
	}

	private static void validateCollisions(String source, Map<String, String> replacements) {
		Set<String> identifiers = identifiers(source);
		for (Map.Entry<String, String> replacement : replacements.entrySet()) {
			if (identifiers.contains(replacement.getValue())) {
				throw new IllegalArgumentException("gbuffer input alias collides with " + replacement.getValue());
			}
		}
	}

	private static String replaceIdentifiers(String source, Map<String, String> replacements) {
		if (replacements.isEmpty()) return source;
		List<Token> tokens = tokens(source);
		StringBuilder result = new StringBuilder(source);
		for (int index = tokens.size() - 1; index >= 0; index--) {
			Token token = tokens.get(index);
			String replacement = replacements.get(token.text());
			if (replacement != null) {
				result.replace(token.start(), token.end(), replacement);
			}
		}
		return result.toString();
	}

	private static boolean containsIdentifier(String source, String name) {
		for (Token token : tokens(source)) {
			if (token.text().equals(name)) return true;
		}
		return false;
	}

	private static Set<String> identifiers(String source) {
		Set<String> result = new HashSet<>();
		for (Token token : tokens(source)) result.add(token.text());
		return result;
	}

	private static List<Token> tokens(String source) {
		List<Token> result = new ArrayList<>();
		int index = 0;
		while (index < source.length()) {
			char character = source.charAt(index);
			if (character == '/' && index + 1 < source.length()) {
				char next = source.charAt(index + 1);
				if (next == '/') {
					index += 2;
					while (index < source.length() && source.charAt(index) != '\n') index++;
					continue;
				}
				if (next == '*') {
					index += 2;
					while (index + 1 < source.length()
						&& !(source.charAt(index) == '*' && source.charAt(index + 1) == '/')) index++;
					index = Math.min(source.length(), index + 2);
					continue;
				}
			}
			if (character == '"' || character == '\'') {
				char quote = character;
				index++;
				while (index < source.length()) {
					char current = source.charAt(index++);
					if (current == '\\' && index < source.length()) index++;
					else if (current == quote) break;
				}
				continue;
			}
			if (Character.isLetter(character) || character == '_') {
				int start = index++;
				while (index < source.length()) {
					char current = source.charAt(index);
					if (!Character.isLetterOrDigit(current) && current != '_') break;
					index++;
				}
				result.add(new Token(source.substring(start, index), start, index));
				continue;
			}
			index++;
		}
		return result;
	}

	private record Token(String text, int start, int end) {
	}
}
