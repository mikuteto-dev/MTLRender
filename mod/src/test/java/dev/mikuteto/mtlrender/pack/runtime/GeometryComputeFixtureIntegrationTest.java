package dev.mikuteto.mtlrender.pack.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.compiler.CompiledShader;
import dev.mikuteto.mtlrender.pack.compiler.Reflection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class GeometryComputeFixtureIntegrationTest {
	@Test
	void runsFixtureStagesAndKeepsZeroOutputAndFailureIsolated() throws Exception {
		ShaderPack pack = ShaderPack.load(fixture(), Map.of());
		ProgramSetFixture programs = new ProgramSetFixture(pack.programSet("minecraft:overworld"));
		Program producing = programs.producing();
		Program zero = programs.zero();
		assertTrue(producing.source(Program.Stage.GEOMETRY).orElseThrow().contains("257"));
		assertTrue(producing.source(Program.Stage.GEOMETRY).orElseThrow().contains("imageStore"));
		assertTrue(producing.source(Program.Stage.GEOMETRY).orElseThrow().contains("atomicAdd"));
		assertFalse(zero.source(Program.Stage.GEOMETRY).orElseThrow().contains("EmitVertex"));

		List<String> events = new ArrayList<>();
		ComputeRuntime.CommandSeam commands = new ComputeRuntime.CommandSeam() {
			@Override public void submit(ComputeRuntime.AccessSet access, boolean concurrent) { }
			@Override public void geometry(String program, GeometryComputeSeam.StageKind stage,
				ComputeDispatchPlanner.Direct dispatch, ComputeRuntime.AccessSet access, boolean concurrent) {
				events.add(program + ":" + stage);
			}
			@Override public void geometryIndirect(String program, GeometryComputeSeam.IndirectDrawArguments arguments,
				ComputeRuntime.AccessSet access, boolean concurrent) {
				events.add(program + ":INDIRECT:" + arguments.indexCount() + ":" + arguments.instanceCount());
			}
		};
		ComputeResources.TargetSize targets = new ComputeResources.TargetSize(16, 16, 16, 16);
		try (ComputeRuntime runtime = new ComputeRuntime(new Factory(), commands)) {
			runtime.load(3, pack, pack.programSet("minecraft:overworld"), targets);
			runtime.beginFrame(new ComputeRuntime.FrameContext("world0", pack.properties(), targets,
				new ComputeDispatchPlanner.FrameSizeContext(new ComputeDispatchPlanner.FrameSize(16, 16),
					new ComputeDispatchPlanner.FrameSize(16, 16), new ComputeDispatchPlanner.FrameSize(16, 16)), false));
			GeometryComputeSeam.Execution failed = execution("optional-failure", GeometryPlan.GeometryPath.MESH, false);
			GeometryComputeSeam.Execution empty = execution(zero.name(), GeometryPlan.GeometryPath.COMPUTE, true);
			GeometryComputeSeam.Execution output = execution(producing.name(), GeometryPlan.GeometryPath.COMPUTE, false);
			List<ComputeRuntime.GeometrySubmission> submissions = runtime.runGeometry(List.of(failed, empty, output));

			assertEquals(List.of("gbuffers_line:VS", "gbuffers_line:COUNT", "gbuffers_line:SCAN",
				"gbuffers_line:GS", "gbuffers_line:COMPACT", "gbuffers_basic:VS", "gbuffers_basic:COUNT",
				"gbuffers_basic:SCAN", "gbuffers_basic:GS", "gbuffers_basic:COMPACT",
				"gbuffers_basic:INDIRECT:9:1"), events);
			assertEquals(2, submissions.size());
			assertEquals(List.of(new GeometryComputeSeam.PrefixBoundary(0, 1, 0, 3),
				new GeometryComputeSeam.PrefixBoundary(1, 1, 3, 3),
				new GeometryComputeSeam.PrefixBoundary(2, 1, 6, 3)), output.prefix());
			assertEquals(9, output.indirect().indexCount());
			assertEquals(1, output.indirect().instanceCount());
			assertTrue(output.geometry().sideEffects().containsAll(Set.of(
				GeometryPlan.SideEffect.IMAGE, GeometryPlan.SideEffect.SSBO, GeometryPlan.SideEffect.ATOMIC)));
			assertEquals(1, runtime.geometryFailures().size());
			assertEquals("optional-failure", runtime.geometryFailures().getFirst().program());
		}
	}

	private static GeometryComputeSeam.Execution execution(String program, GeometryPlan.GeometryPath path, boolean empty) {
		Set<GeometryPlan.SideEffect> effects = Set.of(GeometryPlan.SideEffect.IMAGE,
			GeometryPlan.SideEffect.SSBO, GeometryPlan.SideEffect.ATOMIC);
		GeometryPlan geometry = new GeometryPlan(GeometryPlan.GeometryPresence.PRESENT,
			GeometryPlan.PrimitiveTopology.TRIANGLES, new GeometryPlan.OutputBudget(9, 3), effects,
			new GeometryPlan.LayerMetadata(GeometryPlan.LayerSemantics.EXPLICIT, 3), path,
			new GeometryPlan.StageIdentities(Map.of(Program.Stage.VERTEX, "vs", Program.Stage.FRAGMENT, "fs",
				Program.Stage.GEOMETRY, "gs")), new GeometryPlan.EntryPoints("v", "f", "g", "m", "c"));
		GeometryPlan planGeometry = path == GeometryPlan.GeometryPath.COMPUTE ? geometry : new GeometryPlan(
			GeometryPlan.GeometryPresence.PRESENT, geometry.topology(), geometry.outputBudget(), geometry.sideEffects(),
			geometry.layers(), GeometryPlan.GeometryPath.COMPUTE, geometry.stages(), geometry.entryPoints());
		GeometryComputePlan plan = GeometryComputePlan.build(new GeometryComputePlan.Context("geometry-fixtures", "world0", program, "GS"),
			planGeometry, new GeometryComputePlan.Input(1, 4, 4), new GeometryComputePlan.Limits(4096), false);
		if (path != GeometryPlan.GeometryPath.COMPUTE) return GeometryComputeSeam.Execution.of(geometry, plan,
			List.of(), List.of(), List.of(), null, List.of(), false);
		if (empty) return GeometryComputeSeam.Execution.of(geometry, plan, List.of(), List.of(), List.of(), null, List.of(), false);
		List<GeometryComputeSeam.Primitive> primitives = List.of(
			new GeometryComputeSeam.Primitive(GeometryComputeSeam.PrimitiveTopology.TRIANGLES, false, true, "payload", 0, 0, effects),
			new GeometryComputeSeam.Primitive(GeometryComputeSeam.PrimitiveTopology.TRIANGLES, false, true, "payload", 1, 1, effects),
			new GeometryComputeSeam.Primitive(GeometryComputeSeam.PrimitiveTopology.TRIANGLES, false, true, "payload", 2, 2, effects));
		List<GeometryComputeSeam.Output> outputs = primitives.stream()
			.map(value -> new GeometryComputeSeam.Output(value, 4, 3, 12, "payload", value.primitiveId(), value.layer())).toList();
		return GeometryComputeSeam.Execution.of(geometry, plan, primitives, outputs, List.of(
			new GeometryComputeSeam.PrefixBoundary(0, 1, 0, 3), new GeometryComputeSeam.PrefixBoundary(1, 1, 3, 3),
			new GeometryComputeSeam.PrefixBoundary(2, 1, 6, 3)), new GeometryComputeSeam.IndirectDrawArguments(9, 1, 0, 0, 0),
			List.of(new GeometryComputeSeam.ResourceAccess(GeometryComputeSeam.StageKind.GS, GeometryComputeSeam.ResourceClass.IMAGE, "fixtureImage", GeometryComputeSeam.Access.WRITE),
				new GeometryComputeSeam.ResourceAccess(GeometryComputeSeam.StageKind.GS, GeometryComputeSeam.ResourceClass.SSBO, "writes", GeometryComputeSeam.Access.READ_WRITE),
				new GeometryComputeSeam.ResourceAccess(GeometryComputeSeam.StageKind.GS, GeometryComputeSeam.ResourceClass.ATOMIC, "writes", GeometryComputeSeam.Access.READ_WRITE)), false);
	}

	private record ProgramSetFixture(dev.mikuteto.mtlrender.pack.ProgramSet set) {
		private Program producing() { return set.program("gbuffers_basic").orElseThrow(); }
		private Program zero() { return set.program("gbuffers_line").orElseThrow(); }
	}

	private static Path fixture() throws Exception {
		Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
			Path path = candidate.resolve("mod/run/shaderpacks/geometry-fixtures");
			if (Files.isDirectory(path)) return path;
		}
		throw new Exception("cannot find geometry fixture");
	}

	private static final class Factory implements ComputeRuntime.Factory {
		@Override public ComputePipelineSet pipelines() { return new ComputePipelineSet(null,
			(stage, source, defines) -> new CompiledShader("kernel", new Reflection(Program.Stage.COMPUTE.ordinal(), false,
				List.of(), List.of(), List.of(), List.of(), new Reflection.ComputeLocalSize(1, 1, 1))),
			(msl, entry, x, y, z) -> 1, handle -> { }, 1); }
		@Override public ComputeResources resources(Map<Integer, ComputePlan.SsboSpec> ssbos,
			Map<String, ComputePlan.CustomImageSpec> images, ComputeResources.TargetSize targets) {
			return new ComputeResources(new ComputeResources.Factory() {
				@Override public ComputeResources.Resource buffer(String name, long size) { return new ComputeResources.Resource(null, null, null); }
				@Override public ComputeResources.Resource image(String name, com.mojang.blaze3d.GpuFormat format,
					ComputeResources.ResolvedDimensions dimensions) { return new ComputeResources.Resource(null, null, null); }
			}, ssbos, images, targets); }
		@Override public ComputeTextureBindings textures(List<dev.mikuteto.mtlrender.pack.texture.TextureSpec> textures) throws Exception {
			return new ComputeTextureBindings(List.of(), new ComputeTextureBindings.Factory() {
				@Override public com.mojang.blaze3d.textures.GpuTexture createTexture(com.mojang.blaze3d.GpuFormat format, int width, int height, int depth, String label) { return null; }
				@Override public com.mojang.blaze3d.textures.GpuTextureView createView(com.mojang.blaze3d.textures.GpuTexture texture) { return null; }
				@Override public com.mojang.blaze3d.textures.GpuSampler createSampler(boolean blur, boolean clamp) { return null; }
				@Override public void write(com.mojang.blaze3d.textures.GpuTexture texture, java.nio.ByteBuffer pixels, int depth, int width, int height) { }
			}, reference -> null); }
	}
}
