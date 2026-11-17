package dev.mikuteto.mtlrender.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class LodSaveManagerTest {
	@Test
	void candidatesStayOutsideInnerRingAndWithinBound() {
		ChunkPos center = new ChunkPos(4, -7);
		List<ChunkPos> positions = LodSaveManager.candidatePositions(center, 2, 4, 1000);
		Set<ChunkPos> unique = new HashSet<>(positions);
		assertEquals(positions.size(), unique.size());
		assertTrue(positions.stream().allMatch(pos -> {
			int dx = Math.abs(pos.x() - center.x());
			int dz = Math.abs(pos.z() - center.z());
			return Math.max(dx, dz) > 2 && Math.max(dx, dz) <= 4;
		}));
	}

	@Test
	void staleTokenIsRejected() {
		assertTrue(LodSaveManager.tokenValid(8, 8));
		assertTrue(!LodSaveManager.tokenValid(8, 9));
	}
}
