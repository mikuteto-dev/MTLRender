package dev.mikuteto.mtlrender.blockentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.object.chest.ChestModel;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import dev.mikuteto.mtlrender.mixin.EntityModelSetMemoryAccess;
import dev.mikuteto.mtlrender.mixin.ModelManagerMemoryAccess;

public final class VanillaBlockEntityBakeSet {
	private static final StaticBlockEntityBakeRegistry REGISTRY = new StaticBlockEntityBakeRegistry();
	private static final StaticModelPartBaker BAKER = new StaticModelPartBaker();
	private static final String CHEST_RENDERER = "net.minecraft.client.renderer.blockentity.ChestRenderer";
	private static final String SHULKER_RENDERER = "net.minecraft.client.renderer.blockentity.ShulkerBoxRenderer";
	private static final String POT_RENDERER = "net.minecraft.client.renderer.blockentity.DecoratedPotRenderer";

	private VanillaBlockEntityBakeSet() {}

	public static StaticBlockEntityBakeRegistry registry() {
		return REGISTRY;
	}

	public static void rebuild(ModelManager manager) {
		EntityModelSet modelSet = ((ModelManagerMemoryAccess) (Object) manager).mtlrender$entityModels();
		Map<ModelLayerLocation, ?> roots = ((EntityModelSetMemoryAccess) (Object) modelSet).mtlrender$roots();
		long generation = Generation.next();
		List<StaticBlockEntityBakePlan> plans = new ArrayList<>();
		VanillaBlockEntityBakeCatalog catalog = new VanillaBlockEntityBakeCatalog(REGISTRY);
		for (String material : List.of("oak", "trapped", "ender", "christmas", "copper_unaffected", "copper_exposed", "copper_weathered", "copper_oxidized")) {
			String type = material.equals("ender") ? "minecraft:ender_chest" : material.equals("trapped") ? "minecraft:trapped_chest" : "minecraft:chest";
			String texture = material.equals("oak") ? "normal" : material;
			bakeChest(plans, catalog, modelSet, roots, generation, type, material, texture, ModelLayers.CHEST, "single");
			bakeChest(plans, catalog, modelSet, roots, generation, type, material, texture, ModelLayers.DOUBLE_CHEST_LEFT, "left");
			bakeChest(plans, catalog, modelSet, roots, generation, type, material, texture, ModelLayers.DOUBLE_CHEST_RIGHT, "right");
		}
		bakeShulkers(plans, catalog, modelSet, roots, generation);
		bakePot(plans, catalog, modelSet, roots, generation);
		for (String type : List.of("minecraft:sign", "minecraft:hanging_sign", "minecraft:bed", "minecraft:bell")) {
			String renderer = type.equals("minecraft:bed") ? null : type.equals("minecraft:sign")
				? "net.minecraft.client.renderer.blockentity.StandingSignRenderer"
				: type.equals("minecraft:hanging_sign")
					? "net.minecraft.client.renderer.blockentity.HangingSignRenderer"
					: "net.minecraft.client.renderer.blockentity.BellRenderer";
			plans.add(catalog.classify(generation, type, renderer, "default"));
		}
		REGISTRY.replace(generation, plans);
	}

	private static void bakeChest(List<StaticBlockEntityBakePlan> plans, VanillaBlockEntityBakeCatalog catalog,
		EntityModelSet modelSet, Map<ModelLayerLocation, ?> roots, long generation, String type, String material,
		String texture, ModelLayerLocation layer, String shape) {
		StaticBlockEntityBakeKey key = new StaticBlockEntityBakeKey(generation, type, material + ":" + shape);
		if (!roots.containsKey(layer)) {
			plans.add(fallback(key, StaticBlockEntityBakeFallback.Reason.MISSING_PART, layer.toString()));
			return;
		}
		StaticBlockEntityBakePlan classified = withoutParts(catalog.classify(generation, type, CHEST_RENDERER, material + ":" + shape));
		StaticBlockEntityBakePlan baked = BAKER.bake(key, new ChestModel(modelSet.bakeLayer(layer)),
			material(Sheets.CHEST_MAPPER.apply(Identifier.withDefaultNamespace(texture)), "entity_cutout_cull"), List.of("/bottom"));
		plans.add(merge(classified, baked));
	}

