package dev.mikuteto.mtlrender.pack.gbuffer;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbufferProgramResolverTest {
	@Test
	void absentPackKeepsVanillaAvailable() {
		GbufferProgramResolver resolver = new GbufferProgramResolver(Optional::<ShaderPack>empty, () -> 4L);
		RenderPipeline pipeline = RenderPipelines.SKY;
		ShaderSource vanilla = (id, type) -> "vanilla-" + id + "-" + type;
		GbufferShaderSource source = resolver.source(pipeline, "minecraft:overworld", vanilla,
			pipeline.getVertexShader(), pipeline.getFragmentShader());
		assertEquals("vanilla-" + pipeline.getVertexShader() + "-VERTEX",
			source.get(pipeline.getVertexShader(), ShaderType.VERTEX));
		assertEquals(GbufferProgramResolver.Reason.NO_PACK,
			resolver.resolve(pipeline, "minecraft:overworld").orElseThrow().reason());
	}

	@Test
	void directProgramAndEveryCatalogEntryCanBeInspected(@TempDir Path directory) throws IOException {
		ShaderPack pack = load(directory, Map.of(
			"gbuffers_skybasic.vsh", "direct-v",
			"gbuffers_skybasic.fsh", "direct-f"));
		GbufferProgramResolver resolver = new GbufferProgramResolver(() -> Optional.of(pack), () -> 8L);
		GbufferProgramResolver.Resolution resolution = resolver.resolve(RenderPipelines.SKY, "minecraft:overworld").orElseThrow();
		assertTrue(resolution.resolved());
		assertEquals("gbuffers_skybasic", resolution.program().name());
		assertEquals(GbufferPipelineCatalog.levelPipelines().size(), resolver.resolveLevel("minecraft:overworld").size());
	}

	@Test
	void fallbackAndDimensionBaseFolderAreSelected(@TempDir Path directory) throws IOException {
		ShaderPack pack = load(directory, Map.of(
			"dimension.properties", "dimension.world0=minecraft:overworld\ndimension.*=world0",
			"world0/gbuffers_basic.vsh", "base-v",
			"world0/gbuffers_basic.fsh", "base-f",
			"world0/gbuffers_textured.vsh", "dimension-v",
			"world0/gbuffers_textured.fsh", "dimension-f"));
		GbufferProgramResolver resolver = new GbufferProgramResolver(() -> Optional.of(pack), () -> 1L);
		GbufferProgramResolver.Resolution resolution = resolver.resolve(RenderPipelines.END_SKY, "minecraft:overworld").orElseThrow();
		assertTrue(resolution.resolved(), resolution.reason().toString());
		assertEquals("gbuffers_textured", resolution.program().name());
	}

	@Test
	void missingStageAndDisabledProgramDoNotUsePartialFallback(@TempDir Path directory) throws IOException {
		ShaderPack pack = load(directory, Map.of(
			"shaders.properties", "program.gbuffers_skybasic.enabled=false",
			"gbuffers_basic.vsh", "only-v"));
		GbufferProgramResolver resolver = new GbufferProgramResolver(() -> Optional.of(pack), () -> 2L);
		GbufferProgramResolver.Resolution resolution = resolver.resolve(RenderPipelines.SKY, "minecraft:overworld").orElseThrow();
		assertFalse(resolution.resolved());
		assertEquals(GbufferProgramResolver.Reason.NO_USABLE_FALLBACK, resolution.reason());
	}

	@Test
	void sourceIdentifiersAreStableAndVersionIsCaptured(@TempDir Path directory) throws IOException {
		ShaderPack first = load(directory, Map.of("gbuffers_skybasic.vsh", "v1", "gbuffers_skybasic.fsh", "f1"));
		AtomicLong version = new AtomicLong(3L);
		GbufferProgramResolver resolver = new GbufferProgramResolver(() -> Optional.of(first), version::get);
		ShaderSource vanilla = (id, type) -> "vanilla";
		GbufferShaderSource source = resolver.source(RenderPipelines.SKY, "minecraft:overworld", vanilla,
			RenderPipelines.SKY.getVertexShader(), RenderPipelines.SKY.getFragmentShader());
		GbufferShaderSource again = resolver.source(RenderPipelines.SKY, "minecraft:overworld", vanilla,
			RenderPipelines.SKY.getVertexShader(), RenderPipelines.SKY.getFragmentShader());
		assertEquals(source.vertex(), again.vertex());
		assertEquals("v1\n", source.get(source.vertex(), ShaderType.VERTEX));
		version.set(4L);
		assertNotEquals(resolver.resolve(RenderPipelines.SKY, "minecraft:overworld").orElseThrow().version(), 3L);
	}

	private static ShaderPack load(Path directory, Map<String, String> entries) throws IOException {
		Path path = directory.resolve("pack");
		for (Map.Entry<String, String> entry : entries.entrySet()) {
			Path file = path.resolve(entry.getKey());
			Files.createDirectories(file.getParent());
			Files.writeString(file, entry.getValue(), java.nio.charset.StandardCharsets.ISO_8859_1);
		}
		return ShaderPack.load(path, Map.of());
	}
}
