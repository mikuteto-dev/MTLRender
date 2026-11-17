package dev.mikuteto.mtlrender.pack.shadow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ShadowPassExecutorTest {
	@Test
	void noPackStopsBeforeAnyCollaborator() {
		Recording recording = new Recording();
		ShadowPassExecutor executor = executor(recording);
		ShadowPassExecutor.Result result = executor.execute(ShadowFrameSnapshot.noPack(), generations(0));
		assertFalse(result.rendered());
		assertEquals(List.of(), recording.calls);
	}

	@Test
	void validatesGenerationAndRunsVerifiedOrderIncludingEmptyShadowcomp() {
		Recording recording = new Recording();
		ShadowFrameSnapshot frame = frame(true, true, true, true, List.of());
		ShadowPassExecutor.Result result = executor(recording).execute(frame, frame.generations());
		assertTrue(result.finalized());
		assertEquals(List.of("matrix", "uniform", "acquire", "clear", "resolve", "bridge", "terrain", "features", "close", "bridge-clear", "finalize", "copy", "shadowcomp", "bridge-clear"), recording.calls);
		assertThrows(IllegalArgumentException.class, () -> executor(new Recording()).execute(frame, generations(9)));
	}

	@Test
	void flagsGateTerrainTranslucencyAndFeatureGroups() {
		Recording recording = new Recording();
		ShadowFrameSnapshot frame = frame(false, true, false, true, List.of());
		executor(recording).execute(frame, frame.generations());
		assertEquals(List.of("matrix", "uniform", "acquire", "clear", "resolve", "bridge", "features", "close", "bridge-clear", "finalize", "copy", "shadowcomp", "bridge-clear"), recording.calls);
		assertEquals(List.of("features:false:true"), recording.submissions);
	}

	@Test
	void optionalFailuresStillFinalizeAndClearBridge() {
		Recording recording = new Recording();
		recording.failTerrain = true;
		recording.failPipeline = true;
		ShadowFrameSnapshot frame = frame(true, true, true, true, List.of(selection(ShadowProgramKind.ENTITIES)));
		ShadowPassExecutor.Result result = executor(recording).execute(frame, frame.generations());
		assertTrue(result.finalized());
		assertTrue(recording.calls.contains("finalize"));
		assertTrue(recording.calls.contains("shadowcomp"));
		assertEquals(2, recording.calls.stream().filter("bridge-clear"::equals).count());
	}

	private static ShadowPassExecutor executor(Recording recording) {
		return new ShadowPassExecutor(
			(frame) -> { recording.calls.add("matrix"); return frame.plan().matrices(); },
			(frame, matrices) -> recording.calls.add("uniform"),
			(frame) -> { recording.calls.add("resolve"); return frame.programs(); },
			(frame, program, policy) -> { recording.calls.add("pipeline"); if (recording.failPipeline) throw new IllegalStateException(); },
			(plan, culling, translucent) -> { recording.calls.add("terrain"); recording.submissions.add("terrain:" + culling + ":" + translucent); if (recording.failTerrain) throw new IllegalStateException(); },
			(plan, entities, blockEntities) -> { recording.calls.add("features"); recording.submissions.add("features:" + entities + ":" + blockEntities); },
			new ShadowPassExecutor.FeatureBridge() {
				@Override public Scope enter(ShadowPassPlan plan, List<ShadowProgramSelection> programs) { recording.calls.add("bridge"); return () -> recording.calls.add("close"); }
				@Override public void clear() { recording.calls.add("bridge-clear"); }
			},
			new ShadowPassExecutor.TargetRuntime() {
				@Override public Target acquire(ShadowFrameSnapshot frame) { recording.calls.add("acquire"); return () -> frame.targetGeneration(); }
				@Override public void clear(Target target) { recording.calls.add("clear"); }
				@Override public void finalizeTarget(Target target) { recording.calls.add("finalize"); }
				@Override public void copyDepth(Target target) { recording.calls.add("copy"); }
			},
			(programs) -> recording.calls.add("shadowcomp"));
	}

	private static ShadowFrameSnapshot frame(boolean terrain, boolean translucent, boolean entities, boolean blockEntities,
		List<ShadowProgramSelection> programs) {
		ShadowPassPlan plan = new ShadowPassPlan(1024, 64.0F, Optional.empty(), 1.0F, 2.0F, 0.0F,
			ShadowPassPlan.CullingMode.ADVANCED, ShadowMatrices.identity(), terrain, translucent, entities, blockEntities);
		return ShadowFrameSnapshot.enabled(new ShadowFrameSnapshot.PackIdentity("pack", 1L), "minecraft:overworld",
			4L, plan, 8L, 16L, 32L, programs, false);
	}

	private static ShadowFrameSnapshot.Generations generations(long reload) {
		return new ShadowFrameSnapshot.Generations(reload, 8L, 16L, 32L);
	}

	private static ShadowProgramSelection selection(ShadowProgramKind kind) {
		return new ShadowProgramSelection(kind, Optional.empty(), kind.program().sourceName(), List.of(), true, false);
	}

	private static final class Recording {
		private final List<String> calls = new ArrayList<>();
		private final List<String> submissions = new ArrayList<>();
		private boolean failTerrain;
		private boolean failPipeline;
	}
}
