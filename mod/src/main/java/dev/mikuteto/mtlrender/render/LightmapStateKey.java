package dev.mikuteto.mtlrender.render;

import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3fc;

public record LightmapStateKey(
	float blockFactor,
	float skyFactor,
	float nightVisionEffectIntensity,
	float darknessEffectScale,
	float bossOverlayWorldDarkening,
	float brightness,
	float blockLightTintX,
	float blockLightTintY,
	float blockLightTintZ,
	float skyLightColorX,
	float skyLightColorY,
	float skyLightColorZ,
	float ambientColorX,
	float ambientColorY,
	float ambientColorZ,
	float nightVisionColorX,
	float nightVisionColorY,
	float nightVisionColorZ,
	Object dimension
) {
	public static LightmapStateKey of(final LightmapRenderState state, final Object dimension) {
		return new LightmapStateKey(
			state.blockFactor,
			state.skyFactor,
			state.nightVisionEffectIntensity,
			state.darknessEffectScale,
			state.bossOverlayWorldDarkening,
			state.brightness,
			component(state.blockLightTint, 0),
			component(state.blockLightTint, 1),
			component(state.blockLightTint, 2),
			component(state.skyLightColor, 0),
			component(state.skyLightColor, 1),
			component(state.skyLightColor, 2),
			component(state.ambientColor, 0),
			component(state.ambientColor, 1),
			component(state.ambientColor, 2),
			component(state.nightVisionColor, 0),
			component(state.nightVisionColor, 1),
			component(state.nightVisionColor, 2),
			dimension
		);
	}

	public boolean same(final LightmapStateKey other) {
		return other != null
			&& Float.floatToIntBits(this.blockFactor) == Float.floatToIntBits(other.blockFactor)
			&& Float.floatToIntBits(this.skyFactor) == Float.floatToIntBits(other.skyFactor)
			&& Float.floatToIntBits(this.nightVisionEffectIntensity) == Float.floatToIntBits(other.nightVisionEffectIntensity)
			&& Float.floatToIntBits(this.darknessEffectScale) == Float.floatToIntBits(other.darknessEffectScale)
			&& Float.floatToIntBits(this.bossOverlayWorldDarkening) == Float.floatToIntBits(other.bossOverlayWorldDarkening)
			&& Float.floatToIntBits(this.brightness) == Float.floatToIntBits(other.brightness)
			&& Float.floatToIntBits(this.blockLightTintX) == Float.floatToIntBits(other.blockLightTintX)
			&& Float.floatToIntBits(this.blockLightTintY) == Float.floatToIntBits(other.blockLightTintY)
			&& Float.floatToIntBits(this.blockLightTintZ) == Float.floatToIntBits(other.blockLightTintZ)
			&& Float.floatToIntBits(this.skyLightColorX) == Float.floatToIntBits(other.skyLightColorX)
			&& Float.floatToIntBits(this.skyLightColorY) == Float.floatToIntBits(other.skyLightColorY)
			&& Float.floatToIntBits(this.skyLightColorZ) == Float.floatToIntBits(other.skyLightColorZ)
			&& Float.floatToIntBits(this.ambientColorX) == Float.floatToIntBits(other.ambientColorX)
			&& Float.floatToIntBits(this.ambientColorY) == Float.floatToIntBits(other.ambientColorY)
			&& Float.floatToIntBits(this.ambientColorZ) == Float.floatToIntBits(other.ambientColorZ)
			&& Float.floatToIntBits(this.nightVisionColorX) == Float.floatToIntBits(other.nightVisionColorX)
			&& Float.floatToIntBits(this.nightVisionColorY) == Float.floatToIntBits(other.nightVisionColorY)
			&& Float.floatToIntBits(this.nightVisionColorZ) == Float.floatToIntBits(other.nightVisionColorZ)
			&& this.dimension == other.dimension;
	}

	private static float component(final Vector3fc value, final int index) {
		return index == 0 ? value.x() : index == 1 ? value.y() : value.z();
	}
}
