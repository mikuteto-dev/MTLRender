package dev.mikuteto.mtlrender.pack.shadow;

import dev.mikuteto.mtlrender.pack.ShaderProperties;
import dev.mikuteto.mtlrender.pack.uniform.DepthConvention;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShadowMatrixCalculatorTest {
	@Test
	void wrapsPositiveAndNegativeSunAngles() {
		Map<String, String> options = Map.of("shadowIntervalSize", "0");
		ShadowMatrices positive = ShadowMatrixCalculator.matrices(options, 10.0F, 0.0, 0.0, 0.0);
		ShadowMatrices wrapped = ShadowMatrixCalculator.matrices(options, 370.0F, 0.0, 0.0, 0.0);
		ShadowMatrices negative = ShadowMatrixCalculator.matrices(options, -350.0F, 0.0, 0.0, 0.0);
		assertEquals(positive, wrapped);
		assertEquals(positive, negative);
	}

	@Test
	void usesIrisSnapRemainderAtBoundariesAndKeepsFractionalCameraMotion() {
		Map<String, String> options = Map.of("shadowIntervalSize", "2");
		ShadowMatrices boundary = ShadowMatrixCalculator.matrices(options, 0.0F, 2.0, -2.0, 3.5);
		assertEquals(-1.0F, boundary.snappedOriginX());
		assertEquals(-1.0F, boundary.snappedOriginY());
		assertEquals(0.5F, boundary.snappedOriginZ());
		ShadowMatrices before = ShadowMatrixCalculator.matrices(options, 0.0F, 1.99, 0.0, 0.0);
		ShadowMatrices after = ShadowMatrixCalculator.matrices(options, 0.0F, 2.01, 0.0, 0.0);
		assertEquals(0.99F, before.snappedOriginX(), 0.000001F);
		assertEquals(-0.99F, after.snappedOriginX(), 0.000001F);
	}

	@Test
	void zeroIntervalDisablesTranslationAndNegativeIntervalIsRejected() {
		ShadowMatrices matrices = ShadowMatrixCalculator.matrices(Map.of("shadowIntervalSize", "0"), 0.0F,
			19.25, -4.5, 2.0);
		assertEquals(0.0F, matrices.snappedOriginX());
		assertEquals(0.0F, matrices.snappedOriginY());
		assertEquals(0.0F, matrices.snappedOriginZ());
		assertThrows(IllegalArgumentException.class, () -> ShadowMatrixCalculator.matrices(
			Map.of("shadowIntervalSize", "-2"), 0.0F, 0.0, 0.0, 0.0));
	}

	@Test
	void selectsOrthographicAndPerspectiveProjection() {
		ShadowPassPlan ortho = ShadowMatrixCalculator.calculate(Map.of("shadowDistance", "32"),
			ShaderProperties.empty(), 0.0F, 0.0, 0.0, 0.0);
		assertTrueClose(new Matrix4f().setOrthoSymmetric(64.0F, 64.0F, -100.05F, 156.0F, false), ortho.matrices().projection());
		ShadowPassPlan perspective = ShadowMatrixCalculator.calculate(Map.of("shadowMapFov", "90"),
			ShaderProperties.empty(), 0.0F, 0.0, 0.0, 0.0);
		assertEquals(1.0F, perspective.matrices().projection().m00(), 0.0F);
		assertEquals(-1.0F, perspective.matrices().projection().m23(), 0.0F);
		assertEquals(90.0F, perspective.shadowMapFov().orElseThrow(), 0.0F);
	}

	@Test
	void preservesDistanceAndRenderMultiplierAsSeparateContracts() {
		ShadowPassPlan plan = ShadowMatrixCalculator.calculate(Map.of("shadowDistance", "96",
			"shadowDistanceRenderMul", "0.5"), ShaderProperties.empty(), 0.0F, 0.0, 0.0, 0.0);
		assertEquals(96.0F, plan.shadowDistance(), 0.0F);
		assertEquals(0.5F, plan.shadowDistanceRenderMul(), 0.0F);
		assertEquals(1.0F / 96.0F, plan.matrices().projection().m00(), 0.0F);
	}

	@Test
	void hasHandComputedRotationAndSnappedTranslation() {
		ShadowMatrices matrices = ShadowMatrixCalculator.matrices(Map.of("shadowIntervalSize", "2",
			"sunPathRotation", "0"), 0.0F, 0.0, 0.0, 0.0);
		Matrix4f expected = new Matrix4f().rotateX((float) Math.toRadians(90.0F))
			.rotateZ(0.0F).rotateX(0.0F).translate(-1.0F, -1.0F, -1.0F);
		assertTrueClose(expected, matrices.modelView());
		assertEquals(-1.0F, matrices.snappedOriginX(), 0.0F);
		assertEquals(-1.0F, matrices.snappedOriginY(), 0.0F);
		assertEquals(-1.0F, matrices.snappedOriginZ(), 0.0F);
	}

	@Test
	void inversesRoundTripAndPackProjectionConvertsOnce() {
		ShadowMatrices matrices = ShadowMatrixCalculator.matrices(Map.of("shadowMapFov", "63",
			"shadowIntervalSize", "4", "sunPathRotation", "37"), -121.0F, 13.25, 70.5, -8.75);
		assertIdentity(new Matrix4f(matrices.modelView()).mul(matrices.modelViewInverse()));
		assertIdentity(new Matrix4f(matrices.projection()).mul(matrices.projectionInverse()));
		assertEquals(-1.0F, matrices.projection().m23(), 0.0F);
		Matrix4f gameProjection = DepthConvention.packProjectionToGame(matrices.projection());
		assertTrueClose(matrices.projection(), DepthConvention.gameProjectionToPack(gameProjection));
	}

	private static void assertTrueClose(org.joml.Matrix4fc expected, org.joml.Matrix4fc actual) {
		for (int column = 0; column < 4; column++) {
			for (int row = 0; row < 4; row++) {
				assertEquals(expected.get(column, row), actual.get(column, row), 0.000001F);
			}
		}
	}

	private static void assertIdentity(org.joml.Matrix4fc matrix) {
		assertTrueClose(new Matrix4f(), matrix);
	}
}
