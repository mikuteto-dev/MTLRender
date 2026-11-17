package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.mikuteto.mtlrender.pack.ProgramId;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.runtime.PackProgramCache;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

class PackTerrainPipelinesTest {
	@Test
	void mapsTerrainLayersToThePackProgramChain() {
		assertEquals(ProgramId.TerrainSolid, PackTerrainPipelines.programFor(ChunkSectionLayer.SOLID));
		assertEquals(ProgramId.TerrainCutout, PackTerrainPipelines.programFor(ChunkSectionLayer.CUTOUT));
		assertEquals(ProgramId.Water, PackTerrainPipelines.programFor(ChunkSectionLayer.TRANSLUCENT));
		assertEquals(ProgramId.Terrain, ProgramId.TerrainSolid.fallback().orElseThrow());
		assertEquals(ProgramId.TexturedLit, ProgramId.Terrain.fallback().orElseThrow());
	}

	@Test
	void noPackShortCircuitsBeforeCacheAndIdMap() {
		AtomicInteger cacheCalls = new AtomicInteger();
		AtomicInteger idMapCalls = new AtomicInteger();
		PackProgramCache<PackTerrainPipelines.Artifact> cache = new PackProgramCache<>(
			() -> new PackProgramCache.State(1, false), ignored -> cacheCalls.incrementAndGet());
		PackTerrainPipelines pipelines = new PackTerrainPipelines(
			Optional::<ShaderPack>empty, () -> 1L, () -> {
				idMapCalls.incrementAndGet();
				return null;
			}, cache, (dimension, program, layer) -> Optional.empty());

		assertTrue(pipelines.get(new TerrainPipelines(false, true, false, false, false, false),
			"overworld", ChunkSectionLayer.SOLID, PackTerrainRequirements.none(), PackTerrainPipelines.VertexMode.COMPACT).isEmpty());
		assertEquals(0, cacheCalls.get());
		assertEquals(0, idMapCalls.get());
	}

	@Test
	void vertexModesRemainDistinctInTheLookupContract() {
		assertEquals(List.of(PackTerrainPipelines.VertexMode.COMPACT, PackTerrainPipelines.VertexMode.BLOCK,
			PackTerrainPipelines.VertexMode.PACK), List.of(PackTerrainPipelines.VertexMode.values()));
	}
}
