package dev.mikuteto.mtlrender.pack.gbuffer;

import dev.mikuteto.mtlrender.pack.PackManager;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.ids.IdMap;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.IdentityHashMap;
import java.util.Map;

public final class GbufferItemSemantics {
	private static final ThreadLocal<Map<ItemStackRenderState, Integer>> ITEM_IDS =
		ThreadLocal.withInitial(IdentityHashMap::new);

	private GbufferItemSemantics() {
	}

	public static void remember(ItemStackRenderState state, ItemStack item) {
		ITEM_IDS.get().put(state, itemId(item));
	}

	public static int itemId(ItemStack item) {
		if (item == null || item.isEmpty()) return 0;
		int registryId = BuiltInRegistries.ITEM.getId(item.getItem());
		if (registryId < 0) return 0;
		return PackManager.get().active().map(ShaderPack::idMap).map(map -> mapped(map, registryId)).orElse(0);
	}

	public static int stateItemId(ItemStackRenderState state) {
		return ITEM_IDS.get().getOrDefault(state, 0);
	}

	public static void forget(ItemStackRenderState state) {
		ITEM_IDS.get().remove(state);
	}

	private static int mapped(IdMap map, int registryId) {
		int value = map.itemId(registryId);
		return value < 0 ? 0 : value;
	}
}
