package dev.mikuteto.mtlrender.pack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PackOptions {
	private static final Set<String> VALID_CONST_NAMES = validConstNames();
	private static final Pattern DEFINED_PATTERN =
		Pattern.compile("\\bdefined\\s*\\(?\\s*([A-Za-z_][A-Za-z0-9_]*)");

	private final Includes includes;
	private final Map<String, AnnotatedFile> annotatedFiles;
	private final Map<String, PackOption> booleanOptions;
	private final Map<String, PackOption> stringOptions;

	private PackOptions(Includes includes, Map<String, AnnotatedFile> annotatedFiles,
						Map<String, PackOption> booleanOptions, Map<String, PackOption> stringOptions) {
		this.includes = includes;
		this.annotatedFiles = ordered(annotatedFiles);
		this.booleanOptions = ordered(booleanOptions);
		this.stringOptions = ordered(stringOptions);
	}

	public static PackOptions discover(Includes includes) {
		Map<String, AnnotatedFile> annotations = new LinkedHashMap<>();
		for (Includes.FileNode node : includes.nodes().values()) {
			annotations.put(node.path(), AnnotatedFile.parse(node.lines()));
		}

		Map<String, PackOption> booleans = new LinkedHashMap<>();
		Map<String, PackOption> strings = new LinkedHashMap<>();

		for (List<String> component : includes.components()) {
			Set<String> references = new HashSet<>();
			for (String path : component) {
				AnnotatedFile annotated = annotations.get(path);
				if (annotated != null) {
					references.addAll(annotated.references());
				}
			}

			for (String path : component) {
				AnnotatedFile annotated = annotations.get(path);
				if (annotated == null) {
					continue;
				}
				for (ParsedOption option : annotated.booleanOptions().values()) {
					if (references.contains(option.name())) {
						merge(booleans, option);
					}
				}
				for (ParsedOption option : annotated.stringOptions().values()) {
					merge(strings, option);
				}
			}
		}

		return new PackOptions(includes, annotations, booleans, strings);
	}

	private static void merge(Map<String, PackOption> merged, ParsedOption proposed) {
		PackOption existing = merged.get(proposed.name());
		if (existing == null) {
			merged.put(proposed.name(), proposed.toPackOption());
			return;
		}

		boolean sameDefault = existing.isBoolean()
			? existing.booleanDefaultValue() == proposed.booleanDefaultValue()
			: existing.defaultValue().equals(proposed.defaultValue());

		if (!sameDefault) {
			merged.remove(proposed.name());
			return;
		}

		if (existing.comment() == null && proposed.comment() != null) {
			merged.put(proposed.name(), proposed.toPackOption());
		}
	}

	public Map<String, PackOption> booleanOptions() {
		return booleanOptions;
	}

	public Map<String, PackOption> stringOptions() {
		return stringOptions;
	}

	public Map<String, PackOption> allOptions() {
		Map<String, PackOption> all = new LinkedHashMap<>(booleanOptions);
		all.putAll(stringOptions);
		return ordered(all);
	}

	public Includes apply(Map<String, String> values) {
		Map<String, List<String>> rewritten = new LinkedHashMap<>();
		for (Map.Entry<String, AnnotatedFile> entry : annotatedFiles.entrySet()) {
			rewritten.put(entry.getKey(), entry.getValue().apply(values, booleanOptions, stringOptions));
		}
		return includes.withLines(rewritten);
	}

	private static <V> Map<String, V> ordered(Map<String, V> values) {
		return Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}

	private static Set<String> validConstNames() {
		Set<String> names = new HashSet<>();
		names.add("shadowMapResolution");
		names.add("shadowDistance");
		names.add("voxelDistance");
		names.add("shadowDistanceRenderMul");
		names.add("entityShadowDistanceMul");
		names.add("shadowIntervalSize");
		names.add("generateShadowMipmap");
		names.add("generateShadowColorMipmap");
		names.add("shadowHardwareFiltering");
		names.add("shadowtex0Mipmap");
		names.add("shadowtexMipmap");
		names.add("shadowtex1Mipmap");
		names.add("shadowtex0Nearest");
		names.add("shadowtexNearest");
		names.add("shadow0MinMagNearest");
		names.add("shadowtex1Nearest");
		names.add("shadow1MinMagNearest");
		names.add("wetnessHalflife");
		names.add("drynessHalflife");
		names.add("eyeBrightnessHalflife");
		names.add("centerDepthHalflife");
		names.add("sunPathRotation");
		names.add("ambientOcclusionLevel");
		names.add("superSamplingLevel");
		names.add("noiseTextureResolution");
		for (int index = 0; index < 8; index++) {
			names.add("shadowcolor" + index + "Mipmap");
			names.add("shadowColor" + index + "Mipmap");
			names.add("shadowcolor" + index + "Nearest");
			names.add("shadowColor" + index + "Nearest");
			names.add("shadowcolor" + index + "MinMagNearest");
			names.add("shadowColor" + index + "MinMagNearest");
			names.add("shadowHardwareFiltering" + index);
		}
		return Set.copyOf(names);
	}

	private static boolean hasLeadingComment(String line) {
		return line.trim().startsWith("//");
	}

	private static String removeLeadingComment(String line) {
		Cursor cursor = new Cursor(line);
		cursor.takeWhitespace();
		cursor.takeComments();
		return cursor.rest();
	}

	private static String setBooleanDefineValue(String line, boolean value) {
		if (hasLeadingComment(line) && value) {
			return removeLeadingComment(line);
		}
		if (!value) {
			return "//" + line;
		}
		return line;
	}

	private static String editConst(String line, String currentValue, String newValue) {
		int equals = line.indexOf('=');
		if (equals < 0) {
			return line;
		}
		String prefix = line.substring(0, equals);
		String suffix = line.substring(equals);
		return prefix + suffix.replaceFirst(Pattern.quote(currentValue), Matcher.quoteReplacement(newValue));
	}

	private static Boolean parseBoolean(String value) {
		if ("true".equals(value)) {
			return Boolean.TRUE;
		}
		if ("false".equals(value)) {
			return Boolean.FALSE;
		}
		return null;
	}

	private static final class AnnotatedFile {
		private final List<String> lines;
		private final Map<Integer, ParsedOption> booleanOptions;
		private final Map<Integer, ParsedOption> stringOptions;
		private final Set<String> references;

		private AnnotatedFile(List<String> lines, Map<Integer, ParsedOption> booleanOptions,
							  Map<Integer, ParsedOption> stringOptions, Set<String> references) {
			this.lines = lines;
			this.booleanOptions = booleanOptions;
			this.stringOptions = stringOptions;
			this.references = references;
		}

		static AnnotatedFile parse(List<String> sourceLines) {
			Map<Integer, ParsedOption> booleans = new LinkedHashMap<>();
			Map<Integer, ParsedOption> strings = new LinkedHashMap<>();
			Set<String> references = new LinkedHashSet<>();

			for (int index = 0; index < sourceLines.size(); index++) {
				parseLine(sourceLines.get(index), index, booleans, strings, references);
			}

			return new AnnotatedFile(List.copyOf(sourceLines),
				Collections.unmodifiableMap(new LinkedHashMap<>(booleans)),
				Collections.unmodifiableMap(new LinkedHashMap<>(strings)),
				Collections.unmodifiableSet(new LinkedHashSet<>(references)));
		}

		private static void parseLine(String line, int index, Map<Integer, ParsedOption> booleans,
									  Map<Integer, ParsedOption> strings, Set<String> references) {
			String trimmed = line.trim();
			if (trimmed.isEmpty()) {
				return;
			}

			if (trimmed.contains("#ifdef") || trimmed.contains("#ifndef")) {
				parseIfdef(trimmed, references);
			}

			Matcher defined = DEFINED_PATTERN.matcher(trimmed);
			while (defined.find()) {
				references.add(defined.group(1));
			}

			if (trimmed.startsWith("const")) {
				parseConst(trimmed, index, booleans, strings);
			} else if (trimmed.contains("#define")) {
				parseDefine(trimmed, index, booleans, strings);
			}
		}

		private static void parseIfdef(String line, Set<String> references) {
			Cursor cursor = new Cursor(line);
			if (!cursor.takeLiteral("#ifdef") && !cursor.takeLiteral("#ifndef")) {
				return;
			}
			if (!cursor.takeWhitespace()) {
				return;
			}
			String name = cursor.takeWord();
			cursor.takeWhitespace();
			if (name == null || !cursor.atEnd()) {
				return;
			}
			references.add(name);
		}

		private static void parseConst(String line, int index, Map<Integer, ParsedOption> booleans,
									   Map<Integer, ParsedOption> strings) {
			Cursor cursor = new Cursor(line);
			cursor.takeLiteral("const");
			if (!cursor.takeWhitespace()) {
				return;
			}

			boolean booleanType = false;
			if (cursor.takeLiteral("int") || cursor.takeLiteral("float")) {
				booleanType = false;
			} else if (cursor.takeLiteral("bool")) {
				booleanType = true;
			} else {
				return;
			}

			if (!cursor.takeWhitespace()) {
				return;
			}

			String name = cursor.takeWord();
			if (name == null) {
				return;
			}

			cursor.takeWhitespace();
			if (!cursor.takeLiteral("=")) {
				return;
			}
			cursor.takeWhitespace();

			String value = cursor.takeWordOrNumber();
			if (value == null) {
				return;
			}

			cursor.takeWhitespace();
			if (!cursor.takeLiteral(";")) {
				return;
			}
			cursor.takeWhitespace();

			String comment = null;
			if (cursor.takeComments()) {
				comment = cursor.rest().trim();
			} else if (!cursor.atEnd()) {
				return;
			}

			if (!VALID_CONST_NAMES.contains(name)) {
				return;
			}

			if (booleanType) {
				if (!"true".equals(value) && !"false".equals(value)) {
					return;
				}
				booleans.put(index, ParsedOption.booleanConst(name, comment, "true".equals(value)));
				return;
			}

			ParsedOption option = ParsedOption.string(PackOption.Type.CONST, name, comment, value);
			if (option != null) {
				strings.put(index, option);
			}
		}

		private static void parseDefine(String line, int index, Map<Integer, ParsedOption> booleans,
										Map<Integer, ParsedOption> strings) {
			Cursor cursor = new Cursor(line);
			boolean leadingComment = cursor.takeComments();
			cursor.takeWhitespace();

			if (!cursor.takeLiteral("#define")) {
				return;
			}
			if (!cursor.takeWhitespace()) {
				return;
			}

			String name = cursor.takeWord();
			if (name == null) {
				return;
			}

			boolean whitespace = cursor.takeWhitespace();
			if (cursor.atEnd()) {
				booleans.put(index, ParsedOption.booleanDefine(name, null, !leadingComment));
				return;
			}

			if (cursor.takeComments()) {
				booleans.put(index, ParsedOption.booleanDefine(name, cursor.rest().trim(), !leadingComment));
				return;
			}
			if (!whitespace) {
				return;
			}
			if (leadingComment) {
				return;
			}

			String value = cursor.takeWordOrNumber();
			if (value == null) {
				return;
			}

			whitespace = cursor.takeWhitespace();
			if (cursor.atEnd()) {
				return;
			}
			if (!whitespace) {
				if (!cursor.takeComments()) {
					return;
				}
			} else if (!cursor.takeComments()) {
				return;
			}

			ParsedOption option = ParsedOption.string(PackOption.Type.DEFINE, name, cursor.rest().trim(), value);
			if (option != null) {
				strings.put(index, option);
			}
		}

		List<String> apply(Map<String, String> values, Map<String, PackOption> booleans,
						   Map<String, PackOption> strings) {
			List<String> result = new ArrayList<>(lines.size());
			for (int index = 0; index < lines.size(); index++) {
				String line = lines.get(index);
				ParsedOption booleanOption = booleanOptions.get(index);
				if (booleanOption != null) {
					PackOption option = booleans.get(booleanOption.name());
					if (option != null) {
						Boolean explicit = parseBoolean(values.get(option.name()));
						boolean value = explicit == null ? option.booleanDefaultValue() : explicit;
						if (option.type() == PackOption.Type.DEFINE) {
							line = setBooleanDefineValue(line, value);
						} else if (value != option.booleanDefaultValue()) {
							line = editConst(line, Boolean.toString(option.booleanDefaultValue()), Boolean.toString(value));
						}
					}
				}

				ParsedOption stringOption = stringOptions.get(index);
				if (stringOption != null) {
					PackOption option = strings.get(stringOption.name());
					if (option != null) {
						String value = values.get(option.name());
						if (value != null && !value.equals(option.defaultValue())) {
							if (option.type() == PackOption.Type.DEFINE) {
								line = "#define " + option.name() + " " + value + " // OptionAnnotatedSource: Changed option";
							} else {
								line = editConst(line, option.defaultValue(), value);
							}
						}
					}
				}

				result.add(line);
			}
			return result;
		}

		Set<String> references() {
			return references;
		}

		Map<Integer, ParsedOption> booleanOptions() {
			return booleanOptions;
		}

		Map<Integer, ParsedOption> stringOptions() {
			return stringOptions;
		}
	}

	private record ParsedOption(PackOption.Type type, String name, boolean booleanOption,
								String defaultValue, boolean booleanDefaultValue,
								List<String> allowedValues, String comment) {
		static ParsedOption booleanDefine(String name, String comment, boolean defaultValue) {
			return new ParsedOption(PackOption.Type.DEFINE, name, true, null, defaultValue, List.of(), comment);
		}

		static ParsedOption booleanConst(String name, String comment, boolean defaultValue) {
			return new ParsedOption(PackOption.Type.CONST, name, true, null, defaultValue, List.of(), comment);
		}

		static ParsedOption string(PackOption.Type type, String name, String comment, String defaultValue) {
			if (comment == null) {
				return null;
			}
			int opening = comment.indexOf('[');
			if (opening < 0) {
				return null;
			}
			int closing = comment.indexOf(']', opening);
			if (closing < 0) {
				return null;
			}

			List<String> allowed = new ArrayList<>();
			String list = comment.substring(opening + 1, closing).trim();
			if (!list.isEmpty()) {
				for (String value : list.split(" +")) {
					if (!value.isEmpty()) {
						allowed.add(value);
					}
				}
			}
			if (!allowed.contains(defaultValue)) {
				allowed.add(defaultValue);
			}

			String cleaned = (comment.substring(0, opening) + comment.substring(closing + 1)).trim();
			return new ParsedOption(type, name, false, defaultValue, false, List.copyOf(allowed), cleaned);
		}

		PackOption toPackOption() {
			return new PackOption(type, name, booleanOption, defaultValue, booleanDefaultValue, allowedValues, comment);
		}
	}

	private static final class Cursor {
		private String text;

		private Cursor(String text) {
			this.text = text;
		}

		boolean takeLiteral(String token) {
			if (!text.startsWith(token)) {
				return false;
			}
			text = text.substring(token.length());
			return true;
		}

		boolean takeWhitespace() {
			if (text.isEmpty() || !Character.isWhitespace(text.charAt(0))) {
				return false;
			}
			text = text.trim();
			return true;
		}

		boolean takeComments() {
			if (!text.startsWith("//")) {
				return false;
			}
			text = text.substring(2);
			while (text.startsWith("/")) {
				text = text.substring(1);
			}
			return true;
		}

		boolean atEnd() {
			return text.isEmpty();
		}

		String rest() {
			return text;
		}

		String takeWord() {
			if (text.isEmpty()) {
				return null;
			}
			int position = 0;
			while (position < text.length()) {
				char character = text.charAt(position);
				if (!Character.isLetterOrDigit(character) && character != '_') {
					break;
				}
				position++;
			}
			if (position == 0) {
				return null;
			}
			String word = text.substring(0, position);
			text = text.substring(position);
			return word;
		}

		String takeNumber() {
			if (text.isEmpty()) {
				return null;
			}
			int position = 0;
			if (text.charAt(0) == '-' || text.charAt(0) == '+') {
				position++;
			}
			int digits = 0;
			while (position < text.length() && Character.isDigit(text.charAt(position))) {
				position++;
				digits++;
			}
			if (position < text.length() && text.charAt(position) == '.') {
				position++;
				while (position < text.length() && Character.isDigit(text.charAt(position))) {
					position++;
					digits++;
				}
			}
			if (digits == 0) {
				return null;
			}
			position = consumeExponent(text, position);
			if (position < text.length() && (text.charAt(position) == 'f' || text.charAt(position) == 'F')) {
				position++;
			}
			String number = text.substring(0, position);
			try {
				Double.parseDouble(number);
			} catch (NumberFormatException exception) {
				return null;
			}
			text = text.substring(position);
			return number;
		}

		String takeWordOrNumber() {
			String number = takeNumber();
			if (number != null) {
				return number;
			}
			return takeWord();
		}

		private static int consumeExponent(String value, int start) {
			int position = start;
			if (position < value.length() && (value.charAt(position) == 'e' || value.charAt(position) == 'E')) {
				int candidate = position + 1;
				if (candidate < value.length() && (value.charAt(candidate) == '-' || value.charAt(candidate) == '+')) {
					candidate++;
				}
				int exponentDigits = 0;
				while (candidate < value.length() && Character.isDigit(value.charAt(candidate))) {
					candidate++;
					exponentDigits++;
				}
				if (exponentDigits > 0) {
					return candidate;
				}
			}
			return position;
		}
	}
}
