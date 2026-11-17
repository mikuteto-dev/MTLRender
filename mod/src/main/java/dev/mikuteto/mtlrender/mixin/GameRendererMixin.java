package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.mikuteto.mtlrender.backend.MetalFx;
import dev.mikuteto.mtlrender.pack.runtime.PackRuntimeService;
import dev.mikuteto.mtlrender.pack.runtime.RuntimeFrame;
import dev.mikuteto.mtlrender.terrain.TerrainRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Shadow @Final @Mutable private RenderTarget mainRenderTarget;

	@Inject(
		method = "render",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/blaze3d/textures/GpuTexture;D)V",
			ordinal = 0
		)
	)
	private void mtlrender$beginMetalFx(CallbackInfo ci) {
		this.mainRenderTarget = MetalFx.begin((GameRenderer) (Object) this, this.mainRenderTarget);
	}

	@Inject(
		method = "render",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/FogRenderer;endFrame()V")
	)
	private void mtlrender$finishMetalFx(CallbackInfo ci) {
		MetalFx.finish3d(this.mainRenderTarget);
		this.mainRenderTarget = MetalFx.nativeGuiTarget(this.mainRenderTarget);
	}

	@Inject(method = "close", at = @At("TAIL"))
	private void mtlrender$closeMetalFx(CallbackInfo ci) {
		MetalFx.close();
	}

	@Inject(
		method = "renderLevel",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
			shift = At.Shift.AFTER
		)
	)
	private void mtlrender$renderPack(DeltaTracker deltaTracker, CallbackInfo ci) {
		GameRenderer renderer = (GameRenderer) (Object) this;
		PackRuntimeService service = PackRuntimeService.get();
		RenderTarget target = renderer.mainRenderTarget();
		RuntimeFrame frame = service.activePack()
			.map(pack -> RuntimeFrame.fromTargetPlan(target.width, target.height,
				new RuntimeFrame.Viewport(0, 0, target.width, target.height),
				new RuntimeFrame.Scale(1.0f, 1.0f), pack.pack().targetPlan()))
			.orElse(null);
		service.render(new PackRuntimeService.FrameInput(Minecraft.getInstance(), renderer, target,
			renderer.gameRenderState().levelRenderState,
			renderer.gameRenderState().levelRenderState.cameraRenderState, deltaTracker), frame);
	}

	@WrapOperation(
		method = "renderLevel",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
		)
	)
	private GpuBufferSlice mtlrender$setProjection(ProjectionMatrixBuffer buffer, Matrix4f matrix, Operation<GpuBufferSlice> original) {
		Matrix4f raw = new Matrix4f(matrix);
		Matrix4f adjusted = MetalFx.projection((GameRenderer) (Object) this, matrix);
		TerrainRenderer.get().setProjection(raw);
		return original.call(buffer, adjusted);
	}
}
