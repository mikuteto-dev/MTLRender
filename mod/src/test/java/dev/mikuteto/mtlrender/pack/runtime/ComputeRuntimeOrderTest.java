package dev.mikuteto.mtlrender.pack.runtime;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ComputeRuntimeOrderTest {
	@Test
	void ordersVariantsAndRunsSetupOnlyForNewResources() throws Exception {
		ShaderPack pack = pack(false);
		FakeFactory factory = new FakeFactory();
		List<String> calls = new ArrayList<>();
		try (ComputeRuntime runtime = new ComputeRuntime(factory, (access, concurrent) -> { })) {
			ComputeResources.TargetSize size = new ComputeResources.TargetSize(64, 32, 128, 96);
			runtime.load(1, pack, pack.rootProgramSet(), size);
			runtime.beginFrame(frame(size, pack));
			calls.addAll(runtime.runFrame().stream().map(ComputeRuntime.Submission::program).toList());
			runtime.beginFrame(frame(size, pack));
			assertEquals(0, runtime.runFrame().stream().filter(value -> value.phase() == ComputeRuntime.Phase.SETUP).count());
			ComputeResources.TargetSize resized = new ComputeResources.TargetSize(80, 32, 128, 96);
			runtime.resize(resized);
			runtime.beginFrame(frame(resized, pack));
			assertEquals(2, runtime.runFrame().stream().filter(value -> value.phase() == ComputeRuntime.Phase.SETUP).count());
		}
		assertEquals(List.of("setup", "setup_a", "begin", "shadow", "shadowcomp", "prepare", "deferred", "composite", "final"), calls);
	}

	private static ComputeRuntime.FrameContext frame(ComputeResources.TargetSize size, ShaderPack pack) {
		return new ComputeRuntime.FrameContext("", pack.properties(), size,
			new ComputeDispatchPlanner.FrameSizeContext(
				new ComputeDispatchPlanner.FrameSize(size.renderWidth(), size.renderHeight()),
				new ComputeDispatchPlanner.FrameSize(size.renderWidth(), size.renderHeight()),
				new ComputeDispatchPlanner.FrameSize(size.shadowWidth(), size.shadowHeight())), false);
	}

	private static ShaderPack pack(boolean before) throws Exception {
		Path path = Files.createTempFile("mtlrender-compute-order", ".zip");
		List<String> names = List.of("setup", "setup_a", "setup2", "begin", "shadow", "shadowcomp", "prepare", "deferred", "composite", "final");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
			for (String name : names) {
				out.putNextEntry(new ZipEntry("shaders/" + name + ".csh"));
				out.write("kernel".getBytes());
				out.closeEntry();
			}
			out.putNextEntry(new ZipEntry("shaders/shaders.properties"));
			out.write(("workGroups.setup=2 3 4\nindirect.final=7 16\nbufferObject.7=64 dispatchArgs\nprepareBeforeShadow=" + before).getBytes());
			out.closeEntry();
		}
		return ShaderPack.load(path, Map.of());
	}

	private static final class FakeFactory implements ComputeRuntime.Factory {
		private final AtomicInteger handles = new AtomicInteger();
		@Override public ComputePipelineSet pipelines() {
			return new ComputePipelineSet((stage, source, defines) -> new dev.mikuteto.mtlrender.pack.compiler.CompiledShader("kernel", new dev.mikuteto.mtlrender.pack.compiler.Reflection(
				Program.Stage.COMPUTE.ordinal(), false, List.of(), List.of(), List.of(), List.of(), new dev.mikuteto.mtlrender.pack.compiler.Reflection.ComputeLocalSize(8, 8, 1))),
				(msl, entry, x, y, z) -> handles.incrementAndGet(), 1);
		}
		@Override public ComputeResources resources(Map<Integer, ComputePlan.SsboSpec> ssbos, Map<String, ComputePlan.CustomImageSpec> images, ComputeResources.TargetSize targets) {
			return new ComputeResources(new ComputeResources.Factory() {
				@Override public ComputeResources.Resource buffer(String name, long bytes) { return new ComputeResources.Resource(null, null, null); }
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
