package dev.mikuteto.mtlrender.pack.shadow;

import com.mojang.blaze3d.platform.CompareOp;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferRuntime;
import dev.mikuteto.mtlrender.pack.runtime.PackPassExecutor;
import dev.mikuteto.mtlrender.pack.runtime.PackPassFailurePolicy;
import dev.mikuteto.mtlrender.pack.runtime.PackRuntime;
import dev.mikuteto.mtlrender.pack.runtime.PackRuntimeService;
import dev.mikuteto.mtlrender.pack.runtime.PackPassResult;
import dev.mikuteto.mtlrender.pack.uniform.GameState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ShadowOrchestrationIntegrationTest {
	@Test
	void noPackHasNoShadowOrPassExecutorWork() {
		List<String> sourceCalls = new ArrayList<>();
		PackRuntimeService service = new PackRuntimeService(new PackRuntimeService.PackSource() {
			@Override
			public boolean configured() {
				sourceCalls.add("configured");
				return false;
			}

			@Override
			public Optional<ShaderPack> active() {
				throw new AssertionError("no-pack trace read an active pack");
			}

			@Override
			public long version() {
				throw new AssertionError("no-pack trace read a pack version");
			}
		}, PackRuntime.inactive());
		Trace trace = new Trace(0L);
		ShadowPassExecutor.Result result = executor(trace).execute(ShadowFrameSnapshot.noPack(), generations(0L));
		List<PackPassResult> passes = new PackPassExecutor(new PackPassFailurePolicy()).executeSequence(List.of());
		GameState state = new GameState();
		state.publishShadow(ShadowFrameSnapshot.noPack());

		assertTrue(service.render(null, null).isEmpty(), context(trace, ShadowFrameSnapshot.noPack()));
		assertEquals(List.of(), trace.events, context(trace, ShadowFrameSnapshot.noPack()));
		assertFalse(result.rendered(), context(trace, ShadowFrameSnapshot.noPack()));
		assertFalse(result.shadowcomp(), context(trace, ShadowFrameSnapshot.noPack()));
		assertEquals(List.of(), passes, context(trace, ShadowFrameSnapshot.noPack()));
		assertEquals(List.of("configured"), sourceCalls, context(trace, ShadowFrameSnapshot.noPack()));
		assertEquals(0.0F, state.value("shadowProjection").number(0), context(trace, ShadowFrameSnapshot.noPack()));
		assertEquals(Long.MIN_VALUE, service.generation(), context(trace, ShadowFrameSnapshot.noPack()));
		service.close();
	}

	@Test
	void completeGraphKeepsOneGenerationAcrossAllShadowFlags(@TempDir Path directory) throws IOException {
		ShaderPack pack = fixture(directory);
		ShadowTargetPlan targetPlan = ShadowTargetPlan.build(pack, 41L);
		ShadowPassPlan calculated = ShadowMatrixCalculator.calculate(pack, 37.0F, 12.25, 68.0, -4.5);
		assertEquals(targetPlan.mapResolution(), calculated.mapResolution());
		assertEquals(targetPlan.shadowDistance(), calculated.shadowDistance());
		List<ShadowProgramSelection> programs = selections(pack);

		for (int mask = 0; mask < 16; mask++) {
			long generation = 100L + mask;
			ShadowPassPlan plan = new ShadowPassPlan(calculated.mapResolution(), calculated.shadowDistance(),
				calculated.shadowMapFov(), calculated.shadowDistanceRenderMul(), calculated.shadowIntervalSize(),
				calculated.sunPathRotation(), calculated.cullingMode(), calculated.matrices(),
				(mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0);
			ShadowFrameSnapshot frame = ShadowFrameSnapshot.enabled(
				new ShadowFrameSnapshot.PackIdentity(pack.baseFolder(), generation), "minecraft:overworld",
				generation, plan, generation, generation, generation, programs, (mask & 1) == 0);
			Trace trace = new Trace(generation, ShadowTargetPlan.build(pack, generation));
			ShadowPassExecutor.Result result = executor(trace).execute(frame, frame.generations());

			assertEquals((mask & 1) != 0 || (mask & 12) != 0, result.rendered(), context(trace, frame));
			assertTrue(result.finalized(), context(trace, frame));
			assertTrue(result.shadowcomp(), context(trace, frame));
			assertEquals((mask & 1) != 0, trace.terrainCalls == 1, context(trace, frame));
			assertEquals((mask & 12) != 0, trace.featureCalls == 1, context(trace, frame));
			assertEquals((mask & 3) == 3, trace.terrainTranslucent, context(trace, frame));
			assertEquals((mask & 4) != 0, trace.featureEntities, context(trace, frame));
			assertEquals((mask & 8) != 0, trace.featureBlockEntities, context(trace, frame));
			assertEquals((mask & 1) == 0, trace.prepareCalls == 1, context(trace, frame));
			assertEquals(1L, trace.pipelineKinds.stream().filter(ShadowPipelinePolicy.Kind.ALPHA_TEST::equals).count(), context(trace, frame));
			assertEquals(5L, trace.pipelineKinds.stream().filter(ShadowPipelinePolicy.Kind.DEPTH_ONLY::equals).count(), context(trace, frame));
			assertTrue(trace.targetPlan.generation() == generation, context(trace, frame));
			assertTrue(trace.events.indexOf("copy@" + generation) < trace.events.indexOf("shadowcomp@" + generation),
				context(trace, frame));
			assertTrue(trace.events.stream().allMatch(value -> value.endsWith("@" + generation)), context(trace, frame));
			assertFalse(ShadowFeatureBridge.active(), context(trace, frame));
		}
	}

	@Test
	void emptyShadowListsStillClearFinalizeCopyAndRunShadowcomp(@TempDir Path directory) throws IOException {
		ShaderPack pack = fixture(directory);
		ShadowPassPlan plan = ShadowMatrixCalculator.calculate(pack, 12.0F, 1.0, 2.0, 3.0);
		ShadowPassPlan emptyPlan = new ShadowPassPlan(plan.mapResolution(), plan.shadowDistance(), plan.shadowMapFov(),
			plan.shadowDistanceRenderMul(), plan.shadowIntervalSize(), plan.sunPathRotation(), plan.cullingMode(),
			plan.matrices(), false, false, false, false);
		ShadowFrameSnapshot frame = ShadowFrameSnapshot.enabled(new ShadowFrameSnapshot.PackIdentity(pack.baseFolder(), 7L),
			"minecraft:overworld", 7L, emptyPlan, 7L, 7L, 7L, List.of(), false);
		Trace trace = new Trace(7L, ShadowTargetPlan.build(pack, 7L));
		ShadowPassExecutor.Result result = executor(trace).execute(frame, frame.generations());

		assertFalse(result.rendered(), context(trace, frame));
		assertTrue(result.finalized(), context(trace, frame));
		assertTrue(result.shadowcomp(), context(trace, frame));
		assertEquals(1, trace.clearCalls, context(trace, frame));
		assertEquals(1, trace.finalizeCalls, context(trace, frame));
		assertEquals(1, trace.copyCalls, context(trace, frame));
		assertEquals(List.of("matrix@7", "uniform@7", "acquire@7", "clear@7", "programs@7",
			"bridge-enter@7", "bridge-close@7", "bridge-clear@7", "finalize@7", "copy@7", "shadowcomp@7", "bridge-clear@7"),
			trace.events, context(trace, frame));
	}

	@Test
	void failuresAreIsolatedAndAlwaysClearFeatureState(@TempDir Path directory) throws IOException {
		ShaderPack pack = fixture(directory);
		ShadowPassPlan plan = ShadowMatrixCalculator.calculate(pack, 12.0F, 1.0, 2.0, 3.0);
		ShadowFrameSnapshot frame = ShadowFrameSnapshot.enabled(new ShadowFrameSnapshot.PackIdentity(pack.baseFolder(), 9L),
			"minecraft:overworld", 9L, plan, 9L, 9L, 9L, selections(pack), false);

		Trace matrixFailure = new Trace(9L, ShadowTargetPlan.build(pack, 9L));
		matrixFailure.failMatrix = true;
		ShadowPassExecutor.Result matrixResult = executor(matrixFailure).execute(frame, frame.generations());
		assertEquals(List.of("matrices"), matrixResult.stages(), context(matrixFailure, frame));
		assertEquals(List.of("matrix@9", "bridge-clear@9"), matrixFailure.events, context(matrixFailure, frame));

		Trace drawFailure = new Trace(9L, ShadowTargetPlan.build(pack, 9L));
		drawFailure.failTerrain = true;
		drawFailure.failFeature = true;
		ShadowPassExecutor.Result drawResult = executor(drawFailure).execute(frame, frame.generations());
		assertTrue(drawResult.finalized(), context(drawFailure, frame));
		assertTrue(drawFailure.events.contains("finalize@9"), context(drawFailure, frame));
		assertTrue(drawFailure.events.contains("copy@9"), context(drawFailure, frame));
		assertEquals(2, drawFailure.events.stream().filter("bridge-clear@9"::equals).count(), context(drawFailure, frame));
		assertFalse(ShadowFeatureBridge.active(), context(drawFailure, frame));

		Trace shadowcompFailure = new Trace(9L, ShadowTargetPlan.build(pack, 9L));
		shadowcompFailure.failShadowcomp = true;
		assertThrows(IllegalStateException.class, () -> executor(shadowcompFailure).execute(frame, frame.generations()),
			context(shadowcompFailure, frame));
		assertFalse(ShadowFeatureBridge.active(), context(shadowcompFailure, frame));
		assertTrue(shadowcompFailure.events.contains("bridge-clear@9"), context(shadowcompFailure, frame));
	}

	@Test
	void reloadDimensionAndResizeRetireGenerationsWithoutLeaks(@TempDir Path directory) throws IOException {
		ShaderPack pack = fixture(directory);
		List<String> events = new ArrayList<>();
		GbufferRuntime gbuffer = new GbufferRuntime((generation, transition) ->
			events.add("retire:" + generation.identity().targetPlanGeneration() + ":" + transition));
		GbufferRuntime.Generation first = generation(pack, 1L, "minecraft:overworld", events);
		GbufferRuntime.Generation dimension = generation(pack, 2L, "minecraft:the_nether", events);
		GbufferRuntime.Generation resize = generation(pack, 3L, "minecraft:the_nether", events);
		gbuffer.publish(GbufferRuntime.Transition.PACK_RELOAD, Optional.of(first));
		GbufferRuntime.FrameSnapshot active = gbuffer.beginFrame();
		assertEquals(1L, active.generation().orElseThrow().identity().targetPlanGeneration(), context(events));
		gbuffer.endFrame(active);
		gbuffer.publish(GbufferRuntime.Transition.DIMENSION_CHANGE, Optional.of(dimension));
		gbuffer.publish(GbufferRuntime.Transition.RESIZE, Optional.of(resize));
		assertEquals(List.of("install:1", "remove:1", "retire:1:DIMENSION_CHANGE", "install:2",
			"remove:2", "retire:2:RESIZE", "install:3"), events, context(events));
		assertEquals(3L, gbuffer.published().orElseThrow().identity().targetPlanGeneration(), context(events));
		gbuffer.close();

		AtomicLong submitted = new AtomicLong();
		AtomicLong completed = new AtomicLong();
		List<FakeTargetSet> created = new ArrayList<>();
		ShadowTargetRuntime targets = new ShadowTargetRuntime(plan -> {
			FakeTargetSet result = new FakeTargetSet(plan.generation());
			created.add(result);
			return result;
		}, submitted::get, completed::get);
		ShadowTargetPlan firstPlan = ShadowTargetPlan.build(pack, 1L);
		ShadowTargetPlan secondPlan = ShadowTargetPlan.build(pack, 2L);
		assertTrue(firstPlan.target("shadowtex0").present(), context(events));
		assertTrue(firstPlan.target("shadowtex1").present(), context(events));
		assertTrue(firstPlan.target("shadowcolor0").present(), context(events));
		assertTrue(secondPlan.target("shadowtex0").present(), context(events));
		assertTrue(secondPlan.target("shadowtex1").present(), context(events));
		assertTrue(secondPlan.target("shadowcolor0").present(), context(events));
		targets.publish(firstPlan);
		submitted.set(4L);
		targets.publish(secondPlan);
		completed.set(4L);
		targets.collect(completed.get());
		assertFalse(created.get(0).closed, context(events));
		completed.set(5L);
		targets.collect(completed.get());
		assertTrue(created.get(0).closed, context(events));
		targets.close();
		assertTrue(created.get(1).closed, context(events));
	}

	@Test
	void resolverPolicyFeatureBridgeAndGameStateUseTheSameFrame(@TempDir Path directory) throws IOException {
		ShaderPack pack = fixture(directory);
		List<ShadowProgramSelection> selections = selections(pack);
		ShadowProgramSelection entity = selections.stream().filter(value -> value.requestedKind() == ShadowProgramKind.ENTITIES).findFirst().orElseThrow();
		ShadowProgramSelection block = selections.stream().filter(value -> value.requestedKind() == ShadowProgramKind.BLOCK_ENTITIES).findFirst().orElseThrow();
		ShadowPassPlan plan = ShadowMatrixCalculator.calculate(pack, 20.0F, 0.0, 64.0, 0.0);
		ShadowFrameSnapshot frame = ShadowFrameSnapshot.enabled(new ShadowFrameSnapshot.PackIdentity(pack.baseFolder(), 22L),
			"minecraft:overworld", 22L, plan, 22L, 22L, 22L, selections, false);
		GameState state = new GameState();
		state.publishShadow(frame);
		assertEquals(plan.matrices().projection().m00(), state.value("shadowProjection").number(0), 0.00001F, context(List.of()));
		try (ShadowFeatureBridge.Scope ignored = ShadowFeatureBridge.enter(plan, entity, block)) {
			try (ShadowFeatureBridge.Scope entityScope = ShadowFeatureBridge.enterFeature(ShadowFeatureBridge.FeatureKind.ENTITY)) {
				assertTrue(ShadowFeatureBridge.shouldSubmitEntity(), context(List.of()));
				assertFalse(ShadowFeatureBridge.shouldSubmitBlockEntity(), context(List.of()));
			}
			try (ShadowFeatureBridge.Scope blockScope = ShadowFeatureBridge.enterFeature(ShadowFeatureBridge.FeatureKind.BLOCK_ENTITY)) {
				assertFalse(ShadowFeatureBridge.shouldSubmitEntity(), context(List.of()));
				assertTrue(ShadowFeatureBridge.shouldSubmitBlockEntity(), context(List.of()));
			}
		}
		assertFalse(ShadowFeatureBridge.active(), context(List.of()));
		assertEquals(ShadowPassPlan.CullingMode.ADVANCED, plan.cullingMode(), context(List.of()));
		assertNotEquals(plan.matrices(), ShadowMatrixCalculator.calculate(pack, 21.0F, 0.0, 64.0, 0.0).matrices());
	}

	private static ShadowPassExecutor executor(Trace trace) {
		return new ShadowPassExecutor(
			frame -> {
				trace.add("matrix", frame);
				if (trace.failMatrix) throw new IllegalStateException("matrix failure");
				return frame.plan().matrices();
			},
			(frame, matrices) -> {
				trace.add("uniform", frame);
				trace.state.publishShadow(frame);
			},
			frame -> {
				trace.add("programs", frame);
				return frame.programs();
			},
			(frame, program, policy) -> {
				trace.add("pipeline", frame);
				Optional<dev.mikuteto.mtlrender.pack.gbuffer.GbufferStateOverride.AlphaTest> alpha =
					program.requestedKind() == ShadowProgramKind.TERRAIN_CUTOUT
						? Optional.of(new dev.mikuteto.mtlrender.pack.gbuffer.GbufferStateOverride.AlphaTest(CompareOp.GREATER_THAN, 0.4F))
						: Optional.empty();
				ShadowPipelinePolicy.Decision decision = policy.choose(program, trace.targetPlan,
					Optional.empty(), alpha);
				trace.pipelineKinds.add(decision.kind());
				if (trace.failPipeline) throw new IllegalStateException("pipeline failure");
			},
			(plan, cullingMode, translucent) -> {
				trace.terrainCalls++;
				trace.terrainTranslucent = translucent;
				trace.add("terrain", trace.generation);
				if (trace.failTerrain) throw new IllegalStateException("terrain failure");
			},
			(plan, entities, blockEntities) -> {
				trace.featureCalls++;
				trace.featureEntities = entities;
				trace.featureBlockEntities = blockEntities;
				trace.add("features", trace.generation);
				if (trace.failFeature) throw new IllegalStateException("feature failure");
			},
			new ShadowPassExecutor.FeatureBridge() {
				@Override
				public Scope enter(ShadowPassPlan plan, List<ShadowProgramSelection> programs) {
					trace.add("bridge-enter", trace.generation);
					return () -> trace.add("bridge-close", trace.generation);
				}

				@Override
				public void clear() {
					trace.add("bridge-clear", trace.generation);
				}
			},
			new ShadowPassExecutor.TargetRuntime() {
				@Override
				public Target acquire(ShadowFrameSnapshot frame) {
					trace.add("acquire", frame);
					if (trace.failAcquire) throw new IllegalStateException("target failure");
					return () -> frame.targetGeneration();
				}

				@Override
				public void clear(Target target) {
					trace.clearCalls++;
					trace.add("clear", trace.generation);
				}

				@Override
				public void finalizeTarget(Target target) {
					trace.finalizeCalls++;
					trace.add("finalize", trace.generation);
					if (trace.failFinalize) throw new IllegalStateException("finalize failure");
				}

				@Override
				public void copyDepth(Target target) {
					trace.copyCalls++;
					trace.add("copy", trace.generation);
					if (trace.failCopy) throw new IllegalStateException("copy failure");
				}
			},
			frame -> {
				trace.prepareCalls++;
				trace.add("prepare", frame);
			},
			programs -> {
				trace.add("shadowcomp", trace.generation);
				if (trace.failShadowcomp) throw new IllegalStateException("shadowcomp failure");
			});
	}

	private static List<ShadowProgramSelection> selections(ShaderPack pack) {
		ShadowProgramResolver resolver = new ShadowProgramResolver(pack.programSet("minecraft:overworld"), pack.properties());
		List<ShadowProgramSelection> result = new ArrayList<>();
		for (ShadowProgramKind kind : ShadowProgramKind.values()) result.add(resolver.resolve(kind, true));
		return List.copyOf(result);
	}

	private static ShaderPack fixture(Path directory) throws IOException {
		Path root = directory.resolve("world0");
		Files.createDirectories(root);
		Files.writeString(directory.resolve("dimension.properties"), "dimension.world0=*\n");
		Files.writeString(directory.resolve("shaders.properties"), "shadow.culling=advanced\nprepareBeforeShadow=true\n");
		for (ShadowProgramKind kind : ShadowProgramKind.values()) {
			String name = kind.program().sourceName();
			Files.writeString(root.resolve(name + ".vsh"), "void main() {}\n");
			Files.writeString(root.resolve(name + ".fsh"), "void main() {}\n");
		}
		Files.writeString(root.resolve("shadowcomp.vsh"), "void main() {}\n");
		Files.writeString(root.resolve("shadowcomp.fsh"), "uniform sampler2D shadowtex1;\nuniform sampler2D shadowcolor0;\nvoid main() {}\n");
		return ShaderPack.load(directory, Map.of());
	}

	private static GbufferRuntime.Generation generation(ShaderPack pack, long version, String dimension, List<String> events) {
		GbufferRuntime.Identity identity = new GbufferRuntime.Identity(pack.baseFolder(), version, dimension, version, version, version, version);
		return new GbufferRuntime.Generation(identity, new GbufferRuntime.Providers() {
			@Override public void install() { events.add("install:" + version); }
			@Override public void remove() { events.add("remove:" + version); }
		});
	}

	private static ShadowFrameSnapshot.Generations generations(long value) {
		return new ShadowFrameSnapshot.Generations(value, value, value, value);
	}

	private static String context(Trace trace, ShadowFrameSnapshot frame) {
		return "trace=" + trace.events + " generations=" + frame.generations();
	}

	private static String context(List<String> events) {
		return "events=" + events;
	}

	private static final class Trace {
		private final long generation;
		private final ShadowTargetPlan targetPlan;
		private final List<String> events = new ArrayList<>();
		private final List<ShadowPipelinePolicy.Kind> pipelineKinds = new ArrayList<>();
		private final GameState state = new GameState();
		private int terrainCalls;
		private int featureCalls;
		private int prepareCalls;
		private int clearCalls;
		private int finalizeCalls;
		private int copyCalls;
		private boolean terrainTranslucent;
		private boolean featureEntities;
		private boolean featureBlockEntities;
		private boolean failMatrix;
		private boolean failAcquire;
		private boolean failPipeline;
		private boolean failTerrain;
		private boolean failFeature;
		private boolean failFinalize;
		private boolean failCopy;
		private boolean failShadowcomp;

		private Trace(long generation) {
			this(generation, null);
		}

		private Trace(long generation, ShadowTargetPlan targetPlan) {
			this.generation = generation;
			this.targetPlan = targetPlan;
		}

		private void add(String name, ShadowFrameSnapshot frame) {
			add(name, frame.generations().reload());
		}

		private void add(String name, long value) {
			events.add(name + "@" + value);
		}
	}

	private static final class FakeTargetSet implements ShadowTargetRuntime.TargetSet {
		private final long generation;
		private boolean closed;

		private FakeTargetSet(long generation) {
			this.generation = generation;
		}

		@Override
		public long generation() {
			return generation;
		}

		@Override
		public void close() {
			closed = true;
		}
	}
}
