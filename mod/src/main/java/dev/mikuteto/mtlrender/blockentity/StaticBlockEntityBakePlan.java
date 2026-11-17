package dev.mikuteto.mtlrender.blockentity;

import java.util.List;
import java.util.Objects;

public record StaticBlockEntityBakePlan(
	StaticBlockEntityBakeKey key,
	List<StaticBlockEntityBakePart> staticParts,
	List<StaticBlockEntityBakeFallback> fallbacks
) {
	public StaticBlockEntityBakePlan {
		key = Objects.requireNonNull(key, "key");
		staticParts = List.copyOf(Objects.requireNonNull(staticParts, "staticParts"));
		fallbacks = List.copyOf(Objects.requireNonNull(fallbacks, "fallbacks"));
	}

	public boolean hasStaticPortion() {
		return !staticParts.isEmpty();
	}

	public boolean isFallbackOnly() {
		return staticParts.isEmpty() && !fallbacks.isEmpty();
	}

	public StaticBlockEntityBakePlan withFallback(StaticBlockEntityBakeFallback fallback) {
		Objects.requireNonNull(fallback, "fallback");
		return new StaticBlockEntityBakePlan(key, staticParts, append(fallback));
	}

	private List<StaticBlockEntityBakeFallback> append(StaticBlockEntityBakeFallback fallback) {
		return java.util.stream.Stream.concat(fallbacks.stream(), java.util.stream.Stream.of(fallback)).toList();
	}
}
