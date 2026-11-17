package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.world.level.block.entity.SignText;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractSignRenderer.class)
abstract class AbstractSignRendererMixin {
	@WrapWithCondition(
		method = "submit",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/blockentity/AbstractSignRenderer;submitSignText(Lnet/minecraft/client/renderer/blockentity/state/SignRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/world/level/block/entity/SignText;)V"
		)
	)
	private boolean mtlrender$facesCamera(
		AbstractSignRenderer<?> renderer,
		SignRenderState state,
		PoseStack poseStack,
		SubmitNodeCollector collector,
		SignText text
	) {
		if (text.hasGlowingText() || state.drawOutline) {
			return true;
		}
		Matrix4f pose = poseStack.last().pose();
		float facing = pose.m20() * pose.m30() + pose.m21() * pose.m31() + pose.m22() * pose.m32();
		return facing <= 0.0F;
	}
}
