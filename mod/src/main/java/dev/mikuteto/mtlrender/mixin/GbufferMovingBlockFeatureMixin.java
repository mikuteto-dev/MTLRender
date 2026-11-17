package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferFeatureSemantics;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@Mixin(MovingBlockFeatureRenderer.class)
abstract class GbufferMovingBlockFeatureMixin {
	@WrapMethod(method = "buildGroup")
	private void mtlrender$buildGroup(FeatureFrameContext context, List<MovingBlockFeatureRenderer.Submit> submits,
		Operation<Void> original) {
		Object submit = submits.isEmpty() ? null : submits.getFirst();
		GbufferFeatureSemantics.withSubmit(submit, null, () -> original.call(context, submits));
	}
}
