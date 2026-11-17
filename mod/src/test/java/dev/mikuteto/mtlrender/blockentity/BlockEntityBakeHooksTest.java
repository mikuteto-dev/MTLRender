package dev.mikuteto.mtlrender.blockentity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class BlockEntityBakeHooksTest {
	private static final String CHEST = "net.minecraft.client.renderer.blockentity.ChestRenderer";
	private static final String BELL = "net.minecraft.client.renderer.blockentity.BellRenderer";

	@Test
	void staticPartialRetainsDynamicState() {
		StaticBlockEntityBakeRegistry registry = registry(true, new StaticBlockEntityBakePlan(
			new StaticBlockEntityBakeKey(4, "minecraft:chest", "oak"),
			List.of(part("shell")), List.of(fallback(StaticBlockEntityBakeFallback.Reason.DYNAMIC_SUBPART_RETAINED))));

		assertEquals(BlockEntityBakeHooks.Kind.STATIC_PARTIAL,
			BlockEntityBakeHooks.decide(registry, 4, "minecraft:chest", "oak", CHEST).kind());
	}

	@Test
	void dynamicOnlyIsObservable() {
		StaticBlockEntityBakeRegistry registry = registry(true, new StaticBlockEntityBakePlan(
			new StaticBlockEntityBakeKey(4, "minecraft:bell", "default"), List.of(),
			List.of(fallback(StaticBlockEntityBakeFallback.Reason.DYNAMIC_SUBPART_RETAINED))));

		assertEquals(BlockEntityBakeHooks.Kind.DYNAMIC_ONLY,
			BlockEntityBakeHooks.decide(registry, 4, "minecraft:bell", "default", BELL).kind());
	}

	@Test
	void customRendererFallsBackEvenForSupportedType() {
		StaticBlockEntityBakeRegistry registry = registry(true, new StaticBlockEntityBakePlan(
			new StaticBlockEntityBakeKey(4, "minecraft:chest", "oak"), List.of(part("shell")), List.of()));

		BlockEntityBakeHooks.Decision decision = BlockEntityBakeHooks.decide(
			registry, 4, "minecraft:chest", "oak", "example.CustomRenderer");
		assertEquals(BlockEntityBakeHooks.Kind.FALLBACK, decision.kind());
		assertEquals(StaticBlockEntityBakeFallback.Reason.CUSTOM_RENDERER, decision.plan().fallbacks().getFirst().reason());
	}

	@Test
	void staleGenerationFallsBack() {
		StaticBlockEntityBakeRegistry registry = registry(true, new StaticBlockEntityBakePlan(
			new StaticBlockEntityBakeKey(4, "minecraft:bell", "default"), List.of(), List.of()));

		BlockEntityBakeHooks.Decision decision = BlockEntityBakeHooks.decide(registry, 3,
			"minecraft:bell", "default", BELL);
		assertEquals(BlockEntityBakeHooks.Kind.FALLBACK, decision.kind());
		assertEquals(StaticBlockEntityBakeFallback.Reason.RESOURCE_GENERATION_MISMATCH,
			decision.plan().fallbacks().getFirst().reason());
	}

	@Test
	void disabledConfigPlanFallsBack() {
		StaticBlockEntityBakeKey key = new StaticBlockEntityBakeKey(4, "minecraft:chest", "oak");
		StaticBlockEntityBakeRegistry registry = registry(false,
			new StaticBlockEntityBakePlan(key, List.of(part("shell")), List.of()));

		BlockEntityBakeHooks.Decision decision = BlockEntityBakeHooks.decide(registry, 4,
			"minecraft:chest", "oak", CHEST);
		assertEquals(BlockEntityBakeHooks.Kind.FALLBACK, decision.kind());
		assertEquals(StaticBlockEntityBakeFallback.Reason.DISABLED, decision.plan().fallbacks().getFirst().reason());
	}

	@Test
	void failedBakeFallsBack() {
		StaticBlockEntityBakeRegistry registry = registry(true, new StaticBlockEntityBakePlan(
			new StaticBlockEntityBakeKey(4, "minecraft:chest", "oak"), List.of(),
			List.of(fallback(StaticBlockEntityBakeFallback.Reason.INVALID_GEOMETRY))));

		assertEquals(BlockEntityBakeHooks.Kind.FALLBACK,
			BlockEntityBakeHooks.decide(registry, 4, "minecraft:chest", "oak", CHEST).kind());
	}

	private static StaticBlockEntityBakeRegistry registry(boolean enabled, StaticBlockEntityBakePlan plan) {
		StaticBlockEntityBakeRegistry registry = new StaticBlockEntityBakeRegistry(() -> enabled);
		registry.replace(plan.key().resourceGeneration(), List.of(plan));
		return registry;
	}

	private static StaticBlockEntityBakePart part(String name) {
		return new StaticBlockEntityBakePart(name, new byte[] {1}, "test");
	}

	private static StaticBlockEntityBakeFallback fallback(StaticBlockEntityBakeFallback.Reason reason) {
		return new StaticBlockEntityBakeFallback(reason, "dynamic");
	}
}
