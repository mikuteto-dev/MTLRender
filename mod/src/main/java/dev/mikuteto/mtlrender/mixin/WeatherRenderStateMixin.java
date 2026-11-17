package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.weather.WeatherColumnStream;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WeatherRenderState.class)
abstract class WeatherRenderStateMixin {
	@Unique
	private final WeatherColumnStream mtlrender$columns = new WeatherColumnStream();

	@Inject(method = "reset", at = @At("TAIL"))
	private void mtlrender$resetColumns(CallbackInfo ci) {
		this.mtlrender$columns.reset();
	}
}
