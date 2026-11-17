package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.weather.WeatherColumnStream;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WeatherRenderState.class)
public interface WeatherRenderStateAccess {
	@Accessor("mtlrender$columns")
	WeatherColumnStream mtlrender$columns();
}
