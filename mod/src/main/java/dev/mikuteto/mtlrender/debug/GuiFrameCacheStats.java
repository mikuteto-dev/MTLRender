package dev.mikuteto.mtlrender.debug;

import java.util.EnumMap;
import java.util.Map;

public final class GuiFrameCacheStats {
	public enum InvalidationReason {
		SCREEN,
		RESOURCE_RELOAD,
		RESOURCE_APPLIED,
		DISCONNECT,
		CLIENT_LEVEL,
		LIFECYCLE,
		CLOSE
	}

	private static volatile boolean observed = Boolean.getBoolean("mtlrender.guiFrameStats");
	private static long extractions;
	private static long guiUpdates;
	private static long keyHits;
	private static long keyMisses;
	private static long preparations;
	private static long encodings;
	private static long replays;
	private static long inFlightLeaseFailures;
	private static final EnumMap<InvalidationReason, Long> invalidations = new EnumMap<>(InvalidationReason.class);

	private GuiFrameCacheStats() {
	}

	public static void observe() {
		observed = true;
	}

	public static void stopObserving() {
		observed = false;
	}

	public static boolean isObserved() {
		return observed;
	}

	public static void reset() {
		extractions = 0L;
		guiUpdates = 0L;
		keyHits = 0L;
		keyMisses = 0L;
		preparations = 0L;
		encodings = 0L;
		replays = 0L;
		inFlightLeaseFailures = 0L;
		invalidations.clear();
	}

	public static void recordExtraction() {
		if (observed) extractions++;
	}

	public static void recordGuiUpdate() {
		if (observed) guiUpdates++;
	}

	public static void recordKeyHit() {
		if (observed) keyHits++;
	}

	public static void recordKeyMiss() {
		if (observed) keyMisses++;
	}

	public static void recordPreparation() {
		if (observed) preparations++;
	}

	public static void recordEncoding() {
		if (observed) encodings++;
	}

	public static void recordReplay() {
		if (observed) replays++;
	}

	public static void recordInFlightLeaseFailure() {
		if (observed) inFlightLeaseFailures++;
	}

	public static void recordInvalidation(final InvalidationReason reason) {
		if (observed) invalidations.merge(reason, 1L, Long::sum);
	}

	public static Snapshot snapshot() {
		return new Snapshot(extractions, guiUpdates, keyHits, keyMisses, preparations, encodings, replays,
			inFlightLeaseFailures, Map.copyOf(invalidations));
	}

	public record Snapshot(
		long extractions,
		long guiUpdates,
		long keyHits,
		long keyMisses,
		long preparations,
		long encodings,
		long replays,
		long inFlightLeaseFailures,
		Map<InvalidationReason, Long> invalidations
	) {
		public long invalidations(final InvalidationReason reason) {
			return this.invalidations.getOrDefault(reason, 0L);
		}
	}
}
