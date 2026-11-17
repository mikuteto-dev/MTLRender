package dev.mikuteto.mtlrender.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.culling.Visibility;
import dev.mikuteto.mtlrender.debug.AbSwitch;
import dev.mikuteto.mtlrender.weather.WeatherColumnStream;
import dev.mikuteto.mtlrender.weather.WeatherFeatureRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WeatherEffectRenderer.class)
abstract class WeatherEffectRendererMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void mtlrender$renderGpuWeather(Vec3 cameraPos, WeatherRenderState state, CallbackInfo ci) {
		if (AbSwitch.is(AbSwitch.WEATHER_GPU, Config.get().weather.gpuGeneration) && WeatherFeatureRenderer.render(cameraPos, state)) {
			ci.cancel();
		}
	}
	@Unique
	private WeatherRenderState mtlrender$state;

	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void mtlrender$beginWeatherState(WeatherRenderState state, CallbackInfo ci) {
		this.mtlrender$state = state;
		((WeatherRenderStateAccess) (Object) state).mtlrender$columns().reset();
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void mtlrender$endWeatherState(WeatherRenderState state, CallbackInfo ci) {
		this.mtlrender$state = null;
	}

	@WrapWithCondition(
		method = "extractRenderState",
		at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z")
	)
	private boolean mtlrender$cullWeather(List<WeatherEffectRenderer.ColumnInstance> columns, WeatherEffectRenderer.ColumnInstance column) {
		Minecraft minecraft = Minecraft.getInstance();
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		boolean visible = Visibility.weather(column, camera, minecraft.options.weatherRadius().get());
		if (visible && this.mtlrender$state != null && AbSwitch.is(AbSwitch.WEATHER_GPU, Config.get().weather.gpuGeneration)) {
			WeatherColumnStream stream = ((WeatherRenderStateAccess) (Object) this.mtlrender$state).mtlrender$columns();
			stream.append(column, columns == this.mtlrender$state.snowColumns);
		}
		return visible;
	}
}
