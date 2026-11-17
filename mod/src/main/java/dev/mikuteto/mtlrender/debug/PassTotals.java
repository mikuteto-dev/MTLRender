package dev.mikuteto.mtlrender.debug;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class PassTotals {
	private static final double NANOS_PER_MILLI = 1_000_000.0;

	record Entry(String name, double ms, double perFrame, double passesPerFrame) {
	}

	private long[] nanos = new long[64];
	private long[] counts = new long[64];
	private long[] passes = new long[64];
	private long frames;

	void add(MemorySegment records, int count) {
		if (count == 0) {
			return;
		}
		frames++;
		for (int i = 0; i < count; i++) {
			long base = (long) FrameStats.PASS_RECORD_LONGS * i;
			int id = (int) records.getAtIndex(ValueLayout.JAVA_LONG, base);
			if (id >= nanos.length) {
				nanos = Arrays.copyOf(nanos, Math.max(id + 1, nanos.length * 2));
				counts = Arrays.copyOf(counts, nanos.length);
				passes = Arrays.copyOf(passes, nanos.length);
			}
			nanos[id] += records.getAtIndex(ValueLayout.JAVA_LONG, base + 1);
			passes[id] += records.getAtIndex(ValueLayout.JAVA_LONG, base + 2);
			counts[id]++;
		}
	}

	long frames() {
		return frames;
	}

	double totalMs() {
		return frames == 0 ? 0 : Arrays.stream(nanos).sum() / NANOS_PER_MILLI / frames;
	}

	List<Entry> top(int limit) {
		List<Entry> entries = new ArrayList<>();
		if (frames == 0) {
			return entries;
		}
		for (int id = 0; id < nanos.length; id++) {
			if (counts[id] > 0) {
				entries.add(new Entry(
					PassLabels.name(id),
					nanos[id] / NANOS_PER_MILLI / frames,
					counts[id] / (double) frames,
					passes[id] / (double) frames
				));
			}
		}
		entries.sort(Comparator.comparingDouble(Entry::ms).reversed());
		return entries.subList(0, Math.min(limit, entries.size()));
	}

	long retainedBytes() {
		return (long) (nanos.length + counts.length + passes.length) * Long.BYTES;
	}

	void reset() {
		Arrays.fill(nanos, 0);
		Arrays.fill(counts, 0);
		Arrays.fill(passes, 0);
		frames = 0;
	}
}
