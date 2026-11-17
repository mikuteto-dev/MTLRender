package dev.mikuteto.mtlrender.blockentity;

import java.util.Objects;

public record StaticModelMaterial(String atlasId, String renderLayer, String spriteKey) {
	public StaticModelMaterial {
		atlasId = require(atlasId, "atlasId");
		renderLayer = require(renderLayer, "renderLayer");
		spriteKey = require(spriteKey, "spriteKey");
	}

	public String key() {
		return atlasId + "\u001f" + renderLayer + "\u001f" + spriteKey;
	}

	private static String require(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
