package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.debug.Benchmark;
import dev.mikuteto.mtlrender.system.IdleMode;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void mtlrender$ignoreButtonsWhileBenchmarking(long handle, MouseButtonInfo rawButtonInfo, int action, CallbackInfo ci) {
		IdleMode.wake();
		if (Benchmark.enabled()) {
			ci.cancel();
		}
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void mtlrender$ignoreScrollWhileBenchmarking(long handle, double xoffset, double yoffset, CallbackInfo ci) {
		IdleMode.wake();
		if (Benchmark.enabled()) {
			ci.cancel();
		}
	}

	@Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
	private void mtlrender$ignoreMoveWhileBenchmarking(long handle, double xpos, double ypos, CallbackInfo ci) {
		IdleMode.wake();
		if (Benchmark.enabled()) {
			ci.cancel();
		}
	}
}
