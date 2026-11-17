package dev.mikuteto.mtlrender.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ServerTemporaryBufferLedgerTest {
	@Test
	void completionReleasesReservationOnce() {
		ServerTemporaryBufferLedger ledger = new ServerTemporaryBufferLedger();
		ServerTemporaryBufferLedger.Reservation reservation = ledger.reserve("send", 1L, 128L);
		CompletableFuture<Void> completion = new CompletableFuture<>();

		ledger.retainUntil(reservation, completion);
		assertEquals(128L, ledger.reservedBytes());
		completion.complete(null);

		assertEquals(0L, ledger.reservedBytes());
		assertEquals(0, ledger.reservationCount());
		assertFalse(ledger.complete(reservation, 1L));
	}

	@Test
	void saveInProgressRetainsReservationUntilFutureCompletion() {
		ServerTemporaryBufferLedger ledger = new ServerTemporaryBufferLedger();
		ServerTemporaryBufferLedger.Reservation reservation = ledger.reserve("save", 4L, 256L);
		CompletableFuture<Void> save = new CompletableFuture<>();

		ledger.retainUntil(reservation, save);
		assertEquals(256L, ledger.reservedBytes());
		assertEquals(1, ledger.reservationCount());
		save.complete(null);
		assertEquals(0L, ledger.reservedBytes());
	}

	@Test
	void cancellationReleasesReservation() {
		ServerTemporaryBufferLedger ledger = new ServerTemporaryBufferLedger();
		ServerTemporaryBufferLedger.Reservation reservation = ledger.reserve("unload", 2L, 64L);

		assertTrue(ledger.cancel(reservation, 2L));
		assertEquals(0L, ledger.reservedBytes());
		assertFalse(ledger.cancel(reservation, 2L));
	}

	@Test
	void exceptionalCompletionReleasesReservation() {
		ServerTemporaryBufferLedger ledger = new ServerTemporaryBufferLedger();
		ServerTemporaryBufferLedger.Reservation reservation = ledger.reserve("save", 3L, 512L);
		CompletableFuture<Void> completion = new CompletableFuture<>();

		ledger.retainUntil(reservation, completion);
		completion.completeExceptionally(new IllegalStateException("failed"));

		assertEquals(0L, ledger.reservedBytes());
		assertFalse(ledger.fail(reservation, 3L));
	}

	@Test
	void duplicateCompletionDoesNotReleaseAnotherReservation() {
		ServerTemporaryBufferLedger ledger = new ServerTemporaryBufferLedger();
		ServerTemporaryBufferLedger.Reservation first = ledger.reserve("send", 5L, 32L);
		ServerTemporaryBufferLedger.Reservation second = ledger.reserve("send", 5L, 48L);

		assertTrue(ledger.complete(first, 5L));
		assertFalse(ledger.complete(first, 5L));
		assertEquals(48L, ledger.reservedBytes());
		assertTrue(ledger.complete(second, 5L));
	}

	@Test
	void staleGenerationCompletionIsRejected() {
		ServerTemporaryBufferLedger ledger = new ServerTemporaryBufferLedger();
		ServerTemporaryBufferLedger.Reservation reservation = ledger.reserve("send", 6L, 96L);

		assertFalse(ledger.complete(reservation, 7L));
		assertEquals(96L, ledger.reservedBytes());
		assertTrue(ledger.complete(reservation, 6L));
	}

	@Test
	void failedAllocationLeavesExistingReservationsUnchanged() {
		ServerTemporaryBufferLedger ledger = new ServerTemporaryBufferLedger();
		ledger.reserve("save", 8L, 80L);

		Optional<ServerTemporaryBufferLedger.Reservation> rejected = ledger.tryReserve("send", 8L, 24L, 100L);
		Optional<ServerTemporaryBufferLedger.Reservation> accepted = ledger.tryReserve("send", 8L, 20L, 100L);

		assertTrue(rejected.isEmpty());
		assertTrue(accepted.isPresent());
		assertEquals(100L, ledger.reservedBytes());
		assertTrue(ledger.complete(accepted.orElseThrow(), 8L));
		assertEquals(80L, ledger.reservedBytes());
	}
}
