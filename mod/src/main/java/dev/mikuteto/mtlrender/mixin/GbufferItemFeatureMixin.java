package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferFeatureSemantics;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemFeatureRenderer.class)
abstract class GbufferItemFeatureMixin {
	@WrapMethod(method = "prepareSubmit")
	private void mtlrender$prepareSubmit(ItemFeatureRenderer.Submit submit, boolean foil, Operation<Void> original) {
		GbufferFeatureSemantics.withSubmit(submit, null, () -> original.call(submit, foil));
	}
}
