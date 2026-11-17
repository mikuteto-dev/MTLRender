package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.gui.GuiMapAtlas;
import net.minecraft.client.resources.MapTextureManager;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MapTextureManager.class)
abstract class MapTextureManagerMixin {
	@Inject(method = "update", at = @At("HEAD"))
	private void mtlrender$markMapUpdate(final MapId id, final MapItemSavedData data, final CallbackInfo callbackInfo) {
		GuiMapAtlas.markUpdated(id.id());
	}

	@Inject(method = "resetData", at = @At("HEAD"))
	private void mtlrender$resetMapAtlas(final CallbackInfo callbackInfo) {
		GuiMapAtlas.invalidate();
	}
}
