package dev.mikuteto.mtlrender.pack.precision;

import java.util.LinkedHashMap;
import java.util.Map;

public record TranslatedOutputReflection(Map<String, Integer> locations) {
	public TranslatedOutputReflection {
		locations = Map.copyOf(locations);
	}

	public static TranslatedOutputReflection empty() {
		return new TranslatedOutputReflection(Map.of());
	}

	public static TranslatedOutputReflection of(String name, int location) {
		Map<String, Integer> result = new LinkedHashMap<>();
		result.put(name, location);
		return new TranslatedOutputReflection(result);
	}
}
