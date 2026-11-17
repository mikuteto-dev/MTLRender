package dev.mikuteto.mtlrender.lod;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.server.level.ChunkLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.Test;

class LodMissingTerrainGeneratorTest {
	@Test
	void disabledConfigNeverAccepts() {
		assertFalse(LodMissingTerrainGenerator.canRequest(false, true, true, false,
			new ChunkPos(0, 0), new ChunkPos(9, 0), 8));
	}

	@Test
	void noIntegratedServerNeverAccepts() {
		assertFalse(LodMissingTerrainGenerator.canRequest(true, false, true, false,
			new ChunkPos(0, 0), new ChunkPos(9, 0), 8));
	}

	@Test
	void dimensionAndLoadedChunksAreRejected() {
		ChunkPos center = new ChunkPos(0, 0);
		ChunkPos target = new ChunkPos(9, 0);
		assertFalse(LodMissingTerrainGenerator.canRequest(true, true, false, false, center, target, 8));
		assertFalse(LodMissingTerrainGenerator.canRequest(true, true, true, true, center, target, 8));
	}

	@Test
	void outerDistanceIsBounded() {
		ChunkPos center = new ChunkPos(0, 0);
		assertTrue(LodMissingTerrainGenerator.withinOuterDistance(center, new ChunkPos(9, 0), 8));
		assertFalse(LodMissingTerrainGenerator.withinOuterDistance(center, new ChunkPos(17, 0), 8));
	}

	@Test
	void noiseGenerationUsesLowerPriorityThanFullGeneration() {
		assertTrue(ChunkLevel.byStatus(ChunkStatus.NOISE) > ChunkLevel.byStatus(ChunkStatus.FULL));
	}

	@Test
	void queuePolicyHasFiniteBounds() {
		assertTrue(LodMissingTerrainGenerator.MAX_QUEUE > 0);
		assertTrue(LodMissingTerrainGenerator.MAX_PER_TICK > 0);
		assertTrue(LodMissingTerrainGenerator.MAX_PER_TICK <= LodMissingTerrainGenerator.MAX_QUEUE);
	}
}
