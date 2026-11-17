package dev.mikuteto.mtlrender.pack.gbuffer;

import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbufferPipelineOverrideTest {
	@Test
	void inactivePackDoesNotPollVersionOrDimension() {
		AtomicInteger versionPolls = new AtomicInteger();
		AtomicInteger dimensionPolls = new AtomicInteger();
		GbufferPipelineOverride override = new GbufferPipelineOverride(Optional::empty,
			() -> { versionPolls.incrementAndGet(); return 1L; },
			() -> { dimensionPolls.incrementAndGet(); return "minecraft:overworld"; });
		GbufferPipelineOverride.Selection selection = override.select(RenderPipelines.SKY, vanilla());
		assertFalse(selection.active());
		assertEquals(0, versionPolls.get());
		assertEquals(0, dimensionPolls.get());
	}

	@Test
	void activePackSelectsResolvedSourceAndTracksState(@TempDir Path directory) throws IOException {
		ShaderPack pack = load(directory, Map.of("gbuffers_skybasic.vsh", "pack-v", "gbuffers_skybasic.fsh", "pack-f"));
		AtomicLong version = new AtomicLong(4L);
		AtomicInteger dimension = new AtomicInteger();
		GbufferPipelineOverride override = new GbufferPipelineOverride(() -> Optional.of(pack), version::get,
			() -> dimension.get() == 0 ? "minecraft:overworld" : "minecraft:the_nether");
		GbufferPipelineOverride.Selection selection = override.select(RenderPipelines.SKY, vanilla());
		assertTrue(selection.active());
		assertEquals(4L, selection.version());
		assertEquals("pack-v\n", selection.source().get(RenderPipelines.SKY.getVertexShader(), ShaderType.VERTEX));
		version.incrementAndGet();
		dimension.incrementAndGet();
		GbufferPipelineOverride.Selection changed = override.select(RenderPipelines.SKY, vanilla());
		assertEquals(5L, changed.version());
		assertEquals("minecraft:the_nether", changed.dimension());
	}

	@Test
	void guiIsNeverOverridden(@TempDir Path directory) throws IOException {
		ShaderPack pack = load(directory, Map.of("gbuffers_skybasic.vsh", "pack-v", "gbuffers_skybasic.fsh", "pack-f"));
		GbufferPipelineOverride override = new GbufferPipelineOverride(() -> Optional.of(pack), () -> 1L,
			() -> "minecraft:overworld");
		GbufferPipelineOverride.Selection selection = override.select(RenderPipelines.GUI, vanilla());
		assertFalse(selection.active());
	}

	private static ShaderSource vanilla() {
		return (id, type) -> "vanilla";
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
