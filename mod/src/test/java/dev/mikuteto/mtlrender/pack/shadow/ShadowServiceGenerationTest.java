package dev.mikuteto.mtlrender.pack.shadow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ShadowServiceGenerationTest {
	@Test
	void noPackShortCircuitsEveryShadowCollaborator() {
		List<String> calls = new ArrayList<>();
		ShadowPassExecutor executor = executor(calls);

		ShadowPassExecutor.Result result = executor.execute(ShadowFrameSnapshot.noPack(), generations(0L));

		assertEquals(List.of(), calls);
		assertTrue(!result.rendered());
	}

	@Test
	void oneSnapshotKeepsAllGenerationsCoherent() {
		List<String> calls = new ArrayList<>();
		ShadowFrameSnapshot frame = frame(7L, 11L, 13L, 17L);
		ShadowPassExecutor executor = executor(calls);

		ShadowPassExecutor.Result result = executor.execute(frame, frame.generations());

		assertTrue(result.rendered());
		assertEquals(List.of("matrix:7/11/13/17", "uniform:7/11/13/17", "target:11",
			"terrain", "close", "finalize:11", "copy:11", "shadowcomp"), calls);
	}

	@Test
	void staleGenerationIsRejectedBeforeShadowWork() {
		List<String> calls = new ArrayList<>();
		ShadowFrameSnapshot frame = frame(19L, 23L, 29L, 31L);
		ShadowPassExecutor executor = executor(calls);

		assertThrows(IllegalArgumentException.class, () -> executor.execute(frame, generations(18L)));
		assertEquals(List.of(), calls);
	}

	private static ShadowPassExecutor executor(List<String> calls) {
		return new ShadowPassExecutor(
			frame -> {
				calls.add("matrix:" + generationText(frame));
				return ShadowMatrices.identity();
			},
			(frame, matrices) -> calls.add("uniform:" + generationText(frame)),
			frame -> frame.programs(),
			(frame, program, policy) -> { },
			(plan, culling, translucent) -> calls.add("terrain"),
			(plan, entities, blockEntities) -> calls.add("features"),
			new ShadowPassExecutor.FeatureBridge() {
				@Override
				public Scope enter(ShadowPassPlan plan, List<ShadowProgramSelection> programs) {
					return () -> calls.add("close");
				}

				@Override
				public void clear() {
				}
			},
			new ShadowPassExecutor.TargetRuntime() {
				@Override
				public Target acquire(ShadowFrameSnapshot frame) {
					calls.add("target:" + frame.targetGeneration());
					return () -> frame.targetGeneration();
				}

				@Override public void clear(Target target) { }
				@Override public void finalizeTarget(Target target) { calls.add("finalize:" + target.generation()); }
				@Override public void copyDepth(Target target) { calls.add("copy:" + target.generation()); }
			},
			programs -> calls.add("shadowcomp"));
	}

	private static ShadowFrameSnapshot frame(long reload, long target, long source, long uniform) {
		return ShadowFrameSnapshot.enabled(new ShadowFrameSnapshot.PackIdentity("pack", reload), "overworld",
			reload, new ShadowPassPlan(1024, 80.0F, Optional.empty(), 1.0F, 2.0F, 0.0F,
			ShadowPassPlan.CullingMode.DEFAULT, ShadowMatrices.identity(), true, false, false, false),
			target, source, uniform, List.of(), false);
	}

	private static ShadowFrameSnapshot.Generations generations(long reload) {
		return new ShadowFrameSnapshot.Generations(reload, 11L, 13L, 17L);
	}

	private static String generationText(ShadowFrameSnapshot frame) {
		ShadowFrameSnapshot.Generations generations = frame.generations();
		return generations.reload() + "/" + generations.target() + "/" + generations.programSource() + "/"
			+ generations.uniform();
	}
}
