package dev.mikuteto.mtlrender.pack.dh;

import dev.mikuteto.mtlrender.pack.uniform.DepthConvention;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class DhUniformsTest {
	private static final float EPSILON = 1.0E-4F;
	private static final DhUniforms.LodExtent EXTENT = new DhUniforms.LodExtent(-160, -80, 320, 240, 0.0, 0.0);

	@Test
	void derivesIrisPlanesAndBlockDistanceFromLodExtent() {
		DhUniformState state = new DhUniforms().update(DhUniformState.CameraKind.MAIN, new Matrix4f(), projection(), 0.05F, EXTENT);

		assertEquals(0.05F, state.dhNearPlane(), EPSILON);
		assertEquals(400, state.dhRenderDistance());
		assertEquals((400.0F + 512.0F) * (float) Math.sqrt(2.0), state.dhFarPlane(), EPSILON);
	}

	@Test
	void keepsPackDepthConventionWithoutYConversion() {
		Matrix4f game = new Matrix4f().setPerspective((float) Math.toRadians(70.0), 1.5F, 0.05F, 400.0F);
		Matrix4f pack = DepthConvention.gameProjectionToPack(game);
		DhUniformState state = new DhUniforms().update(DhUniformState.CameraKind.MAIN, new Matrix4f(), pack, 0.05F, EXTENT);
		Matrix4f restored = DepthConvention.packProjectionToGame(state.dhProjectionMatrix());

		assertEquals(0.0F, state.dhProjectionMatrix().m10(), EPSILON);
		assertEquals(game.m00(), restored.m00(), EPSILON);
		assertEquals(game.m11(), restored.m11(), EPSILON);
		assertEquals(game.m10(), restored.m10(), EPSILON);
		assertNotEquals(-game.m11(), restored.m11(), EPSILON);
	}

	@Test
	void firstFrameUsesCurrentMatricesAsPrevious() {
		Matrix4f modelView = new Matrix4f().translate(2.0F, -3.0F, 4.0F).rotateY(0.4F);
		DhUniformState state = new DhUniforms().update(DhUniformState.CameraKind.MAIN, modelView, projection(), 0.1F, EXTENT);

		assertMatrixEquals(state.dhModelViewMatrix(), state.dhPreviousModelViewMatrix());
		assertMatrixEquals(state.dhProjectionMatrix(), state.dhPreviousProjectionMatrix());
	}

	@Test
	void publishesExactShadowMatricesAndInverses() {
		DhUniforms uniforms = new DhUniforms();
		Matrix4f modelView = new Matrix4f().rotateX(0.3F).translate(4.0F, -2.0F, 7.0F);
		Matrix4f projection = new Matrix4f().setOrthoSymmetric(128.0F, 128.0F, -96.0F, 320.0F, true);
		DhUniformState state = uniforms.updateShadow(new DhUniforms.ShadowCamera(modelView, projection,
			-96.0F, 320.0F, 0.05F, 17L), EXTENT);

		assertMatrixEquals(modelView, state.shadowModelViewMatrix());
		assertMatrixEquals(projection, state.shadowProjectionMatrix());
		assertIdentity(state.shadowModelViewMatrix().mul(state.shadowModelViewInverseMatrix()));
		assertIdentity(state.shadowProjectionMatrix().mul(state.shadowProjectionInverseMatrix()));
		assertEquals(-96.0F, state.shadowNearPlane(), EPSILON);
		assertEquals(320.0F, state.shadowFarPlane(), EPSILON);
		assertEquals(17L, state.generation());
	}

	@Test
	void shadowPreviousValuesFollowShadowFramesAndDoNotUseMainCamera() {
		DhUniforms uniforms = new DhUniforms();
		Matrix4f main = new Matrix4f().translate(99.0F, 0.0F, 0.0F);
		Matrix4f first = new Matrix4f().translate(1.0F, 2.0F, 3.0F);
		Matrix4f second = new Matrix4f().translate(5.0F, 6.0F, 7.0F);
		uniforms.update(DhUniformState.CameraKind.MAIN, main, projection(), 0.1F, EXTENT);
		DhUniformState firstState = uniforms.updateShadow(new DhUniforms.ShadowCamera(first, projection(), -8.0F,
			64.0F, 0.1F, 3L), EXTENT);
		DhUniformState secondState = uniforms.updateShadow(new DhUniforms.ShadowCamera(second, projection(), -8.0F,
			64.0F, 0.1F, 4L), EXTENT);

		assertMatrixEquals(first, firstState.shadowPreviousModelViewMatrix());
		assertMatrixEquals(first, secondState.shadowPreviousModelViewMatrix());
		assertMatrixEquals(second, secondState.shadowModelViewMatrix());
		assertEquals(4L, secondState.generation());
	}

	@Test
	void shadowProjectionIsNotDepthOrYAxisConverted() {
		Matrix4f projection = new Matrix4f().setOrthoSymmetric(80.0F, 80.0F, -32.0F, 256.0F, true);
		DhUniformState state = new DhUniforms().updateShadow(new DhUniforms.ShadowCamera(new Matrix4f(), projection,
			-32.0F, 256.0F, 0.1F, 1L), EXTENT);

		assertMatrixEquals(projection, state.shadowProjectionMatrix());
		assertEquals(projection.m11(), state.shadowProjectionMatrix().m11(), EPSILON);
	}

	@Test
	void previousSnapshotIsStableAndCameraKindsAreIndependent() {
		DhUniforms uniforms = new DhUniforms();
		Matrix4f first = new Matrix4f().translate(1.0F, 0.0F, 0.0F);
		Matrix4f second = new Matrix4f().translate(2.0F, 0.0F, 0.0F);
		DhUniformState firstState = uniforms.update(DhUniformState.CameraKind.MAIN, first, projection(), 0.1F, EXTENT);
		DhUniformState secondState = uniforms.update(DhUniformState.CameraKind.MAIN, second, projection(), 0.1F, EXTENT);
		DhUniformState shadow = uniforms.update(DhUniformState.CameraKind.SHADOW, first, projection(), 0.1F, EXTENT);

		assertMatrixEquals(firstState.dhModelViewMatrix(), secondState.dhPreviousModelViewMatrix());
		assertMatrixEquals(first, shadow.dhPreviousModelViewMatrix());
	}

	private static Matrix4f projection() {
		return DepthConvention.gameProjectionToPack(new Matrix4f().setPerspective((float) Math.toRadians(70.0), 1.5F, 0.05F, 400.0F));
	}

	private static void assertMatrixEquals(Matrix4fc expected, Matrix4fc actual) {
		for (int column = 0; column < 4; column++) {
			for (int row = 0; row < 4; row++) {
				assertEquals(expected.get(column, row), actual.get(column, row), EPSILON);
			}
		}
	}

	private static void assertIdentity(Matrix4fc matrix) {
		assertMatrixEquals(new Matrix4f(), matrix);
	}
}
