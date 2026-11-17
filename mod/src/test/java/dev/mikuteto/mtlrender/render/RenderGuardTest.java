package dev.mikuteto.mtlrender.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class RenderGuardTest {
	@Test
	void zeroEffectScaleSkipsOnlyNeutralModifier() {
		assertTrue(FovGuards.skipNeutralModifier(0.0F, false));
		assertFalse(FovGuards.skipNeutralModifier(0.0F, true));
		assertFalse(FovGuards.skipNeutralModifier(0.5F, false));
	}

	@Test
	void lightmapKeyCoversScalarAndColorInputs() {
		LightmapRenderState first = state();
		LightmapStateKey key = LightmapStateKey.of(first, "overworld");
		assertTrue(key.same(LightmapStateKey.of(first, "overworld")));

		first.blockFactor += 0.01F;
		assertFalse(key.same(LightmapStateKey.of(first, "overworld")));
		first.blockFactor -= 0.01F;
		first.skyLightColor = new Vector3f(0.5F, 1.0F, 1.0F);
		assertFalse(key.same(LightmapStateKey.of(first, "overworld")));
		assertFalse(key.same(LightmapStateKey.of(state(), "nether")));
	}

	private static LightmapRenderState state() {
		LightmapRenderState state = new LightmapRenderState();
		state.blockFactor = 1.4F;
		state.skyFactor = 1.0F;
		state.nightVisionEffectIntensity = 0.0F;
		state.darknessEffectScale = 0.0F;
		state.bossOverlayWorldDarkening = 0.0F;
		state.brightness = 1.0F;
		state.blockLightTint = new Vector3f(1.0F, 1.0F, 1.0F);
		state.skyLightColor = new Vector3f(1.0F, 1.0F, 1.0F);
		state.ambientColor = new Vector3f(1.0F, 1.0F, 1.0F);
		state.nightVisionColor = new Vector3f(1.0F, 1.0F, 1.0F);
		return state;
	}
}
