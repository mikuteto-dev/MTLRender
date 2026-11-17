package dev.mikuteto.mtlrender.blockentity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class StaticBlockEntityBakeRegistryTest {
	@Test
	void keyAndPayloadHaveDeterministicValueSemantics() {
		StaticBlockEntityBakeKey firstKey = new StaticBlockEntityBakeKey(3, "minecraft:chest", "oak");
		StaticBlockEntityBakeKey secondKey = new StaticBlockEntityBakeKey(3, "minecraft:chest", "oak");
		byte[] source = {1, 2, 3};
		StaticBlockEntityBakePart firstPart = new StaticBlockEntityBakePart("base", source, "chest/oak");
		source[0] = 9;
		StaticBlockEntityBakePart secondPart = new StaticBlockEntityBakePart("base", new byte[] {1, 2, 3}, "chest/oak");

		assertEquals(firstKey, secondKey);
		assertEquals(firstPart, secondPart);
		assertArrayEquals(new byte[] {1, 2, 3}, firstPart.geometryPayload());
		byte[] exposed = firstPart.geometryPayload();
		exposed[1] = 8;
		assertArrayEquals(new byte[] {1, 2, 3}, firstPart.geometryPayload());
		assertNotSame(exposed, firstPart.geometryPayload());
	}

	@Test
	void planCollectionsAreImmutableAndStaticPortionIsExplicit() {
		StaticBlockEntityBakeKey key = new StaticBlockEntityBakeKey(1, "minecraft:shulker", "purple");
		StaticBlockEntityBakePart part = new StaticBlockEntityBakePart("shell", new byte[] {4}, "shulker/purple");
		StaticBlockEntityBakePlan plan = new StaticBlockEntityBakePlan(key, List.of(part), List.of(
			new StaticBlockEntityBakeFallback(StaticBlockEntityBakeFallback.Reason.DYNAMIC_SUBPART_RETAINED, "lid")));

		assertTrue(plan.hasStaticPortion());
		assertFalse(plan.isFallbackOnly());
		org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> plan.staticParts().add(part));
		org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> plan.fallbacks().clear());
	}

	@Test
	void newGenerationInvalidatesOldResults() {
		StaticBlockEntityBakeRegistry registry = new StaticBlockEntityBakeRegistry(() -> true);
		StaticBlockEntityBakeKey oldKey = new StaticBlockEntityBakeKey(7, "minecraft:bed", "red");
		StaticBlockEntityBakeKey newKey = new StaticBlockEntityBakeKey(8, "minecraft:bed", "red");
		StaticBlockEntityBakePlan oldPlan = new StaticBlockEntityBakePlan(oldKey, List.of(), List.of(
			new StaticBlockEntityBakeFallback(StaticBlockEntityBakeFallback.Reason.DYNAMIC_ONLY_RENDERER, "")));
		StaticBlockEntityBakePlan newPlan = new StaticBlockEntityBakePlan(newKey, List.of(), List.of(
			new StaticBlockEntityBakeFallback(StaticBlockEntityBakeFallback.Reason.UNSUPPORTED_RENDERER, "")));

		registry.install(oldPlan);
		assertTrue(registry.lookup(oldKey).isPresent());
		registry.install(newPlan);
		assertTrue(registry.lookup(newKey).isPresent());
		assertEquals(StaticBlockEntityBakeFallback.Reason.UNSUPPORTED_RENDERER,
			registry.lookup(newKey).orElseThrow().fallbacks().getFirst().reason());
		assertTrue(registry.lookup(oldKey).isEmpty());
		assertFalse(registry.hasStaticPortion(oldKey));
		StaticBlockEntityBakeKey customKey = new StaticBlockEntityBakeKey(8, "mod:machine", "custom");
		registry.install(new StaticBlockEntityBakePlan(customKey, List.of(), List.of(
			new StaticBlockEntityBakeFallback(StaticBlockEntityBakeFallback.Reason.CUSTOM_RENDERER, "body"))));
		assertEquals(StaticBlockEntityBakeFallback.Reason.CUSTOM_RENDERER,
			registry.lookup(customKey).orElseThrow().fallbacks().getFirst().reason());
	}

	@Test
	void publicationRequiresCurrentGenerationAndCanRemoveOnlyCurrentMesh() {
		StaticBlockEntityBakeRegistry registry = new StaticBlockEntityBakeRegistry(() -> true);
		StaticBlockEntityBakePlan oldPlan = new StaticBlockEntityBakePlan(
			new StaticBlockEntityBakeKey(4, "minecraft:bell", "default"), List.of(
				new StaticBlockEntityBakePart("body", new byte[] {1}, "bell")), List.of());
		registry.install(oldPlan);
		StaticBlockEntityBakePublication candidate = StaticBlockEntityBakePublication.candidate(12L, 99L, oldPlan);
		assertTrue(registry.tryPublish(candidate));
		assertTrue(registry.published(12L).isPresent());
		registry.install(new StaticBlockEntityBakePlan(
			new StaticBlockEntityBakeKey(5, "minecraft:bell", "default"), List.of(), List.of()));
		assertFalse(registry.tryPublish(candidate));
		assertTrue(registry.published(12L).isEmpty());
	}

	@Test
	void disabledBakingProducesExplicitFallback() {
		StaticBlockEntityBakeRegistry registry = new StaticBlockEntityBakeRegistry(() -> false);
		StaticBlockEntityBakeKey key = new StaticBlockEntityBakeKey(4, "mod:machine", "default");
		StaticBlockEntityBakePlan source = new StaticBlockEntityBakePlan(key,
			List.of(new StaticBlockEntityBakePart("body", new byte[] {1}, "machine")), List.of());

		StaticBlockEntityBakePlan installed = registry.install(source);
		assertFalse(installed.hasStaticPortion());
		assertEquals(StaticBlockEntityBakeFallback.Reason.DISABLED, installed.fallbacks().getFirst().reason());
		assertTrue(registry.lookup(key).orElseThrow().isFallbackOnly());
	}
}
