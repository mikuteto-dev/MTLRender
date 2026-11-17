package dev.mikuteto.mtlrender.pack.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

final class GeometryComputeRuntimeTest {
	@Test
	void dispatchesValidatedStagesWithPerStageAccesses() throws Exception {
		ShaderPack pack = pack();
		List<String> stages = new ArrayList<>();
		List<ComputeRuntime.AccessSet> accesses = new ArrayList<>();
		ComputeResources.TargetSize targets = new ComputeResources.TargetSize(64, 32, 64, 32);
		ComputeRuntime.CommandSeam commands = new ComputeRuntime.CommandSeam() {
			@Override public void submit(ComputeRuntime.AccessSet access, boolean concurrent) { }
			@Override public void geometry(String program, GeometryComputeSeam.StageKind stage,
				ComputeDispatchPlanner.Direct dispatch, ComputeRuntime.AccessSet access, boolean concurrent) {
				stages.add(stage.name() + ":" + dispatch.x() + ":" + dispatch.y() + ":" + dispatch.z());
				accesses.add(access);
			}
			@Override public void geometryIndirect(String program, GeometryComputeSeam.IndirectDrawArguments arguments,
				ComputeRuntime.AccessSet access, boolean concurrent) {
				stages.add("INDIRECT:" + arguments.indexCount() + ":" + arguments.instanceCount());
				accesses.add(access);
			}
		};
		try (ComputeRuntime runtime = new ComputeRuntime(new Factory(), commands)) {
			runtime.load(1, pack, pack.rootProgramSet(), targets);
			runtime.beginFrame(new ComputeRuntime.FrameContext("", pack.properties(), targets,
				new ComputeDispatchPlanner.FrameSizeContext(new ComputeDispatchPlanner.FrameSize(64, 32),
					new ComputeDispatchPlanner.FrameSize(64, 32), new ComputeDispatchPlanner.FrameSize(64, 32)), false));
			GeometryComputeSeam.Execution execution = execution("good", GeometryPlan.GeometryPath.COMPUTE);
			assertEquals(1, runtime.runGeometry(List.of(execution)).size());
			assertEquals(List.of("VS:1:1:1", "COUNT:1:1:1", "SCAN:1:1:1", "GS:1:1:1",
				"COMPACT:1:1:1", "INDIRECT:3:1"), stages);
			assertEquals(List.of(new GeometryComputeSeam.PrefixBoundary(0, 1, 0, 3)), execution.prefix());
			assertEquals(3, execution.indirect().indexCount());
			assertEquals(Set.of(new ComputeRuntime.ResourceAccess("image", ComputeRuntime.Access.WRITE)),
				Set.copyOf(accesses.get(3).resources()));
		}
	}

	@Test
	void isolatesAFailedProgramAndSuppressesZeroOutputDraw() throws Exception {
		ShaderPack pack = pack();
		ComputeResources.TargetSize targets = new ComputeResources.TargetSize(16, 16, 16, 16);
		List<String> stages = new ArrayList<>();
		ComputeRuntime.CommandSeam commands = new ComputeRuntime.CommandSeam() {
			@Override public void submit(ComputeRuntime.AccessSet access, boolean concurrent) { }
			@Override public void geometry(String program, GeometryComputeSeam.StageKind stage,
				ComputeDispatchPlanner.Direct dispatch, ComputeRuntime.AccessSet access, boolean concurrent) {
				stages.add(program + ":" + stage);
			}
		};
		try (ComputeRuntime runtime = new ComputeRuntime(new Factory(), commands)) {
			runtime.load(2, pack, pack.rootProgramSet(), targets);
			runtime.beginFrame(new ComputeRuntime.FrameContext("", pack.properties(), targets,
				new ComputeDispatchPlanner.FrameSizeContext(new ComputeDispatchPlanner.FrameSize(16, 16),
					new ComputeDispatchPlanner.FrameSize(16, 16), new ComputeDispatchPlanner.FrameSize(16, 16)), false));
			GeometryComputeSeam.Execution failed = execution("failed", GeometryPlan.GeometryPath.MESH);
			GeometryComputeSeam.Execution zero = GeometryComputeSeam.Execution.of(computeGeometry(),
				plan(), List.of(), List.of(), List.of(), null, List.of(), false);
			assertEquals(1, runtime.runGeometry(List.of(failed, zero)).size());
			assertFalse(stages.stream().anyMatch(value -> value.startsWith("failed:")));
			assertEquals(1, runtime.geometryFailures().size());
			assertEquals("failed", runtime.geometryFailures().getFirst().program());
			assertFalse(stages.contains("good:INDIRECT"));
		}
	}

