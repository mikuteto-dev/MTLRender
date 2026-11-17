package dev.mikuteto.mtlrender.debug;

import java.util.concurrent.atomic.LongAdder;

public final class StaticBlockEntityBakeStats {
	private static final int VARIANTS = 2;
	private static final LongAdder[][] COUNTERS = counters();

	private StaticBlockEntityBakeStats() {
	}

	public static void reset() {
		for (LongAdder[] variant : COUNTERS) {
			for (LongAdder counter : variant) {
				counter.reset();
			}
		}
	}

	public static void extracted() {
		counter(Counter.EXTRACTED).increment();
	}

	public static void dynamicSubmission() {
		counter(Counter.DYNAMIC_SUBMISSIONS).increment();
	}

	public static void skippedStatic() {
		counter(Counter.SKIPPED_STATIC).increment();
	}

	public static void retainedDynamic() {
		counter(Counter.RETAINED_DYNAMIC).increment();
	}

	public static void retainedException() {
		counter(Counter.RETAINED_EXCEPTION).increment();
	}

	public static void staticPublished() {
		counter(Counter.STATIC_PUBLISHED).increment();
	}

	public static void fallback() {
		counter(Counter.FALLBACKS).increment();
	}

	public static void failedBake() {
		counter(Counter.FAILED_BAKES).increment();
	}

	public static Snapshot snapshot(boolean variantB) {
		LongAdder[] values = COUNTERS[variantB ? 1 : 0];
		return new Snapshot(values[0].sum(), values[1].sum(), values[2].sum(), values[3].sum(), values[4].sum(), values[5].sum(), values[6].sum(), values[7].sum());
	}

	private static LongAdder counter(Counter counter) {
		return COUNTERS[variant() ? 1 : 0][counter.ordinal()];
	}

	private static boolean variant() {
		return AbSwitch.NAME.equals(AbSwitch.BLOCK_ENTITY_BAKE) && AbSwitch.b();
	}

	private static LongAdder[][] counters() {
		LongAdder[][] values = new LongAdder[VARIANTS][Counter.values().length];
		for (int variant = 0; variant < VARIANTS; variant++) {
			for (int counter = 0; counter < Counter.values().length; counter++) {
				values[variant][counter] = new LongAdder();
			}
		}
		return values;
	}

	private enum Counter {
		EXTRACTED,
		DYNAMIC_SUBMISSIONS,
		SKIPPED_STATIC,
		RETAINED_DYNAMIC,
		RETAINED_EXCEPTION,
		STATIC_PUBLISHED,
		FALLBACKS,
		FAILED_BAKES
	}

	public record Snapshot(long extracted, long dynamicSubmissions, long skippedStatic, long retainedDynamic,
		long retainedException, long staticPublished, long fallbacks, long failedBakes) {
	}
}
