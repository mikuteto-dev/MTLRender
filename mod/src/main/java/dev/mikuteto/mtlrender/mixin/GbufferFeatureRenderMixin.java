package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferFeatureStage;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferSemanticContext;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "net.minecraft.client.renderer.feature.FeatureRenderDispatcher$PreparedFrame")
abstract class GbufferFeatureRenderMixin {
	@WrapMethod(method = "executeSolid")
	private void mtlrender$solid(Operation<Void> original) {
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enterFeature(GbufferFeatureStage.Solid)) {
			original.call();
		}
	}

	@WrapMethod(method = "executeTranslucent")
	private void mtlrender$translucent(Operation<Void> original) {
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enterFeature(GbufferFeatureStage.Translucent)) {
			original.call();
		}
	}

	@WrapMethod(method = "executeOutline")
	private void mtlrender$outline(Operation<Void> original) {
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enterFeature(GbufferFeatureStage.Outline)) {
			original.call();
		}
	}

	@WrapMethod(method = "executeTranslucentAfterTerrain")
	private void mtlrender$afterTerrain(Operation<Void> original) {
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enterFeature(GbufferFeatureStage.AfterTerrain)) {
			original.call();
		}
	}
}
