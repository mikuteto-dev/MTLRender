package dev.mikuteto.mtlrender.chunk;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class ServerTemporaryBufferLedger {
	public record Key(String owner, long generation) {
		public Key {
			if (owner == null || owner.isBlank()) {
				throw new IllegalArgumentException("owner must not be blank");
			}
			if (generation < 0) {
				throw new IllegalArgumentException("generation must not be negative");
			}
		}
	}

	public final class Reservation {
		private final Key key;
		private final long bytes;
		private boolean released;

		private Reservation(Key key, long bytes) {
			this.key = key;
			this.bytes = bytes;
		}

		public Key key() {
			return key;
		}

		public long bytes() {
			return bytes;
		}
	}

	private final Map<Key, Entry> entries = new HashMap<>();
	private long reservedBytes;

	public synchronized Reservation reserve(String owner, long generation, long bytes) {
		Key key = new Key(owner, generation);
		validateBytes(bytes);
		if (bytes == 0) {
			return new Reservation(key, bytes);
		}
		entries.computeIfAbsent(key, ignored -> new Entry()).count++;
		entries.get(key).bytes += bytes;
		reservedBytes += bytes;
		return new Reservation(key, bytes);
	}

	public synchronized Optional<Reservation> tryReserve(String owner, long generation, long bytes, long limit) {
		validateLimit(limit);
		validateBytes(bytes);
		if (bytes > limit - reservedBytes) {
			return Optional.empty();
		}
		return Optional.of(reserve(owner, generation, bytes));
	}

	public void retainUntil(Reservation reservation, CompletableFuture<?> completion) {
		Objects.requireNonNull(completion, "completion");
		Objects.requireNonNull(reservation, "reservation");
		completion.whenComplete((ignored, failure) -> complete(reservation, reservation.key.generation()));
	}

	public synchronized boolean complete(Reservation reservation, long generation) {
		return release(reservation, generation);
	}

	public synchronized boolean fail(Reservation reservation, long generation) {
		return release(reservation, generation);
	}

	public synchronized boolean cancel(Reservation reservation, long generation) {
		return release(reservation, generation);
	}

	public synchronized long reservedBytes() {
		return reservedBytes;
	}

	public synchronized int reservationCount() {
		return entries.values().stream().mapToInt(entry -> entry.count).sum();
	}

	private boolean release(Reservation reservation, long generation) {
		Objects.requireNonNull(reservation, "reservation");
		if (reservation.released || reservation.key.generation() != generation) {
			return false;
		}
		if (reservation.bytes == 0) {
			reservation.released = true;
			return true;
		}
		Entry entry = entries.get(reservation.key);
		if (entry == null || entry.count == 0 || entry.bytes < reservation.bytes) {
			return false;
		}
		reservation.released = true;
		entry.count--;
		entry.bytes -= reservation.bytes;
		reservedBytes -= reservation.bytes;
		if (entry.count == 0) {
			entries.remove(reservation.key);
		}
		return true;
	}

	private static void validateBytes(long bytes) {
		if (bytes < 0) {
			throw new IllegalArgumentException("bytes must not be negative");
		}
	}

	private static void validateLimit(long limit) {
		if (limit < 0) {
			throw new IllegalArgumentException("limit must not be negative");
		}
	}

	private static final class Entry {
		private long bytes;
		private int count;
	}
}
