package dev.mikuteto.mtlrender.mixin;

import java.nio.ByteBuffer;
import com.mojang.blaze3d.audio.SoundBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundBuffer.class)
abstract class SoundBufferMixin {
	@Shadow
	private ByteBuffer data;

	private ByteBuffer mtlrender$retainedData;

	@Inject(method = "getAlBuffer", at = @At("HEAD"))
	private void mtlrender$retainDecodedData(CallbackInfoReturnable<?> cir) {
		if (!Boolean.parseBoolean(System.getProperty("mtlrender.memory.audioRelease", "true")) && data != null) {
			mtlrender$retainedData = data;
		}
	}

	@Inject(method = "getAlBuffer", at = @At("RETURN"))
	private void mtlrender$restoreDecodedData(CallbackInfoReturnable<?> cir) {
		if (mtlrender$retainedData != null) {
			data = mtlrender$retainedData;
		}
	}
}
