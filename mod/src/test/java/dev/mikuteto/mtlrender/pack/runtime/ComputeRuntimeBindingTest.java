package dev.mikuteto.mtlrender.pack.runtime;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.compiler.CompiledShader;
import dev.mikuteto.mtlrender.pack.compiler.Reflection;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ComputeRuntimeBindingTest {
	@Test
	void bindsReflectionSlotsAndReportsTheExactAccessSet() throws Exception {
		ShaderPack pack = pack();
		List<String> bindings = new ArrayList<>();
		List<ComputeRuntime.AccessSet> sets = new ArrayList<>();
		ComputeRuntime.CommandSeam commands = new ComputeRuntime.CommandSeam() {
			@Override public void begin(boolean concurrent) { bindings.add("begin:" + concurrent); }
			@Override public void pipeline(long handle) { bindings.add("pipeline:" + handle); }
			@Override public void storageBuffer(int slot, GpuBuffer buffer, ComputePlan.Access access) { bindings.add("ssbo:" + slot + ":" + access); }
			@Override public void uniform(int slot, ByteBuffer state) { bindings.add("uniform:" + slot + ":" + state.remaining()); }
			@Override public void direct(ComputeDispatchPlanner.Direct dispatch) { bindings.add("direct:" + dispatch.x() + ":" + dispatch.y() + ":" + dispatch.z()); }
			@Override public void end() { bindings.add("end"); }
			@Override public void submit(ComputeRuntime.AccessSet accessSet, boolean concurrent) { }
			@Override public void dispatch(String program, ComputeDispatchPlanner.Result dispatch, ComputeRuntime.AccessSet accessSet, boolean concurrent) {
				sets.add(accessSet);
			}
		};
		try (ComputeRuntime runtime = new ComputeRuntime(new Factory(), commands)) {
			ComputeResources.TargetSize size = new ComputeResources.TargetSize(64, 32, 64, 32);
			runtime.load(1, pack, pack.rootProgramSet(), size);
			runtime.beginFrame(new ComputeRuntime.FrameContext("", pack.properties(), size,
				new ComputeDispatchPlanner.FrameSizeContext(new ComputeDispatchPlanner.FrameSize(64, 32),
					new ComputeDispatchPlanner.FrameSize(64, 32), new ComputeDispatchPlanner.FrameSize(64, 32)), true,
				ByteBuffer.allocate(32)));
			assertEquals(List.of("begin"), runtime.run(ComputeRuntime.Phase.BEGIN).stream().map(ComputeRuntime.Submission::program).toList());
			assertTrue(runtime.failures().stream().anyMatch(value -> value.message().contains("dimension=")
				&& value.message().contains("pass=BEGIN") && value.message().contains("variant=begin_a")));
		}
		assertEquals(List.of("begin:true", "pipeline:41", "ssbo:7:READ_WRITE", "uniform:5:32", "direct:2:3:1", "end"), bindings);
		assertEquals(List.of(new ComputeRuntime.ResourceAccess("input", ComputeRuntime.Access.READ_WRITE),
			new ComputeRuntime.ResourceAccess("state", ComputeRuntime.Access.READ)), sets.getFirst().resources());
		assertTrue(pack.rootProgramSet().program("begin").orElseThrow().isRenderProgram());
	}

	private static ShaderPack pack() throws Exception {
		Path path = Files.createTempFile("mtlrender-compute-binding", ".zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
			entry(out, "shaders/begin.csh", "good");
			entry(out, "shaders/begin_a.csh", "bad");
			entry(out, "shaders/begin.vsh", "render");
			entry(out, "shaders/begin.fsh", "render");
			entry(out, "shaders/shaders.properties", "bufferObject.0=16 input\nworkGroups.begin=2 3 1\n");
		}
		return ShaderPack.load(path, Map.of());
	}

	private static void entry(ZipOutputStream out, String name, String value) throws Exception {
		out.putNextEntry(new ZipEntry(name));
		out.write(value.getBytes());
		out.closeEntry();
	}

	private static Reflection reflection() {
		return new Reflection(Program.Stage.COMPUTE.ordinal(), false,
			List.of(new Reflection.Resource("input", 1, 0, 0, 7, true, "device", 0, ""),
				new Reflection.Resource("state", 0, 0, 0, 5, false, "constant", 0, "")),
			List.of(), List.of(), List.of(), new Reflection.ComputeLocalSize(8, 8, 1));
	}

	private static final class Factory implements ComputeRuntime.Factory {
		@Override public ComputePipelineSet pipelines() {
			return new ComputePipelineSet(null, (stage, source, defines) -> {
				if (source.contains("bad")) throw new IllegalStateException("broken variant");
				return new CompiledShader("kernel", reflection());
			}, (msl, entry, x, y, z) -> 41, handle -> { }, 1);
		}
		@Override public ComputeResources resources(Map<Integer, ComputePlan.SsboSpec> ssbos, Map<String, ComputePlan.CustomImageSpec> images, ComputeResources.TargetSize targets) {
			return new ComputeResources(new ComputeResources.Factory() {
				@Override public ComputeResources.Resource buffer(String name, long size) { return new ComputeResources.Resource(null, null, null); }
				@Override public ComputeResources.Resource image(String name, GpuFormat format, ComputeResources.ResolvedDimensions dimensions) { return new ComputeResources.Resource(null, null, null); }
			}, ssbos, images, targets);
		}
		@Override public ComputeTextureBindings textures(List<dev.mikuteto.mtlrender.pack.texture.TextureSpec> textures) throws Exception {
			return new ComputeTextureBindings(List.of(), new ComputeTextureBindings.Factory() {
				@Override public GpuTexture createTexture(GpuFormat format, int width, int height, int depth, String label) { return null; }
				@Override public GpuTextureView createView(GpuTexture texture) { return null; }
				@Override public GpuSampler createSampler(boolean blur, boolean clamp) { return null; }
				@Override public void write(GpuTexture texture, ByteBuffer pixels, int depth, int width, int height) { }
			}, reference -> null);
		}
	}
}
