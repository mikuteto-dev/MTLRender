package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererEntityOutlineMixin {
	@Unique
	private boolean mtlrender$outlineSubmitted;

	@Inject(method = "addMainPass", at = @At("HEAD"))
	private void mtlrender$rememberOutline(
		FrameGraphBuilder frame,
		FeatureRenderDispatcher.PreparedFrame featureFrame,
		GpuBufferSlice terrainFog,
		LevelRenderState levelRenderState,
		ProfilerFiller profiler,
		ChunkSectionsToRender chunkSectionsToRender,
		CallbackInfo ci
	) {
		this.mtlrender$outlineSubmitted = featureFrame.hasAnyOutline();
	}

	@WrapWithCondition(
		method = "lambda$addMainPass$0",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/blaze3d/textures/GpuTexture;D)V"
		)
	)
	private boolean mtlrender$skipOutlineTargetClear(
		CommandEncoder encoder, GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth
	) {
		return this.mtlrender$outlineSubmitted;
	}

	@WrapWithCondition(
		method = "doEntityOutline",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitAndBlendToTexture(Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuTextureView;)V"
		)
	)
	private boolean mtlrender$skipOutlineComposite(RenderTarget target, GpuTextureView colorView, GpuTextureView depthView) {
		return this.mtlrender$outlineSubmitted;
	}
}
