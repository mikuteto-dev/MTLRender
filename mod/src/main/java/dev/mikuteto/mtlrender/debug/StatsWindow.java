package dev.mikuteto.mtlrender.debug;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.List;

import dev.mikuteto.mtlrender.config.Config;
import it.unimi.dsi.fastutil.longs.LongArrayList;

final class StatsWindow {
	private static final long NANOS_PER_SECOND = 1_000_000_000L;
	private static final double NANOS_PER_MILLI = 1_000_000.0;
	private static final int TOP_PASSES = 3;
	private static final int MAX_SAMPLES = 4096;

	record Summary(
		double fps, double averageMs, double p99Ms, double maxMs, double lowFps, double gpuFrameMs, double gpuFrameP99Ms, BackendSummary backend,
		List<PassTotals.Entry> topPasses, boolean comparable
	) {
	}

	record BackendSummary(
		double submitMs, double gpuMs, double waitMs, double encodeMs, double drawableMs, double commitMs, long draws, long passes, long words,
		double shownFps
	) {
	}

	private final long length;
	private final boolean offscreen;
	private final LongArrayList frameTimes = new LongArrayList();
	private final LongArrayList gpuTimes = new LongArrayList();
	private final PassTotals passTimes = new PassTotals();
	private long start;
	private int backendFrames;
	private long submitNanos;
	private long draws;
	private long passes;
	private long words;
	private long gpuNanos;
	private long waitNanos;
	private long encodeNanos;
	private long drawableNanos;
	private long commitNanos;
	private long presents;

	StatsWindow(long length) {
		this.length = length;
		this.offscreen = "offscreen".equals(Config.get().present);
	}

	void addGpu(LongArrayList samples) {
		for (int i = 0; i < samples.size(); i++) {
			addBounded(gpuTimes, samples.getLong(i));
		}
	}

	void addPasses(MemorySegment records, int count) {
		passTimes.add(records, count);
	}

	Summary add(long now, long frameTime, FrameStats.BackendFrame backend) {
		if (start == 0) {
			start = now - frameTime;
		}
		addBounded(frameTimes, frameTime);
		if (backend != null) {
			backendFrames++;
			submitNanos += backend.submitNanos();
			draws += backend.draws();
			passes += backend.passes();
			words += backend.words();
			gpuNanos += backend.gpuNanos();
			waitNanos += backend.waitNanos();
			encodeNanos += backend.encodeNanos();
			drawableNanos += backend.drawableNanos();
			commitNanos += backend.commitNanos();
			presents += backend.presents();
		}
		if (now - start < length) {
			return null;
		}
		Summary summary = summarize(now - start);
		reset(now);
		return summary;
	}

	private Summary summarize(long elapsed) {
		long[] sorted = frameTimes.toLongArray();
		Arrays.sort(sorted);
		long[] gpu = gpuTimes.toLongArray();
		Arrays.sort(gpu);
		double frameFps = sorted.length * (double) NANOS_PER_SECOND / elapsed;
		double shownFps = presents * (double) NANOS_PER_SECOND / elapsed;
		boolean comparable = backendFrames > 0 && !offscreen;
		BackendSummary backend = backendFrames == 0 ? null : new BackendSummary(
			submitNanos / NANOS_PER_MILLI / backendFrames,
			gpuNanos / NANOS_PER_MILLI / backendFrames,
			waitNanos / NANOS_PER_MILLI / backendFrames,
			encodeNanos / NANOS_PER_MILLI / backendFrames,
			drawableNanos / NANOS_PER_MILLI / backendFrames,
			commitNanos / NANOS_PER_MILLI / backendFrames,
			draws / backendFrames,
			passes / backendFrames,
			words / backendFrames,
			presents * (double) NANOS_PER_SECOND / elapsed
		);
		return new Summary(
			comparable ? shownFps : frameFps,
			average(sorted) / NANOS_PER_MILLI,
			percentile(sorted, 0.99) / NANOS_PER_MILLI,
			sorted[sorted.length - 1] / NANOS_PER_MILLI,
			NANOS_PER_SECOND / onePercentLow(sorted),
			gpu.length == 0 ? Double.NaN : average(gpu) / NANOS_PER_MILLI,
			gpu.length == 0 ? Double.NaN : percentile(gpu, 0.99) / NANOS_PER_MILLI,
			backend,
			passTimes.top(TOP_PASSES),
			comparable
		);
	}

	long retainedBytes() {
		return (long) (frameTimes.size() + gpuTimes.size()) * Long.BYTES + passTimes.retainedBytes();
	}

	private static void addBounded(LongArrayList values, long value) {
		if (values.size() == MAX_SAMPLES) {
			values.removeLong(0);
		}
		values.add(value);
	}

	private void reset(long now) {
		start = now;
		frameTimes.clear();
		gpuTimes.clear();
		passTimes.reset();
		backendFrames = 0;
		submitNanos = 0;
		draws = 0;
		passes = 0;
		words = 0;
		gpuNanos = 0;
		waitNanos = 0;
		encodeNanos = 0;
		drawableNanos = 0;
		commitNanos = 0;
		presents = 0;
	}

	static double average(long[] values) {
		double sum = 0;
		for (long value : values) {
			sum += value;
		}
		return values.length == 0 ? 0 : sum / values.length;
	}

	static long percentile(long[] sorted, double fraction) {
		int index = (int) Math.min(sorted.length - 1, Math.round(fraction * (sorted.length - 1)));
		return sorted[index];
	}

	static double onePercentLow(long[] sorted) {
		int count = Math.max(1, sorted.length / 100);
		double sum = 0;
		for (int i = sorted.length - count; i < sorted.length; i++) {
			sum += sorted[i];
		}
		return sum / count;
	}
}
