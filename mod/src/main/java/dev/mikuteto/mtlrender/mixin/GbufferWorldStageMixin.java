package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferSemanticContext;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferStage;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LevelRenderer.class)
abstract class GbufferWorldStageMixin {
	@WrapMethod(method = "lambda$addSkyPass$0")
	private void mtlrender$sky(
		GpuBufferSlice skyFog,
		SkyRenderState state,
		Operation<Void> original
	) {
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enterWorld(GbufferStage.Sky)) {
			original.call(skyFog, state);
		}
	}

	@WrapMethod(method = "lambda$addCloudsPass$0")
	private void mtlrender$clouds(
		int cloudColor,
		CloudStatus cloudStatus,
		float cloudHeight,
		int cloudRange,
		Vec3 cameraPosition,
		long gameTime,
		float partialTicks,
		Operation<Void> original
	) {
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enterWorld(GbufferStage.Clouds)) {
			original.call(cloudColor, cloudStatus, cloudHeight, cloudRange, cameraPosition, gameTime, partialTicks);
		}
	}

	@WrapMethod(method = "lambda$addWeatherPass$0")
	private void mtlrender$weather(
		GpuBufferSlice fog,
		int renderDistance,
		Operation<Void> original
	) {
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enterWorld(GbufferStage.Weather)) {
			original.call(fog, renderDistance);
		}
	}
}
