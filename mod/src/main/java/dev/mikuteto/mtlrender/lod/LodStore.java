package dev.mikuteto.mtlrender.lod;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class LodStore {
	private final long budgetBytes;
	private final LinkedHashMap<LodData.LodKey, LodData.LodSection> sections = new LinkedHashMap<>(16, 0.75F, true);
	private long currentBytes;
	private long evictions;

	public LodStore(long budgetBytes) {
		if (budgetBytes <= 0) {
			throw new IllegalArgumentException("budgetBytes must be positive");
		}
		this.budgetBytes = budgetBytes;
	}

	synchronized boolean put(LodData.LodSection section) {
		Objects.requireNonNull(section, "section");
		long size = section.estimatedBytes();
		if (size > budgetBytes) {
			return false;
		}

		LodData.LodKey key = section.key();
		LodData.LodSection replaced = sections.remove(key);
		if (replaced != null) {
			currentBytes -= replaced.estimatedBytes();
		}
		while (currentBytes > budgetBytes - size) {
			evictOldest();
		}
		sections.put(key, section);
		currentBytes += size;
		return true;
	}

	public synchronized Optional<LodData.LodSection> get(LodData.LodKey key) {
		return Optional.ofNullable(sections.get(Objects.requireNonNull(key, "key")));
	}

	synchronized Optional<LodData.LodSection> remove(LodData.LodKey key) {
		LodData.LodSection removed = sections.remove(Objects.requireNonNull(key, "key"));
		if (removed != null) {
			currentBytes -= removed.estimatedBytes();
		}
		return Optional.ofNullable(removed);
	}

	synchronized int clearByDimension(String dimension) {
		Objects.requireNonNull(dimension, "dimension");
		int removed = 0;
		Iterator<Map.Entry<LodData.LodKey, LodData.LodSection>> iterator = sections.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<LodData.LodKey, LodData.LodSection> entry = iterator.next();
			if (entry.getKey().dimension().equals(dimension)) {
				currentBytes -= entry.getValue().estimatedBytes();
				iterator.remove();
				removed++;
			}
		}
		return removed;
	}

	synchronized void clear() {
		sections.clear();
		currentBytes = 0;
	}

	public synchronized long budgetBytes() {
		return budgetBytes;
	}

	public synchronized long currentBytes() {
		return currentBytes;
	}

	public synchronized int entries() {
		return sections.size();
	}

	public synchronized long evictions() {
		return evictions;
	}

	private void evictOldest() {
		Iterator<Map.Entry<LodData.LodKey, LodData.LodSection>> iterator = sections.entrySet().iterator();
		if (!iterator.hasNext()) {
			throw new IllegalStateException("LOD store accounting is inconsistent");
		}
		Map.Entry<LodData.LodKey, LodData.LodSection> oldest = iterator.next();
		currentBytes -= oldest.getValue().estimatedBytes();
		iterator.remove();
		evictions++;
	}
}
