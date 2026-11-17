package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.gui.GuiMapAtlas;
import dev.mikuteto.mtlrender.gui.GuiRenderSnapshot;
import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MapRenderer.class)
abstract class MapRendererMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void mtlrender$prepareMapAtlas(
		final MapId mapId,
		final MapItemSavedData data,
		final MapRenderState state,
		final CallbackInfo callbackInfo
	) {
		if (!Config.get().gui.mapAtlas) {
			return;
		}
		long updateToken = GuiMapAtlas.updateToken(mapId.id());
		long pixelVersion = GuiMapAtlas.version(data, data.colors, updateToken);
		long decorationVersion = 0xcbf29ce484222325L;
		for (MapRenderState.MapDecorationRenderState decoration : state.decorations) {
			decorationVersion = decorationVersion * 0x100000001b3L + decoration.x;
			decorationVersion = decorationVersion * 0x100000001b3L + decoration.y;
			decorationVersion = decorationVersion * 0x100000001b3L + decoration.rot;
			decorationVersion = decorationVersion * 0x100000001b3L + (decoration.renderOnFrame ? 1 : 0);
			decorationVersion = decorationVersion * 0x100000001b3L + (decoration.name == null ? 0 : decoration.name.hashCode());
		}
		long resourceGeneration = GuiRenderSnapshot.resourceGeneration();
		GuiMapAtlas.Slot slot = GuiMapAtlas.current().prepare(mapId.id(), data.colors, pixelVersion, resourceGeneration);
		MapRenderStateAccess access = (MapRenderStateAccess)(Object)state;
		if (slot != null) {
			access.mtlrender$setAtlas(slot.texture(), slot.u0(), slot.u1(), slot.v0(), slot.v1());
		}
		access.mtlrender$setVersions(pixelVersion, decorationVersion, resourceGeneration);
	}
}
