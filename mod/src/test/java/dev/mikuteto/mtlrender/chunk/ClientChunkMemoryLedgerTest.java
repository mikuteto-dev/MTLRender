package dev.mikuteto.mtlrender.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikuteto.mtlrender.debug.MemoryAccount;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ClientChunkMemoryLedgerTest {
	@Test
	void rangeEvictionRemovesOnlyOutOfRangeChunks() {
		ClientChunkMemoryLedger ledger = new ClientChunkMemoryLedger(new MemoryAccount());
		ledger.replaceChunk(pack(0, 0));
		ledger.replaceChunk(pack(2, 1));
		ledger.replaceChunk(pack(3, 0));

		assertEquals(1, ledger.evictOutside(0, 0, 2));
		assertEquals(Set.of(pack(0, 0), pack(2, 1)), ledger.snapshot().chunks().stream()
			.map(ClientChunkMemoryLedger.Identity::key).collect(Collectors.toSet()));
	}

	@Test
	void sameCoordinateReplacementKeepsOnlyNewestGeneration() {
		MemoryAccount account = new MemoryAccount();
		ClientChunkMemoryLedger ledger = new ClientChunkMemoryLedger(account);
		long key = pack(4, -2);
		long oldGeneration = ledger.replaceChunk(key, 10, 1);
		long newGeneration = ledger.replaceChunk(key, 20, 1);

		assertTrue(newGeneration > oldGeneration);
		assertEquals(20, account.snapshot().bytes());
		assertFalse(ledger.releaseChunk(key, oldGeneration));
		assertEquals(20, account.snapshot().bytes());
	}

	@Test
	void dropAndReloadUseIndependentGenerations() {
		ClientChunkMemoryLedger ledger = new ClientChunkMemoryLedger(new MemoryAccount());
		long key = pack(-3, 7);
		long first = ledger.replaceChunk(key);
		ledger.removeChunk(key);
		long second = ledger.replaceChunk(key);

		assertTrue(second > first);
		assertFalse(ledger.releaseChunk(key, first));
		assertTrue(ledger.releaseChunk(key, second));
		assertTrue(ledger.snapshot().chunks().isEmpty());
	}

	@Test
	void releaseIsStaleSafeAndIdempotent() {
		MemoryAccount account = new MemoryAccount();
		ClientChunkMemoryLedger ledger = new ClientChunkMemoryLedger(account);
		long key = pack(1, 1);
		long generation = ledger.replaceChunk(key, 64, 1);

		assertTrue(ledger.releaseChunk(key, generation));
		assertFalse(ledger.releaseChunk(key, generation));
		assertEquals(0, account.snapshot().bytes());
	}

	@Test
	void snapshotEnumeratesChunksAndSections() {
		ClientChunkMemoryLedger ledger = new ClientChunkMemoryLedger(new MemoryAccount());
		long chunk = pack(2, 5);
		long section = sectionKey(2, 9, 5);
		long chunkGeneration = ledger.replaceChunk(chunk, 12, 1);
		long sectionGeneration = ledger.replaceSection(section, chunk, 24, 1);

		assertEquals(chunkGeneration, ledger.snapshot().chunks().get(0).generation());
		assertEquals(sectionGeneration, ledger.snapshot().sections().get(0).generation());
		assertEquals(chunk, ledger.snapshot().sections().get(0).chunkKey());
	}

	private static long pack(int x, int z) {
		return ((long)x << 32) | (z & 0xffffffffL);
	}

	private static long sectionKey(int x, int y, int z) {
		return pack(x, z) ^ ((long)y << 20);
	}
}
