package dev.mikuteto.mtlrender.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class LodRenderManagerTest {
	@Test
	void candidatesAreOutsideDistanceAndDeduplicated() {
		ChunkPos center = new ChunkPos(3, -4);
		List<ChunkPos> positions = LodRenderManager.ring(center, 5, 7, 1000);
		assertEquals(positions.size(), new HashSet<>(positions).size());
		assertTrue(positions.stream().allMatch(pos -> {
			int distance = Math.max(Math.abs(pos.x() - center.x()), Math.abs(pos.z() - center.z()));
			return distance > 5 && distance <= 7;
		}));
	}

	@Test
	void noWorldDoesNotCreateCandidatesOrPartialMeshes() {
		try (LodRenderManager manager = new LodRenderManager()) {
			manager.update(new LodRenderManager.RenderInput(null, null, 0, 0, 8, false,
				Map.of(), new LodMesh.Material[0], 0));
			assertTrue(manager.snapshot().candidates().isEmpty());
			assertEquals(0, manager.snapshot().counters().aggregates());
		}
	}

	@Test
	void resetInvalidatesGeneration() {
		try (LodRenderManager manager = new LodRenderManager()) {
			long before = manager.snapshot().counters().generation();
			manager.reset();
			assertTrue(manager.snapshot().counters().generation() > before);
		}
	}
}
