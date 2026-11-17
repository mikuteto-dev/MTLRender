package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferSemanticContext;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferStage;
import net.minecraft.client.renderer.feature.LeashFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LeashFeatureRenderer.class)
abstract class GbufferLeashFeatureMixin {
	@WrapMethod(method = "prepare")
	private void mtlrender$leashPrepare(LeashFeatureRenderer.Submit submit, Operation<Void> original) {
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enterStage(GbufferStage.Leash)) {
			original.call(submit);
		}
	}
}
