package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferSemanticContext;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferStage;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemFeatureRenderer.class)
abstract class GbufferItemGlintFeatureMixin {
	@WrapMethod(method = "prepareFoilSubmit")
	private void mtlrender$glint(ItemFeatureRenderer.Submit submit, Operation<Void> original) {
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enterStage(GbufferStage.Glint)) {
			original.call(submit);
		}
	}
}
