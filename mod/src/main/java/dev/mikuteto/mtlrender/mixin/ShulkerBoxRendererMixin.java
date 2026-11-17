package dev.mikuteto.mtlrender.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.mikuteto.mtlrender.blockentity.StaticBlockEntityDynamicPolicy;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.ShulkerBoxRenderer;
import net.minecraft.client.renderer.blockentity.state.ShulkerBoxRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.sprite.SpriteId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShulkerBoxRenderer.class)
abstract class ShulkerBoxRendererMixin {
	@Inject(method = "submit(Lnet/minecraft/client/renderer/blockentity/state/ShulkerBoxRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"), cancellable = true)
	private void mtlrender$submitDynamicLid(ShulkerBoxRenderState state, PoseStack poseStack,
		SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo callback) {
		if (StaticBlockEntityDynamicPolicy.decide(state, "base") != StaticBlockEntityDynamicPolicy.Decision.SKIP_STATIC) {
			return;
		}
		try {
			ShulkerBoxRenderer renderer = (ShulkerBoxRenderer) (Object) this;
			ShulkerBoxRendererAccess access = (ShulkerBoxRendererAccess) (Object) renderer;
			ModelPart lid = access.mtlrender$model().root().getChild("lid");
			lid.setPos(0.0F, 24.0F - state.progress * 0.5F * 16.0F, 0.0F);
			lid.yRot = 270.0F * state.progress * (float) (Math.PI / 180.0);
			SpriteId sprite = state.color == null ? net.minecraft.client.renderer.Sheets.DEFAULT_SHULKER_TEXTURE_LOCATION
				: net.minecraft.client.renderer.Sheets.getShulkerBoxSprite(state.color);
			poseStack.pushPose();
			poseStack.mulPose(ShulkerBoxRenderer.modelTransform(state.direction));
			collector.submitModelPart(lid, poseStack, sprite.renderType(RenderTypes::entityCutout), state.lightCoords,
				OverlayTexture.NO_OVERLAY, access.mtlrender$sprites().get(sprite), -1, state.breakProgress, 0);
			poseStack.popPose();
			callback.cancel();
		} catch (RuntimeException exception) {
			return;
		}
	}
}
