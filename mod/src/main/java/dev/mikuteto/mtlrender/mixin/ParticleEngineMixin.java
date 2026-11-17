package dev.mikuteto.mtlrender.mixin;

import java.util.Map;

import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.debug.AbSwitch;
import dev.mikuteto.mtlrender.particle.ParticleLightCache;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
abstract class ParticleEngineMixin {
	@Shadow @Final private Map<ParticleRenderType, ParticleGroup<?>> particles;

	@Inject(method = "setLevel", at = @At("HEAD"))
	private void mtlrender$invalidateLightCache(net.minecraft.client.multiplayer.ClientLevel level, CallbackInfo ci) {
		ParticleLightCache.clear();
	}

	@Inject(method = "extract", at = @At("HEAD"), cancellable = true)
	private void mtlrender$skipEmptyParticleGroups(
		ParticlesRenderState state, Frustum frustum, Camera camera, float partialTickTime, CallbackInfo ci
	) {
		ParticleLightCache.clear();
		if (AbSwitch.misc06(Config.get().misc06.emptyState) && this.particles.isEmpty()) {
			ci.cancel();
		}
	}
}
