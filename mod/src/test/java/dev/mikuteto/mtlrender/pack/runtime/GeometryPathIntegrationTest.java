package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GeometryPathIntegrationTest {
	private static final String PACK = "geometry-fixtures";
	private static final String DIMENSION = "minecraft:overworld";
	private static final GeometryPathSelector.MeshContract MESH = new GeometryPathSelector.MeshContract(
		Set.of(GeometryPlan.PrimitiveTopology.POINTS, GeometryPlan.PrimitiveTopology.LINES,
			GeometryPlan.PrimitiveTopology.TRIANGLES), Set.of(GeometryPlan.SideEffect.IMAGE,
			GeometryPlan.SideEffect.SSBO, GeometryPlan.SideEffect.ATOMIC));

	@Test
	void selectedGeometryPathIsTheOnlyCompiledCachedAndRecordedPath() throws Exception {
		ShaderPack pack = ShaderPack.load(fixture(), Map.of());
		List<Fixture> fixtures = List.of(
			fixture("strict-pass-through", "pass", GeometryPathSelector.AnalyzerResult.passThrough(),
				geometry(GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.LayerSemantics.NONE, 3, 1, Set.of()),
				GeometryPathSelector.Path.PASS_THROUGH),
			fixture("layer-instancing", "layer", GeometryPathSelector.AnalyzerResult.layerInstance(),
				geometry(GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.LayerSemantics.INSTANCE, 3, 1, Set.of()),
				GeometryPathSelector.Path.LAYER_INSTANCED),
			fixture("bounded-mesh", "mesh", GeometryPathSelector.AnalyzerResult.general(),
				geometry(GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.LayerSemantics.NONE, 256, 512, Set.of()),
				GeometryPathSelector.Path.MESH),
			fixture("over-budget-mesh", "over-budget", GeometryPathSelector.AnalyzerResult.general(),
				geometry(GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.LayerSemantics.NONE, 257, 512, Set.of()),
				GeometryPathSelector.Path.COMPUTE),
			fixture("compute-fallback", "fallback", GeometryPathSelector.AnalyzerResult.general(),
				geometry(GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.LayerSemantics.NONE, 8, 8,
					Set.of(GeometryPlan.SideEffect.IMAGE, GeometryPlan.SideEffect.SSBO, GeometryPlan.SideEffect.ATOMIC)),
				GeometryPathSelector.Path.COMPUTE));

		PackProgramCache<String> cache = new PackProgramCache<>(() -> new PackProgramCache.State(9, true), ignored -> { });
		List<String> compiled = new ArrayList<>();
		for (Fixture fixture : fixtures) {
			GeometryPathSelector.Result result = select(fixture);
			assertEquals(fixture.selected(), result.selectedPath().orElseThrow());
			assertEquals(1, result.selectedPath().stream().count());
			assertEquals(fixture.budget(), result.outputBudget());
			assertEquals(fixture.effects(), result.sideEffects());
			if (fixture.name().equals("compute-fallback")) {
				assertEquals(GeometryPathSelector.RejectionReason.UNREPRESENTABLE_SIDE_EFFECTS,
					result.diagnostics().getFirst().reason());
			}
			cache.result(DIMENSION, new GeometryPathSelector.ProgramIdentity(
				GeometryPathSelector.ProgramKind.RENDER, fixture.program()), () -> {
				compiled.add(fixture.name());
				return PackProgramCache.ProgramResult.success(fixture.selected().name());
			});
		}
		assertEquals(fixtures.stream().map(Fixture::name).toList(), compiled);
		assertEquals(5, cache.generation(DIMENSION).orElseThrow().results().size());
		cache.close();

		PackRuntimeService service = new PackRuntimeService(new PackRuntimeService.PackSource() {
			@Override public boolean configured() { return false; }
			@Override public Optional<ShaderPack> active() { throw new AssertionError("inactive selector queried pack"); }
			@Override public long version() { throw new AssertionError("inactive selector queried version"); }
		}, PackRuntime.inactive());
		assertTrue(service.render(null, null).isEmpty());
		service.close();
		assertTrue(pack.programSet(DIMENSION).program("gbuffers_basic").isPresent());
	}

	@Test
	void rejectedAttemptsStayDiagnosticOnlyAndNamesRemainIsolated() {
		Fixture fixture = fixture("diagnostic", "ordinary", GeometryPathSelector.AnalyzerResult.general(),
			geometry(GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.LayerSemantics.NONE, 257, 513,
				Set.of(GeometryPlan.SideEffect.ATOMIC)), GeometryPathSelector.Path.COMPUTE);
		GeometryPathSelector.Result result = GeometryPathSelector.select(new GeometryPathSelector.Input(PACK, DIMENSION,
			new GeometryPathSelector.ProgramIdentity(GeometryPathSelector.ProgramKind.RENDER, fixture.program()), fixture.plan(),
			fixture.analyzer(), new GeometryPathSelector.MeshContract(Set.of(), Set.of()), Optional.of(computePlan(fixture.plan()))),
			(path, ignored) -> GeometryPathSelector.Attempt.rejected(GeometryPathSelector.RejectionReason.LOWERING_FAILED, path.name()));
		assertTrue(result.selectedPath().isEmpty());
		assertEquals(List.of(GeometryPathSelector.Path.MESH, GeometryPathSelector.Path.COMPUTE), result.attempts());
		assertEquals(2, result.diagnostics().size());
		assertEquals("ordinary", result.program().name());
		GeometryPathSelector.Result compute = GeometryPathSelector.select(new GeometryPathSelector.Input(PACK, DIMENSION,
			new GeometryPathSelector.ProgramIdentity(GeometryPathSelector.ProgramKind.COMPUTE, "ordinary"), fixture.plan(),
			fixture.analyzer(), MESH, Optional.of(computePlan(fixture.plan()))), (path, ignored) -> GeometryPathSelector.Attempt.success());
		assertTrue(compute.selectedPath().isEmpty());
		assertFalse(compute.attempts().contains(GeometryPathSelector.Path.COMPUTE));
	}

	private static GeometryPathSelector.Result select(Fixture fixture) {
		GeometryPathSelector.MeshContract mesh = fixture.effects().isEmpty() ? MESH
			: new GeometryPathSelector.MeshContract(MESH.topologies(), Set.of());
		return GeometryPathSelector.select(new GeometryPathSelector.Input(PACK, DIMENSION,
			new GeometryPathSelector.ProgramIdentity(GeometryPathSelector.ProgramKind.RENDER, fixture.program()), fixture.plan(),
			fixture.analyzer(), mesh, Optional.of(computePlan(fixture.plan()))), (path, ignored) -> GeometryPathSelector.Attempt.success());
	}

	private static GeometryComputePlan computePlan(GeometryPlan plan) {
		return GeometryComputePlan.build(new GeometryComputePlan.Context(PACK, DIMENSION, "fixture", "geometry"),
			new GeometryPlan(plan.presence(), plan.topology(), plan.outputBudget(), plan.sideEffects(),
				new GeometryPlan.LayerMetadata(GeometryPlan.LayerSemantics.NONE, 0), GeometryPlan.GeometryPath.COMPUTE,
				plan.stages(), plan.entryPoints()), new GeometryComputePlan.Input(1, 16, 12),
			new GeometryComputePlan.Limits(10_000_000), true);
	}

	private static GeometryPlan geometry(GeometryPlan.GeometryPresence presence, GeometryPlan.LayerSemantics layers,
		int vertices, int primitives, Set<GeometryPlan.SideEffect> effects) {
		return new GeometryPlan(presence, GeometryPlan.PrimitiveTopology.TRIANGLES,
			new GeometryPlan.OutputBudget(vertices, primitives), effects,
			new GeometryPlan.LayerMetadata(layers, layers == GeometryPlan.LayerSemantics.NONE ? 0 : 3),
			layers == GeometryPlan.LayerSemantics.INSTANCE ? GeometryPlan.GeometryPath.LAYER_INSTANCED
				: vertices > 256 || primitives > 512 ? GeometryPlan.GeometryPath.COMPUTE : GeometryPlan.GeometryPath.MESH,
			new GeometryPlan.StageIdentities(Map.of(Program.Stage.VERTEX, "v", Program.Stage.FRAGMENT, "f",
				Program.Stage.GEOMETRY, "g")), GeometryPlan.EntryPoints.generate(PACK + vertices + primitives));
	}

	private static Fixture fixture(String name, String program, GeometryPathSelector.AnalyzerResult analyzer,
		GeometryPlan plan, GeometryPathSelector.Path selected) {
		return new Fixture(name, program, analyzer, plan, selected, plan.outputBudget(), plan.sideEffects());
	}

	private static Path fixture() throws Exception {
		Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
			Path path = candidate.resolve("mod/run/shaderpacks/geometry-fixtures");
			if (Files.isDirectory(path)) return path;
		}
		throw new Exception("cannot find geometry fixture");
	}

	private record Fixture(String name, String program, GeometryPathSelector.AnalyzerResult analyzer,
		GeometryPlan plan, GeometryPathSelector.Path selected, GeometryPlan.OutputBudget budget,
		Set<GeometryPlan.SideEffect> effects) { }
}
