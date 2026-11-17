package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.mikuteto.mtlrender.backend.PackPipelineDesc;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

class TerrainRendererPackDrawTest {
	@Test
	void directDrawLayoutsKeepIndexedAndNonIndexedCounts() {
		assertEquals(48, PackTerrainUnits.unitBytes(false));
		assertEquals(64, PackTerrainUnits.unitBytes(true));
		assertEquals(TerrainShaderPatch.VERTICES_PER_UNIT, 32 * 6);
		assertEquals(TerrainShaderPatch.QUADS_PER_UNIT * 4, 128);
	}

	@Test
	void indirectTailUsesTheSelectedUnitStride() {
		long legacy = 9L * PackTerrainUnits.unitBytes(false);
		long pack = 9L * PackTerrainUnits.unitBytes(true);
		assertEquals(432L, legacy);
		assertEquals(576L, pack);
		assertTrue(pack > legacy);
	}

	@Test
	void layerOrderAndWaterProgramAreIndependentOfPackSelection() {
		assertEquals(List.of(ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT, ChunkSectionLayer.TRANSLUCENT),
			List.of(ChunkSectionLayer.values()));
		assertEquals(dev.mikuteto.mtlrender.pack.ProgramId.Water,
			PackTerrainPipelines.programFor(ChunkSectionLayer.TRANSLUCENT));
	}

	@Test
	void staleSidecarCannotBeUsedByTheCurrentPack() {
		PackTerrainRequirements requirements = PackTerrainRequirements.fromDeclaredInputs(true, List.of("mc_Entity"), null);
		PackTerrainRenderState state = PackTerrainRenderState.active(8L, requirements, 13L,
			Optional.empty(), Optional.empty(), Optional.empty());
		assertFalse(state.metadataCompatible(7L, requirements.fingerprint(), 13L));
		assertFalse(state.metadataCompatible(8L, requirements.fingerprint(), 12L));
		assertTrue(state.metadataCompatible(8L, requirements.fingerprint(), 13L));
	}

	@Test
	void cutoutDepthComparisonIsSeparateFromTranslucentBlending() {
		assertEquals(com.mojang.blaze3d.platform.CompareOp.GREATER_THAN_OR_EQUAL,
			PackPipelineDesc.gameCompare(com.mojang.blaze3d.platform.CompareOp.LESS_THAN_OR_EQUAL));
		assertEquals(com.mojang.blaze3d.platform.CompareOp.ALWAYS_PASS,
			PackPipelineDesc.gameCompare(com.mojang.blaze3d.platform.CompareOp.ALWAYS_PASS));
	}

	@Test
	void missingPipelineFallsBackWithoutActivatingPackState() {
		PackTerrainRenderState state = PackTerrainRenderState.none();
		assertFalse(state.packActive());
		assertTrue(state.pipeline(ChunkSectionLayer.CUTOUT).isEmpty());
	}
}
