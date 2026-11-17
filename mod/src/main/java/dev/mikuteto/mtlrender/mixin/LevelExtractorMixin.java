package dev.mikuteto.mtlrender.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mikuteto.mtlrender.access.ChestRenderStateAccess;
import dev.mikuteto.mtlrender.blockentity.StaticBlockEntityRenderPolicy;
import dev.mikuteto.mtlrender.culling.Visibility;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;

@Mixin(targets = "net.minecraft.client.renderer.extract.LevelExtractor")
abstract class LevelExtractorMixin {
	@WrapWithCondition(
		method = "extractVisibleEntities",
		at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z")
	)
	private boolean mtlrender$raycastEntities(List<EntityRenderState> states, EntityRenderState state) {
		return Visibility.entity(state);
	}

	@WrapWithCondition(
		method = "extractVisibleBlockEntities",
		at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 0)
	)
	private boolean mtlrender$sectionBlockEntities(List<BlockEntityRenderState> states, BlockEntityRenderState state) {
		return Visibility.blockEntity(state);
	}

	@WrapWithCondition(
		method = "extractVisibleBlockEntities",
		at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 1)
	)
	private boolean mtlrender$globalBlockEntities(List<BlockEntityRenderState> states, BlockEntityRenderState state) {
		return Visibility.blockEntity(state);
	}

	@Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
	private void mtlrender$sectionCull(Entity entity, Frustum frustum, double camX, double camY, double camZ,
		CallbackInfoReturnable<Boolean> callback) {
		callback.setReturnValue(Visibility.entityCandidate(entity, frustum, camX, camY, camZ));
	}

	@WrapOperation(
		method = "extractVisibleBlockEntities",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;Z)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;")
	)
	private BlockEntityRenderState mtlrender$sectionCullBlockEntity(BlockEntityRenderDispatcher dispatcher, BlockEntity blockEntity, float partialTicks,
		ModelFeatureRenderer.CrumblingOverlay breakProgress, boolean globallyRendered, Operation<BlockEntityRenderState> original,
		@Local SectionRenderDispatcher.RenderSection section) {
		if (!Visibility.blockEntityCandidate(blockEntity, globallyRendered)) {
			return null;
		}
		BlockEntityRenderState state = original.call(dispatcher, blockEntity, partialTicks, breakProgress, globallyRendered);
		if (state != null && StaticBlockEntityRenderPolicy.suppress(
			section.getSectionNode(), state, globallyRendered)) {
			return null;
		}
		if (state instanceof ChestRenderState chestState) {
			((ChestRenderStateAccess) (Object) chestState).mtlrender$setDynamicOnly(
				StaticBlockEntityRenderPolicy.chestDynamicOnly(section.getSectionNode(), chestState, globallyRendered));
		}
		return state;
	}
}
