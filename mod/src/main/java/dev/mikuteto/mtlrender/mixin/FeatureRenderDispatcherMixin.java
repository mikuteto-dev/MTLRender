package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.access.FeaturePreparedFrameAccess;
import dev.mikuteto.mtlrender.entity.EntityFeatureRenderer;
import dev.mikuteto.mtlrender.particle.ParticleFeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererMap;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FeatureRenderDispatcher.class)
abstract class FeatureRenderDispatcherMixin {
	@Shadow @Final private FeatureRendererMap featureRenderers;
	@Shadow @Final private FeatureRenderDispatcher.PreparedFrame preparedFrame;

	@Inject(
		method = "prepareFrameWithContext",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeStorage;drainPhases(Ljava/util/function/Consumer;)V", shift = At.Shift.AFTER)
	)
	private void mtlrender$prepareFeatureCandidates(CallbackInfo ci) {
		FeatureRenderer<?> renderer = this.featureRenderers.get(ModelFeatureRenderer.TYPE);
		if (renderer instanceof EntityFeatureRenderer entityRenderer) {
			entityRenderer.prepareCandidates(((FeaturePreparedFrameAccess) this.preparedFrame).mtlrender$allSubmits());
		}
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void mtlrender$useEntityBatching(CallbackInfo ci) {
		this.featureRenderers.put(ModelFeatureRenderer.TYPE, new EntityFeatureRenderer());
		this.featureRenderers.put(QuadParticleFeatureRenderer.TYPE, new ParticleFeatureRenderer());
	}
}
