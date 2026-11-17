package dev.mikuteto.mtlrender.pack.shadow;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowFrameSnapshotTest {
	private static final ShadowFrameSnapshot.PackIdentity PACK = new ShadowFrameSnapshot.PackIdentity("test", 4L);

	@Test
	void noPackIsAbsentAndDisabled() {
		ShadowFrameSnapshot snapshot = ShadowFrameSnapshot.noPack();
		assertFalse(snapshot.enabled());
		assertTrue(snapshot.pack().isEmpty());
		assertTrue(snapshot.dimension().isEmpty());
		assertFalse(snapshot.plan().shadowTerrain());
		assertTrue(snapshot.programs().isEmpty());
		assertFalse(snapshot.prepareBeforeShadow());
	}

	@Test
	void matchingGenerationsAreCapturedOnce() {
		ShadowFrameSnapshot snapshot = snapshot(8L, 9L, 10L, 11L);
		assertEquals(PACK, snapshot.pack().orElseThrow());
		assertEquals("minecraft:overworld", snapshot.dimension().orElseThrow());
		assertEquals(8L, snapshot.reloadGeneration());
		assertEquals(9L, snapshot.targetGeneration());
		assertEquals(10L, snapshot.programSourceGeneration());
		assertEquals(11L, snapshot.uniformGeneration());
		assertTrue(snapshot.prepareBeforeShadow());
		assertEquals(ShadowPassPlan.CullingMode.ADVANCED, snapshot.plan().cullingMode());
	}

	@Test
	void everyGenerationMismatchIsRejected() {
		ShadowFrameSnapshot snapshot = snapshot(8L, 9L, 10L, 11L);
		assertThrows(IllegalArgumentException.class, () -> snapshot.validate(7L, 9L, 10L, 11L));
		assertThrows(IllegalArgumentException.class, () -> snapshot.validate(8L, 7L, 10L, 11L));
		assertThrows(IllegalArgumentException.class, () -> snapshot.validate(8L, 9L, 7L, 11L));
		assertThrows(IllegalArgumentException.class, () -> snapshot.validate(8L, 9L, 10L, 7L));
	}

	@Test
	void reloadResizeAndDimensionIdentitiesAreNotInterchangeable() {
		ShadowFrameSnapshot first = snapshot(1L, 2L, 3L, 4L);
		ShadowFrameSnapshot reloaded = snapshot(5L, 2L, 3L, 4L);
		ShadowFrameSnapshot resized = snapshot(1L, 6L, 3L, 4L);
		ShadowFrameSnapshot changed = ShadowFrameSnapshot.enabled(PACK, "minecraft:the_nether", 1L,
			first.plan(), 2L, 3L, 4L, List.of(), true);
		assertThrows(IllegalArgumentException.class, () -> first.validate(reloaded.generations()));
		assertThrows(IllegalArgumentException.class, () -> first.validate(resized.generations()));
		assertEquals("minecraft:the_nether", changed.dimension().orElseThrow());
		assertEquals(1L, changed.reloadGeneration());
	}

	@Test
	void defensiveCopiesProtectPlanMatricesAndProgramList() {
		ShadowMatrices matrices = new ShadowMatrices(new Matrix4f().translate(1.0F, 2.0F, 3.0F), new Matrix4f(),
			new Matrix4f(), new Matrix4f(), 0.0F, 0.0F, 0.0F, 2.0F);
		ShadowPassPlan plan = new ShadowPassPlan(1024, 64.0F, Optional.empty(), 1.0F, 2.0F, 0.0F,
			ShadowPassPlan.CullingMode.DEFAULT, matrices, true, false, false, false);
		ArrayList<ShadowProgramSelection> programs = new ArrayList<>();
		ShadowFrameSnapshot snapshot = ShadowFrameSnapshot.enabled(PACK, "minecraft:overworld", 1L, plan,
			2L, 3L, 4L, programs, false);
		programs.add(null);
		assertTrue(snapshot.programs().isEmpty());
		assertEquals(matrices, snapshot.plan().matrices());
		assertNotSame(snapshot.plan().matrices().modelView(), snapshot.plan().matrices().modelView());
	}

	private static ShadowFrameSnapshot snapshot(long reload, long target, long source, long uniform) {
		ShadowPassPlan plan = new ShadowPassPlan(1024, 64.0F, Optional.empty(), 1.0F, 2.0F, 0.0F,
			ShadowPassPlan.CullingMode.ADVANCED, ShadowMatrices.identity(), true, true, false, false);
		return ShadowFrameSnapshot.enabled(PACK, "minecraft:overworld", reload, plan, target, source, uniform,
			List.of(), true);
	}
}
