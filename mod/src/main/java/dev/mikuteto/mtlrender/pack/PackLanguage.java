package dev.mikuteto.mtlrender.pack;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public final class PackLanguage {
	private final Map<String, String> values;

	private PackLanguage(Map<String, String> values) {
		this.values = Map.copyOf(values);
	}

	public static PackLanguage empty() {
		return new PackLanguage(Map.of());
	}

	public static PackLanguage of(Map<String, String> values) {
		return new PackLanguage(values == null ? Map.of() : values);
	}

	public static PackLanguage load(Path packPath) throws IOException {
		return load(packPath, Locale.getDefault());
	}

	public static PackLanguage load(Path packPath, Locale locale) throws IOException {
		Map<String, Map<String, String>> files = new LinkedHashMap<>();
		try (PackSource source = PackSource.open(packPath)) {
			for (String path : source.list("/lang")) {
				String file = path.substring(path.lastIndexOf('/') + 1);
				if (!file.toLowerCase(Locale.ROOT).endsWith(".lang")) continue;
				String name = file.substring(0, file.length() - 5).toLowerCase(Locale.ROOT);
				files.put(name, read(source.read(path).orElse("")));
			}
		}
		Map<String, String> merged = new LinkedHashMap<>();
		String requested = locale == null ? "en_us" : locale.toLanguageTag().replace('-', '_').toLowerCase(Locale.ROOT);
		add(files, merged, "en_us");
		add(files, merged, "en_us".equals(requested) ? "" : requested);
		return new PackLanguage(merged);
	}

	private static void add(Map<String, Map<String, String>> files, Map<String, String> target, String name) {
		if (!name.isEmpty()) target.putAll(files.getOrDefault(name, Map.of()));
	}

	private static Map<String, String> read(String source) throws IOException {
		Properties properties = new Properties();
		try (Reader reader = new java.io.StringReader(source)) {
			properties.load(reader);
		}
		Map<String, String> result = new LinkedHashMap<>();
		for (Map.Entry<Object, Object> entry : properties.entrySet()) {
			result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
		}
		return result;
	}

	public String label(String kind, String name) {
		String key = kind + "." + name;
		return values.getOrDefault(key, name);
	}

	public String profile(String name) { return label("profile", name); }
	public String screen(String name) { return label("screen", name); }
	public String option(String name) { return label("option", name); }
	public Map<String, String> values() { return values; }
}
