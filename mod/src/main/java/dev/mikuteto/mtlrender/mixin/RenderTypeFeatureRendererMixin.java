package dev.mikuteto.mtlrender.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.debug.AbSwitch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
abstract class RenderTypeFeatureRendererMixin {
	@WrapOperation(
		method = "getOrAddDraw",
		at = @At(value = "INVOKE", target = "Ljava/util/List;indexOf(Ljava/lang/Object;)I")
	)
	private int mtlrender$batchFeatureDraws(List<?> renderTypes, Object renderType, Operation<Integer> original) {
		boolean enabled = Config.get().entities.batching;
		if ("entityBatch".equals(AbSwitch.NAME)) {
			enabled = AbSwitch.b();
		}
		return enabled ? original.call(renderTypes, renderType) : -1;
	}
}
