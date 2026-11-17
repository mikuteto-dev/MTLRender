package dev.mikuteto.mtlrender.terrain;

import dev.mikuteto.mtlrender.pack.ShaderProperties;
import dev.mikuteto.mtlrender.pack.shadow.ShadowMatrixCalculator;
import dev.mikuteto.mtlrender.pack.shadow.ShadowMatrices;
import dev.mikuteto.mtlrender.pack.shadow.ShadowPassPlan;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LightShadowFrustumTest {
	@Test
	void usesShadowMatrixReferenceAndLightOriginForGpuCoordinates() {
		ShadowPassPlan plan = ShadowMatrixCalculator.calculate(Map.of("shadowDistance", "64", "shadowIntervalSize", "4"),
			ShaderProperties.empty(), 37.0F, -19.25, 72.5, 11.75);
		LightShadowFrustum frustum = LightShadowFrustum.from(plan);
		Matrix4f reference = new Matrix4f(plan.matrices().projection()).mul(plan.matrices().modelView())
			.translate(plan.matrices().snappedOriginX(), plan.matrices().snappedOriginY(), plan.matrices().snappedOriginZ());
		float factor = 64.0F / (64.0F + frustum.casterExtent());
		float[] expected = new float[16];
		reference.get(expected);
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 4; column++) {
				expected[column * 4 + row] *= factor;
			}
		}
		assertArrayEquals(expected, frustum.clipValues(), 0.0F);
		assertEquals(-6, frustum.cameraBlockX());
		assertEquals(-0.75F, frustum.cameraOffsetX(), 0.0F);
		assertEquals(-2, frustum.cameraBlockY());
		assertEquals(-0.5F, frustum.cameraOffsetY(), 0.0F);
		assertEquals(1, frustum.cameraBlockZ());
		assertEquals(-0.75F, frustum.cameraOffsetZ(), 0.0F);
	}

	@Test
	void terrainCullViewUsesLightClipAndOriginInsteadOfViewerValues() {
		ShadowPassPlan plan = orthographicPlan(32.0F, 17.25F, 4.5F, -9.75F);
		LightShadowFrustum frustum = LightShadowFrustum.from(plan);
		ShadowCullView view = new ShadowCullView(plan, frustum.clipValues(), frustum.cameraBlockX(), frustum.cameraBlockY(),
			frustum.cameraBlockZ(), frustum.cameraOffsetX(), frustum.cameraOffsetY(), frustum.cameraOffsetZ());
		assertArrayEquals(frustum.clipValues(), view.clip(), 0.0F);
		assertEquals(frustum.cameraBlockX(), view.cameraX());
		assertEquals(frustum.cameraBlockY(), view.cameraY());
		assertEquals(frustum.cameraBlockZ(), view.cameraZ());
		assertEquals(frustum.cameraOffsetX(), view.offsetX(), 0.0F);
		assertEquals(frustum.cameraOffsetY(), view.offsetY(), 0.0F);
		assertEquals(frustum.cameraOffsetZ(), view.offsetZ(), 0.0F);
	}

	@Test
	void keepsNearAndFarEdgesAndNegativeCoordinatesConservative() {
		ShadowPassPlan plan = orthographicPlan(32.0F, 0.0F, 0.0F, 0.0F);
		LightShadowFrustum frustum = LightShadowFrustum.from(plan);
		assertTrue(frustum.includes(-32.0F, -32.0F, -100.05F, 32.0F, 32.0F, 156.0F));
		assertTrue(frustum.includes(-20.0F, -20.0F, -40.0F, -4.0F, -4.0F, -24.0F));
		assertTrue(frustum.includes(31.9F, 31.9F, 155.0F, 32.0F, 32.0F, 156.0F));
	}

	@Test
	void acceptsCasterAabbWhenOneCornerIsBehindTheLightClip() {
		ShadowPassPlan plan = orthographicPlan(16.0F, -33.5F, 64.0F, -17.25F);
		LightShadowFrustum frustum = LightShadowFrustum.from(plan);
		assertTrue(frustum.includes(-7.0F, -8.0F, -8.0F, 9.0F, 8.0F, 8.0F));
		assertEquals(ShadowPassPlan.CullingMode.DEFAULT, frustum.cullingMode());
	}

	private static ShadowPassPlan orthographicPlan(float distance, float originX, float originY, float originZ) {
		Matrix4f projection = new Matrix4f().setOrthoSymmetric(distance * 2.0F, distance * 2.0F, -100.05F, 156.0F, false);
		ShadowMatrices matrices = new ShadowMatrices(new Matrix4f(), new Matrix4f(), projection,
			new Matrix4f(projection).invert(), originX, originY, originZ, 0.0F);
		return new ShadowPassPlan(1024, distance, Optional.empty(), -1.0F, 0.0F, 0.0F,
			ShadowPassPlan.CullingMode.DEFAULT, matrices, true, true, true, true);
	}
}
