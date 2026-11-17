package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.debug.Benchmark;
import dev.mikuteto.mtlrender.system.IdleMode;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void mtlrender$ignoreKeysWhileBenchmarking(long handle, int action, KeyEvent event, CallbackInfo ci) {
		IdleMode.wake();
		if (Benchmark.enabled()) {
			ci.cancel();
		}
	}

	@Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
	private void mtlrender$ignoreCharsWhileBenchmarking(long handle, CharacterEvent event, CallbackInfo ci) {
		IdleMode.wake();
		if (Benchmark.enabled()) {
			ci.cancel();
		}
	}
}
