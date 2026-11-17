package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.shadow.ShadowFrameSnapshot;
import dev.mikuteto.mtlrender.pack.shadow.ShadowPassExecutor;
import dev.mikuteto.mtlrender.pack.shadow.ShadowPassPlan;
import dev.mikuteto.mtlrender.pack.shadow.ShadowProgramSelection;
import dev.mikuteto.mtlrender.pack.shadow.ShadowMatrices;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ShadowFrameOrderTest {
	@Test
	void shadowCopyPrecedesShadowcompForEmptyAndNonEmptyShadowLists() {
		assertEquals(List.of("matrix", "uniform", "acquire", "clear", "resolve", "finalize", "copy", "shadowcomp"),
			run(false, List.of()));
		assertEquals(List.of("matrix", "uniform", "acquire", "clear", "resolve", "terrain", "features", "finalize", "copy", "shadowcomp"),
			run(true, List.of(selection())));
	}

	@Test
	void noPackAndGenerationFailureRecordNoShadowCommands() {
		List<String> noPack = new ArrayList<>();
		executor(noPack).execute(ShadowFrameSnapshot.noPack(), generations(0L));
		assertEquals(List.of(), noPack);

		List<String> changed = new ArrayList<>();
		ShadowFrameSnapshot frame = frame(true, List.of());
		assertThrows(IllegalArgumentException.class, () -> executor(changed).execute(frame, generations(9L)));
		assertEquals(List.of(), changed);
	}

	private static List<String> run(boolean draws, List<ShadowProgramSelection> programs) {
		List<String> calls = new ArrayList<>();
		ShadowFrameSnapshot frame = frame(draws, programs);
		executor(calls).execute(frame, frame.generations());
		return calls;
	}

	private static ShadowPassExecutor executor(List<String> calls) {
		return new ShadowPassExecutor(
			frame -> { calls.add("matrix"); return frame.plan().matrices(); },
			(frame, matrices) -> calls.add("uniform"),
			frame -> { calls.add("resolve"); return frame.programs(); },
			(frame, program, policy) -> { },
			(plan, culling, translucent) -> calls.add("terrain"),
			(plan, entities, blockEntities) -> calls.add("features"),
			new ShadowPassExecutor.FeatureBridge() {
				@Override public Scope enter(ShadowPassPlan plan, List<ShadowProgramSelection> programs) { return () -> { }; }
				@Override public void clear() { }
			},
			new ShadowPassExecutor.TargetRuntime() {
				@Override public Target acquire(ShadowFrameSnapshot frame) { calls.add("acquire"); return () -> frame.targetGeneration(); }
				@Override public void clear(Target target) { calls.add("clear"); }
				@Override public void finalizeTarget(Target target) { calls.add("finalize"); }
				@Override public void copyDepth(Target target) { calls.add("copy"); }
			},
			programs -> calls.add("shadowcomp"));
	}

	private static ShadowFrameSnapshot frame(boolean draws, List<ShadowProgramSelection> programs) {
		ShadowPassPlan plan = new ShadowPassPlan(1024, 64.0F, Optional.empty(), 1.0F, 2.0F, 0.0F,
			ShadowPassPlan.CullingMode.DEFAULT, ShadowMatrices.identity(), draws, false, draws, draws);
		return ShadowFrameSnapshot.enabled(new ShadowFrameSnapshot.PackIdentity("pack", 1L), "overworld",
			4L, plan, 8L, 16L, 32L, programs, false);
	}

	private static ShadowFrameSnapshot.Generations generations(long reload) {
		return new ShadowFrameSnapshot.Generations(reload, 8L, 16L, 32L);
	}

	private static ShadowProgramSelection selection() {
		return new ShadowProgramSelection(dev.mikuteto.mtlrender.pack.shadow.ShadowProgramKind.TERRAIN_SOLID,
			Optional.empty(), "shadow", List.of(), true, false);
	}
}