	private static GeometryComputeSeam.Execution execution(String program, GeometryPlan.GeometryPath path) {
		GeometryPlan geometry = new GeometryPlan(GeometryPlan.GeometryPresence.PRESENT,
			GeometryPlan.PrimitiveTopology.TRIANGLES, new GeometryPlan.OutputBudget(3, 1), Set.of(GeometryPlan.SideEffect.IMAGE),
			new GeometryPlan.LayerMetadata(GeometryPlan.LayerSemantics.NONE, 0), path, stages(),
			new GeometryPlan.EntryPoints("v", "f", "g", "m", "c"));
		GeometryComputeSeam.Primitive primitive = new GeometryComputeSeam.Primitive(
			GeometryComputeSeam.PrimitiveTopology.TRIANGLES, false, true, "varying", 7, 0, Set.of(GeometryPlan.SideEffect.IMAGE));
		GeometryComputeSeam.Output output = new GeometryComputeSeam.Output(primitive, 4, 3, 12, "varying", 7, 0);
		return GeometryComputeSeam.Execution.of(geometry, new GeometryComputePlan(
			new GeometryComputePlan.Context("fixture", "", program, "GS"), plan().stages(), plan().buffers(), 1, 3, 1, false),
			List.of(primitive), List.of(output), List.of(new GeometryComputeSeam.PrefixBoundary(0, 1, 0, 3)),
			new GeometryComputeSeam.IndirectDrawArguments(3, 1, 0, 0, 0),
			List.of(new GeometryComputeSeam.ResourceAccess(GeometryComputeSeam.StageKind.GS,
				GeometryComputeSeam.ResourceClass.IMAGE, "image", GeometryComputeSeam.Access.WRITE)), false);
	}

	private static GeometryPlan computeGeometry() {
		return new GeometryPlan(GeometryPlan.GeometryPresence.PRESENT, GeometryPlan.PrimitiveTopology.TRIANGLES,
			new GeometryPlan.OutputBudget(3, 1), Set.of(GeometryPlan.SideEffect.IMAGE), new GeometryPlan.LayerMetadata(GeometryPlan.LayerSemantics.NONE, 0),
			GeometryPlan.GeometryPath.COMPUTE, stages(), new GeometryPlan.EntryPoints("v", "f", "g", "m", "c"));
	}

	private static GeometryPlan.StageIdentities stages() {
		return new GeometryPlan.StageIdentities(Map.of(Program.Stage.VERTEX, "v", Program.Stage.FRAGMENT, "f", Program.Stage.GEOMETRY, "g"));
	}

	private static GeometryComputePlan plan() {
		return GeometryComputePlan.build(new GeometryComputePlan.Context("fixture", "", "good", "GS"), computeGeometry(),
			new GeometryComputePlan.Input(1, 4, 4), new GeometryComputePlan.Limits(4096), false);
	}

	private static ShaderPack pack() throws Exception {
		Path path = Files.createTempFile("mtlrender-geometry", ".zip");
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
			output.putNextEntry(new ZipEntry("shaders/setup.csh"));
			output.write("kernel".getBytes());
			output.closeEntry();
		}
		return ShaderPack.load(path, Map.of());
	}

	private static final class Factory implements ComputeRuntime.Factory {
		@Override public ComputePipelineSet pipelines() {
			return new ComputePipelineSet(null, (stage, source, defines) -> new CompiledShader("kernel",
				new Reflection(Program.Stage.COMPUTE.ordinal(), false, List.of(), List.of(), List.of(), List.of(),
					new Reflection.ComputeLocalSize(1, 1, 1))), (msl, entry, x, y, z) -> 1, handle -> { }, 1);
		}
		@Override public ComputeResources resources(Map<Integer, ComputePlan.SsboSpec> ssbos,
			Map<String, ComputePlan.CustomImageSpec> images, ComputeResources.TargetSize targets) {
			return new ComputeResources(new ComputeResources.Factory() {
				@Override public ComputeResources.Resource buffer(String name, long size) { return new ComputeResources.Resource(null, null, null); }
				@Override public ComputeResources.Resource image(String name, com.mojang.blaze3d.GpuFormat format,
					ComputeResources.ResolvedDimensions dimensions) { return new ComputeResources.Resource(null, null, null); }
			}, ssbos, images, targets);
		}
		@Override public ComputeTextureBindings textures(List<dev.mikuteto.mtlrender.pack.texture.TextureSpec> textures) throws Exception {
			return new ComputeTextureBindings(List.of(), new ComputeTextureBindings.Factory() {
				@Override public com.mojang.blaze3d.textures.GpuTexture createTexture(com.mojang.blaze3d.GpuFormat format,
					int width, int height, int depth, String label) { return null; }
				@Override public com.mojang.blaze3d.textures.GpuTextureView createView(com.mojang.blaze3d.textures.GpuTexture texture) { return null; }
				@Override public com.mojang.blaze3d.textures.GpuSampler createSampler(boolean blur, boolean clamp) { return null; }
				@Override public void write(com.mojang.blaze3d.textures.GpuTexture texture, java.nio.ByteBuffer pixels,
					int depth, int width, int height) { }
			}, reference -> null);
		}
	}
}
