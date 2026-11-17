package dev.mikuteto.mtlrender.mixin;

import com.mojang.blaze3d.systems.CommandEncoder;
import dev.mikuteto.mtlrender.debug.FrameStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CommandEncoder.class)
abstract class CommandEncoderMixin {
	@Inject(method = "submit", at = @At("HEAD"))
	private void mtlrender$endGpuFrame(CallbackInfo ci) {
		FrameStats.get().beforeSubmit();
	}

	@Inject(method = "submit", at = @At("TAIL"))
	private void mtlrender$recordFrame(CallbackInfo ci) {
		FrameStats.get().onFrameSubmitted();
	}
}
