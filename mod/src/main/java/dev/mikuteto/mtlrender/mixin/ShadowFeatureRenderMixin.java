package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mikuteto.mtlrender.pack.shadow.ShadowFeatureBridge;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "net.minecraft.client.renderer.feature.FeatureRenderDispatcher$PreparedFrame")
abstract class ShadowFeatureRenderMixin {
	@WrapMethod(method = "executeSolid")
	private void mtlrender$solid(Operation<Void> original) {
		try (ShadowFeatureBridge.Scope ignored = ShadowFeatureBridge.enterFeature(ShadowFeatureBridge.FeatureKind.ENTITY)) {
			original.call();
		}
	}

	@WrapMethod(method = "executeTranslucent")
	private void mtlrender$translucent(Operation<Void> original) {
		try (ShadowFeatureBridge.Scope ignored = ShadowFeatureBridge.enterFeature(ShadowFeatureBridge.FeatureKind.ENTITY)) {
			original.call();
		}
	}

	@WrapMethod(method = "executeTranslucentAfterTerrain")
	private void mtlrender$afterTerrain(Operation<Void> original) {
		try (ShadowFeatureBridge.Scope ignored = ShadowFeatureBridge.enterFeature(ShadowFeatureBridge.FeatureKind.BLOCK_ENTITY)) {
			original.call();
		}
	}
}
