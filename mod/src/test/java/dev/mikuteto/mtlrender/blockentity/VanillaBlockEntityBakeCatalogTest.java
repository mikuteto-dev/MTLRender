package dev.mikuteto.mtlrender.blockentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VanillaBlockEntityBakeCatalogTest {
	private static final String CHEST = "net.minecraft.client.renderer.blockentity.ChestRenderer";
	private static final String SIGN = "net.minecraft.client.renderer.blockentity.StandingSignRenderer";
	private static final String HANGING_SIGN = "net.minecraft.client.renderer.blockentity.HangingSignRenderer";
	private static final String BELL = "net.minecraft.client.renderer.blockentity.BellRenderer";
	private static final String SHULKER = "net.minecraft.client.renderer.blockentity.ShulkerBoxRenderer";
	private static final String POT = "net.minecraft.client.renderer.blockentity.DecoratedPotRenderer";

	@Test
	void chestKeepsAnimationAndStateOutOfStaticPart() {
		StaticBlockEntityBakePlan plan = catalog().classify(1, "minecraft:chest", CHEST, "oak");

		assertParts(plan, "shell");
		assertFallbacks(plan, "lid", "lock", "open", "double-chest", "break-overlay", "light", "material");
	}

	@Test
	void unsupportedChestMaterialFallsBackWithoutStaticGeometry() {
		StaticBlockEntityBakePlan plan = catalog().classify(1, "minecraft:chest", CHEST, "modded_material");

		assertFalse(plan.hasStaticPortion());
		assertEquals(StaticBlockEntityBakeFallback.Reason.DYNAMIC_SUBPART_RETAINED, plan.fallbacks().getFirst().reason());
		assertEquals("material", plan.fallbacks().getFirst().partKey());
	}

	@Test
	void signsKeepTerrainGeometryAndTextDynamic() {
		StaticBlockEntityBakePlan standing = catalog().classify(1, "minecraft:sign", SIGN, "oak");
		StaticBlockEntityBakePlan hanging = catalog().classify(1, "minecraft:hanging_sign", HANGING_SIGN, "oak");

		assertFalse(standing.hasStaticPortion());
		assertFalse(hanging.hasStaticPortion());
		assertEquals(StaticBlockEntityBakeFallback.Reason.TERRAIN_OWNED, standing.fallbacks().getFirst().reason());
		assertEquals(StaticBlockEntityBakeFallback.Reason.TERRAIN_OWNED, hanging.fallbacks().getFirst().reason());
		assertFallbacks(standing, "board-post", "text", "filtering", "outline");
	}

	@Test
	void bedsAreTerrainOwnedWithoutARegisteredRenderer() {
		StaticBlockEntityBakePlan plan = catalog().classify(1, "minecraft:bed", (String) null, "red");

		assertFalse(plan.hasStaticPortion());
		assertEquals(StaticBlockEntityBakeFallback.Reason.TERRAIN_OWNED, plan.fallbacks().getFirst().reason());
		assertEquals("bed-block-model", plan.fallbacks().getFirst().partKey());
	}

	@Test
	void bellDoesNotBakeAnimatedHierarchy() {
		StaticBlockEntityBakePlan plan = catalog().classify(1, "minecraft:bell", BELL, "default");

		assertFalse(plan.hasStaticPortion());
		assertFallbacks(plan, "bell-body-motion", "break-overlay");
	}

	@Test
	void shulkerSeparatesBaseFromLidAndVariant() {
		StaticBlockEntityBakePlan plan = catalog().classify(1, "minecraft:shulker_box", SHULKER, "purple");

		assertParts(plan, "base");
		assertFallbacks(plan, "lid", "progress", "color", "sprite", "break-overlay");
	}

	@Test
	void decoratedPotSeparatesBaseFromDecorationAndWobble() {
		StaticBlockEntityBakePlan plan = catalog().classify(1, "minecraft:decorated_pot", POT, "front-back-left-right");

		assertParts(plan, "neck", "top", "bottom");
		assertFallbacks(plan, "front", "back", "left", "right", "decorations", "material", "wobble", "direction", "break-overlay");
	}

	@Test
	void customRendererAndUnknownTypeAreExplicitFallbacks() {
		StaticBlockEntityBakePlan custom = catalog().classify(1, "minecraft:chest", "example.CustomChestRenderer", "oak");
		StaticBlockEntityBakePlan unknown = catalog().classify(1, "minecraft:unknown", (String) null, "default");

		assertFalse(custom.hasStaticPortion());
		assertEquals(StaticBlockEntityBakeFallback.Reason.CUSTOM_RENDERER, custom.fallbacks().getFirst().reason());
		assertFalse(unknown.hasStaticPortion());
		assertEquals(StaticBlockEntityBakeFallback.Reason.UNSUPPORTED_RENDERER, unknown.fallbacks().getFirst().reason());
	}

	private static VanillaBlockEntityBakeCatalog catalog() {
		return new VanillaBlockEntityBakeCatalog(new StaticBlockEntityBakeRegistry(() -> true));
	}

	private static void assertParts(StaticBlockEntityBakePlan plan, String... expected) {
		assertEquals(expected.length, plan.staticParts().size());
		for (String part : expected) {
			assertTrue(plan.staticParts().stream().anyMatch(value -> value.partKey().equals(part)));
		}
	}

	private static void assertFallbacks(StaticBlockEntityBakePlan plan, String... expected) {
		assertEquals(expected.length, plan.fallbacks().size());
		for (String part : expected) {
			assertTrue(plan.fallbacks().stream().anyMatch(value -> value.partKey().equals(part)));
		}
	}
}
