package dev.mikuteto.mtlrender.gui;

import java.util.Objects;
import dev.mikuteto.mtlrender.debug.GuiFrameCacheStats;

public final class GuiFrameCache<T> {
	private Entry<T> current;
	private long hits;
	private long misses;
	private long extractions;
	private long preparations;
	private long replays;

	public Lease<T> lookup(final GuiFrameCacheKey key) {
		Entry<T> entry = this.current;
		if (entry != null && entry.key().equals(key)) {
			this.hits++;
			GuiFrameCacheStats.recordKeyHit();
			entry.retain();
			return new Lease<>(entry, this);
		}
		this.misses++;
		GuiFrameCacheStats.recordKeyMiss();
		return null;
	}

	public void publish(final GuiFrameCacheKey key, final T value) {
		Objects.requireNonNull(key);
		Objects.requireNonNull(value);
		Entry<T> previous = this.current;
		this.current = new Entry<>(key, value);
		if (previous != null) {
			previous.releaseOwner();
		}
	}

	public void invalidate() {
		Entry<T> previous = this.current;
		this.current = null;
		if (previous != null) {
			previous.releaseOwner();
		}
	}

	public void recordExtraction() {
		this.extractions++;
	}

	public void recordPreparation() {
		this.preparations++;
		GuiFrameCacheStats.recordPreparation();
	}

	public void recordReplay() {
		this.replays++;
		GuiFrameCacheStats.recordReplay();
	}

	public Counters counters() {
		return new Counters(this.hits, this.misses, this.extractions, this.preparations, this.replays);
	}

	public record Counters(long hits, long misses, long extractions, long preparations, long replays) {
	}

	public static final class Lease<T> implements AutoCloseable {
		private Entry<T> entry;
		private GuiFrameCache<T> owner;

		private Lease(final Entry<T> entry, final GuiFrameCache<T> owner) {
			this.entry = entry;
			this.owner = owner;
		}

		public T replayableValue() {
			Entry<T> retained = this.entry;
			if (retained == null) {
				GuiFrameCacheStats.recordInFlightLeaseFailure();
				throw new IllegalStateException("released GUI frame lease");
			}
			return retained.value();
		}

		@Override
		public void close() {
			Entry<T> retained = this.entry;
			if (retained != null) {
				this.entry = null;
				this.owner = null;
				retained.releaseLease();
			}
		}
	}

	private static final class Entry<T> {
		private final GuiFrameCacheKey key;
		private final T value;
		private int leases = 1;

		private Entry(final GuiFrameCacheKey key, final T value) {
			this.key = key;
			this.value = value;
		}

		private GuiFrameCacheKey key() {
			return this.key;
		}

		private T value() {
			return this.value;
		}

		private void retain() {
			this.leases++;
		}

		private void releaseOwner() {
			this.leases--;
		}

		private void releaseLease() {
			this.leases--;
		}
	}
}
