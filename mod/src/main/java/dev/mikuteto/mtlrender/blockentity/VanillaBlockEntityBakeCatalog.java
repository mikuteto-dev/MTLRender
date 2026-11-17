package dev.mikuteto.mtlrender.blockentity;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class VanillaBlockEntityBakeCatalog {
	private static final String CHEST_RENDERER = "net.minecraft.client.renderer.blockentity.ChestRenderer";
	private static final String SIGN_RENDERER = "net.minecraft.client.renderer.blockentity.StandingSignRenderer";
	private static final String HANGING_SIGN_RENDERER = "net.minecraft.client.renderer.blockentity.HangingSignRenderer";
	private static final String BELL_RENDERER = "net.minecraft.client.renderer.blockentity.BellRenderer";
	private static final String SHULKER_RENDERER = "net.minecraft.client.renderer.blockentity.ShulkerBoxRenderer";
	private static final String POT_RENDERER = "net.minecraft.client.renderer.blockentity.DecoratedPotRenderer";

	private static final Set<String> CHEST_MATERIALS = Set.of(
		"default", "oak", "regular", "trapped", "ender", "christmas",
		"copper_unaffected", "copper_exposed", "copper_weathered", "copper_oxidized"
	);
	private static final Map<String, String> RENDERERS = Map.of(
		"minecraft:chest", CHEST_RENDERER,
		"minecraft:ender_chest", CHEST_RENDERER,
		"minecraft:trapped_chest", CHEST_RENDERER,
		"minecraft:sign", SIGN_RENDERER,
		"minecraft:hanging_sign", HANGING_SIGN_RENDERER,
		"minecraft:bell", BELL_RENDERER,
		"minecraft:shulker_box", SHULKER_RENDERER,
		"minecraft:decorated_pot", POT_RENDERER
	);

	private final StaticBlockEntityBakeRegistry registry;

	public VanillaBlockEntityBakeCatalog(StaticBlockEntityBakeRegistry registry) {
		this.registry = Objects.requireNonNull(registry, "registry");
	}

	public StaticBlockEntityBakePlan classify(long resourceGeneration, String typeKey, Class<?> rendererClass, String variantKey) {
		Objects.requireNonNull(typeKey, "typeKey");
		Objects.requireNonNull(variantKey, "variantKey");
		String rendererName = rendererClass == null ? null : rendererClass.getName();
		return classify(resourceGeneration, typeKey, rendererName, variantKey);
	}

	public StaticBlockEntityBakePlan classify(long resourceGeneration, String typeKey, String rendererClassName, String variantKey) {
		Objects.requireNonNull(typeKey, "typeKey");
		Objects.requireNonNull(variantKey, "variantKey");
		StaticBlockEntityBakeKey key = new StaticBlockEntityBakeKey(resourceGeneration, typeKey, variantKey);
		StaticBlockEntityBakePlan plan = build(key, rendererClassName);
		return registry.install(plan);
	}

	public StaticBlockEntityBakeRegistry registry() {
		return registry;
	}

	public static boolean isVanillaRenderer(String typeKey, Class<?> rendererClass) {
		return isVanillaRenderer(typeKey, rendererClass == null ? null : rendererClass.getName());
	}

	public static boolean isVanillaRenderer(String typeKey, String rendererClassName) {
		if (typeKey.equals("minecraft:bed")) {
			return rendererClassName == null;
		}
		String expected = RENDERERS.get(typeKey);
		return expected != null && expected.equals(rendererClassName);
	}

	private StaticBlockEntityBakePlan build(StaticBlockEntityBakeKey key, String rendererClassName) {
		if (key.typeKey().equals("minecraft:bed")) {
			return rendererClassName == null
				? terrainPlan(key, "bed-block-model")
				: fallbackPlan(key, StaticBlockEntityBakeFallback.Reason.CUSTOM_RENDERER, "renderer");
		}
		String expectedRenderer = RENDERERS.get(key.typeKey());
		if (expectedRenderer == null) {
			return fallbackPlan(key, StaticBlockEntityBakeFallback.Reason.UNSUPPORTED_RENDERER, "");
		}
		if (!expectedRenderer.equals(rendererClassName)) {
			return fallbackPlan(key, rendererClassName == null
				? StaticBlockEntityBakeFallback.Reason.UNSUPPORTED_RENDERER
				: StaticBlockEntityBakeFallback.Reason.CUSTOM_RENDERER, "renderer");
		}
		return switch (key.typeKey()) {
			case "minecraft:chest", "minecraft:ender_chest", "minecraft:trapped_chest" -> chestPlan(key);
			case "minecraft:sign", "minecraft:hanging_sign" -> signPlan(key);
			case "minecraft:bell" -> dynamicPlan(key, "bell-body-motion");
			case "minecraft:shulker_box" -> shulkerPlan(key);
			case "minecraft:decorated_pot" -> potPlan(key);
			default -> fallbackPlan(key, StaticBlockEntityBakeFallback.Reason.UNSUPPORTED_RENDERER, "");
		};
	}

	private StaticBlockEntityBakePlan chestPlan(StaticBlockEntityBakeKey key) {
		String material = key.variantKey().split(":", 2)[0];
		if (!CHEST_MATERIALS.contains(material)) {
			return fallbackPlan(key, StaticBlockEntityBakeFallback.Reason.DYNAMIC_SUBPART_RETAINED, "material");
		}
		return new StaticBlockEntityBakePlan(key, List.of(part("shell", "chest-shell")), List.of(
			fallback("lid"), fallback("lock"), fallback("open"), fallback("double-chest"),
			fallback("break-overlay"), fallback("light"), fallback("material")));
	}

	private StaticBlockEntityBakePlan signPlan(StaticBlockEntityBakeKey key) {
		return new StaticBlockEntityBakePlan(key, List.of(), List.of(
			new StaticBlockEntityBakeFallback(StaticBlockEntityBakeFallback.Reason.TERRAIN_OWNED, "board-post"),
			fallback("text"), fallback("filtering"), fallback("outline")));
	}

	private StaticBlockEntityBakePlan shulkerPlan(StaticBlockEntityBakeKey key) {
		return new StaticBlockEntityBakePlan(key, List.of(part("base", "shulker-base")), List.of(
			fallback("lid"), fallback("progress"), fallback("color"), fallback("sprite"), fallback("break-overlay")));
	}

	private StaticBlockEntityBakePlan potPlan(StaticBlockEntityBakeKey key) {
		return new StaticBlockEntityBakePlan(key, List.of(part("neck", "decorated-pot-base"),
			part("top", "decorated-pot-base"), part("bottom", "decorated-pot-base")), List.of(
			fallback("front"), fallback("back"), fallback("left"), fallback("right"), fallback("decorations"),
			fallback("material"), fallback("wobble"), fallback("direction"), fallback("break-overlay")));
	}

	private StaticBlockEntityBakePlan dynamicPlan(StaticBlockEntityBakeKey key, String partKey) {
		return new StaticBlockEntityBakePlan(key, List.of(), List.of(fallback(partKey), fallback("break-overlay")));
	}

	private StaticBlockEntityBakePlan terrainPlan(StaticBlockEntityBakeKey key, String partKey) {
		return new StaticBlockEntityBakePlan(key, List.of(), List.of(
			new StaticBlockEntityBakeFallback(StaticBlockEntityBakeFallback.Reason.TERRAIN_OWNED, partKey)));
	}

	private StaticBlockEntityBakePlan fallbackPlan(StaticBlockEntityBakeKey key, StaticBlockEntityBakeFallback.Reason reason, String partKey) {
		return new StaticBlockEntityBakePlan(key, List.of(), List.of(new StaticBlockEntityBakeFallback(reason, partKey)));
	}

	private static StaticBlockEntityBakePart part(String partKey, String materialKey) {
		return new StaticBlockEntityBakePart(partKey, new byte[0], materialKey);
	}

	private static StaticBlockEntityBakeFallback fallback(String partKey) {
		return new StaticBlockEntityBakeFallback(StaticBlockEntityBakeFallback.Reason.DYNAMIC_SUBPART_RETAINED, partKey);
	}
}
