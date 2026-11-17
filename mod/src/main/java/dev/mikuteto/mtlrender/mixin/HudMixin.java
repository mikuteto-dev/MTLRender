package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.debug.StatsOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
abstract class HudMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void mtlrender$drawStats(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		StatsOverlay.draw(graphics);
	}
}
