package dev.mikuteto.mtlrender.pack.uniform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.Test;

class DepthConventionTest {
	private static final float EPSILON = 1.0E-6F;

	@Test
	void convertsExactReversedZEndpoints() {
		assertEquals(-1.0F, DepthConvention.gameNdcZToPack(1.0F), EPSILON);
		assertEquals(1.0F, DepthConvention.gameNdcZToPack(0.0F), EPSILON);
		assertEquals(1.0F, DepthConvention.packNdcZToGame(-1.0F), EPSILON);
		assertEquals(0.0F, DepthConvention.packNdcZToGame(1.0F), EPSILON);
		assertEquals(1.0F, DepthConvention.packWindowDepthToGame(0.0F), EPSILON);
		assertEquals(0.0F, DepthConvention.packWindowDepthToGame(1.0F), EPSILON);
	}

	@Test
	void convertsHomogeneousClipDepthWithNonUnitW() {
		float gameZ = 2.75F;
		float w = 5.5F;
		float packZ = DepthConvention.gameClipZToPack(gameZ, w);

		assertEquals(0.0F, packZ, EPSILON);
		assertEquals(gameZ, DepthConvention.packClipZToGame(packZ, w), EPSILON);
		assertEquals(0.0F, DepthConvention.gameClipZToPack(0.0F, 0.0F), EPSILON);
	}

	@Test
	void convertsMidRangeNdcAndWindowDepthBothWays() {
		float gameNdc = 0.375F;
		float packNdc = DepthConvention.gameNdcZToPack(gameNdc);
		float packWindow = 0.625F;

		assertEquals(0.25F, packNdc, EPSILON);
		assertEquals(gameNdc, DepthConvention.packNdcZToGame(packNdc), EPSILON);
		assertEquals(0.375F, DepthConvention.packWindowDepthToGame(packWindow), EPSILON);
		assertEquals(packWindow, DepthConvention.gameWindowDepthToPack(0.375F), EPSILON);
	}

	@Test
	void projectionConversionDoesNotAliasOrMutateInput() {
		Matrix4f game = new Matrix4f(
			1.2F, 0.1F, 0.2F, 0.3F,
			0.4F, 1.3F, 0.5F, 0.6F,
			0.7F, 0.8F, -2.0F, -1.0F,
			0.9F, 1.0F, -3.0F, 0.0F
		);
		Matrix4f original = new Matrix4f(game);
		Matrix4f pack = DepthConvention.gameProjectionToPack(game);
		Matrix4f roundTrip = DepthConvention.packProjectionToGame(pack);

		assertNotSame(game, pack);
		assertMatrixEquals(original, game);
		assertMatrixEquals(game, roundTrip);
		assertEquals(game.m03() - 2.0F * game.m02(), pack.m02(), EPSILON);
	}

	@Test
	void inverseProjectionConversionIsTheInverseOfProjectionConversion() {
		Matrix4f gameProjection = new Matrix4f(
			1.1F, 0.0F, 0.0F, 0.0F,
			0.0F, 1.2F, 0.0F, 0.0F,
			0.0F, 0.0F, -1.1F, -1.0F,
			0.0F, 0.0F, -2.1F, 0.0F
		);
		Matrix4f gameInverse = new Matrix4f(gameProjection).invert();
		Matrix4f packProjection = DepthConvention.gameProjectionToPack(gameProjection);
		Matrix4f packInverse = DepthConvention.gameProjectionInverseToPack(gameInverse);
		Matrix4f expected = new Matrix4f(packProjection).invert();
		Matrix4f restored = DepthConvention.packProjectionInverseToGame(packInverse);

		assertMatrixEquals(expected, packInverse);
		assertMatrixEquals(gameInverse, restored);
	}

	private static void assertMatrixEquals(Matrix4fc expected, Matrix4fc actual) {
		for (int column = 0; column < 4; column++) {
			for (int row = 0; row < 4; row++) {
				assertEquals(expected.get(column, row), actual.get(column, row), EPSILON);
			}
		}
	}
}
