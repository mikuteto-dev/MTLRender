package dev.mikuteto.mtlrender.blockentity;

import java.util.Optional;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.DyeColor;

public final class StaticBlockEntityDynamicPolicy {
	private StaticBlockEntityDynamicPolicy() {
	}

	public static Decision decide(BlockEntityRenderState state, String partKey) {
		return decide(VanillaBlockEntityBakeSet.registry(), state, partKey);
	}

	public static Decision decide(StaticBlockEntityBakeRegistry registry, BlockEntityRenderState state, String partKey) {
		if (state == null || state.breakProgress != null || state.blockPos == null) {
			return Decision.COMPLETE;
		}
		String type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(state.blockEntityType).toString();
		String variant = variant(state, type);
		Optional<StaticBlockEntityBakePublication.StaticBlockEntityBakeInstance> instance =
			registry.publishedByPositionAndType(state.blockPos.asLong(), type);
		if (instance.isEmpty()) {
			return Decision.COMPLETE;
		}
		StaticBlockEntityBakePublication.StaticBlockEntityBakeInstance value = instance.get();
		if (!value.key().variantKey().equals(variant) || !value.hasStaticPart()) {
			return Decision.COMPLETE;
		}
		boolean staticPart = value.fallbacks().stream().noneMatch(fallback -> fallback.partKey().equals(partKey));
		return staticPart ? Decision.SKIP_STATIC : Decision.RETAIN_DYNAMIC;
	}

	private static String variant(BlockEntityRenderState state, String type) {
		if (type.equals("minecraft:shulker_box") && state instanceof net.minecraft.client.renderer.blockentity.state.ShulkerBoxRenderState shulker) {
			DyeColor color = shulker.color;
			return color == null ? "default" : color.getName();
		}
		return "default";
	}

	public enum Decision {
		SKIP_STATIC,
		RETAIN_DYNAMIC,
		COMPLETE
	}
}
