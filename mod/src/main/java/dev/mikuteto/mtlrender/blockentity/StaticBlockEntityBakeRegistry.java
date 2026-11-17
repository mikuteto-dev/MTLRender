package dev.mikuteto.mtlrender.blockentity;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;

import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.debug.AbSwitch;
import dev.mikuteto.mtlrender.debug.StaticBlockEntityBakeStats;

public final class StaticBlockEntityBakeRegistry {
	private final Map<StaticBlockEntityBakeKey, StaticBlockEntityBakePlan> plans = new HashMap<>();
	private final Map<Long, StaticBlockEntityBakePublication> publications = new HashMap<>();
	private final BooleanSupplier bakingEnabled;
	private Long generation;

	public StaticBlockEntityBakeRegistry() {
		this(() -> AbSwitch.is(AbSwitch.BLOCK_ENTITY_BAKE,
			Config.get().blockEntities.bake && Config.get().blockEntities.staticBake));
	}

	public StaticBlockEntityBakeRegistry(BooleanSupplier bakingEnabled) {
		this.bakingEnabled = Objects.requireNonNull(bakingEnabled, "bakingEnabled");
	}

	public synchronized void replace(long resourceGeneration, Collection<StaticBlockEntityBakePlan> source) {
		Objects.requireNonNull(source, "source");
		Map<StaticBlockEntityBakeKey, StaticBlockEntityBakePlan> replacement = new HashMap<>();
		for (StaticBlockEntityBakePlan plan : source) {
			Objects.requireNonNull(plan, "plan");
			if (plan.key().resourceGeneration() != resourceGeneration) {
				throw new IllegalArgumentException("plan generation does not match replacement generation");
			}
			replacement.put(plan.key(), plan);
		}
		plans.clear();
		plans.putAll(replacement);
		publications.clear();
		generation = resourceGeneration;
	}

	public synchronized StaticBlockEntityBakePlan install(StaticBlockEntityBakePlan plan) {
		Objects.requireNonNull(plan, "plan");
		long resourceGeneration = plan.key().resourceGeneration();
		if (generation == null || generation.longValue() != resourceGeneration) {
			plans.clear();
			publications.clear();
			generation = resourceGeneration;
		}
		plans.put(plan.key(), plan);
		return enabled(plan);
	}

	public synchronized Optional<StaticBlockEntityBakePlan> lookup(StaticBlockEntityBakeKey key) {
		Objects.requireNonNull(key, "key");
		if (generation == null || generation.longValue() != key.resourceGeneration()) {
			return Optional.empty();
		}
		StaticBlockEntityBakePlan plan = plans.get(key);
		return plan == null ? Optional.empty() : Optional.of(enabled(plan));
	}

	public synchronized boolean hasStaticPortion(StaticBlockEntityBakeKey key) {
		return lookup(key).map(StaticBlockEntityBakePlan::hasStaticPortion).orElse(false);
	}

	public synchronized OptionalLong generation() {
		return generation == null ? OptionalLong.empty() : OptionalLong.of(generation);
	}

	public synchronized void publish(StaticBlockEntityBakePublication publication) {
		if (!tryPublish(publication)) {
			throw new IllegalArgumentException("publication generation is not current");
		}
	}

	public synchronized boolean tryPublish(StaticBlockEntityBakePublication publication) {
		Objects.requireNonNull(publication, "publication");
		if (publication.state() != StaticBlockEntityBakePublication.State.CANDIDATE) {
			throw new IllegalArgumentException("only a candidate can be published");
		}
		if (!isCurrentGeneration(publication.resourceGeneration())) {
			return false;
		}
		publications.put(publication.sectionKey(), publication.published());
		StaticBlockEntityBakeStats.staticPublished();
		return true;
	}

	public synchronized boolean isCurrent(StaticBlockEntityBakePublication publication) {
		Objects.requireNonNull(publication, "publication");
		return isCurrentGeneration(publication.resourceGeneration());
	}

	public synchronized void remove(long sectionKey, StaticBlockEntityBakePublication expected) {
		StaticBlockEntityBakePublication current = publications.get(sectionKey);
		if (expected == null || current == expected || current != null && current.equals(expected.published())) {
			publications.remove(sectionKey);
		}
	}

	public synchronized Optional<StaticBlockEntityBakePublication> published(long sectionKey) {
		if (generation == null) {
			return Optional.empty();
		}
		StaticBlockEntityBakePublication publication = publications.get(sectionKey);
		if (publication == null || !publication.isAvailableAt(generation)) {
			return Optional.empty();
		}
		return bakingEnabled.getAsBoolean() ? Optional.of(publication) : Optional.empty();
	}

	public synchronized Optional<StaticBlockEntityBakePublication.StaticBlockEntityBakeInstance> published(
		long sectionKey, long blockPos, String typeKey, String variantKey) {
		return published(sectionKey).flatMap(publication -> publication.lookup(blockPos, typeKey, variantKey));
	}

	public synchronized Optional<StaticBlockEntityBakePublication.StaticBlockEntityBakeInstance> publishedByPositionAndType(
		long sectionKey, long blockPos, String typeKey) {
		return published(sectionKey).flatMap(publication -> publication.instances().stream()
			.filter(instance -> instance.blockPos() == blockPos
				&& instance.key().typeKey().equals(typeKey))
			.findFirst());
	}

	public synchronized Optional<StaticBlockEntityBakePublication.StaticBlockEntityBakeInstance> publishedByPositionAndType(
		long blockPos, String typeKey) {
		if (publications.isEmpty() || !bakingEnabled.getAsBoolean()) {
			return Optional.empty();
		}
		return publications.values().stream()
			.filter(StaticBlockEntityBakePublication::isPublished)
			.flatMap(publication -> publication.instances().stream())
			.filter(instance -> instance.blockPos() == blockPos && instance.key().typeKey().equals(typeKey))
			.findFirst();
	}

	public synchronized void clear() {
		plans.clear();
		publications.clear();
		generation = null;
	}

	private boolean isCurrentGeneration(long resourceGeneration) {
		return generation != null && generation.longValue() == resourceGeneration;
	}

	private StaticBlockEntityBakePlan enabled(StaticBlockEntityBakePlan plan) {
		return bakingEnabled.getAsBoolean()
			? plan
			: new StaticBlockEntityBakePlan(plan.key(), List.of(), List.of(
				new StaticBlockEntityBakeFallback(StaticBlockEntityBakeFallback.Reason.DISABLED, "")));
	}

}
