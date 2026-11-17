package dev.mikuteto.mtlrender.pack.uniform;

import dev.mikuteto.mtlrender.pack.shadow.ShadowFrameSnapshot;
import dev.mikuteto.mtlrender.pack.shadow.ShadowMatrices;
import dev.mikuteto.mtlrender.pack.shadow.ShadowPassPlan;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GameStateShadowUniformTest {
	private static final float EPSILON = 1.0E-5F;

	@Test
	void publishesMatricesInTheExistingColumnMajorLayout() {
		Matrix4f modelView = new Matrix4f().translate(2.0F, -3.0F, 4.0F).rotateY(0.4F);
		Matrix4f projection = new Matrix4f().setOrthoSymmetric(32.0F, 32.0F, -100.0F, 156.0F, false);
		ShadowMatrices matrices = new ShadowMatrices(modelView, new Matrix4f(modelView).invert(), projection,
			new Matrix4f(projection).invert(), 0.0F, 0.0F, 0.0F, 2.0F);
		GameState state = new GameState();

		state.publishShadow(snapshot(matrices));

		assertMatrixEquals(modelView, state.value("shadowModelView"));
		assertMatrixEquals(new Matrix4f(modelView).invert(), state.value("shadowModelViewInverse"));
		assertMatrixEquals(projection, state.value("shadowProjection"));
		assertMatrixEquals(new Matrix4f(projection).invert(), state.value("shadowProjectionInverse"));
		assertMatrixEquals(state.value("shadowModelViewInverse"), state.value("iris_ShadowModelViewMatrixInverse"));
		assertMatrixEquals(state.value("shadowProjectionInverse"), state.value("iris_ShadowProjectionMatrixInverse"));
	}

	@Test
	void normalizedAngleSelectsTheSameDayAndNightLightDirection() {
		GameState state = new GameState();
		Matrix4f view = new Matrix4f().rotateZ(0.3F);

		state.setCelestialValues(view, 360.0F, 40.0F);
		assertEquals(90.0F / 360.0F, state.value("shadowAngle").number(0), EPSILON);
		assertEquals(state.value("sunPosition").number(0), state.value("shadowLightPosition").number(0), EPSILON);
		assertEquals(state.value("sunPosition").number(1), state.value("shadowLightPosition").number(1), EPSILON);

		state.setCelestialValues(view, 200.0F, 40.0F);
		assertEquals(290.0F / 360.0F, state.value("shadowAngle").number(0), EPSILON);
		assertEquals(state.value("moonPosition").number(0), state.value("shadowLightPosition").number(0), EPSILON);
		assertEquals(state.value("moonPosition").number(1), state.value("shadowLightPosition").number(1), EPSILON);
	}

	@Test
	void noPackClearsShadowValuesWithoutChangingCameraValues() {
		GameState state = new GameState();
		Matrix4f projection = new Matrix4f().setPerspective(1.2F, 1.5F, 0.1F, 100.0F);
		Matrix4f modelView = new Matrix4f().translate(3.0F, 4.0F, -5.0F);
		state.setCameraMatrices(projection, modelView);
		state.set("renderStage", 7);
		state.publishShadow(snapshot(ShadowMatrices.identity()));
		state.publishShadow(ShadowFrameSnapshot.noPack());

		assertMatrixEquals(DepthConvention.gameProjectionToPack(projection), state.value("gbufferProjection"));
		assertMatrixEquals(modelView, state.value("gbufferModelView"));
		assertEquals(7, state.value("renderStage").integer(0));
		for (String name : new String[] {"shadowModelView", "shadowModelViewInverse", "shadowProjection", "shadowProjectionInverse"}) {
			for (int index = 0; index < 16; index++) assertEquals(0.0F, state.value(name).number(index), EPSILON);
		}
	}

	private static ShadowFrameSnapshot snapshot(ShadowMatrices matrices) {
		ShadowPassPlan plan = new ShadowPassPlan(1024, 160.0F, Optional.empty(), -1.0F, 2.0F, 0.0F,
			ShadowPassPlan.CullingMode.DEFAULT, matrices, true, true, true, true);
		return ShadowFrameSnapshot.enabled(new ShadowFrameSnapshot.PackIdentity("test", 1L),
			"minecraft:overworld", 1L, plan, 1L, 1L, 1L, java.util.List.of(), false);
	}

	private static void assertMatrixEquals(Matrix4fc expected, Value actual) {
		for (int column = 0; column < 4; column++) {
			for (int row = 0; row < 4; row++) {
				assertEquals(expected.get(column, row), actual.number(column * 4 + row), EPSILON);
			}
		}
	}

	private static void assertMatrixEquals(Value expected, Value actual) {
		for (int index = 0; index < 16; index++) assertEquals(expected.number(index), actual.number(index), EPSILON);
	}
}
