package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.client.SectionCompileCachePool;
import dev.mikuteto.mtlrender.terrain.TerrainRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Options;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	@Inject(method = "prepareChunkRenders", at = @At("HEAD"), cancellable = true)
	private void mtlrender$prepareTerrain(Matrix4fc modelViewMatrix, CallbackInfoReturnable<ChunkSectionsToRender> cir) {
		ChunkSectionsToRender prepared = TerrainRenderer.get().prepare((LevelRenderer) (Object) this, modelViewMatrix);
		if (prepared != null) {
			cir.setReturnValue(prepared);
		}
	}

	@Inject(method = "invalidateCompiledGeometry", at = @At("TAIL"))
	private void mtlrender$invalidateCompiledGeometry(ClientLevel level, Options options, Camera camera, BlockColors blockColors, CallbackInfo ci) {
		TerrainRenderer.get().markAllDirty();
	}

	@Inject(method = "resetLevelRenderData", at = @At("TAIL"))
	private void mtlrender$resetLevelRenderData(CallbackInfo ci) {
		TerrainRenderer.get().markAllDirty();
		SectionCompileCachePool.reset();
	}
}
