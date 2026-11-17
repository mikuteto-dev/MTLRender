package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mikuteto.mtlrender.system.IdleMode;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
abstract class IdleRenderingMixin {
	@WrapOperation(
		method = "renderFrame",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V")
	)
	private void mtlrender$extract(GameRenderer renderer, DeltaTracker deltaTracker, boolean advanceGameTime, Operation<Void> original) {
		Minecraft minecraft = (Minecraft)(Object)this;
		if (IdleMode.shouldRender(minecraft, advanceGameTime)) {
			original.call(renderer, deltaTracker, advanceGameTime);
		}
	}

	@WrapOperation(
		method = "renderFrame",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V")
	)
	private void mtlrender$render(GameRenderer renderer, DeltaTracker deltaTracker, boolean advanceGameTime, Operation<Void> original) {
		if (IdleMode.rendering()) {
			original.call(renderer, deltaTracker, advanceGameTime);
		}
	}

}
