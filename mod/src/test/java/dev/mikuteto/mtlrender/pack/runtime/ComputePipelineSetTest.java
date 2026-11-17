package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.compiler.CompiledShader;
import dev.mikuteto.mtlrender.pack.compiler.GlslCompiler;
import dev.mikuteto.mtlrender.pack.compiler.Reflection;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ComputePipelineSetTest {
	@Test
	void compilesComputeProgramsAndKeepsReflectionBindings() throws Exception {
		ShaderPack pack = pack("setup.csh", "compute setup");
		AtomicInteger creates = new AtomicInteger();
		try (ComputePipelineSet set = new ComputePipelineSet(
			(stage, source, defines) -> compiled(),
			(msl, entry, x, y, z) -> {
				assertEquals(ComputePipelineSet.ENTRY_POINT, entry);
				assertEquals(List.of(8, 4, 2), List.of(x, y, z));
				creates.incrementAndGet();
				return 17;
			}, 2)) {
			ComputePipelineSet.Result result = set.compile(pack, 4);
			ComputePipelineSet.Pipeline pipeline = result.pipelines().getFirst();
			assertEquals(1, result.attempted());
			assertEquals(1, creates.get());
			assertEquals("setup", pipeline.program());
			assertEquals("ssbo", pipeline.bindings().getFirst().name());
			assertEquals(3, pipeline.bindings().getFirst().slot());
			assertEquals(2, pipeline.bindings().get(1).slot());
			assertEquals(8, pipeline.localSize().x());
		}
	}

	@Test
	void isolatesCompileFailuresAndCachesByIdentity() throws Exception {
		ShaderPack pack = pack("setup.csh", "compute setup");
		AtomicInteger creates = new AtomicInteger();
		try (ComputePipelineSet set = new ComputePipelineSet(
			(stage, source, defines) -> { throw new IllegalStateException("bad compute"); },
			(msl, entry, x, y, z) -> {
				creates.incrementAndGet();
				return 19;
			}, 1)) {
			ComputePipelineSet.Result first = set.compile(pack, 9);
			ComputePipelineSet.Result second = set.compile(pack, 9);
			assertEquals(1, first.failures().size());
			assertEquals(0, first.pipelines().size());
			assertTrue(first.failures().getFirst().message().contains("bad compute"));
			assertTrue(first == second);
			assertEquals(0, creates.get());
		}
	}

	private static CompiledShader compiled() {
		Reflection reflection = new Reflection(Program.Stage.COMPUTE.ordinal(), false,
			List.of(new Reflection.Resource("ssbo", 1, 0, 0, 3, true, "device", 0, ""),
				new Reflection.Resource("image", 5, 2, 0, 2, true, "texture", 0, "")),
			List.of(), List.of(), List.of(), new Reflection.ComputeLocalSize(8, 4, 2));
		return new CompiledShader("kernel void mtlrender_compute_main() {}", reflection);
	}

	private static ShaderPack pack(String sourceName, String source) throws Exception {
		Path path = Files.createTempFile("mtlrender-compute", ".zip");
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
			output.putNextEntry(new ZipEntry("shaders/" + sourceName));
			output.write(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			output.closeEntry();
		}
		return ShaderPack.load(path, Map.of());
	}
}
