package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.debug.AbSwitch;
import dev.mikuteto.mtlrender.render.FovGuards;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerMixin {
	@Inject(method = "getFieldOfViewModifier", at = @At("HEAD"), cancellable = true)
	private void mtlrender$skipNeutralEffectModifier(
		final boolean firstPerson,
		final float effectScale,
		final CallbackInfoReturnable<Float> cir
	) {
		AbstractClientPlayer player = (AbstractClientPlayer) (Object) this;
		boolean scoping = firstPerson && player.isUsingItem() && player.isScoping();
		if (AbSwitch.misc06(Config.get().misc06.zeroFov) && FovGuards.skipNeutralModifier(effectScale, scoping)) {
			cir.setReturnValue(1.0F);
		}
	}
}
