package dev.mikuteto.mtlrender.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.mikuteto.mtlrender.debug.Benchmark;
import dev.mikuteto.mtlrender.tune.SystemTune;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FramerateLimitTracker.class)
abstract class FramerateLimitTrackerMixin {
	@Shadow
	private Minecraft minecraft;

	@Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
	private void mtlrender$unfocusedLimit(CallbackInfoReturnable<Integer> cir) {
		int fps = SystemTune.unfocusedFps(minecraft);
		if (!Benchmark.enabled() && fps > 0) {
			cir.setReturnValue(fps);
		}
	}

	@Inject(method = "getThrottleReason", at = @At("HEAD"), cancellable = true)
	private void mtlrender$noThrottleWhileBenchmarking(CallbackInfoReturnable<FramerateLimitTracker.FramerateThrottleReason> cir) {
		if (Benchmark.enabled()) {
			cir.setReturnValue(FramerateLimitTracker.FramerateThrottleReason.NONE);
		}
	}
}
