package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.Program;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GeometryPathSelectorTest {
	private static final GeometryPathSelector.MeshContract ALL_MESH = new GeometryPathSelector.MeshContract(
		Set.of(GeometryPlan.PrimitiveTopology.POINTS, GeometryPlan.PrimitiveTopology.LINES, GeometryPlan.PrimitiveTopology.TRIANGLES),
		Set.of(GeometryPlan.SideEffect.IMAGE, GeometryPlan.SideEffect.SSBO, GeometryPlan.SideEffect.ATOMIC));

	@Test
	void ordersPassThroughAndLayerSpecializationsBeforeMeshAndCompute() {
		GeometryPathSelector.Result pass = select(geometry(GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.LayerSemantics.NONE,
			new GeometryPlan.OutputBudget(4, 2), Set.of()), GeometryPathSelector.AnalyzerResult.passThrough(), ALL_MESH,
			attempts(GeometryPathSelector.Path.PASS_THROUGH));
		assertEquals(GeometryPathSelector.Path.PASS_THROUGH, pass.selected());
		assertEquals(List.of(GeometryPathSelector.Path.PASS_THROUGH, GeometryPathSelector.Path.MESH, GeometryPathSelector.Path.COMPUTE), pass.attempts());

		GeometryPathSelector.Result layer = select(geometry(GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.LayerSemantics.INSTANCE,
			new GeometryPlan.OutputBudget(4, 2), Set.of()), GeometryPathSelector.AnalyzerResult.layerInstance(), ALL_MESH,
			attempts(GeometryPathSelector.Path.LAYER_INSTANCED));
		assertEquals(GeometryPathSelector.Path.LAYER_INSTANCED, layer.selected());
	}

	@Test
	void meshLimitsAreInclusiveAndUnsupportedSemanticsFallThrough() {
		for (GeometryPlan.OutputBudget budget : List.of(new GeometryPlan.OutputBudget(256, 512), new GeometryPlan.OutputBudget(1, 512), new GeometryPlan.OutputBudget(256, 1))) {
			GeometryPathSelector.Result result = select(geometry(GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.LayerSemantics.NONE, budget, Set.of()),
				GeometryPathSelector.AnalyzerResult.general(), ALL_MESH, attempts(GeometryPathSelector.Path.MESH));
			assertEquals(GeometryPathSelector.Path.MESH, result.selected());
		}
		GeometryPathSelector.Result rejected = select(geometry(GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.LayerSemantics.NONE,
			new GeometryPlan.OutputBudget(257, 512), Set.of()), GeometryPathSelector.AnalyzerResult.general(), ALL_MESH,
			attempts(GeometryPathSelector.Path.COMPUTE));
		assertEquals(GeometryPathSelector.Path.COMPUTE, rejected.selected());
		assertTrue(rejected.diagnostics().getFirst().reason() == GeometryPathSelector.RejectionReason.OUTPUT_LIMIT);

		GeometryPathSelector.MeshContract noImage = new GeometryPathSelector.MeshContract(ALL_MESH.topologies(), Set.of());
		GeometryPathSelector.Result sideEffect = select(geometry(GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.LayerSemantics.NONE,
			new GeometryPlan.OutputBudget(4, 2), Set.of(GeometryPlan.SideEffect.IMAGE)), GeometryPathSelector.AnalyzerResult.general(), noImage,
			attempts(GeometryPathSelector.Path.COMPUTE));
		assertEquals(GeometryPathSelector.Path.COMPUTE, sideEffect.selected());
	}

	@Test
	void specializedFailureRetainsRemainingAttemptsAndDiagnostics() {
		GeometryPathSelector.Result result = select(geometry(GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.LayerSemantics.NONE,
			new GeometryPlan.OutputBudget(4, 2), Set.of()), GeometryPathSelector.AnalyzerResult.general(), ALL_MESH,
			(path, ignored) -> path == GeometryPathSelector.Path.MESH
				? GeometryPathSelector.Attempt.rejected(GeometryPathSelector.RejectionReason.LOWERING_FAILED, "mesh compiler rejected")
				: GeometryPathSelector.Attempt.success());
		assertEquals(GeometryPathSelector.Path.COMPUTE, result.selected());
		assertEquals(List.of(), result.remainingAttempts());
		assertEquals(GeometryPathSelector.RejectionReason.LOWERING_FAILED, result.diagnostics().getFirst().reason());
	}

	@Test
	void allRejectionsAreContextualAndComputeProgramsAreIsolated() {
		GeometryPathSelector.Result result = select(geometry(GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.LayerSemantics.NONE,
			new GeometryPlan.OutputBudget(257, 513), Set.of(GeometryPlan.SideEffect.ATOMIC)), GeometryPathSelector.AnalyzerResult.general(),
			new GeometryPathSelector.MeshContract(Set.of(), Set.of()), (path, ignored) -> GeometryPathSelector.Attempt.rejected(
				GeometryPathSelector.RejectionReason.LOWERING_FAILED, path.name()));
		assertTrue(result.selectedPath().isEmpty());
		assertEquals(2, result.diagnostics().size());
		assertEquals(GeometryPathSelector.Path.MESH, result.diagnostics().getFirst().path());
		assertEquals(GeometryPathSelector.Path.COMPUTE, result.diagnostics().getLast().path());

		GeometryPathSelector.Result compute = select(geometry(GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.LayerSemantics.NONE,
			new GeometryPlan.OutputBudget(4, 2), Set.of()), GeometryPathSelector.AnalyzerResult.general(), ALL_MESH,
			new GeometryPathSelector.ProgramIdentity(GeometryPathSelector.ProgramKind.COMPUTE, "same"), attempts());
		assertTrue(compute.selectedPath().isEmpty());
		assertEquals(GeometryPathSelector.RejectionReason.COMPUTE_PROGRAM, compute.terminalRejection().diagnostics().getFirst().reason());
		assertFalse(compute.attempts().contains(GeometryPathSelector.Path.COMPUTE));
	}

	@Test
	void optionalProgramFailureDoesNotChangeAnotherProgramIdentity() {
		GeometryPathSelector.Result render = select(geometry(GeometryPlan.GeometryPresence.ABSENT, GeometryPlan.LayerSemantics.NONE,
			new GeometryPlan.OutputBudget(1, 1), Set.of()), GeometryPathSelector.AnalyzerResult.passThrough(), ALL_MESH, attempts(GeometryPathSelector.Path.PASS_THROUGH));
		GeometryPathSelector.Result other = select(geometry(GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.LayerSemantics.NONE,
			new GeometryPlan.OutputBudget(4, 2), Set.of()), GeometryPathSelector.AnalyzerResult.general(), ALL_MESH,
			new GeometryPathSelector.ProgramIdentity(GeometryPathSelector.ProgramKind.RENDER, "same"), attempts(GeometryPathSelector.Path.MESH));
		assertEquals(GeometryPathSelector.ProgramKind.RENDER, render.program().kind());
		assertEquals("same", other.program().name());
		assertEquals(GeometryPathSelector.Path.MESH, other.selected());
	}

	private static GeometryPathSelector.Result select(GeometryPlan plan, GeometryPathSelector.AnalyzerResult analyzer,
		GeometryPathSelector.MeshContract mesh, GeometryPathSelector.Lowering lowering) {
		return select(plan, analyzer, mesh, new GeometryPathSelector.ProgramIdentity(GeometryPathSelector.ProgramKind.RENDER, "program"), lowering);
	}

	private static GeometryPathSelector.Result select(GeometryPlan plan, GeometryPathSelector.AnalyzerResult analyzer,
		GeometryPathSelector.MeshContract mesh, GeometryPathSelector.ProgramIdentity identity, GeometryPathSelector.Lowering lowering) {
		Optional<GeometryComputePlan> computePlan = Optional.empty();
		if (plan.presence() == GeometryPlan.GeometryPresence.PRESENT && plan.layers().semantics() == GeometryPlan.LayerSemantics.NONE) {
			GeometryPlan computeGeometry = new GeometryPlan(plan.presence(), plan.topology(), plan.outputBudget(), plan.sideEffects(), plan.layers(),
				GeometryPlan.GeometryPath.COMPUTE, plan.stages(), plan.entryPoints());
			computePlan = Optional.of(GeometryComputePlan.build(new GeometryComputePlan.Context("pack", "overworld", "program", "geometry"),
				computeGeometry, new GeometryComputePlan.Input(1, 16, 12), new GeometryComputePlan.Limits(1_000_000), true));
		}
		return GeometryPathSelector.select(new GeometryPathSelector.Input("pack", "overworld", identity, plan, analyzer, mesh, computePlan), lowering);
	}

	private static GeometryPathSelector.Lowering attempts(GeometryPathSelector.Path... accepted) {
		return (path, ignored) -> List.of(accepted).contains(path) ? GeometryPathSelector.Attempt.success()
			: GeometryPathSelector.Attempt.rejected(GeometryPathSelector.RejectionReason.LOWERING_FAILED, "fixture rejection");
	}

	private static GeometryPlan geometry(GeometryPlan.GeometryPresence presence, GeometryPlan.LayerSemantics semantics,
		GeometryPlan.OutputBudget budget, Set<GeometryPlan.SideEffect> effects) {
		return new GeometryPlan(presence, GeometryPlan.PrimitiveTopology.TRIANGLES, budget, effects,
			new GeometryPlan.LayerMetadata(semantics, semantics == GeometryPlan.LayerSemantics.NONE ? 0 : 2),
			presence == GeometryPlan.GeometryPresence.ABSENT ? GeometryPlan.GeometryPath.PASS_THROUGH
				: semantics == GeometryPlan.LayerSemantics.INSTANCE ? GeometryPlan.GeometryPath.LAYER_INSTANCED
					: budget.vertices() > GeometryPlan.MAX_MESH_VERTICES || budget.primitives() > GeometryPlan.MAX_MESH_PRIMITIVES
						? GeometryPlan.GeometryPath.COMPUTE : GeometryPlan.GeometryPath.MESH,
			new GeometryPlan.StageIdentities(Map.of(Program.Stage.VERTEX, "v", Program.Stage.FRAGMENT, "f",
				Program.Stage.GEOMETRY, "g")), GeometryPlan.EntryPoints.generate("fixture"));
	}
}
