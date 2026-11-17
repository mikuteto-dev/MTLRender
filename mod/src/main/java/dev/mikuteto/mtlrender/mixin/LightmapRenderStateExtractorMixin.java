package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.debug.AbSwitch;
import dev.mikuteto.mtlrender.render.LightmapStateKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightmapRenderStateExtractor.class)
abstract class LightmapRenderStateExtractorMixin {
	@Shadow @Final private Minecraft minecraft;
	@Shadow private boolean needsUpdate;
	@Unique private LightmapStateKey mtlrender$previousKey;

	@Inject(method = "extract", at = @At("HEAD"))
	private void mtlrender$initialize(final LightmapRenderState renderState, final float partialTicks, final CallbackInfo ci) {
		if (this.mtlrender$previousKey == null
			&& AbSwitch.misc06(Config.get().misc06.lightmap)
			&& this.minecraft.level != null
			&& this.minecraft.player != null) {
			this.needsUpdate = true;
		}
	}

	@Inject(method = "extract", at = @At("TAIL"))
	private void mtlrender$compareState(final LightmapRenderState renderState, final float partialTicks, final CallbackInfo ci) {
		if (!renderState.needsUpdate || !AbSwitch.misc06(Config.get().misc06.lightmap)) {
			return;
		}
		ClientLevel level = this.minecraft.level;
		if (level == null || this.minecraft.player == null) {
			return;
		}
		LightmapStateKey currentKey = LightmapStateKey.of(renderState, level);
		boolean changed = this.mtlrender$previousKey == null || !this.mtlrender$previousKey.same(currentKey);
		this.mtlrender$previousKey = currentKey;
		renderState.needsUpdate = changed;
	}
}
