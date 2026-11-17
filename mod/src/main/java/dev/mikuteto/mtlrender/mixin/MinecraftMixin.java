package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mikuteto.mtlrender.backend.MetalSurface;
import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.debug.BenchWorld;
import dev.mikuteto.mtlrender.debug.CaptureKeyBinding;
import dev.mikuteto.mtlrender.debug.GuiFrameCacheStats;
import dev.mikuteto.mtlrender.particle.ParticleLightCache;
import dev.mikuteto.mtlrender.pack.runtime.PackRuntimeService;
import dev.mikuteto.mtlrender.gui.GuiRenderSnapshot;
import dev.mikuteto.mtlrender.system.IdleMode;
import dev.mikuteto.mtlrender.tune.SystemTune;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.GameLoadCookie;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.systems.GpuSurface;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Shadow
	@Final
	private GpuSurface windowSurface;

	@Unique
	private boolean mtlrender$skipRendering;

	@WrapOperation(
		method = "renderFrame",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;isMinimized()Z")
	)
	private boolean mtlrender$stopWhenHidden(Window window, Operation<Boolean> original) {
		boolean minimized = original.call(window);
		Config config = Config.get();
		mtlrender$skipRendering = config.hideRenderingWhenOccluded
			&& !"offscreen".equals(config.present)
			&& ((Minecraft)(Object)this).gui.overlay() == null
			&& ((GpuSurfaceAccessor)(Object)windowSurface).mtlrender$backend() instanceof MetalSurface surface
			&& !surface.visible();
		return minimized || mtlrender$skipRendering;
	}

	@WrapWithCondition(
		method = "renderFrame",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V")
	)
	private boolean mtlrender$skipExtract(GameRenderer renderer, DeltaTracker deltaTracker, boolean advanceGameTime) {
		return !mtlrender$skipRendering;
	}

	@WrapWithCondition(
		method = "renderFrame",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V")
	)
	private boolean mtlrender$skipRender(GameRenderer renderer, DeltaTracker deltaTracker, boolean advanceGameTime) {
		return !mtlrender$skipRendering;
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void mtlrender$closePackRuntime(CallbackInfo ci) {
		PackRuntimeService.get().close();
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void mtlrender$benchWorld(CallbackInfo ci) {
		CaptureKeyBinding.tick();
		Minecraft minecraft = (Minecraft)(Object)this;
		if (minecraft.level != null && !minecraft.isPaused()) {
			IdleMode.wake();
		}
		BenchWorld.tick(minecraft);
	}

	@Inject(method = "renderFrame", at = @At("HEAD"))
	private void mtlrender$systemTune(boolean advanceGameTime, CallbackInfo ci) {
		Minecraft minecraft = (Minecraft)(Object)this;
		GuiRenderSnapshot.beginFrame();
		SystemTune.poll(minecraft);
		IdleMode.beginFrame(minecraft, advanceGameTime);
	}

	@Inject(method = "renderFrame", at = @At("TAIL"))
	private void mtlrender$endGuiFrame(boolean advanceGameTime, CallbackInfo ci) {
		GuiRenderSnapshot.endFrame();
	}

	@Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
	private void mtlrender$disconnectGuiFrame(net.minecraft.client.gui.screens.Screen screen, boolean keepResourcePacks, boolean stopSound, CallbackInfo ci) {
		GuiFrameCacheStats.recordInvalidation(GuiFrameCacheStats.InvalidationReason.DISCONNECT);
		GuiRenderSnapshot.invalidate();
	}

	@Inject(method = "clearClientLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"))
	private void mtlrender$clearClientLevelGuiFrame(net.minecraft.client.gui.screens.Screen screen, CallbackInfo ci) {
		GuiFrameCacheStats.recordInvalidation(GuiFrameCacheStats.InvalidationReason.CLIENT_LEVEL);
		GuiRenderSnapshot.invalidate();
	}

	@Inject(method = "reloadResourcePacks(ZLnet/minecraft/client/GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"))
	private void mtlrender$resourceReload(boolean isRecovery, GameLoadCookie loadCookie, CallbackInfoReturnable<?> cir) {
		GuiFrameCacheStats.recordInvalidation(GuiFrameCacheStats.InvalidationReason.RESOURCE_RELOAD);
		ParticleLightCache.clear();
		GuiRenderSnapshot.resourceReloadStarted();
		IdleMode.wake();
	}
}
