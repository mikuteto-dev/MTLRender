package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.blockentity.ChestRenderIntegration;
import net.minecraft.client.model.object.chest.ChestModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChestModel.class)
abstract class ChestModelMixin {
	@Inject(method = "setupAnim", at = @At("HEAD"))
	private void mtlrender$hideStaticBody(Float open, CallbackInfo callback) {
		((ChestModel) (Object) this).root().getChild("bottom").visible = !ChestRenderIntegration.dynamicOnly();
	}
}
