package dev.mikuteto.mtlrender.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.server.level.FullChunkStatus;
import org.junit.jupiter.api.Test;

class ServerChunkMemoryLedgerTest {
	@Test
	void reloadCreatesGenerationAndRejectsOldRelease() {
		ServerChunkMemoryLedger ledger = new ServerChunkMemoryLedger();
		ServerChunkMemoryLedger.Handle oldHandle = ledger.load("minecraft:overworld", 4L);
		ServerChunkMemoryLedger.Claim oldSave = ledger.begin(oldHandle, ServerChunkMemoryLedger.Pending.SAVE);
		ServerChunkMemoryLedger.Handle newHandle = ledger.load("minecraft:overworld", 4L);

		assertTrue(newHandle.generation() > oldHandle.generation());
		assertFalse(ledger.release(oldSave));
		assertEquals(0, ledger.snapshot().chunks().getLast().pendingSave());
	}

	@Test
	void statusTransitionsTrackLoadedAndTicking() {
		ServerChunkMemoryLedger ledger = new ServerChunkMemoryLedger();
		ServerChunkMemoryLedger.Handle handle = ledger.load("minecraft:overworld", 8L);

		assertTrue(ledger.transition(handle, FullChunkStatus.FULL));
		assertTrue(ledger.snapshot().chunks().getFirst().loaded());
		assertFalse(ledger.snapshot().chunks().getFirst().ticking());
		assertTrue(ledger.transition(handle, FullChunkStatus.ENTITY_TICKING));
		assertTrue(ledger.snapshot().chunks().getFirst().ticking());
		assertTrue(ledger.transition(handle, FullChunkStatus.INACCESSIBLE));
		assertFalse(ledger.snapshot().chunks().getFirst().loaded());
	}

	@Test
	void saveRetentionAndUnloadWaitForCompletion() {
		ServerChunkMemoryLedger ledger = new ServerChunkMemoryLedger();
		ServerChunkMemoryLedger.Handle handle = ledger.load("minecraft:overworld", 12L);
		ServerChunkMemoryLedger.Claim save = ledger.begin(handle, ServerChunkMemoryLedger.Pending.SAVE);

		assertTrue(ledger.unload(handle));
		assertEquals(1, ledger.snapshot().chunks().size());
		assertTrue(ledger.release(save));
		assertTrue(ledger.snapshot().chunks().isEmpty());
		assertFalse(ledger.release(save));
	}

	@Test
	void exceptionalCompletionUsesTheSameIdempotentRelease() {
		ServerChunkMemoryLedger ledger = new ServerChunkMemoryLedger();
		ServerChunkMemoryLedger.Handle handle = ledger.load("minecraft:the_nether", 16L);
		ServerChunkMemoryLedger.Claim send = ledger.begin(handle, ServerChunkMemoryLedger.Pending.SEND);
		ServerChunkMemoryLedger.Claim task = ledger.begin(handle, ServerChunkMemoryLedger.Pending.TASK);

		assertNotNull(send);
		assertTrue(ledger.release(send));
		assertFalse(ledger.release(send));
		assertTrue(ledger.release(task));
		assertEquals(0, ledger.snapshot().chunks().getFirst().pendingSend());
		assertEquals(0, ledger.snapshot().chunks().getFirst().pendingTask());
	}

	@Test
	void sectionReplacementRejectsStaleRelease() {
		ServerChunkMemoryLedger ledger = new ServerChunkMemoryLedger();
		ServerChunkMemoryLedger.SectionHandle oldHandle = ledger.loadSection("minecraft:overworld", 20L);
		ServerChunkMemoryLedger.SectionHandle newHandle = ledger.loadSection("minecraft:overworld", 20L);

		assertFalse(ledger.releaseSection(oldHandle));
		assertTrue(ledger.releaseSection(newHandle));
		assertTrue(ledger.snapshot().sections().isEmpty());
	}
}
