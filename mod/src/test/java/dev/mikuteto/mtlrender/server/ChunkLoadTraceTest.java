package dev.mikuteto.mtlrender.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ChunkLoadTraceTest {
	private static final String PROPERTY = "mtlrender.debug.chunkTiming";

	@AfterEach
	void reset() {
		System.clearProperty(PROPERTY);
		ChunkLoadTrace.clearSink();
	}

	@Test
	void disabledTracingDoesNotCallSink() {
		System.clearProperty(PROPERTY);
		List<ChunkLoadTrace.Event> events = new ArrayList<>();
		ChunkLoadTrace.setSink(events::add);

		try (ChunkLoadTrace.Scope ignored = ChunkLoadTrace.begin(new ChunkPos(2, 3), ChunkLoadPhase.REGION_READ)) {
		}
		ChunkLoadTrace.record(new ChunkPos(2, 3), ChunkLoadPhase.NBT_DECODE, 10L);

		assertTrue(events.isEmpty());
	}

	@Test
	void enabledScopeRecordsPhaseAndPosition() {
		System.setProperty(PROPERTY, "true");
		List<ChunkLoadTrace.Event> events = new ArrayList<>();
		ChunkLoadTrace.setSink(events::add);

		try (ChunkLoadTrace.Scope ignored = ChunkLoadTrace.begin(new ChunkPos(2, 3), ChunkLoadPhase.NBT_DECODE)) {
		}

		assertEquals(1, events.size());
		assertEquals(new ChunkPos(2, 3), events.getFirst().chunkPos());
		assertEquals(ChunkLoadPhase.NBT_DECODE, events.getFirst().phase());
		assertTrue(events.getFirst().durationNanos() >= 0L);
	}
}
