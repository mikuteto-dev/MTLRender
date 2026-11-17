package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.particle.ParticleInstanceStore;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(QuadParticleRenderState.class)
abstract class QuadParticleRenderStateMixin {
	@Inject(method = "add", at = @At("TAIL"))
	private void mtlrender$packInstance(
		SingleQuadParticle.Layer layer,
		float x, float y, float z,
		float xRot, float yRot, float zRot, float wRot,
		float scale, float u0, float u1, float v0, float v1,
		int color, int lightCoords, CallbackInfo ci
	) {
		ParticleInstanceStore.add(
			(QuadParticleRenderState) (Object) this,
			layer, x, y, z, xRot, yRot, zRot, wRot, scale, u0, u1, v0, v1, color, lightCoords
		);
	}

	@Inject(method = "clear", at = @At("HEAD"))
	private void mtlrender$clearInstances(CallbackInfo ci) {
		ParticleInstanceStore.clear((QuadParticleRenderState) (Object) this);
	}
}
