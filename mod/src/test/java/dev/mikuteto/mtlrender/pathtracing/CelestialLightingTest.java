package dev.mikuteto.mtlrender.pathtracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType.Skybox;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class CelestialLightingTest {
	@Test
	void transformsVanillaAngleAndNormalizesDirection() {
		Vector3f direction = CelestialLighting.direction(new Matrix4f(), (float) (Math.PI / 2.0));

		assertEquals(-1.0F, direction.x, 1.0E-6F);
		assertEquals(0.0F, direction.y, 1.0E-6F);
		assertEquals(0.0F, direction.z, 1.0E-6F);
		assertEquals(1.0F, direction.length(), 1.0E-6F);
	}

	@Test
	void disablesLightsForDimensionsWithoutCelestialPass() {
		SkyRenderState sky = new SkyRenderState();
		CelestialLighting lighting = CelestialLighting.from(sky, new Matrix4f());

		assertEquals(new CelestialLighting(), lighting);
		sky.skybox = Skybox.END;
		assertEquals(new CelestialLighting(), CelestialLighting.from(sky, new Matrix4f()));
		sky.skybox = Skybox.OVERWORLD;
		sky.rainBrightness = 0.5F;
		assertNotEquals(new CelestialLighting(), CelestialLighting.from(sky, new Matrix4f()));
	}
}
