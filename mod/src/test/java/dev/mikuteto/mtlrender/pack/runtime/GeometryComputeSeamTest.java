package dev.mikuteto.mtlrender.pack.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

final class GeometryComputeSeamTest {
	@Test
	void preservesStagesMetadataAndAccessClasses() {
		GeometryComputeSeam.Primitive primitive = primitive(4, 2);
		GeometryComputeSeam.Output output = new GeometryComputeSeam.Output(primitive, 16, 3, 48, "v0", 4, 2);
		GeometryComputeSeam.Execution execution = GeometryComputeSeam.Execution.of(geometry(), plan(1), List.of(primitive),
			List.of(output), List.of(new GeometryComputeSeam.PrefixBoundary(0, 1, 0, 3)),
			new GeometryComputeSeam.IndirectDrawArguments(3, 1, 0, 0, 0), List.of(
				new GeometryComputeSeam.ResourceAccess(GeometryComputeSeam.StageKind.GS, GeometryComputeSeam.ResourceClass.IMAGE, "image", GeometryComputeSeam.Access.WRITE),
				new GeometryComputeSeam.ResourceAccess(GeometryComputeSeam.StageKind.GS, GeometryComputeSeam.ResourceClass.SSBO, "buffer", GeometryComputeSeam.Access.READ_WRITE),
				new GeometryComputeSeam.ResourceAccess(GeometryComputeSeam.StageKind.GS, GeometryComputeSeam.ResourceClass.ATOMIC, "counter", GeometryComputeSeam.Access.WRITE)), true);
		assertEquals(GeometryComputeSeam.StageKind.SCAN, execution.stages().get(2));
		assertEquals("v0", execution.outputs().getFirst().varyingPayload());
		assertEquals(4, execution.outputs().getFirst().primitiveId());
		assertEquals(2, execution.outputs().getFirst().layer());
		assertTrue(execution.hasOutput());
		assertEquals(ComputePlan.Access.READ_WRITE, execution.accesses().get(1).planAccess());
	}

	@Test
	void acceptsZeroOutputWithoutCreatingAZeroDraw() {
		GeometryComputeSeam.Execution execution = GeometryComputeSeam.Execution.of(geometry(), plan(1), List.of(), List.of(), List.of(), null, List.of(), false);
		assertFalse(execution.hasOutput());
	}

	@Test
	void checksTopologyStrideCountsAndIndirectArguments() {
		GeometryComputeSeam.Primitive primitive = primitive(0, 0);
		assertThrows(IllegalArgumentException.class, () -> new GeometryComputeSeam.Output(primitive, 0, 3, 0, "v0", 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new GeometryComputeSeam.Output(primitive, 4, 2, 8, "v0", 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new GeometryComputeSeam.IndirectDrawArguments(0, 1, 0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new GeometryComputeSeam.IndirectDrawArguments(1, 1, -1, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new GeometryComputeSeam.Primitive(
			GeometryComputeSeam.PrimitiveTopology.TRIANGLES, true, false, "v0", 0, 0));
	}

	@Test
	void checksPrefixBoundariesAndContextualFailure() {
		GeometryComputeSeam.Primitive primitive = primitive(0, 0);
		GeometryComputeSeam.Output output = new GeometryComputeSeam.Output(primitive, 4, 3, 12, "v0", 0, 0);
		assertThrows(IllegalArgumentException.class, () -> GeometryComputeSeam.Execution.of(geometry(), plan(1), List.of(primitive),
			List.of(output), List.of(new GeometryComputeSeam.PrefixBoundary(0, 1, 1, 3)), null, List.of(), false));
		GeometryComputeSeam.ProgramFailure failure = new GeometryComputeSeam.ProgramFailure(
			new GeometryComputeSeam.Context("fixture", "overworld", "gbuffers", "GS"), "bad output");
		assertEquals("fixture", failure.pack());
		assertEquals("gbuffers", failure.program());
		assertEquals("GS", failure.stage());
	}

	private static GeometryComputeSeam.Primitive primitive(long id, int layer) {
		return new GeometryComputeSeam.Primitive(GeometryComputeSeam.PrimitiveTopology.TRIANGLES, false, true, "v0", id, layer, Set.of(GeometryPlan.SideEffect.IMAGE));
	}

	private static GeometryPlan geometry() {
		return new GeometryPlan(GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.PrimitiveTopology.TRIANGLES,
			new GeometryPlan.OutputBudget(3, 1), Set.of(GeometryPlan.SideEffect.IMAGE),
			new GeometryPlan.LayerMetadata(GeometryPlan.LayerSemantics.EXPLICIT, 4), GeometryPlan.GeometryPath.COMPUTE,
			new GeometryPlan.StageIdentities(Map.of(dev.mikuteto.mtlrender.pack.Program.Stage.VERTEX, "vs",
				dev.mikuteto.mtlrender.pack.Program.Stage.FRAGMENT, "fs", dev.mikuteto.mtlrender.pack.Program.Stage.GEOMETRY, "gs")),
			new GeometryPlan.EntryPoints("v", "f", "g", "m", "c"));
	}

	private static GeometryComputePlan plan(long count) {
		return GeometryComputePlan.build(new GeometryComputePlan.Context("fixture", "overworld", "gbuffers", "GS"), geometry(),
			new GeometryComputePlan.Input(count, 16, 12), new GeometryComputePlan.Limits(1_000_000), true);
	}
}
