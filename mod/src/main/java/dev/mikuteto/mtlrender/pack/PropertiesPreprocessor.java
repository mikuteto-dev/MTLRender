package dev.mikuteto.mtlrender.pack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PropertiesPreprocessor {
	private static final Pattern DEFINED_PATTERN =
		Pattern.compile("\\bdefined\\s*\\(?\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)?");
	private static final Pattern IDENTIFIER_PATTERN =
		Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	private PropertiesPreprocessor() {
	}

	public static String preprocess(String source, Map<String, String> environment) {
		Map<String, String> macros = new HashMap<>(environment);
		List<String> output = new ArrayList<>();
		List<Frame> frames = new ArrayList<>();
		boolean active = true;

		for (String line : logicalLines(source)) {
			String trimmed = line.trim();
			String directive = directive(trimmed);

			if (directive == null) {
				if (active && !trimmed.isEmpty() && !trimmed.startsWith("!")) {
					output.add(expand(trimmed, macros));
				}
				continue;
			}

			switch (directive) {
				case "if" -> {
					boolean condition = active && evaluate(trimmed.substring(3).trim(), macros);
					frames.add(new Frame(active, condition, condition));
					active = condition;
				}
				case "ifdef" -> {
					String name = argument(trimmed, 6);
					boolean condition = active && macros.containsKey(name);
					frames.add(new Frame(active, condition, condition));
					active = condition;
				}
				case "ifndef" -> {
					String name = argument(trimmed, 7);
					boolean condition = active && !macros.containsKey(name);
					frames.add(new Frame(active, condition, condition));
					active = condition;
				}
				case "elif" -> {
					if (!frames.isEmpty()) {
						Frame frame = frames.get(frames.size() - 1);
						boolean condition = frame.parentActive && !frame.taken
							&& evaluate(trimmed.substring(5).trim(), macros);
						frame.active = condition;
						frame.taken |= condition;
						active = condition;
					}
				}
				case "else" -> {
					if (!frames.isEmpty()) {
						Frame frame = frames.get(frames.size() - 1);
						boolean condition = frame.parentActive && !frame.taken;
						frame.active = condition;
						frame.taken = true;
						active = condition;
					}
				}
				case "endif" -> {
					if (!frames.isEmpty()) {
						frames.remove(frames.size() - 1);
					}
					active = frames.isEmpty() || frames.get(frames.size() - 1).active;
				}
				case "define" -> {
					if (active) {
						define(trimmed.substring(7).trim(), macros);
					}
				}
				case "undef" -> {
					if (active) {
						macros.remove(argument(trimmed, 6));
					}
				}
				default -> {
				}
			}
		}

		return String.join("\n", output) + (output.isEmpty() ? "" : "\n");
	}

	public static boolean evaluate(String expression, Map<String, String> macros) {
		String expanded = expandDefined(expression, macros);
		expanded = expand(expanded, macros);
		ExpressionParser parser = new ExpressionParser(expanded);
		double value = parser.parse();
		return value != 0.0;
	}

	private static void define(String definition, Map<String, String> macros) {
		int position = 0;
		while (position < definition.length()) {
			char character = definition.charAt(position);
			if (!Character.isLetterOrDigit(character) && character != '_') {
				break;
			}
			position++;
		}
		if (position == 0) {
			return;
		}
		String name = definition.substring(0, position);
		String value = definition.substring(position).trim();
		macros.put(name, value);
	}

	private static String argument(String line, int offset) {
		if (line.length() <= offset) {
			return "";
		}
		String remainder = line.substring(offset).trim();
		int space = indexOfWhitespace(remainder);
		return space < 0 ? remainder : remainder.substring(0, space);
	}

	private static int indexOfWhitespace(String value) {
		for (int index = 0; index < value.length(); index++) {
			if (Character.isWhitespace(value.charAt(index))) {
				return index;
			}
		}
		return -1;
	}

	private static String directive(String trimmed) {
		if (!trimmed.startsWith("#")) {
			return null;
		}
		int position = 1;
		while (position < trimmed.length() && Character.isWhitespace(trimmed.charAt(position))) {
			position++;
		}
		int start = position;
		while (position < trimmed.length() && Character.isLetter(trimmed.charAt(position))) {
			position++;
		}
		if (start == position) {
			return null;
		}
		return trimmed.substring(start, position);
	}

	private static List<String> logicalLines(String source) {
		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean joining = false;

		String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
		for (String line : normalized.split("\n", -1)) {
			if (joining) {
				current.append(line.stripLeading());
			} else {
				current.setLength(0);
				current.append(line);
			}

			if (endsWithContinuation(current)) {
				current.setLength(current.length() - 1);
				joining = true;
			} else {
				lines.add(current.toString());
				joining = false;
			}
		}

		if (joining) {
			lines.add(current.toString());
		}
		return lines;
	}

	private static boolean endsWithContinuation(StringBuilder value) {
		return value.length() > 0 && value.charAt(value.length() - 1) == '\\';
	}

	private static String expandDefined(String expression, Map<String, String> macros) {
		Matcher matcher = DEFINED_PATTERN.matcher(expression);
		StringBuilder expanded = new StringBuilder();
		while (matcher.find()) {
			String replacement = macros.containsKey(matcher.group(1)) ? "1" : "0";
			matcher.appendReplacement(expanded, replacement);
		}
		matcher.appendTail(expanded);
		return expanded.toString();
	}

	private static String expand(String text, Map<String, String> macros) {
		String current = text;
		for (int pass = 0; pass < 16; pass++) {
			Matcher matcher = IDENTIFIER_PATTERN.matcher(current);
			StringBuilder expanded = new StringBuilder();
			boolean changed = false;
			while (matcher.find()) {
				String name = matcher.group();
				String value = macros.get(name);
				if ("true".equals(name)) {
					value = "1";
				} else if ("false".equals(name)) {
					value = "0";
				}
				if (value == null || value.equals(name)) {
					matcher.appendReplacement(expanded, Matcher.quoteReplacement(name));
				} else {
					matcher.appendReplacement(expanded, Matcher.quoteReplacement(value.isEmpty() ? "1" : value));
					changed = true;
				}
			}
			matcher.appendTail(expanded);
			current = expanded.toString();
			if (!changed) {
				break;
			}
		}
		return current;
	}

	private static final class Frame {
		private final boolean parentActive;
		private boolean active;
		private boolean taken;

		private Frame(boolean parentActive, boolean active, boolean taken) {
			this.parentActive = parentActive;
			this.active = active;
			this.taken = taken;
		}
	}

	private static final class ExpressionParser {
		private final String text;
		private int position;

		private ExpressionParser(String text) {
			this.text = text;
		}

		double parse() {
			double value = parseOr();
			return value;
		}

		private double parseOr() {
			double value = parseAnd();
			while (take("||")) {
				double right = parseAnd();
				value = value != 0.0 || right != 0.0 ? 1.0 : 0.0;
			}
			return value;
		}

		private double parseAnd() {
			double value = parseEquality();
			while (take("&&")) {
				double right = parseEquality();
				value = value != 0.0 && right != 0.0 ? 1.0 : 0.0;
			}
			return value;
		}

		private double parseEquality() {
			double value = parseRelational();
			while (true) {
				if (take("==")) {
					value = value == parseRelational() ? 1.0 : 0.0;
				} else if (take("!=")) {
					value = value != parseRelational() ? 1.0 : 0.0;
				} else {
					return value;
				}
			}
		}

		private double parseRelational() {
			double value = parseAdditive();
			while (true) {
				if (take("<=")) {
					value = value <= parseAdditive() ? 1.0 : 0.0;
				} else if (take(">=")) {
					value = value >= parseAdditive() ? 1.0 : 0.0;
				} else if (take("<")) {
					value = value < parseAdditive() ? 1.0 : 0.0;
				} else if (take(">")) {
					value = value > parseAdditive() ? 1.0 : 0.0;
				} else {
					return value;
				}
			}
		}

		private double parseAdditive() {
			double value = parseMultiplicative();
			while (true) {
				if (take("+")) {
					value += parseMultiplicative();
				} else if (take("-")) {
					value -= parseMultiplicative();
				} else {
					return value;
				}
			}
		}

		private double parseMultiplicative() {
			double value = parseUnary();
			while (true) {
				if (take("*")) {
					value *= parseUnary();
				} else if (take("/")) {
					double right = parseUnary();
					value = right == 0.0 ? 0.0 : value / right;
				} else if (take("%")) {
					double right = parseUnary();
					value = right == 0.0 ? 0.0 : value % right;
				} else {
					return value;
				}
			}
		}

		private double parseUnary() {
			if (take("!")) {
				return parseUnary() == 0.0 ? 1.0 : 0.0;
			}
			if (take("-")) {
				return -parseUnary();
			}
			if (take("+")) {
				return parseUnary();
			}
			return parsePrimary();
		}

		private double parsePrimary() {
			skipWhitespace();
			if (take("(")) {
				double value = parseOr();
				take(")");
				return value;
			}

			skipWhitespace();
			int start = position;
			if (position < text.length() && (text.charAt(position) == '.' || Character.isDigit(text.charAt(position)))) {
				position++;
				while (position < text.length()) {
					char character = text.charAt(position);
					if (Character.isDigit(character) || character == '.'
						|| character == 'e' || character == 'E'
						|| ((character == '-' || character == '+')
						&& (text.charAt(position - 1) == 'e' || text.charAt(position - 1) == 'E'))) {
						position++;
					} else {
						break;
					}
				}
				try {
					return Double.parseDouble(text.substring(start, position));
				} catch (NumberFormatException exception) {
					return 0.0;
				}
			}

			if (position < text.length()
				&& (Character.isLetter(text.charAt(position)) || text.charAt(position) == '_')) {
				position++;
				while (position < text.length()
					&& (Character.isLetterOrDigit(text.charAt(position)) || text.charAt(position) == '_')) {
					position++;
				}
				return 0.0;
			}

			return 0.0;
		}

		private boolean take(String token) {
			skipWhitespace();
			if (!text.startsWith(token, position)) {
				return false;
			}
			position += token.length();
			return true;
		}

		private void skipWhitespace() {
			while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
				position++;
			}
		}
	}
}
