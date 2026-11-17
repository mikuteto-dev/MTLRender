package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.debug.AbSwitch;
import dev.mikuteto.mtlrender.gui.GuiItemCacheKey;
import dev.mikuteto.mtlrender.gui.GuiRenderSnapshot;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.render.DynamicAtlasAllocator;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiItemAtlas.class)
abstract class GuiItemAtlasMixin {
	@Shadow @Final private DynamicAtlasAllocator<Object> allocator;
	@Unique private final Map<DynamicAtlasAllocator.Slot, GuiItemCacheKey> mtlrender$itemKeys = new HashMap<>();

	@WrapOperation(
		method = "getOrUpdate",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/DynamicAtlasAllocator;getOrAllocate(Ljava/lang/Object;Z)Lnet/minecraft/client/gui/render/DynamicAtlasAllocator$Slot;")
	)
	private DynamicAtlasAllocator.Slot mtlrender$invalidateChangedItem(
		final DynamicAtlasAllocator<Object> allocator,
		final Object modelIdentity,
		final boolean animated,
		final Operation<DynamicAtlasAllocator.Slot> original,
		final TrackingItemStackRenderState item
	) {
		DynamicAtlasAllocator.Slot slot = original.call(allocator, modelIdentity, animated);
		if (slot == null || !AbSwitch.guiAtlas(Config.get().gui.itemAtlas)) {
			return slot;
		}
		GuiItemCacheKey key = GuiItemCacheKey.from(item, GuiRenderSnapshot.resourceGeneration(), GuiRenderSnapshot.frameGeneration());
		GuiItemCacheKey previous = this.mtlrender$itemKeys.put(slot, key);
		if (previous != null && !previous.equals(key)) {
			((DynamicAtlasAllocatorSlotAccess)(Object)slot).mtlrender$setExternalState(DynamicAtlasAllocator.SlotState.STALE);
		}
		return slot;
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void mtlrender$clearItemKeys(final CallbackInfo callbackInfo) {
		this.mtlrender$itemKeys.clear();
	}
}
