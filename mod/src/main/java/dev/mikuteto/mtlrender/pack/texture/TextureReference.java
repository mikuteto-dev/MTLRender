package dev.mikuteto.mtlrender.pack.texture;

import java.util.Optional;

public record TextureReference(String namespace, String location, boolean lightmap) {
	public static Optional<TextureReference> parse(String value) {
		if ("lightmap".equals(value)) {
			return Optional.of(new TextureReference("minecraft", "dynamic/lightmap_1", true));
		}
		int separator = value.indexOf(':');
		if (separator <= 0 || separator == value.length() - 1) {
			return Optional.empty();
		}
		String namespace = value.substring(0, separator);
		String location = value.substring(separator + 1);
		boolean lightmap = "minecraft".equals(namespace)
			&& ("dynamic/lightmap_1".equals(location) || "dynamic/light_map_1".equals(location));
		return Optional.of(new TextureReference(namespace, location, lightmap));
	}
}
