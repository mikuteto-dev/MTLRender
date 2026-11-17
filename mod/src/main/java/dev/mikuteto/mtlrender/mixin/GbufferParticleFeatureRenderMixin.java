package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferFeatureStage;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferSemanticContext;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferStage;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(QuadParticleFeatureRenderer.class)
abstract class GbufferParticleFeatureRenderMixin {
	@WrapMethod(method = "executeGroup")
	private void mtlrender$particleGroup(
		FeatureFrameContext context, int groupIndex, List<QuadParticleFeatureRenderer.Submit> submits,
		boolean strictlyOrdered, Operation<Void> original
	) {
		GbufferFeatureStage stage = particleStage(submits);
		try (GbufferSemanticContext.Scope phase = GbufferSemanticContext.enterFeature(stage);
			 GbufferSemanticContext.Scope semantic = enterParticleSemantic()) {
			original.call(context, groupIndex, submits, strictlyOrdered);
		}
	}

	private static GbufferSemanticContext.Scope enterParticleSemantic() {
		if (!GbufferSemanticContext.active()) return GbufferSemanticContext.enterFeature(GbufferFeatureStage.ParticleTranslucent);
		return GbufferSemanticContext.enter(GbufferStage.Particles);
	}

	private static GbufferFeatureStage particleStage(List<QuadParticleFeatureRenderer.Submit> submits) {
		boolean translucent = !submits.isEmpty() && submits.getFirst().translucent();
		return GbufferSemanticContext.featureStage().map(stage -> switch (stage) {
			case AfterTerrain -> GbufferFeatureStage.ParticleAfterTerrain;
			case Solid, Translucent -> translucent
				? GbufferFeatureStage.ParticleTranslucent
				: GbufferFeatureStage.ParticleSolid;
			default -> stage;
		}).orElse(translucent ? GbufferFeatureStage.ParticleTranslucent : GbufferFeatureStage.ParticleSolid);
	}
}
