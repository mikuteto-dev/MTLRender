package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.pipeline.ColorTargetState;
import dev.mikuteto.mtlrender.backend.PackPipelineDesc;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

class TerrainRendererPackPassTest {
	@Test
	void noPackStateHasNoSelectedBinding() {
		PackTerrainRenderState state = PackTerrainRenderState.none();

		assertFalse(state.packActive());
		assertTrue(state.pipeline(ChunkSectionLayer.SOLID).isEmpty());
	}

	@Test
	void eachLayerKeepsItsSelectedGenerationAndRequirements() {
		PackTerrainRequirements solidRequirements = PackTerrainRequirements.fromDeclaredInputs(true, List.of("Position"), null);
		PackTerrainRequirements cutoutRequirements = PackTerrainRequirements.fromDeclaredInputs(true, List.of("mc_Entity"), null);
		PackTerrainRequirements waterRequirements = PackTerrainRequirements.fromDeclaredInputs(true, List.of("Normal"), null);
		PackTerrainPipelines.Selection solid = selection(11L, solidRequirements, "solid");
		PackTerrainPipelines.Selection cutout = selection(12L, cutoutRequirements, "cutout");
		PackTerrainPipelines.Selection water = selection(13L, waterRequirements, "water");
		PackTerrainRenderState state = PackTerrainRenderState.active(7L, waterRequirements, 19L,
			Optional.of(solid), Optional.of(cutout), Optional.of(water));

		assertEquals(11L, state.pipeline(ChunkSectionLayer.SOLID).orElseThrow().pipeline());
		assertEquals(solidRequirements.fingerprint(), state.pipeline(ChunkSectionLayer.SOLID).orElseThrow().requirements().fingerprint());
		assertEquals(12L, state.pipeline(ChunkSectionLayer.CUTOUT).orElseThrow().pipeline());
		assertEquals(13L, state.pipeline(ChunkSectionLayer.TRANSLUCENT).orElseThrow().pipeline());
		assertEquals("water", state.pipeline(ChunkSectionLayer.TRANSLUCENT).orElseThrow().descriptor().vertexEntry());
	}

	@Test
	void selectedBindingsContainTerrainBuffersAndTexture() {
		PackPipelineDesc descriptor = descriptor("terrain", List.of(
			new PackPipelineDesc.Binding("ChunkSection", PackPipelineDesc.Kind.BUFFER, 0, PackPipelineDesc.STAGE_VERTEX),
			new PackPipelineDesc.Binding("MtlrUnits", PackPipelineDesc.Kind.BUFFER, 1, PackPipelineDesc.STAGE_VERTEX),
			new PackPipelineDesc.Binding("Sampler0", PackPipelineDesc.Kind.TEXTURE, 2, PackPipelineDesc.STAGE_FRAGMENT)));
		assertEquals(3, descriptor.bindings().size());
		assertTrue(descriptor.bindings().stream().anyMatch(binding -> binding.name().equals("ChunkSection")));
		assertTrue(descriptor.bindings().stream().anyMatch(binding -> binding.name().equals("MtlrUnits")));
		assertTrue(descriptor.bindings().stream().anyMatch(binding -> binding.name().equals("Sampler0")));
	}

	private static PackTerrainPipelines.Selection selection(long pipeline, PackTerrainRequirements requirements, String entry) {
		return new PackTerrainPipelines.Selection(pipeline, descriptor(entry, List.of()), requirements, pipeline,
			PackTerrainPipelines.VertexMode.PACK, Optional.empty());
	}

	private static PackPipelineDesc descriptor(String entry, List<PackPipelineDesc.Binding> bindings) {
		return new PackPipelineDesc("vertex", entry, "fragment", "fragment", bindings, null,
			List.<ColorTargetState>of(), null, null, false, PackPipelineDesc.CullMode.NONE);
	}
}
