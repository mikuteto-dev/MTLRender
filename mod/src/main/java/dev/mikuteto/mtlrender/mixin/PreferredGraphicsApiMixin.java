package dev.mikuteto.mtlrender.mixin;

import com.mojang.blaze3d.systems.GpuBackend;
import dev.mikuteto.mtlrender.backend.MetalBackend;
import dev.mikuteto.mtlrender.config.Config;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PreferredGraphicsApi.class)
abstract class PreferredGraphicsApiMixin {
	@Inject(method = "getBackendsToTry", at = @At("RETURN"), cancellable = true)
	private void mtlrender$preferMetal(CallbackInfoReturnable<GpuBackend[]> cir) {
		if (!Config.get().enabled) {
			return;
		}
		GpuBackend[] fallback = cir.getReturnValue();
		GpuBackend[] backends = new GpuBackend[fallback.length + 1];
		backends[0] = new MetalBackend();
		System.arraycopy(fallback, 0, backends, 1, fallback.length);
		cir.setReturnValue(backends);
	}
}
