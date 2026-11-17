package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferSemanticContext;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferStage;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferSubmitMetadata;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(BlockEntityRenderDispatcher.class)
abstract class GbufferBlockEntityContextMixin {
	@WrapMethod(method = "tryExtractRenderState")
	private <E extends BlockEntity, S extends BlockEntityRenderState> S mtlrender$captureBlockEntity(E blockEntity,
		float partialTicks, ModelFeatureRenderer.CrumblingOverlay breakProgress, boolean globallyRendered,
		Operation<S> original) {
		S state = original.call(blockEntity, partialTicks, breakProgress, globallyRendered);
		if (GbufferSemanticContext.isActive() && state != null) {
			GbufferSemanticContext.recordState(state, metadata(blockEntity));
		}
		return state;
	}

	@WrapMethod(method = "submit")
	private <S extends BlockEntityRenderState> void mtlrender$submitBlockEntity(S state, PoseStack poseStack,
		SubmitNodeCollector collector, CameraRenderState camera, Operation<Void> original) {
		if (!GbufferSemanticContext.isActive()) {
			original.call(state, poseStack, collector, camera);
			return;
		}
		GbufferSubmitMetadata metadata = GbufferSemanticContext.metadata(state)
			.orElse(metadata(state));
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enter(metadata)) {
			original.call(state, poseStack, collector, camera);
		}
	}

	private static GbufferSubmitMetadata metadata(BlockEntity blockEntity) {
		return new GbufferSubmitMetadata(0, BuiltInRegistries.BLOCK_ENTITY_TYPE.getId(blockEntity.getType()), 0,
			GbufferStage.Blocks);
	}

	private static GbufferSubmitMetadata metadata(BlockEntityRenderState state) {
		return new GbufferSubmitMetadata(0, BuiltInRegistries.BLOCK_ENTITY_TYPE.getId(state.blockEntityType), 0,
			GbufferStage.Blocks);
	}
}
