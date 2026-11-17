package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ComputeRuntimeLifecycleTest {
	@Test
	void loadReuseReloadResizeAndFrameReset() throws Exception {
		ShaderPack pack = pack("setup.vsh", "setup.fsh", "setup.csh");
		FakeFactory factory = new FakeFactory(false);
		ComputeRuntime runtime = new ComputeRuntime(factory, (access, concurrent) -> { });
		ComputeResources.TargetSize first = new ComputeResources.TargetSize(100, 80, 200, 160);
		runtime.load(1, pack, pack.rootProgramSet(), first);
		assertTrue(runtime.active());
		assertTrue(runtime.setupPending());
		assertEquals(List.of("setup"), runtime.computePrograms().stream().map(Program::name).toList());
		runtime.load(1, pack, pack.rootProgramSet(), first);
		assertEquals(1, factory.created.get());
		runtime.beginFrame(frame(first));
		runtime.markSetupExecuted();
		assertFalse(runtime.setupPending());
		runtime.resize(new ComputeResources.TargetSize(120, 80, 200, 160));
		assertTrue(runtime.setupPending());
		runtime.beginFrame(frame(new ComputeResources.TargetSize(120, 80, 200, 160)));
		runtime.close();
		runtime.close();
		assertEquals(1, factory.closed.get());
	}

	@Test
	void reloadRetiresOldGenerationOnlyAfterNewGenerationIsReady() throws Exception {
		ShaderPack pack = pack("setup.csh");
		FakeFactory factory = new FakeFactory(false);
		ComputeRuntime runtime = new ComputeRuntime(factory, (access, concurrent) -> { });
		ComputeResources.TargetSize targets = new ComputeResources.TargetSize(10, 10, 10, 10);
		runtime.load(4, pack, pack.rootProgramSet(), targets);
		runtime.load(5, pack, pack.rootProgramSet(), targets);
		assertEquals(5, runtime.generation().id());
		assertEquals(1, factory.closed.get());
		runtime.close();
		assertEquals(2, factory.closed.get());
	}

	@Test
	void failedGenerationDoesNotPublishPartialState() throws Exception {
		ShaderPack pack = pack("setup.csh");
		FakeFactory factory = new FakeFactory(true);
		ComputeRuntime runtime = new ComputeRuntime(factory, (access, concurrent) -> { });
		ComputeResources.TargetSize targets = new ComputeResources.TargetSize(10, 10, 10, 10);
		assertThrows(IllegalStateException.class, () -> runtime.load(9, pack, pack.rootProgramSet(), targets));
		assertFalse(runtime.active());
		assertEquals(0, factory.closed.get());
	}

	@Test
	void inactiveRuntimeMakesNoFactoryCalls() {
		ComputeRuntime runtime = ComputeRuntime.inactive();
		runtime.close();
		assertFalse(runtime.active());
		assertFalse(runtime.setupPending());
	}

	private static ComputeRuntime.FrameContext frame(ComputeResources.TargetSize targets) {
		ComputeDispatchPlanner.FrameSize size = new ComputeDispatchPlanner.FrameSize(targets.renderWidth(), targets.renderHeight());
		return new ComputeRuntime.FrameContext("", dev.mikuteto.mtlrender.pack.ShaderProperties.empty(), targets,
			new ComputeDispatchPlanner.FrameSizeContext(size, size, new ComputeDispatchPlanner.FrameSize(targets.shadowWidth(), targets.shadowHeight())), false);
	}

	private static ShaderPack pack(String... files) throws Exception {
		Path path = Files.createTempFile("mtlrender-compute-runtime", ".zip");
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
			for (String file : files) {
				output.putNextEntry(new ZipEntry("shaders/" + file));
				output.write(file.endsWith(".csh") ? "kernel".getBytes() : "render".getBytes());
				output.closeEntry();
			}
		}
		return ShaderPack.load(path, Map.of());
	}

	private static final class FakeFactory implements ComputeRuntime.Factory {
		private final boolean fail;
		private final AtomicInteger created = new AtomicInteger();
		private final AtomicInteger closed = new AtomicInteger();

		private FakeFactory(boolean fail) { this.fail = fail; }
		@Override public ComputePipelineSet pipelines() {
			created.incrementAndGet();
			return new ComputePipelineSet(null, (stage, source, defines) -> {
				if (fail) throw new IllegalStateException("pipeline failure");
				return new dev.mikuteto.mtlrender.pack.compiler.CompiledShader("kernel", new dev.mikuteto.mtlrender.pack.compiler.Reflection(
					Program.Stage.COMPUTE.ordinal(), false, List.of(), List.of(), List.of(), List.of(), new dev.mikuteto.mtlrender.pack.compiler.Reflection.ComputeLocalSize(1, 1, 1)));
			}, (msl, entry, x, y, z) -> 1, handle -> closed.incrementAndGet(), 1);
		}
		@Override public ComputeResources resources(Map<Integer, ComputePlan.SsboSpec> ssbos,
			Map<String, ComputePlan.CustomImageSpec> images, ComputeResources.TargetSize targets) {
			return new ComputeResources(new ComputeResources.Factory() {
				@Override public ComputeResources.Resource buffer(String name, long size) { return new ComputeResources.Resource(null, null, null); }
				@Override public ComputeResources.Resource image(String name, com.mojang.blaze3d.GpuFormat format, ComputeResources.ResolvedDimensions dimensions) { return new ComputeResources.Resource(null, null, null); }
			}, ssbos, images, targets);
		}
		@Override public ComputeTextureBindings textures(List<dev.mikuteto.mtlrender.pack.texture.TextureSpec> textures) throws Exception { return new ComputeTextureBindings(List.of(), new ComputeTextureBindings.Factory() {
			@Override public com.mojang.blaze3d.textures.GpuTexture createTexture(com.mojang.blaze3d.GpuFormat format, int width, int height, int depth, String label) { return null; }
			@Override public com.mojang.blaze3d.textures.GpuTextureView createView(com.mojang.blaze3d.textures.GpuTexture texture) { return null; }
			@Override public com.mojang.blaze3d.textures.GpuSampler createSampler(boolean blur, boolean clamp) { return null; }
			@Override public void write(com.mojang.blaze3d.textures.GpuTexture texture, java.nio.ByteBuffer pixels, int depth, int width, int height) { }
		}, reference -> null); }
	}
}
