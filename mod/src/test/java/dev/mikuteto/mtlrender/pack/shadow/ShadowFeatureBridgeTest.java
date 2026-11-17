package dev.mikuteto.mtlrender.pack.shadow;

import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ProgramId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowFeatureBridgeTest {
	@AfterEach
	void clear() {
		ShadowFeatureBridge.clear();
	}

	@Test
	void flagsSelectOnlyTheCorrespondingFeature() {
		ShadowProgramSelection entity = selection(ShadowProgramKind.ENTITIES);
		ShadowProgramSelection block = selection(ShadowProgramKind.BLOCK_ENTITIES);
		ShadowPassPlan plan = plan(true, false);
		try (ShadowFeatureBridge.Scope ignored = ShadowFeatureBridge.enter(plan, entity, block)) {
			try (ShadowFeatureBridge.Scope feature = ShadowFeatureBridge.enterFeature(ShadowFeatureBridge.FeatureKind.ENTITY)) {
				assertTrue(ShadowFeatureBridge.shouldSubmitEntity());
				assertFalse(ShadowFeatureBridge.shouldSubmitBlockEntity());
			}
			try (ShadowFeatureBridge.Scope feature = ShadowFeatureBridge.enterFeature(ShadowFeatureBridge.FeatureKind.BLOCK_ENTITY)) {
				assertFalse(ShadowFeatureBridge.shouldSubmitEntity());
				assertFalse(ShadowFeatureBridge.shouldSubmitBlockEntity());
			}
		}
		assertFalse(ShadowFeatureBridge.active());
	}

	@Test
	void nestedScopeRestoresAndExceptionClears() {
		ShadowPassPlan plan = plan(false, true);
		try {
			try (ShadowFeatureBridge.Scope ignored = ShadowFeatureBridge.enter(plan, selection(ShadowProgramKind.ENTITIES), selection(ShadowProgramKind.BLOCK_ENTITIES))) {
				assertTrue(ShadowFeatureBridge.active());
				try (ShadowFeatureBridge.Scope feature = ShadowFeatureBridge.enterFeature(ShadowFeatureBridge.FeatureKind.BLOCK_ENTITY)) {
					assertTrue(ShadowFeatureBridge.shouldSubmitBlockEntity());
					throw new IllegalStateException("test");
				}
			} catch (IllegalStateException expected) {
			}
		} finally {
			ShadowFeatureBridge.clear();
		}
		assertFalse(ShadowFeatureBridge.active());
	}

	@Test
	void disabledPlanIsNoOp() {
		try (ShadowFeatureBridge.Scope ignored = ShadowFeatureBridge.enter(ShadowPassPlan.disabled(),
			selection(ShadowProgramKind.ENTITIES), selection(ShadowProgramKind.BLOCK_ENTITIES))) {
			assertFalse(ShadowFeatureBridge.active());
		}
	}

	private static ShadowProgramSelection selection(ShadowProgramKind kind) {
		Program program = new Program(kind.program().sourceName(), Map.of(Program.Stage.VERTEX, "v", Program.Stage.FRAGMENT, "f"));
		return new ShadowProgramSelection(kind, Optional.of(program), program.name(),
			java.util.List.of(ProgramId.Shadow), true, false);
	}

	private static ShadowPassPlan plan(boolean entities, boolean blockEntities) {
		return new ShadowPassPlan(1024, 160.0F, Optional.empty(), 1.0F, 2.0F, 0.0F,
			ShadowPassPlan.CullingMode.DEFAULT, ShadowMatrices.identity(), true, true, entities, blockEntities);
	}
}
