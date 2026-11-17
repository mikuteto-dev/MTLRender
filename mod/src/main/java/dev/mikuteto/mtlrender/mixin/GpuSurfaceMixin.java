package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.system.IdleMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GpuSurface.class)
abstract class GpuSurfaceMixin {
	@WrapOperation(
		method = "blitFromTexture",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/GpuSurfaceBackend;blitFromTexture(Lcom/mojang/blaze3d/systems/CommandEncoderBackend;Lcom/mojang/blaze3d/textures/GpuTextureView;)V"
		)
	)
	private void mtlrender$blit(
		GpuSurfaceBackend surface,
		CommandEncoderBackend encoder,
		GpuTextureView texture,
		Operation<Void> original
	) {
		if (IdleMode.rendering()) {
			original.call(surface, encoder, texture);
		}
	}

	@WrapOperation(
		method = "present",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurfaceBackend;present()V")
	)
	private void mtlrender$present(GpuSurfaceBackend surface, Operation<Void> original) {
		if (IdleMode.rendering()) {
			original.call(surface);
		}
	}
}
