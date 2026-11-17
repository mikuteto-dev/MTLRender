package dev.mikuteto.mtlrender.particle;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;

public final class ParticleLightCache {
	private static final Map<Long, Integer> VALUES = new HashMap<>();
	private static long hits;
	private static long misses;

	private ParticleLightCache() {
	}

	public static int get(long position, IntSupplier lookup) {
		Integer value = VALUES.get(position);
		if (value != null) {
			hits++;
			return value;
		}
		misses++;
		int result = lookup.getAsInt();
		VALUES.put(position, result);
		return result;
	}

	public static void clear() {
		VALUES.clear();
	}

	public static long hits() {
		return hits;
	}

	public static long misses() {
		return misses;
	}

	public static int size() {
		return VALUES.size();
	}
}
