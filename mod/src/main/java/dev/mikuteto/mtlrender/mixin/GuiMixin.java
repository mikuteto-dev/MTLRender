package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.debug.AbSwitch;
import dev.mikuteto.mtlrender.debug.GuiFrameCacheStats;
import dev.mikuteto.mtlrender.gui.GuiFrameCacheKey;
import dev.mikuteto.mtlrender.gui.GuiRenderSnapshot;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Gui.class)
abstract class GuiMixin {
	@Shadow @Final private Minecraft minecraft;
	@Shadow @Final private GuiRenderState guiRenderState;
	@Shadow @Nullable private Screen screen;
	@Shadow @Nullable private Overlay overlay;
	@Unique private boolean mtlrender$resourcesLoaded = true;

	@Inject(method = "setScreen(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"))
	private void mtlrender$invalidateScreen(net.minecraft.client.gui.screens.Screen screen, CallbackInfo ci) {
		GuiFrameCacheStats.recordInvalidation(GuiFrameCacheStats.InvalidationReason.SCREEN);
		GuiRenderSnapshot.invalidate();
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void mtlrender$publishGuiKey(
		DeltaTracker deltaTracker,
		boolean shouldRenderLevel,
		boolean resourcesLoaded,
		CallbackInfo ci
	) {
		GuiFrameCacheStats.recordExtraction();
		if (this.mtlrender$resourcesLoaded && !resourcesLoaded) {
			GuiFrameCacheStats.recordInvalidation(GuiFrameCacheStats.InvalidationReason.RESOURCE_RELOAD);
			GuiRenderSnapshot.resourceReloadStarted();
		}
		this.mtlrender$resourcesLoaded = resourcesLoaded;
		var window = this.minecraft.getWindow();
		GuiFrameCacheKey key = GuiRenderSnapshot.capture(
			this.guiRenderState,
			(int)this.minecraft.mouseHandler.getScaledXPos(window),
			(int)this.minecraft.mouseHandler.getScaledYPos(window),
			window.getWidth(),
			window.getHeight(),
			window.getGuiScale(),
			System.identityHashCode(this.screen),
			System.identityHashCode(this.overlay),
			GuiRenderSnapshot.resourceGeneration(),
			System.identityHashCode(this.minecraft.level)
		);
		boolean enabled = Config.get().gui.frameCache;
		if ("guiFrame".equals(AbSwitch.NAME)) {
			enabled = AbSwitch.b();
		}
		GuiRenderSnapshot.publish(key, enabled);
	}
}
