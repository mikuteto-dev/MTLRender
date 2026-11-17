package dev.mikuteto.mtlrender.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientChunkMemorySourceTest {
	@Test
	void snapshotKeepsChunkAndSectionGenerationConsistent() {
		ClientChunkMemorySnapshot.Chunk chunk = new ClientChunkMemorySnapshot.Chunk(2, 3, 7L, 24L, 1L,
			ClientChunkMemorySnapshot.UNKNOWN, 128L);
		ClientChunkMemorySnapshot.Section section = new ClientChunkMemorySnapshot.Section(2, 4, 3, 7L,
			palette(), palette(), ClientChunkMemorySnapshot.UNKNOWN, 2048L, 0L);

		ClientChunkMemorySnapshot snapshot = new ClientChunkMemorySnapshot(11L, 1, 1, 1L, 24L,
			List.of(chunk), List.of(section), 32L, 64L);

		assertEquals(snapshot.chunks().get(0).generation(), snapshot.sections().get(0).generation());
	}

	@Test
	void snapshotRejectsRecordsAboveBound() {
		ClientChunkMemorySnapshot.Chunk chunk = new ClientChunkMemorySnapshot.Chunk(0, 0, 0L, 0L, 0L, 0L, 0L);
		List<ClientChunkMemorySnapshot.Chunk> chunks = new ArrayList<>();
		chunks.add(chunk);

		assertThrows(IllegalArgumentException.class, () -> new ClientChunkMemorySnapshot(0L, 0, 0, 1L, 0L,
			chunks, List.of(), 0L, 0L));
	}

	@Test
	void snapshotListsAreImmutableAndUnknownTransientIsPreserved() {
		List<ClientChunkMemorySnapshot.Chunk> chunks = new ArrayList<>();
		chunks.add(new ClientChunkMemorySnapshot.Chunk(0, 0, 0L, 0L, 0L, ClientChunkMemorySnapshot.UNKNOWN,
			ClientChunkMemorySnapshot.UNKNOWN));
		ClientChunkMemorySnapshot snapshot = new ClientChunkMemorySnapshot(0L, 1, 0, 1L, 0L, chunks, List.of(),
			ClientChunkMemorySnapshot.UNKNOWN, ClientChunkMemorySnapshot.UNKNOWN);
		chunks.clear();

		assertEquals(1, snapshot.chunks().size());
		assertEquals(ClientChunkMemorySnapshot.UNKNOWN, snapshot.transientPacketBytes());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.chunks().clear());
	}

	private static ClientChunkMemorySnapshot.Palette palette() {
		return new ClientChunkMemorySnapshot.Palette(ClientChunkMemorySnapshot.Mode.LINEAR, 2L, 128L);
	}
}
