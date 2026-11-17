package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.Program;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PackRuntimeGeometryPathTest {
	@Test
	void emptySelectorDoesNoPackWork() {
		List<String> calls = new java.util.ArrayList<>();
		PackRuntimeService service = new PackRuntimeService(new PackRuntimeService.PackSource() {
			@Override
			public boolean configured() {
				calls.add("configured");
				return false;
			}

			@Override
			public Optional<dev.mikuteto.mtlrender.pack.ShaderPack> active() {
				throw new AssertionError("active pack was queried");
			}

			@Override
			public long version() {
				throw new AssertionError("pack version was queried");
			}
		}, PackRuntime.inactive());
		assertTrue(service.render(null, null).isEmpty());
		assertEquals(List.of("configured"), calls);
		service.close();
	}

	@Test
	void renderAndComputeNamesRemainDistinctAndPassOrderIsStable() {
		PackProgramCache<String> cache = new PackProgramCache<>(
			() -> new PackProgramCache.State(4L, true), ignored -> { });
		cache.result("overworld", new GeometryPathSelector.ProgramIdentity(
			GeometryPathSelector.ProgramKind.RENDER, "same"), () -> PackProgramCache.ProgramResult.success("render"));
		cache.result("overworld", new GeometryPathSelector.ProgramIdentity(
			GeometryPathSelector.ProgramKind.COMPUTE, "same"), () -> PackProgramCache.ProgramResult.success("compute"));
		assertEquals("render", cache.generation("overworld").orElseThrow().results().get(
			new PackProgramCache.ProgramIdentity(GeometryPathSelector.ProgramKind.RENDER, "same")).pipeline().orElseThrow());
		assertEquals("compute", cache.generation("overworld").orElseThrow().results().get(
			new PackProgramCache.ProgramIdentity(GeometryPathSelector.ProgramKind.COMPUTE, "same")).pipeline().orElseThrow());
		assertEquals(List.of(PassKind.SETUP, PassKind.BEGIN, PassKind.SHADOWCOMP, PassKind.PREPARE,
			PassKind.DEFERRED, PassKind.COMPOSITE, PassKind.FINAL), PackRuntimeService.scheduledKinds(true));
		assertEquals(Set.of(GeometryPathSelector.Path.MESH, GeometryPathSelector.Path.COMPUTE),
			Set.copyOf(GeometryPathSelector.select(new GeometryPathSelector.Input("pack", "overworld",
				new GeometryPathSelector.ProgramIdentity(GeometryPathSelector.ProgramKind.RENDER, "same"),
				new GeometryPlan(GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.PrimitiveTopology.TRIANGLES,
					new GeometryPlan.OutputBudget(257, 513), Set.of(), new GeometryPlan.LayerMetadata(GeometryPlan.LayerSemantics.NONE, 0),
					GeometryPlan.GeometryPath.COMPUTE, new GeometryPlan.StageIdentities(java.util.Map.of(
						Program.Stage.VERTEX, "v", Program.Stage.FRAGMENT, "f", Program.Stage.GEOMETRY, "g")),
					GeometryPlan.EntryPoints.generate("test")), GeometryPathSelector.AnalyzerResult.general(),
				new GeometryPathSelector.MeshContract(Set.of(), Set.of()), Optional.empty()),
				(path, ignored) -> GeometryPathSelector.Attempt.rejected(
					GeometryPathSelector.RejectionReason.LOWERING_FAILED, path.name())).attempts()));
		cache.close();
	}
}
