package dev.mikuteto.mtlrender.pack;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class PackOptionFile {
	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private PackOptionFile() {
	}

	public static Map<String, String> read(Path path) throws IOException {
		Map<String, String> values = new LinkedHashMap<>();
		if (!Files.isRegularFile(path)) {
			return values;
		}

		List<String> lines = Files.readAllLines(path, StandardCharsets.ISO_8859_1);
		for (int index = 0; index < lines.size();) {
			String line = lines.get(index++);
			while (continues(line) && index < lines.size()) {
				line = line.substring(0, line.length() - 1) + lines.get(index++).stripLeading();
			}
			if (line.stripLeading().isEmpty() || line.stripLeading().startsWith("#")
				|| line.stripLeading().startsWith("!")) {
				continue;
			}

			Properties property = new Properties();
			try {
				property.load(new StringReader(line + "\n"));
				for (Map.Entry<Object, Object> entry : property.entrySet()) {
					values.put((String) entry.getKey(), (String) entry.getValue());
				}
			} catch (IllegalArgumentException exception) {
				Map.Entry<String, String> fallback = parseLenient(line);
				if (fallback != null) {
					values.put(fallback.getKey(), fallback.getValue());
				}
			}
		}
		return values;
	}

	public static void write(Path path, Map<String, String> values) throws IOException {
		Path parent = path.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		List<String> keys = new ArrayList<>(values.keySet());
		keys.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
		try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.ISO_8859_1)) {
			for (String key : keys) {
				String value = values.get(key);
				if (key == null || value == null) {
					throw new IOException("Pack option keys and values must not be null");
				}
				writer.write(encode(key, true));
				writer.write('=');
				writer.write(encode(value, false));
				writer.newLine();
			}
		}
	}

	private static Map.Entry<String, String> parseLenient(String line) {
		int start = 0;
		while (start < line.length() && Character.isWhitespace(line.charAt(start))) {
			start++;
		}
		if (start == line.length()) {
			return null;
		}

		int separator = -1;
		boolean escaped = false;
		for (int index = start; index < line.length(); index++) {
			char character = line.charAt(index);
			if (escaped) {
				escaped = false;
				continue;
			}
			if (character == '\\') {
				escaped = true;
			} else if (character == '=' || character == ':' || Character.isWhitespace(character)) {
				separator = index;
				break;
			}
		}

		String key;
		String value;
		if (separator < 0) {
			key = line.substring(start);
			value = "";
		} else {
			key = line.substring(start, separator);
			int valueStart = separator;
			while (valueStart < line.length() && Character.isWhitespace(line.charAt(valueStart))) {
				valueStart++;
			}
			if (valueStart < line.length()
				&& (line.charAt(valueStart) == '=' || line.charAt(valueStart) == ':')) {
				valueStart++;
			}
			while (valueStart < line.length() && Character.isWhitespace(line.charAt(valueStart))) {
				valueStart++;
			}
			value = line.substring(valueStart);
		}
		return Map.entry(decodeLenient(key), decodeLenient(value));
	}

	private static String decodeLenient(String value) {
		StringBuilder result = new StringBuilder(value.length());
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character != '\\' || index + 1 >= value.length()) {
				result.append(character);
				continue;
			}
			char escaped = value.charAt(++index);
			if (escaped == 't') {
				result.append('\t');
			} else if (escaped == 'n') {
				result.append('\n');
			} else if (escaped == 'r') {
				result.append('\r');
			} else if (escaped == 'f') {
				result.append('\f');
			} else if (escaped == 'u' && index + 4 < value.length()) {
				int code = 0;
				boolean valid = true;
				for (int digit = 1; digit <= 4; digit++) {
					int number = Character.digit(value.charAt(index + digit), 16);
					if (number < 0) {
						valid = false;
						break;
					}
					code = (code << 4) | number;
				}
				if (valid) {
					result.append((char) code);
					index += 4;
				} else {
					result.append('\\').append(escaped);
				}
			} else {
				result.append(escaped);
			}
		}
		return result.toString();
	}

	private static boolean continues(String line) {
		int backslashes = 0;
		for (int index = line.length() - 1; index >= 0 && line.charAt(index) == '\\'; index--) {
			backslashes++;
		}
		return (backslashes & 1) == 1;
	}

	private static String encode(String value, boolean key) {
		StringBuilder result = new StringBuilder(value.length());
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character == '\\') {
				result.append("\\\\");
			} else if (character == '\t') {
				result.append("\\t");
			} else if (character == '\n') {
				result.append("\\n");
			} else if (character == '\r') {
				result.append("\\r");
			} else if (character == '\f') {
				result.append("\\f");
			} else if (key && (character == '=' || character == ':' || character == '#' || character == '!')) {
				result.append('\\').append(character);
			} else if (index == 0 && character == ' ') {
				result.append("\\ ");
			} else if (character < 0x20 || character > 0x7e) {
				result.append("\\u");
				result.append(HEX[(character >>> 12) & 0xf]);
				result.append(HEX[(character >>> 8) & 0xf]);
				result.append(HEX[(character >>> 4) & 0xf]);
				result.append(HEX[character & 0xf]);
			} else {
				result.append(character);
			}
		}
		return result.toString();
	}
}
