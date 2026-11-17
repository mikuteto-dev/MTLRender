package dev.mikuteto.mtlrender.pack.uniform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.Test;

final class GameStateProjectionTest {
	private static final float EPSILON = 1.0E-5F;

	@Test
	void firstFrameUsesConvertedProjectionForCurrentPreviousAndInverse() {
		Matrix4f gameProjection = projection(1.2F, -2.1F);
		GameState state = new GameState();

		state.setCameraMatrices(gameProjection, new Matrix4f().rotateX(0.4F));

		Matrix4f expected = DepthConvention.gameProjectionToPack(gameProjection);
		assertMatrixEquals(expected, state.value("gbufferProjection"));
		assertMatrixEquals(expected, state.value("gbufferPreviousProjection"));
		assertMatrixEquals(new Matrix4f(expected).invert(), state.value("gbufferProjectionInverse"));
		assertMatrixEquals(state.value("gbufferProjectionInverse"), state.value("iris_DefaultProjectionMatrixInverse"));
		assertMatrixEquals(expected, state.value("iris_ProjectionMatrix"));
		assertMatrixEquals(expected, state.value("iris_ProjMat"));
	}

	@Test
	void subsequentFrameRetainsTheConvertedPreviousProjection() {
		Matrix4f first = projection(1.1F, -1.7F);
		Matrix4f second = projection(0.9F, -3.4F);
		GameState state = new GameState();
		state.setCameraMatrices(first, new Matrix4f());
		state.setCameraMatrices(second, new Matrix4f());

		assertMatrixEquals(DepthConvention.gameProjectionToPack(first), state.value("gbufferPreviousProjection"));
		assertMatrixEquals(DepthConvention.gameProjectionToPack(second), state.value("gbufferProjection"));
	}

	@Test
	void conversionDoesNotTouchModelViewOrApplyTwice() {
		Matrix4f gameProjection = projection(1.3F, -2.8F);
		Matrix4f modelView = new Matrix4f().translate(2.0F, -3.0F, 4.0F).rotateY(0.6F);
		GameState state = new GameState();
		state.setCameraMatrices(gameProjection, modelView);

		assertMatrixEquals(modelView, state.value("gbufferModelView"));
		assertMatrixEquals(new Matrix4f(modelView).invert(), state.value("gbufferModelViewInverse"));
		assertMatrixEquals(DepthConvention.gameProjectionToPack(gameProjection), state.value("gbufferProjection"));
	}

	private static Matrix4f projection(float x, float z) {
		return new Matrix4f(
			1.4F, 0.1F, 0.2F, 0.3F,
			0.0F, 1.7F, -0.4F, 0.5F,
			0.2F, -0.3F, z, -1.0F,
			0.7F, 0.8F, -2.6F, 0.4F
		).scale(x, 1.0F, 1.0F);
	}

	private static void assertMatrixEquals(Matrix4fc expected, Value actual) {
		for (int column = 0; column < 4; column++) {
			for (int row = 0; row < 4; row++) {
				assertEquals(expected.get(column, row), actual.number(column * 4 + row), EPSILON);
			}
		}
	}

	private static void assertMatrixEquals(Value expected, Value actual) {
		for (int index = 0; index < 16; index++) {
			assertEquals(expected.number(index), actual.number(index), EPSILON);
		}
	}
}
