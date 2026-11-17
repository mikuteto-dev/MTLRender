package dev.mikuteto.mtlrender.blockentity;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record StaticBlockEntityBakeMetadata(
	Set<Long> staticPositions,
	Set<Long> dynamicPositions,
	Optional<StaticBlockEntityBakePublication> publication
) {
	public StaticBlockEntityBakeMetadata {
		staticPositions = immutable(staticPositions);
		dynamicPositions = immutable(dynamicPositions);
		publication = Objects.requireNonNull(publication, "publication");
	}

	public StaticBlockEntityBakeMetadata(Set<Long> staticPositions, Set<Long> dynamicPositions) {
		this(staticPositions, dynamicPositions, Optional.empty());
	}

	public static StaticBlockEntityBakeMetadata empty() {
		return new StaticBlockEntityBakeMetadata(Set.of(), Set.of(), Optional.empty());
	}

	private static Set<Long> immutable(Set<Long> source) {
		Objects.requireNonNull(source, "positions");
		return Collections.unmodifiableSet(new LinkedHashSet<>(source));
	}
}
