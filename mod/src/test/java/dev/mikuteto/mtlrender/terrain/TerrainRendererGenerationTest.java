package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.mikuteto.mtlrender.backend.PackPipelineDesc;

class TerrainRendererGenerationTest {
	private static final PackTerrainRequirements VANILLA = PackTerrainRequirements.fromDeclaredInputs(true,
		List.of("Position", "Color", "UV0", "UV2"), null);
	private static final PackTerrainRequirements EXTRA = PackTerrainRequirements.fromDeclaredInputs(true,
		List.of("Position", "Normal", "mc_Entity"), null);

	@Test
	void unchangedIdentityReusesRecords() {
		PackTerrainRenderState first = state(4L, 8L, 12L, 19L, VANILLA, 7L);
		PackTerrainRenderState same = state(4L, 8L, 12L, 19L, VANILLA, 7L);

		assertTrue(first.equals(same));
		assertTrue(first.metadataCompatible(8L, VANILLA.fingerprint(), 7L));
	}

	@Test
	void everyPackInputRetiresThePreviousSelection() {
		PackTerrainRenderState packReload = state(5L, 8L, 12L, 19L, VANILLA, 7L);
		PackTerrainRenderState requirements = state(4L, 9L, 12L, 19L, EXTRA, 7L);
		PackTerrainRenderState idMap = state(4L, 8L, 12L, 19L, VANILLA, 8L);
		PackTerrainRenderState resourceReload = state(4L, 8L, 13L, 20L, VANILLA, 7L);
		PackTerrainRenderState meshReplacement = state(4L, 9L, 12L, 21L, VANILLA, 7L);

		assertNotEquals(packReload, requirements);
		assertNotEquals(packReload, idMap);
		assertNotEquals(packReload, resourceReload);
		assertNotEquals(packReload, meshReplacement);
		assertFalse(meshReplacement.metadataCompatible(8L, VANILLA.fingerprint(), 7L));
	}

	@Test
	void transitionToNoPackDropsPipelineAndMetadata() {
		PackTerrainRenderState active = state(4L, 8L, 12L, 19L, VANILLA, 7L);
		PackTerrainRenderState none = PackTerrainRenderState.none();

		assertFalse(none.packActive());
		assertTrue(none.pipeline(net.minecraft.client.renderer.chunk.ChunkSectionLayer.SOLID).isEmpty());
		assertFalse(none.metadataCompatible(8L, VANILLA.fingerprint(), 7L));
		assertNotEquals(active, none);
	}

	private static PackTerrainRenderState state(long pack, long metadata, long pipeline, long handle,
		PackTerrainRequirements requirements, long idMap) {
		PackTerrainPipelines.Selection selection = new PackTerrainPipelines.Selection(handle, descriptor(), requirements,
			pipeline, PackTerrainPipelines.VertexMode.PACK, Optional.empty());
		return PackTerrainRenderState.active(pack, metadata, pipeline, requirements, idMap,
			Optional.of(selection), Optional.empty(), Optional.empty());
	}

	private static PackPipelineDesc descriptor() {
		return new PackPipelineDesc("v", "v", "f", "f", List.of(), null, List.of(), null, null, false,
			PackPipelineDesc.CullMode.NONE);
	}
}
