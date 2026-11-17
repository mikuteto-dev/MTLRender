package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.Program;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GeometryPlanTest {
	@Test
	void selectsEveryExecutionPath() {
		assertEquals(GeometryPlan.GeometryPath.PASS_THROUGH, plan(false, 1, 1, Set.of()).path());
		assertEquals(GeometryPlan.GeometryPath.LAYER_INSTANCED, layerPlan().path());
		assertEquals(GeometryPlan.GeometryPath.MESH, plan(true, 256, 512, Set.of()).path());
		assertEquals(GeometryPlan.GeometryPath.COMPUTE, plan(true, 257, 512, Set.of()).path());
	}

	@Test
	void preservesAllSideEffectsAndTopologies() {
		for (GeometryPlan.PrimitiveTopology topology : GeometryPlan.PrimitiveTopology.values()) {
			for (Set<GeometryPlan.SideEffect> effects : Set.of(Set.<GeometryPlan.SideEffect>of(), Set.of(GeometryPlan.SideEffect.IMAGE),
				Set.of(GeometryPlan.SideEffect.SSBO), Set.of(GeometryPlan.SideEffect.ATOMIC),
				Set.of(GeometryPlan.SideEffect.IMAGE, GeometryPlan.SideEffect.SSBO, GeometryPlan.SideEffect.ATOMIC))) {
				GeometryPlan plan = plan(true, 256, 512, effects, topology);
				assertEquals(topology, plan.topology());
				assertEquals(effects, plan.sideEffects());
				assertEquals(GeometryPlan.GeometryPath.MESH, plan.path());
			}
		}
	}

	@Test
	void roundTripRetainsGeometryAndExcludesCompute() {
		PackProgramArtifact artifact = artifact(true);
		GeometryPlan.GeometryMetadata metadata = metadata(GeometryPlan.GeometryPresence.PRESENT,
			new GeometryPlan.OutputBudget(4, 2), Set.of(), stages(true));
		GeometryPlan result = GeometryPlan.select(artifact, metadata);
		assertEquals(GeometryPlan.GeometryPresence.PRESENT, result.presence());
		assertTrue(result.geometryEntryPoint().isPresent());
		assertTrue(artifact.request().source(Program.Stage.GEOMETRY).isPresent());
		assertFalse(artifact.request().source(Program.Stage.COMPUTE).isPresent());
		assertThrows(IllegalArgumentException.class, () -> new PackProgramRequest("pack", "", "compute",
			Map.of(Program.Stage.COMPUTE, "compute")));
	}

	@Test
	void acceptsMetalBoundariesAndRejectsInvalidMeshBudgets() {
		assertEquals(GeometryPlan.GeometryPath.MESH, plan(true, 256, 512, Set.of()).path());
		assertEquals(GeometryPlan.GeometryPath.COMPUTE, plan(true, 257, 512, Set.of()).path());
		assertEquals(GeometryPlan.GeometryPath.COMPUTE, plan(true, 256, 513, Set.of()).path());
		assertThrows(IllegalArgumentException.class, () -> new GeometryPlan.OutputBudget(0, 1));
		assertThrows(IllegalArgumentException.class, () -> new GeometryPlan.OutputBudget(1, -1));
		assertThrows(IllegalArgumentException.class, () -> new GeometryPlan(GeometryPlan.GeometryPresence.PRESENT,
			GeometryPlan.PrimitiveTopology.TRIANGLES, new GeometryPlan.OutputBudget(257, 1), Set.of(), layersNone(),
			GeometryPlan.GeometryPath.MESH, stages(true), GeometryPlan.EntryPoints.generate("bad")));
	}

	@Test
	void rejectsMissingIdentitiesAndInconsistentLayers() {
		assertThrows(IllegalArgumentException.class, () -> new GeometryPlan.StageIdentities(
			Map.of(Program.Stage.VERTEX, "v")));
		assertThrows(IllegalArgumentException.class, () -> new GeometryPlan.LayerMetadata(
			GeometryPlan.LayerSemantics.NONE, 1));
		assertThrows(IllegalArgumentException.class, () -> new GeometryPlan.LayerMetadata(
			GeometryPlan.LayerSemantics.INSTANCE, 0));
		assertThrows(IllegalArgumentException.class, () -> GeometryPlan.select(artifact(true), new GeometryPlan.GeometryMetadata(
			GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.PrimitiveTopology.POINTS,
			new GeometryPlan.OutputBudget(4, 2), Set.of(), layersNone(), stages(false))));
	}

	@Test
	void generatedEntryPointsAreDeterministicAndUnique() {
		GeometryPlan first = plan(true, 4, 2, Set.of());
		GeometryPlan second = plan(true, 4, 2, Set.of());
		assertEquals(first.entryPoints(), second.entryPoints());
		assertEquals(5, Set.of(first.entryPoints().vertex(), first.entryPoints().fragment(),
			first.entryPoints().geometry(), first.entryPoints().mesh(), first.entryPoints().compute()).size());
	}

	private static GeometryPlan layerPlan() {
		GeometryPlan.GeometryMetadata metadata = new GeometryPlan.GeometryMetadata(
			GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.PrimitiveTopology.TRIANGLES,
			new GeometryPlan.OutputBudget(32, 16), Set.of(), layer(), stages(true));
		return GeometryPlan.select(artifact(true), metadata);
	}

	private static GeometryPlan plan(boolean present, int vertices, int primitives, Set<GeometryPlan.SideEffect> effects) {
		return plan(present, vertices, primitives, effects, GeometryPlan.PrimitiveTopology.TRIANGLES);
	}

	private static GeometryPlan plan(boolean present, int vertices, int primitives, Set<GeometryPlan.SideEffect> effects,
		GeometryPlan.PrimitiveTopology topology) {
		return GeometryPlan.select(artifact(present), metadata(present ? GeometryPlan.GeometryPresence.PRESENT : GeometryPlan.GeometryPresence.ABSENT,
			new GeometryPlan.OutputBudget(vertices, primitives), effects, stages(present), topology));
	}

	private static GeometryPlan.GeometryMetadata metadata(GeometryPlan.GeometryPresence presence,
		GeometryPlan.OutputBudget budget, Set<GeometryPlan.SideEffect> effects, GeometryPlan.StageIdentities stages) {
		return metadata(presence, budget, effects, stages, GeometryPlan.PrimitiveTopology.TRIANGLES);
	}

	private static GeometryPlan.GeometryMetadata metadata(GeometryPlan.GeometryPresence presence,
		GeometryPlan.OutputBudget budget, Set<GeometryPlan.SideEffect> effects, GeometryPlan.StageIdentities stages,
		GeometryPlan.PrimitiveTopology topology) {
		return new GeometryPlan.GeometryMetadata(presence, topology, budget, effects,
			layersNone(), stages);
	}

	private static GeometryPlan.LayerMetadata layersNone() {
		return new GeometryPlan.LayerMetadata(GeometryPlan.LayerSemantics.NONE, 0);
	}

	private static GeometryPlan.LayerMetadata layer() {
		return new GeometryPlan.LayerMetadata(GeometryPlan.LayerSemantics.INSTANCE, 4);
	}

	private static GeometryPlan.StageIdentities stages(boolean geometry) {
		Map<Program.Stage, String> values = new EnumMap<>(Program.Stage.class);
		values.put(Program.Stage.VERTEX, "vertex");
		values.put(Program.Stage.FRAGMENT, "fragment");
		if (geometry) values.put(Program.Stage.GEOMETRY, "geometry");
		return new GeometryPlan.StageIdentities(values);
	}

	private static PackProgramArtifact artifact(boolean geometry) {
		Map<Program.Stage, String> sources = new EnumMap<>(Program.Stage.class);
		sources.put(Program.Stage.VERTEX, "vertex");
		sources.put(Program.Stage.FRAGMENT, "fragment");
		if (geometry) sources.put(Program.Stage.GEOMETRY, "geometry");
		Map<Program.Stage, String> translated = new EnumMap<>(sources);
		return new PackProgramArtifact(new PackProgramRequest("pack", "", "program", sources),
			new RuntimeProgram("", "program", "variant", 1), translated);
	}
}
