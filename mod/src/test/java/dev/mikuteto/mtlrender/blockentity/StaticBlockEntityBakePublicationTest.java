package dev.mikuteto.mtlrender.blockentity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class StaticBlockEntityBakePublicationTest {
	@Test
	void absentCandidateIsNotAPublishedPublication() {
		StaticBlockEntityBakeRegistry registry = new StaticBlockEntityBakeRegistry(() -> true);
		assertTrue(registry.published(11L).isEmpty());
	}

	@Test
	void explicitFallbackIsDynamicAndNotSuppressible() {
		StaticBlockEntityBakePlan plan = plan(4, "minecraft:chest", "oak", false, true);
		StaticBlockEntityBakePublication.StaticBlockEntityBakeInstance instance = instance(plan);

		assertFalse(instance.hasStaticPart());
		assertTrue(instance.hasDynamicPart());
		assertFalse(instance.fullyStaticAndSuppressible());
		assertEquals(StaticBlockEntityBakeFallback.Reason.DYNAMIC_SUBPART_RETAINED,
			instance.fallbacks().getFirst().reason());
	}

	@Test
	void staticOnlyIsSuppressible() {
		StaticBlockEntityBakePlan plan = plan(4, "minecraft:bell", "default", true, false);
		StaticBlockEntityBakePublication.StaticBlockEntityBakeInstance instance = instance(plan);

		assertTrue(instance.hasStaticPart());
		assertFalse(instance.hasDynamicPart());
		assertTrue(instance.fullyStaticAndSuppressible());
	}

	@Test
	void staticPlusDynamicKeepsDynamicFallback() {
		StaticBlockEntityBakePlan plan = plan(4, "minecraft:shulker_box", "purple", true, true);
		StaticBlockEntityBakePublication.StaticBlockEntityBakeInstance instance = instance(plan);

		assertTrue(instance.hasStaticPart());
		assertTrue(instance.hasDynamicPart());
		assertFalse(instance.fullyStaticAndSuppressible());
	}

	@Test
	void generationAndTypeVariantArePartOfLookup() {
		StaticBlockEntityBakeRegistry registry = new StaticBlockEntityBakeRegistry(() -> true);
		StaticBlockEntityBakePlan plan = plan(8, "minecraft:chest", "oak", true, false);
		registry.install(plan);
		registry.publish(StaticBlockEntityBakePublication.candidate(12L, 900L, plan));

		assertTrue(registry.published(12L, 900L, "minecraft:chest", "oak").isPresent());
		assertTrue(registry.published(12L, 900L, "minecraft:chest", "spruce").isEmpty());
		assertTrue(registry.published(12L, 900L, "minecraft:barrel", "oak").isEmpty());
		assertTrue(registry.published(12L).orElseThrow().isAvailableAt(8));
		assertFalse(registry.published(12L).orElseThrow().isAvailableAt(9));
	}

	@Test
	void failedReplacementLeavesOldPublicationActiveUntilSuccessfulPublish() {
		StaticBlockEntityBakeRegistry registry = new StaticBlockEntityBakeRegistry(() -> true);
		StaticBlockEntityBakePlan oldPlan = plan(3, "minecraft:bell", "default", true, false);
		StaticBlockEntityBakePlan newPlan = plan(3, "minecraft:bell", "default", true, false);
		registry.install(oldPlan);
		registry.publish(StaticBlockEntityBakePublication.candidate(2L, 100L, oldPlan));
		StaticBlockEntityBakePublication old = registry.published(2L).orElseThrow();

		StaticBlockEntityBakePublication candidate = StaticBlockEntityBakePublication.candidate(2L, 100L, newPlan);
		assertEquals(StaticBlockEntityBakePublication.State.CANDIDATE, candidate.state());
		assertEquals(old, registry.published(2L).orElseThrow());

		registry.publish(candidate);
		assertEquals(StaticBlockEntityBakePublication.State.PUBLISHED, registry.published(2L).orElseThrow().state());
		assertNotSame(old, registry.published(2L).orElseThrow());
	}

	@Test
	void reloadMakesOldPublicationUnavailableWithoutMutatingItsRecord() {
		StaticBlockEntityBakeRegistry registry = new StaticBlockEntityBakeRegistry(() -> true);
		StaticBlockEntityBakePlan oldPlan = plan(3, "minecraft:bell", "default", true, false);
		registry.install(oldPlan);
		registry.publish(StaticBlockEntityBakePublication.candidate(2L, 100L, oldPlan));
		StaticBlockEntityBakePublication old = registry.published(2L).orElseThrow();

		registry.install(plan(4, "minecraft:bell", "default", true, false));
		assertTrue(registry.published(2L).isEmpty());
		assertEquals(StaticBlockEntityBakePublication.State.PUBLISHED, old.state());
		assertEquals(StaticBlockEntityBakePublication.State.INVALIDATED, old.invalidated().state());
	}

	@Test
	void publicationAndPayloadCollectionsAreImmutable() {
		StaticBlockEntityBakePlan plan = plan(5, "minecraft:pot", "default", true, true);
		StaticBlockEntityBakePublication publication = StaticBlockEntityBakePublication.candidate(1L, 200L, plan);
		byte[] source = {1, 2, 3};
		StaticBlockEntityBakePart part = new StaticBlockEntityBakePart("base", source, "pot");
		source[0] = 9;

		assertArrayEquals(new byte[] {1, 2, 3}, part.geometryPayload());
		byte[] exposed = part.geometryPayload();
		exposed[1] = 8;
		assertArrayEquals(new byte[] {1, 2, 3}, part.geometryPayload());
		assertNotSame(exposed, part.geometryPayload());
		assertEquals(1, publication.instances().size());
		org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
			() -> publication.instances().clear());
		org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
			() -> publication.instances().getFirst().fallbacks().clear());
	}

	private static StaticBlockEntityBakePublication.StaticBlockEntityBakeInstance instance(StaticBlockEntityBakePlan plan) {
		return StaticBlockEntityBakePublication.candidate(1L, 10L, plan).instances().getFirst();
	}

	private static StaticBlockEntityBakePlan plan(long generation, String type, String variant, boolean staticPart, boolean dynamicPart) {
		return new StaticBlockEntityBakePlan(
			new StaticBlockEntityBakeKey(generation, type, variant),
			staticPart ? List.of(new StaticBlockEntityBakePart("base", new byte[] {1}, "material")) : List.of(),
			dynamicPart ? List.of(new StaticBlockEntityBakeFallback(
				StaticBlockEntityBakeFallback.Reason.DYNAMIC_SUBPART_RETAINED, "moving")) : List.of()
		);
	}
}
