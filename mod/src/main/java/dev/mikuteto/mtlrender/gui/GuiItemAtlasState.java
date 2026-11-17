package dev.mikuteto.mtlrender.gui;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GuiItemAtlasState<K> {
	private final int width;
	private final int height;
	private final ArrayDeque<Integer> free = new ArrayDeque<>();
	private final Map<K, Entry> entries = new HashMap<>();
	private final Map<Integer, K> keys = new HashMap<>();

	public GuiItemAtlasState(final int width, final int height) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("atlas dimensions must be positive");
		}
		this.width = width;
		this.height = height;
		for (int index = 0; index < width * height; index++) {
			this.free.addLast(index);
		}
	}

	public Lookup lookup(final K key, final boolean animated, final long version, final long resourceGeneration) {
		Entry entry = this.entries.get(key);
		if (entry != null) {
			boolean update = entry.resourceGeneration != resourceGeneration
				|| animated && (!entry.animated || entry.version != version);
			entry.animated = animated;
			entry.version = animated ? version : 0L;
			entry.resourceGeneration = resourceGeneration;
			return new Lookup(entry.x, entry.y, update, false);
		}
		Integer index = this.free.pollFirst();
		if (index == null) {
			return null;
		}
		int x = index % this.width;
		int y = index / this.width;
		this.entries.put(key, new Entry(x, y, animated, animated ? version : 0L, resourceGeneration));
		this.keys.put(index, key);
		return new Lookup(x, y, true, true);
	}

	public boolean retainOnly(final Set<? extends K> requested) {
		Set<K> missing = new HashSet<>(requested);
		missing.removeAll(this.entries.keySet());
		int reclaimable = 0;
		for (K key : this.entries.keySet()) {
			if (!requested.contains(key)) {
				reclaimable++;
			}
		}
		if (missing.size() > this.free.size() + reclaimable) {
			return false;
		}
		this.entries.entrySet().removeIf(entry -> {
			if (requested.contains(entry.getKey())) {
				return false;
			}
			int index = entry.getValue().index(this.width);
			this.keys.remove(index);
			this.free.addLast(index);
			return true;
		});
		return true;
	}

	public void endFrame(final Set<? extends K> requested) {
		this.retainOnly(requested);
	}

	public int capacity() {
		return this.width * this.height;
	}

	public int size() {
		return this.entries.size();
	}

	public List<Integer> slotOrder(final List<? extends K> requested) {
		return requested.stream().map(this::slotIndex).toList();
	}

	private int slotIndex(final K key) {
		Entry entry = this.entries.get(key);
		return entry == null ? -1 : entry.index(this.width);
	}

	public record Lookup(int x, int y, boolean update, boolean allocated) {
	}

	private static final class Entry {
		private final int x;
		private final int y;
		private boolean animated;
		private long version;
		private long resourceGeneration;

		private Entry(final int x, final int y, final boolean animated, final long version, final long resourceGeneration) {
			this.x = x;
			this.y = y;
			this.animated = animated;
			this.version = version;
			this.resourceGeneration = resourceGeneration;
		}

		private int index(final int width) {
			return this.y * width + this.x;
		}
	}
}
