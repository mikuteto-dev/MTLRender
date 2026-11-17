package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferPipelineCatalog;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferSemanticContext;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferStage;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferSubmitMetadata;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@Mixin({ModelFeatureRenderer.class, BlockModelFeatureRenderer.class, ItemFeatureRenderer.class, MovingBlockFeatureRenderer.class})
abstract class GbufferModelFeatureMixin {
	@WrapMethod(method = "buildGroup")
	private void mtlrender$group(Object context, List<?> submits, Operation<Void> original) {
		Object submit = submits.isEmpty() ? null : submits.get(0);
		Object self = this;
		GbufferStage fallback = self instanceof ItemFeatureRenderer ? GbufferStage.Items
			: self instanceof MovingBlockFeatureRenderer ? GbufferStage.Blocks : GbufferStage.Entities;
		withSubmit(submit, renderType(submit), fallback, () -> original.call(context, submits));
	}

	@WrapOperation(
		method = "buildGroup",
		at = @org.spongepowered.asm.mixin.injection.At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer;prepareModel(Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$Submit;)V"
		)
	)
	private <S> void mtlrender$model(ModelFeatureRenderer renderer, ModelFeatureRenderer.Submit<S> submit,
		Operation<Void> original) {
		withSubmit(submit, submit.renderType(), GbufferStage.Entities, () -> original.call(renderer, submit));
	}

	@WrapOperation(
		method = "buildGroup",
		at = @org.spongepowered.asm.mixin.injection.At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer;prepareSubmit(Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;Z)V"
		)
	)
	private void mtlrender$item(ItemFeatureRenderer renderer, ItemFeatureRenderer.Submit submit, boolean foil,
		Operation<Void> original) {
		withSubmit(submit, null, GbufferStage.Items, () -> original.call(renderer, submit, foil));
	}

	@WrapOperation(
		method = "prepareSubmit",
		at = @org.spongepowered.asm.mixin.injection.At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer;prepareFoilSubmit(Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;)V"
		)
	)
	private void mtlrender$itemFoilSubmit(ItemFeatureRenderer renderer, ItemFeatureRenderer.Submit submit,
		Operation<Void> original) {
		withSubmit(submit, net.minecraft.client.renderer.rendertype.RenderTypes.glint(), GbufferStage.Glint,
			() -> original.call(renderer, submit));
	}

	@WrapOperation(
		method = "prepareSubmit",
		at = @org.spongepowered.asm.mixin.injection.At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer;prepareOutlineSubmit(Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;)V"
		)
	)
	private void mtlrender$itemOutlineSubmit(ItemFeatureRenderer renderer, ItemFeatureRenderer.Submit submit,
		Operation<Void> original) {
		withSubmit(submit, null, GbufferStage.Items, () -> original.call(renderer, submit));
	}

	@WrapOperation(
		method = "prepareSubmit",
		at = @org.spongepowered.asm.mixin.injection.At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer;prepareMainSubmit(Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;)V"
		)
	)
	private void mtlrender$itemMainSubmit(ItemFeatureRenderer renderer, ItemFeatureRenderer.Submit submit,
		Operation<Void> original) {
		withSubmit(submit, null, GbufferStage.Items, () -> original.call(renderer, submit));
	}

	@WrapOperation(
		method = "prepareMainSubmit",
		at = @org.spongepowered.asm.mixin.injection.At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer;getVertexBuilder(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
		)
	)
	private com.mojang.blaze3d.vertex.VertexConsumer mtlrender$itemMain(ItemFeatureRenderer renderer, RenderType renderType,
		Operation<com.mojang.blaze3d.vertex.VertexConsumer> original) {
		return original.call(renderer, renderType);
	}

	@WrapOperation(
		method = "prepareOutlineSubmit",
		at = @org.spongepowered.asm.mixin.injection.At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer;getVertexBuilder(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
		)
	)
	private com.mojang.blaze3d.vertex.VertexConsumer mtlrender$itemOutline(ItemFeatureRenderer renderer, RenderType renderType,
		Operation<com.mojang.blaze3d.vertex.VertexConsumer> original) {
		return original.call(renderer, renderType);
	}

	@WrapOperation(
		method = "prepareFoilSubmit",
		at = @org.spongepowered.asm.mixin.injection.At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer;getFoilBuffer(Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack$Pose;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
		)
	)
	private com.mojang.blaze3d.vertex.VertexConsumer mtlrender$itemFoil(ItemFeatureRenderer renderer, RenderType renderType,
		com.mojang.blaze3d.vertex.PoseStack.Pose pose, Operation<com.mojang.blaze3d.vertex.VertexConsumer> original) {
		return original.call(renderer, renderType, pose);
	}

	private static RenderType renderType(Object submit) {
		if (submit instanceof ModelFeatureRenderer.Submit<?> model) return model.renderType();
		if (submit instanceof BlockModelFeatureRenderer.Submit block) return block.renderType();
		return null;
	}

	private static void withSubmit(Object submit, RenderType renderType, GbufferStage fallback, Runnable action) {
		if (!GbufferSemanticContext.isActive()) {
			action.run();
			return;
		}
		GbufferSubmitMetadata metadata = GbufferSemanticContext.metadata(submit)
			.orElse(GbufferSubmitMetadata.stage(fallback));
		GbufferStage stage = GbufferPipelineCatalog.find(renderType == null ? null : renderType.pipeline())
			.map(spec -> spec.stage()).orElse(metadata.stage());
		GbufferSubmitMetadata refined = new GbufferSubmitMetadata(metadata.entityId(), metadata.blockEntityId(),
			metadata.currentRenderedItemId(), stage);
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enter(refined)) {
			action.run();
		}
	}
}
