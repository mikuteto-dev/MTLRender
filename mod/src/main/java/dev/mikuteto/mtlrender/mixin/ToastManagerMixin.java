package dev.mikuteto.mtlrender.mixin;

import java.util.List;

import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.debug.AbSwitch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ToastManager.class)
abstract class ToastManagerMixin {
	@Shadow @Final private Minecraft minecraft;
	@Shadow @Final private List<?> visibleToasts;

	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void mtlrender$skipAbsentToasts(net.minecraft.client.gui.GuiGraphicsExtractor graphics, CallbackInfo ci) {
		if (AbSwitch.misc06(Config.get().misc06.emptyState)
			&& this.visibleToasts.isEmpty()
			&& (!this.minecraft.options.musicToast().get().renderToast() || this.minecraft.gui.screen() instanceof PauseScreen)) {
			ci.cancel();
		}
	}
}
