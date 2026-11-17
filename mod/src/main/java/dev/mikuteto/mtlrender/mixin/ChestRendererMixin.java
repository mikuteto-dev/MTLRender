package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.access.ChestRenderStateAccess;
import dev.mikuteto.mtlrender.blockentity.ChestRenderIntegration;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChestRenderer.class)
abstract class ChestRendererMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void mtlrender$reset(BlockEntity blockEntity, ChestRenderState state, float partialTicks, Vec3 cameraPosition,
		ModelFeatureRenderer.CrumblingOverlay breakProgress, CallbackInfo callback) {
		((ChestRenderStateAccess) (Object) state).mtlrender$setDynamicOnly(false);
	}

	@Inject(method = "submit", at = @At("HEAD"))
	private void mtlrender$begin(ChestRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
		CameraRenderState camera, CallbackInfo callback) {
		ChestRenderIntegration.enter(((ChestRenderStateAccess) (Object) state).mtlrender$dynamicOnly());
	}

	@Inject(method = "submit", at = @At("RETURN"))
	private void mtlrender$end(ChestRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
		CameraRenderState camera, CallbackInfo callback) {
		ChestRenderIntegration.exit();
	}
}
