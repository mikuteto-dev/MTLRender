package dev.mikuteto.mtlrender.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class ServerChunkMemorySnapshotTest {
	@Test
	void constructionSortsDimensionsAndCopiesInput() {
		List<ServerChunkMemorySnapshot.Dimension> input = new ArrayList<>(List.of(
			ServerChunkMemorySnapshot.Dimension.unknown("z"),
			ServerChunkMemorySnapshot.Dimension.unknown("minecraft:overworld")));
		ServerChunkMemorySnapshot snapshot = new ServerChunkMemorySnapshot(4L, 2,
			new ServerChunkMemorySnapshot.ServerIdentity(Optional.of("integrated-1"), OptionalLong.of(3L)), input);

		input.clear();
		assertEquals(List.of("minecraft:overworld", "z"), snapshot.dimensions().stream()
			.map(ServerChunkMemorySnapshot.Dimension::dimensionId).toList());
		assertEquals("integrated-1", snapshot.server().instanceId().orElseThrow());
		assertEquals(3L, snapshot.server().lifecycleGeneration().orElseThrow());
	}

	@Test
	void collectionsAreImmutable() {
		ServerChunkMemorySnapshot snapshot = new ServerChunkMemorySnapshot(0L, 1,
			ServerChunkMemorySnapshot.ServerIdentity.unknown(),
			List.of(ServerChunkMemorySnapshot.Dimension.unknown("minecraft:overworld")));

		assertThrows(UnsupportedOperationException.class, () -> snapshot.dimensions().clear());
	}

	@Test
	void unknownValuesRemainUnknown() {
		ServerChunkMemorySnapshot.Dimension dimension = ServerChunkMemorySnapshot.Dimension.unknown("minecraft:the_nether");
		ServerChunkMemorySnapshot snapshot = new ServerChunkMemorySnapshot(0L, 1,
			ServerChunkMemorySnapshot.ServerIdentity.unknown(), List.of(dimension));

		assertTrue(snapshot.server().instanceId().isEmpty());
		assertTrue(snapshot.aggregate().totalBytes().isEmpty());
		assertTrue(snapshot.aggregate().poiCount().isEmpty());
	}

	@Test
	void aggregateTotalsSumEveryDimensionWhenAvailable() {
		ServerChunkMemorySnapshot.Dimension first = dimension("minecraft:the_nether", 10L, 2L, 1L);
		ServerChunkMemorySnapshot.Dimension second = dimension("minecraft:overworld", 20L, 4L, 3L);
		ServerChunkMemorySnapshot snapshot = new ServerChunkMemorySnapshot(0L, 2,
			ServerChunkMemorySnapshot.ServerIdentity.unknown(), List.of(first, second));

		ServerChunkMemorySnapshot.Totals totals = snapshot.aggregate();
		assertEquals(30L, totals.totalBytes().orElseThrow());
		assertEquals(6L, totals.loadedChunkCount().orElseThrow());
		assertEquals(4L, totals.tickingChunkCount().orElseThrow());
	}

	@Test
	void duplicateOrOverLimitDimensionsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> new ServerChunkMemorySnapshot(0L, 1,
			ServerChunkMemorySnapshot.ServerIdentity.unknown(), List.of(
				ServerChunkMemorySnapshot.Dimension.unknown("same"),
				ServerChunkMemorySnapshot.Dimension.unknown("same"))));
	}

	private static ServerChunkMemorySnapshot.Dimension dimension(String id, long total, long loaded, long ticking) {
		return new ServerChunkMemorySnapshot.Dimension(id, OptionalLong.of(total), OptionalLong.of(loaded), OptionalLong.of(ticking),
			OptionalLong.of(1L), OptionalLong.of(2L), OptionalLong.of(3L), OptionalLong.of(4L), OptionalLong.of(5L),
			OptionalLong.of(6L), OptionalLong.of(7L), OptionalLong.of(8L), OptionalLong.of(9L), OptionalLong.of(10L),
			OptionalLong.of(11L), OptionalLong.of(12L));
	}
}