	private static void bakeShulkers(List<StaticBlockEntityBakePlan> plans, VanillaBlockEntityBakeCatalog catalog,
		EntityModelSet modelSet, Map<ModelLayerLocation, ?> roots, long generation) {
		for (DyeColor color : DyeColor.values()) {
			String variant = color.getName();
			bakeShulker(plans, catalog, modelSet, roots, generation, variant,
				Sheets.getShulkerBoxSprite(color));
		}
		bakeShulker(plans, catalog, modelSet, roots, generation, "default", Sheets.DEFAULT_SHULKER_TEXTURE_LOCATION);
	}

	private static void bakeShulker(List<StaticBlockEntityBakePlan> plans, VanillaBlockEntityBakeCatalog catalog,
		EntityModelSet modelSet, Map<ModelLayerLocation, ?> roots, long generation, String variant, SpriteId sprite) {
		StaticBlockEntityBakeKey key = new StaticBlockEntityBakeKey(generation, "minecraft:shulker_box", variant);
		if (!roots.containsKey(ModelLayers.SHULKER_BOX)) {
			plans.add(fallback(key, StaticBlockEntityBakeFallback.Reason.MISSING_PART, "base"));
			return;
		}
		StaticBlockEntityBakePlan classified = withoutParts(catalog.classify(generation, key.typeKey(), SHULKER_RENDERER, variant));
		StaticBlockEntityBakePlan baked = BAKER.bake(key, simple(modelSet.bakeLayer(ModelLayers.SHULKER_BOX)),
			material(sprite, "entity_cutout"), List.of("/base"));
		plans.add(merge(classified, baked));
	}

	private static void bakePot(List<StaticBlockEntityBakePlan> plans, VanillaBlockEntityBakeCatalog catalog,
		EntityModelSet modelSet, Map<ModelLayerLocation, ?> roots, long generation) {
		StaticBlockEntityBakeKey key = new StaticBlockEntityBakeKey(generation, "minecraft:decorated_pot", "default");
		if (!roots.containsKey(ModelLayers.DECORATED_POT_BASE) || !roots.containsKey(ModelLayers.DECORATED_POT_SIDES)) {
			plans.add(fallback(key, StaticBlockEntityBakeFallback.Reason.MISSING_PART, "decorated_pot"));
			return;
		}
		StaticBlockEntityBakePlan classified = withoutParts(catalog.classify(generation, key.typeKey(), POT_RENDERER, key.variantKey()));
		StaticBlockEntityBakePlan base = BAKER.bake(key, simple(modelSet.bakeLayer(ModelLayers.DECORATED_POT_BASE)),
			material(Sheets.DECORATED_POT_BASE, "entity_solid"), List.of("/neck", "/top", "/bottom"));
		plans.add(merge(classified, base));
	}

	private static Model<?> simple(ModelPart root) {
		return new Model.Simple(root, value -> null);
	}

	private static StaticModelMaterial material(SpriteId sprite, String layer) {
		return new StaticModelMaterial(sprite.atlasLocation().toString(), layer, sprite.texture().toString());
	}

	private static StaticBlockEntityBakePlan withoutParts(StaticBlockEntityBakePlan plan) {
		return new StaticBlockEntityBakePlan(plan.key(), List.of(), plan.fallbacks());
	}

	private static StaticBlockEntityBakePlan merge(StaticBlockEntityBakePlan first, StaticBlockEntityBakePlan second) {
		List<StaticBlockEntityBakePart> parts = new ArrayList<>(first.staticParts());
		parts.addAll(second.staticParts());
		List<StaticBlockEntityBakeFallback> fallbacks = new ArrayList<>(first.fallbacks());
		fallbacks.addAll(second.fallbacks());
		return new StaticBlockEntityBakePlan(first.key(), parts, fallbacks);
	}

	private static StaticBlockEntityBakePlan fallback(StaticBlockEntityBakeKey key, StaticBlockEntityBakeFallback.Reason reason, String part) {
		return new StaticBlockEntityBakePlan(key, List.of(), List.of(new StaticBlockEntityBakeFallback(reason, part)));
	}

	private static final class Generation {
		private static long value;
		private static synchronized long next() {
			return value++;
		}
	}
}
