package dev.mikuteto.mtlrender.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.mikuteto.mtlrender.blockentity.BlockEntityBakeHooks;
import dev.mikuteto.mtlrender.debug.StaticBlockEntityBakeStats;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockEntityRenderDispatcher.class)
abstract class BlockEntityRenderDispatcherMixin {
	@Inject(method = "tryExtractRenderState", at = @At("RETURN"))
	private void mtlrender$observeExtraction(BlockEntity blockEntity, float partialTicks,
		ModelFeatureRenderer.CrumblingOverlay breakProgress, boolean isGloballyRendered,
		CallbackInfoReturnable<BlockEntityRenderState> callback) {
		BlockEntityRenderState state = callback.getReturnValue();
		if (state != null) {
			StaticBlockEntityBakeStats.extracted();
			BlockEntityRenderer<?, ?> renderer = ((BlockEntityRenderDispatcher) (Object) this).getRenderer(blockEntity);
			BlockEntityBakeHooks.observeExtraction(blockEntity, state, renderer == null ? null : renderer.getClass());
		}
	}

	@Inject(method = "submit", at = @At("HEAD"))
	private void mtlrender$observeSubmission(BlockEntityRenderState state, PoseStack poseStack,
		SubmitNodeCollector submitNodeCollector, CameraRenderState camera, CallbackInfo callback) {
		BlockEntityRenderer<?, ?> renderer = ((BlockEntityRenderDispatcher) (Object) this).getRenderer(state);
		BlockEntityBakeHooks.observeSubmission(state, renderer == null ? null : renderer.getClass());
		StaticBlockEntityBakeStats.dynamicSubmission();
	}
}
