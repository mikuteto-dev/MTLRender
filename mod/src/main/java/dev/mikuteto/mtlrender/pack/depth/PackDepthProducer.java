package dev.mikuteto.mtlrender.pack.depth;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class PackDepthProducer<T> {
	private final Map<String, Produced<T>> produced = new HashMap<>();

	public Optional<T> produce(String name, long version, T source, Function<T, T> convert) {
		String key = DepthConversion.canonical(name);
		if (key == null || source == null || convert == null) return Optional.empty();
		Produced<T> current = produced.get(key);
		if (current != null && current.version() == version) return Optional.of(current.value());
		T value = convert.apply(source);
		if (value == null) return Optional.empty();
		produced.put(key, new Produced<>(version, value));
		return Optional.of(value);
	}

	public Optional<T> read(String name) {
		String key = DepthConversion.canonical(name);
		if (key == null) return Optional.empty();
		Produced<T> value = produced.get(key);
		return value == null ? Optional.empty() : Optional.of(value.value());
	}

	public void clear() {
		produced.clear();
	}

	private record Produced<T>(long version, T value) {
	}
}
