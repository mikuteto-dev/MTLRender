package dev.mikuteto.mtlrender.blockentity;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record StaticBlockEntityBakePublication(
	long sectionKey,
	long resourceGeneration,
	List<StaticBlockEntityBakeInstance> instances,
	State state
) {
	public StaticBlockEntityBakePublication {
		if (resourceGeneration < 0) {
			throw new IllegalArgumentException("resourceGeneration must not be negative");
		}
		instances = List.copyOf(Objects.requireNonNull(instances, "instances"));
		Set<Long> positions = new HashSet<>();
		for (StaticBlockEntityBakeInstance instance : instances) {
			Objects.requireNonNull(instance, "instances contains null");
			if (instance.key().resourceGeneration() != resourceGeneration) {
				throw new IllegalArgumentException("instance generation does not match publication generation");
			}
			if (!positions.add(instance.blockPos())) {
				throw new IllegalArgumentException("publication contains duplicate block position");
			}
		}
		state = Objects.requireNonNull(state, "state");
	}

	public static StaticBlockEntityBakePublication candidate(long sectionKey, long blockPos, StaticBlockEntityBakePlan plan) {
		Objects.requireNonNull(plan, "plan");
		return new StaticBlockEntityBakePublication(
			sectionKey,
			plan.key().resourceGeneration(),
			List.of(StaticBlockEntityBakeInstance.fromPlan(blockPos, plan)),
			State.CANDIDATE
		);
	}

	public StaticBlockEntityBakePublication published() {
		return new StaticBlockEntityBakePublication(sectionKey, resourceGeneration, instances, State.PUBLISHED);
	}

	public StaticBlockEntityBakePublication invalidated() {
		return new StaticBlockEntityBakePublication(sectionKey, resourceGeneration, instances, State.INVALIDATED);
	}

	public Optional<StaticBlockEntityBakeInstance> lookup(long blockPos, String typeKey, String variantKey) {
		StaticBlockEntityBakeKey key = new StaticBlockEntityBakeKey(resourceGeneration, typeKey, variantKey);
		return instances.stream()
			.filter(instance -> instance.blockPos() == blockPos && instance.key().equals(key))
			.findFirst();
	}

	public boolean isPublished() {
		return state == State.PUBLISHED;
	}

	public boolean isAvailableAt(long generation) {
		return isPublished() && resourceGeneration == generation;
	}

	public enum State {
		CANDIDATE,
		PUBLISHED,
		INVALIDATED
	}

	public record StaticBlockEntityBakeInstance(
		long blockPos,
		StaticBlockEntityBakeKey key,
		boolean hasStaticPart,
		boolean hasDynamicPart,
		List<StaticBlockEntityBakeFallback> fallbacks
	) {
		public StaticBlockEntityBakeInstance {
			key = Objects.requireNonNull(key, "key");
			fallbacks = List.copyOf(Objects.requireNonNull(fallbacks, "fallbacks"));
			if (!hasStaticPart && !hasDynamicPart && fallbacks.isEmpty()) {
				throw new IllegalArgumentException("instance must describe a bake candidate");
			}
		}

		public static StaticBlockEntityBakeInstance fromPlan(long blockPos, StaticBlockEntityBakePlan plan) {
		return new StaticBlockEntityBakeInstance(
			blockPos,
			plan.key(),
			plan.hasStaticPortion(),
			!plan.fallbacks().isEmpty(),
			plan.fallbacks()
		);
		}

		public boolean fullyStaticAndSuppressible() {
			return hasStaticPart && !hasDynamicPart && fallbacks.isEmpty();
		}
	}
}
