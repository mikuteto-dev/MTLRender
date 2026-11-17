package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.debug.AudioMemory;
import net.minecraft.client.Options;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundEngine.class)
abstract class SoundEngineMixin {
	@Shadow
	private SoundBufferLibrary soundBuffers;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void mtlrender$attachAudioMemory(SoundManager soundManager, Options options, ResourceProvider resourceProvider, CallbackInfo ci) {
		AudioMemory.global().attach(soundBuffers);
	}
}
