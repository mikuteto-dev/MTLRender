package dev.mikuteto.mtlrender.mixin;

import java.util.List;
import dev.mikuteto.mtlrender.gui.GuiSpatialIndex;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.ScreenArea;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GuiRenderState.class)
abstract class GuiRenderStateMixin {
	@Inject(method = "hasIntersection", at = @At("HEAD"), cancellable = true)
	private void mtlrender$querySpatialIndex(
		ScreenRectangle bounds,
		@Nullable List<? extends ScreenArea> states,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (states instanceof GuiSpatialIndex.IndexedStates<?> indexedStates) {
			cir.setReturnValue(indexedStates.hasIntersection(bounds));
		}
	}
}
