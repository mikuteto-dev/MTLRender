package dev.mikuteto.mtlrender.gui;

import java.util.HashMap;
import java.util.Map;

public final class GuiMapAtlasState<K> {
	private final int capacity;
	private final Map<K, Entry> entries = new HashMap<>();
	private long sequence;

	public GuiMapAtlasState(final int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("atlas capacity must be positive");
		}
		this.capacity = capacity;
	}

	public Lookup<K> acquire(final K key) {
		Entry entry = this.entries.get(key);
		if (entry != null) {
			entry.lastUse = ++this.sequence;
			return new Lookup<>(entry.slot, null, false);
		}
		K evicted = null;
		if (this.entries.size() == this.capacity) {
			evicted = this.entries.entrySet().stream()
				.min((first, second) -> {
					int order = Long.compare(first.getValue().lastUse, second.getValue().lastUse);
					return order != 0 ? order : Integer.compare(first.getValue().slot, second.getValue().slot);
				})
				.map(entrySet -> entrySet.getKey())
				.orElse(null);
			if (evicted != null) {
				this.entries.remove(evicted);
			}
		}
		int slot = this.entries.size();
		if (evicted != null) {
			slot = this.entries.values().stream().mapToInt(entryValue -> entryValue.slot).min().orElse(0);
			boolean[] used = new boolean[this.capacity];
			for (Entry value : this.entries.values()) {
				used[value.slot] = true;
			}
			for (int index = 0; index < used.length; index++) {
				if (!used[index]) {
					slot = index;
					break;
				}
			}
		}
		this.entries.put(key, new Entry(slot, ++this.sequence));
		return new Lookup<>(slot, evicted, true);
	}

	public int capacity() {
		return this.capacity;
	}

	public int size() {
		return this.entries.size();
	}

	public int slot(final K key) {
		Entry entry = this.entries.get(key);
		return entry == null ? -1 : entry.slot;
	}

	public record Lookup<K>(int slot, K evicted, boolean allocated) {
	}

	private static final class Entry {
		private final int slot;
		private long lastUse;

		private Entry(final int slot, final long lastUse) {
			this.slot = slot;
			this.lastUse = lastUse;
		}
	}
}
