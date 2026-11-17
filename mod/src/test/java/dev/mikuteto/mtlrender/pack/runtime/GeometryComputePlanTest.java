package dev.mikuteto.mtlrender.pack.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class GeometryComputePlanTest {
	@Test
	void plansOrderedStagesAndExactBuffers() {
		GeometryComputePlan plan = plan(100_000, Set.of());
		assertEquals(List.of(GeometryComputePlan.StageKind.VERTEX_PREPASS, GeometryComputePlan.StageKind.OUTPUT_COUNT,
			GeometryComputePlan.StageKind.PREFIX_SUM, GeometryComputePlan.StageKind.GEOMETRY_EXPANSION,
			GeometryComputePlan.StageKind.INDIRECT_DRAW), plan.stages().stream().map(GeometryComputePlan.Stage::kind).toList());
		assertEquals(List.of(24L, 8224L, 12312L, 8L, 8L, 8224L, 12312L, 20L), plan.buffers().stream().map(GeometryComputePlan.Buffer::size).toList());
		assertEquals(List.of(0L, 24L, 8248L, 20560L, 20568L, 20576L, 28800L, 41112L), plan.buffers().stream().map(GeometryComputePlan.Buffer::offset).toList());
		assertEquals(Set.of(GeometryComputePlan.BufferKind.INPUT_VERTICES), plan.stages().getFirst().reads());
	}

	@Test
	void retainsEffectsRejectsBoundsAndSkipsEmptyDraws() {
		Set<GeometryPlan.SideEffect> effects = Set.of(GeometryPlan.SideEffect.IMAGE, GeometryPlan.SideEffect.SSBO, GeometryPlan.SideEffect.ATOMIC);
		GeometryComputePlan plan = plan(100_000, effects);
		assertEquals(effects, plan.stages().getFirst().sideEffects());
		assertEquals(effects, plan.stages().get(3).sideEffects());
		assertTrue(plan.drawArguments(0, 1).isEmpty());
		assertEquals(3, plan.drawArguments(3, 1).orElseThrow().indexCount());
		assertThrows(IllegalArgumentException.class, () -> plan(1, Set.of()));
		assertThrows(IllegalArgumentException.class, () -> GeometryComputePlan.build(context(), geometry(),
			new GeometryComputePlan.Input(Long.MAX_VALUE, 4, 4), new GeometryComputePlan.Limits(Long.MAX_VALUE), false));
	}

	private static GeometryComputePlan plan(long limit, Set<GeometryPlan.SideEffect> effects) {
		return GeometryComputePlan.build(context(), geometry(effects), new GeometryComputePlan.Input(2, 16, 12),
			new GeometryComputePlan.Limits(limit), true);
	}
	private static GeometryComputePlan.Context context() { return new GeometryComputePlan.Context("fixture", "overworld", "gbuffers", "geometry"); }
	private static GeometryPlan geometry() { return geometry(Set.of()); }
	private static GeometryPlan geometry(Set<GeometryPlan.SideEffect> effects) {
		return new GeometryPlan(GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.PrimitiveTopology.TRIANGLES,
			new GeometryPlan.OutputBudget(257, 513), effects, new GeometryPlan.LayerMetadata(GeometryPlan.LayerSemantics.NONE, 0),
			GeometryPlan.GeometryPath.COMPUTE, new GeometryPlan.StageIdentities(java.util.Map.of(
				dev.mikuteto.mtlrender.pack.Program.Stage.VERTEX, "v", dev.mikuteto.mtlrender.pack.Program.Stage.FRAGMENT, "f",
				dev.mikuteto.mtlrender.pack.Program.Stage.GEOMETRY, "g")), GeometryPlan.EntryPoints.generate("fixture"));
	}
}
