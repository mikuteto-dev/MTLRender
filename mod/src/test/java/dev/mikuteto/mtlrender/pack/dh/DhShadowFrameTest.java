package dev.mikuteto.mtlrender.pack.dh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import dev.mikuteto.mtlrender.pack.uniform.DepthConvention;

final class DhShadowFrameTest {
	@Test
	void disabledFrameDoesNotResolvePackOrUniforms() {
		DhShadowFrame frame = DhShadowFrame.disabled();
		assertFalse(frame.enabled());
		assertFalse(frame.hasPack());
		assertFalse(frame.shadowProgramAvailable());
		assertFalse(frame.shadowTerrain());
		assertFalse(frame.shadowTranslucent());
		assertTrue(frame.uniforms().isEmpty());
		assertFalse(frame.canRenderTerrain());
		assertFalse(frame.canRenderTranslucent());
	}

	@Test
	void matchingGenerationsCreateAnEnabledShadowFrame() {
		DhShadowFrame frame = frame(7L, true, true, false);
		assertTrue(frame.enabled());
		assertEquals(7L, frame.generation());
		assertEquals(7L, frame.uniformGeneration());
		assertEquals(7L, frame.targetGeneration());
		assertEquals(7L, frame.depthGeneration());
		assertTrue(frame.canRenderTerrain());
		assertFalse(frame.canRenderTranslucent());
	}

	@Test
	void depthResourceGenerationIsValidated() {
		DhShadowFrame frame = DhShadowFrame.enabled(new DhShadowFrame.PackIdentity("test"), 7L, true, true, true,
			uniforms(new Matrix4f(), 7L), 7L, 7L, DhDepthResources.of(7L, Map.of()));
		assertEquals(7L, frame.depthGeneration());
		assertThrows(IllegalArgumentException.class, () -> DhShadowFrame.enabled(
			new DhShadowFrame.PackIdentity("test"), 7L, true, true, true, uniforms(new Matrix4f(), 7L), 7L, 7L,
			DhDepthResources.of(6L, Map.of())));
	}

	@Test
	void everyGenerationMismatchIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> create(6L, 7L, 7L, 7L));
		assertThrows(IllegalArgumentException.class, () -> create(7L, 6L, 7L, 7L));
		assertThrows(IllegalArgumentException.class, () -> create(7L, 7L, 6L, 7L));
		assertThrows(IllegalArgumentException.class, () -> create(7L, 7L, 7L, 6L));
	}

	@Test
	void bothShadowFlagsAndMissingShadowProgramAreRepresented() {
		DhShadowFrame both = frame(2L, true, true, true);
		assertTrue(both.canRenderTerrain());
		assertTrue(both.canRenderTranslucent());
		DhShadowFrame missing = frame(2L, false, true, true);
		assertTrue(missing.shadowTerrain());
		assertTrue(missing.shadowTranslucent());
		assertFalse(missing.canRenderTerrain());
		assertFalse(missing.canRenderTranslucent());
	}

	@Test
	void shadowAndMainDrawsHaveDistinctClassification() {
		DhRenderContract mainContract = contract(DhProgramKind.TERRAIN, false);
		DhRenderContract shadowContract = contract(DhProgramKind.SHADOW, true);
		DhShadowDraw main = new DhShadowDraw(11L, mainContract, false, DhShadowDraw.CullingMode.BACK, 4L);
		DhShadowDraw shadow = new DhShadowDraw(12L, shadowContract, true, DhShadowDraw.CullingMode.FRONT, 4L);
		assertFalse(main.shadow());
		assertEquals(DhProgramKind.TERRAIN, main.programKind());
		assertTrue(shadow.shadow());
		assertEquals(DhProgramKind.SHADOW, shadow.programKind());
		assertEquals(12L, shadow.lodEntryIdentity());
	}

	@Test
	void mutableMatrixInputsAreDefensivelyCopied() {
		Matrix4f modelView = new Matrix4f().translation(1.0F, 2.0F, 3.0F);
		DhUniformState uniforms = uniforms(modelView);
		float before = uniforms.dhModelView()[12];
		modelView.m30(99.0F);
		float[] returned = uniforms.dhModelView();
		returned[12] = 88.0F;
		assertEquals(before, uniforms.dhModelView()[12]);
	}

	private static DhShadowFrame frame(long generation, boolean program, boolean terrain, boolean translucent) {
		return create(generation, generation, generation, generation, program, terrain, translucent);
	}

	private static DhShadowFrame create(long generation, long uniformGeneration, long targetGeneration,
		long depthGeneration) {
		return create(generation, uniformGeneration, targetGeneration, depthGeneration, true, true, true);
	}

	private static DhShadowFrame create(long generation, long uniformGeneration, long targetGeneration,
		long depthGeneration, boolean program, boolean terrain, boolean translucent) {
		return DhShadowFrame.enabled(new DhShadowFrame.PackIdentity("test"), generation, program, terrain, translucent,
			uniforms(new Matrix4f(), uniformGeneration), uniformGeneration, targetGeneration, depthGeneration);
	}

	private static DhUniformState uniforms(Matrix4f modelView) {
		return uniforms(modelView, 0L);
	}

	private static DhUniformState uniforms(Matrix4f modelView, long generation) {
		DhUniforms uniforms = new DhUniforms();
		return uniforms.update(DhUniformState.CameraKind.SHADOW, modelView,
			DepthConvention.gameProjectionToPack(new Matrix4f().setPerspective(1.0F, 1.5F, 0.1F, 400.0F)), 0.1F,
			new DhUniforms.LodExtent(0, 0, 32, 32, 0.0, 0.0), generation);
	}

	private static DhRenderContract contract(DhProgramKind program, boolean shadow) {
		return new DhRenderContract(program, 2, DhRenderContract.MaterialKind.TERRAIN,
			List.of(new DhVertexAttribute(DhVertexAttribute.Identity.POSITION),
				new DhVertexAttribute(DhVertexAttribute.Identity.COLOR),
				new DhVertexAttribute(DhVertexAttribute.Identity.EXTRA)),
			new DhRenderContract.WorldOrigin(0, 0, 0), shadow);
	}
}
