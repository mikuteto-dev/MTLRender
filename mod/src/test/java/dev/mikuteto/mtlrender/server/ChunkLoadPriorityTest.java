package dev.mikuteto.mtlrender.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class ChunkLoadPriorityTest {
	@Test
	void nearerPlayerAlwaysHasHigherPriority() {
		ChunkLoadPriority priority = new ChunkLoadPriority();
		priority.register(1L, new ChunkPos(0, 0));
		priority.register(2L, new ChunkPos(20, 0));

		int near = priority.score(new ChunkPos(1, 0));
		int far = priority.score(new ChunkPos(10, 0));

		assertTrue(near < far);
	}

	@Test
	void noPlayerUsesVanillaSafeFallback() {
		assertEquals(Integer.MAX_VALUE, new ChunkLoadPriority().score(new ChunkPos(0, 0)));
	}

	@Test
	void equalDistancesHaveStableEqualScores() {
		ChunkLoadPriority priority = new ChunkLoadPriority();
		priority.register(1L, new ChunkPos(0, 0));

		assertEquals(priority.score(new ChunkPos(1, 0)), priority.score(new ChunkPos(0, 1)));
	}

	@Test
	void removingPlayerRestoresFallback() {
		ChunkLoadPriority priority = new ChunkLoadPriority();
		priority.register(1L, new ChunkPos(0, 0));
		priority.remove(1L);

		assertEquals(Integer.MAX_VALUE, priority.score(new ChunkPos(0, 0)));
	}
}
