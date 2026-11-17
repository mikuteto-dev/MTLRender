package dev.mikuteto.mtlrender.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkLoadCoordinatorTest {
	@Test
	void preservesPacketOrderAndDrainsInBounds() {
		ChunkLoadCoordinator coordinator = new ChunkLoadCoordinator(4);
		long token = coordinator.generation();
		assertTrue(coordinator.recordChunkPacket(11L, token));
		assertTrue(coordinator.recordChunkPacket(22L, token));
		assertTrue(coordinator.notifyLightReady(31L, token));
		assertTrue(coordinator.notifyCompileAdmission(41L, token));
		List<String> events = new ArrayList<>();
		ChunkLoadCoordinator.Listener listener = new RecordingListener(events);

		assertEquals(2, coordinator.drain(2, listener));
		assertEquals(List.of("chunk:11", "chunk:22"), events);
		assertEquals(2, coordinator.pending());
		assertEquals(2, coordinator.drain(10, listener));
		assertEquals(List.of("chunk:11", "chunk:22", "light:31", "compile:41"), events);
		assertEquals(0, coordinator.pending());
	}

	@Test
	void suppressesDuplicateNotifications() {
		ChunkLoadCoordinator coordinator = new ChunkLoadCoordinator(4);
		long token = coordinator.generation();
		assertTrue(coordinator.recordChunkPacket(7L, token));
		assertFalse(coordinator.recordChunkPacket(7L, token));
		assertEquals(1, coordinator.pending());
		assertTrue(coordinator.notifyLightReady(7L, token));
		assertFalse(coordinator.notifyLightReady(7L, token));
		assertTrue(coordinator.notifyCompileAdmission(7L, token));
		assertFalse(coordinator.notifyCompileAdmission(7L, token));
		assertEquals(3, coordinator.pending());
	}

	@Test
	void rejectsStaleGenerationAndResetsQueue() {
		ChunkLoadCoordinator coordinator = new ChunkLoadCoordinator(4);
		long oldToken = coordinator.generation();
		assertTrue(coordinator.recordChunkPacket(1L, oldToken));
		coordinator.reset();
		long newToken = coordinator.generation();
		assertFalse(coordinator.recordChunkPacket(2L, oldToken));
		assertFalse(coordinator.notifyLightReady(3L, oldToken));
		assertEquals(0, coordinator.pending());
		assertTrue(coordinator.notifyLightReady(3L, newToken));
		assertEquals(1, coordinator.pending());
	}

	@Test
	void refusesEventsPastCapacityAndHandlesEmptyDrain() {
		ChunkLoadCoordinator coordinator = new ChunkLoadCoordinator(2);
		long token = coordinator.generation();
		assertEquals(0, coordinator.drain(0, new RecordingListener(new ArrayList<>())));
		assertEquals(0, coordinator.drain(2, new RecordingListener(new ArrayList<>())));
		assertTrue(coordinator.recordChunkPacket(1L, token));
		assertTrue(coordinator.recordChunkPacket(2L, token));
		assertFalse(coordinator.recordChunkPacket(3L, token));
		assertEquals(2, coordinator.pending());
	}

	private static final class RecordingListener implements ChunkLoadCoordinator.Listener {
		private final List<String> events;

		private RecordingListener(List<String> events) {
			this.events = events;
		}

		@Override
		public void onChunkPacket(long chunkKey, long generation) {
			this.events.add("chunk:" + chunkKey);
		}

		@Override
		public void onLightReady(long sectionKey, long generation) {
			this.events.add("light:" + sectionKey);
		}

		@Override
		public void onCompileAdmission(long sectionKey, long generation) {
			this.events.add("compile:" + sectionKey);
		}
	}
}
