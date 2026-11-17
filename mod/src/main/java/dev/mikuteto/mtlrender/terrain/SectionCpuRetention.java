package dev.mikuteto.mtlrender.terrain;

import java.util.concurrent.atomic.LongAdder;

public final class SectionCpuRetention {
	public static final int STATE_BYTES = 16 * 16 * 16 * Integer.BYTES;
	private static final LongAdder RETAINED_BYTES = new LongAdder();

	private SectionCpuRetention() {
	}

	public static boolean required() {
		return shouldRetain(
			Boolean.getBoolean("mtlrender.memory.keepSectionCpuCopy"),
			Boolean.getBoolean("mtlrender.debug.mesherCheck")
				|| Boolean.getBoolean("mtlrender.debug.expandCheck")
				|| Boolean.getBoolean("mtlrender.bench.quadStats")
		);
	}

	static boolean shouldRetain(boolean keepCopy, boolean diagnostic) {
		return keepCopy || diagnostic;
	}

	public static void retainStateIds() {
		RETAINED_BYTES.add(STATE_BYTES);
	}

	public static void releaseStateIds() {
		RETAINED_BYTES.add(-STATE_BYTES);
	}

	public static long retainedBytes() {
		return RETAINED_BYTES.sum();
	}
}
