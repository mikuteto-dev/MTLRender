package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferItemSemantics;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferSemanticContext;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemStackRenderState.class)
abstract class GbufferItemContextMixin {
	@WrapMethod(method = "submit")
	private void mtlrender$submitItem(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
		int lightCoords, int overlayCoords, int outlineColor, Operation<Void> original) {
		if (!GbufferSemanticContext.isActive()) {
			original.call(poseStack, submitNodeCollector, lightCoords, overlayCoords, outlineColor);
			return;
		}
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enterItem(
			GbufferItemSemantics.stateItemId((ItemStackRenderState) (Object) this))) {
			original.call(poseStack, submitNodeCollector, lightCoords, overlayCoords, outlineColor);
		} finally {
			GbufferItemSemantics.forget((ItemStackRenderState) (Object) this);
		}
	}
}
