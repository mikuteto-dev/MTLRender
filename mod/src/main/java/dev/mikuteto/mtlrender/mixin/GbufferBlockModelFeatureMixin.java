package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferFeatureSemantics;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockModelFeatureRenderer.class)
abstract class GbufferBlockModelFeatureMixin {
	@WrapOperation(
		method = "buildGroup",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/BlockModelFeatureRenderer;putPartQuads(Lnet/minecraft/client/renderer/block/dispatch/BlockStateModelPart;Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/QuadInstance;I[ILcom/mojang/blaze3d/vertex/VertexConsumer;)V"
		)
	)
	private static void mtlrender$putPartQuads(BlockStateModelPart part, PoseStack.Pose pose, QuadInstance quadInstance,
		int baseTintColor, int[] tintLayers, VertexConsumer buffer, Operation<Void> original) {
		GbufferFeatureSemantics.withSubmit(pose, null,
			() -> original.call(part, pose, quadInstance, baseTintColor, tintLayers, buffer));
	}
}
