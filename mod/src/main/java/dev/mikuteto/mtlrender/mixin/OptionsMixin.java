package dev.mikuteto.mtlrender.mixin;

import java.util.Arrays;

import dev.mikuteto.mtlrender.debug.CaptureKeyBinding;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Options.class)
abstract class OptionsMixin {
	@Shadow @Final @Mutable public KeyMapping[] keyMappings;

	@Inject(method = "load", at = @At("HEAD"))
	private void mtlrender$addCaptureKey(CallbackInfo ci) {
		for (KeyMapping mapping : keyMappings) {
			if (mapping == CaptureKeyBinding.KEY) {
				return;
			}
		}
		keyMappings = Arrays.copyOf(keyMappings, keyMappings.length + 1);
		keyMappings[keyMappings.length - 1] = CaptureKeyBinding.KEY;
	}
}
