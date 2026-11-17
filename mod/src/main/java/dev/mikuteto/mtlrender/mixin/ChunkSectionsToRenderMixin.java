package dev.mikuteto.mtlrender.mixin;

import com.mojang.blaze3d.textures.GpuSampler;
import dev.mikuteto.mtlrender.terrain.TerrainRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkSectionsToRender.class)
abstract class ChunkSectionsToRenderMixin {
	@Inject(method = "renderGroup", at = @At("HEAD"), cancellable = true)
	private void mtlrender$renderTerrain(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
		ChunkSectionsToRender self = (ChunkSectionsToRender) (Object) this;
		TerrainRenderer renderer = TerrainRenderer.get();
		if (renderer.owns(self)) {
			renderer.renderGroup(self, group, sampler);
			ci.cancel();
		}
	}
}
