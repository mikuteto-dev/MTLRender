package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferSemanticContext;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferStage;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferSubmitMetadata;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(EntityRenderDispatcher.class)
abstract class GbufferEntityContextMixin {
	@WrapMethod(method = "extractEntity")
	private <E extends Entity> EntityRenderState mtlrender$captureEntity(E entity, float partialTicks,
		Operation<EntityRenderState> original) {
		EntityRenderState state = original.call(entity, partialTicks);
		if (GbufferSemanticContext.isActive() && state != null) {
			GbufferSemanticContext.recordState(state,
				new GbufferSubmitMetadata(entity.getId(), 0, 0, GbufferStage.Entities));
		}
		return state;
	}

	@WrapMethod(method = "submit")
	private <S extends EntityRenderState> void mtlrender$submitEntity(S state, CameraRenderState camera,
		double x, double y, double z, PoseStack poseStack, SubmitNodeCollector collector,
		Operation<Void> original) {
		if (!GbufferSemanticContext.isActive()) {
			original.call(state, camera, x, y, z, poseStack, collector);
			return;
		}
		GbufferSubmitMetadata metadata = GbufferSemanticContext.metadata(state)
			.orElse(GbufferSubmitMetadata.stage(GbufferStage.Entities));
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enter(metadata)) {
			original.call(state, camera, x, y, z, poseStack, collector);
		}
	}
}
