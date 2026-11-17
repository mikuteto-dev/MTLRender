package dev.mikuteto.mtlrender.terrain;

final class CullTuner {
	private static final int WARMUP = 3;
	private static final int PROBE = 12;
	private static final int MIN_HOLD = 30;
	private static final int MAX_INTERVAL = 7680;
	private static final double MARGIN = 0.97;
	private static final OcclusionMode[] PROBES = {
		OcclusionMode.TWO_PHASE_HIZ,
		OcclusionMode.SINGLE_PHASE_HIZ,
		OcclusionMode.CPU_BFS,
		OcclusionMode.OFF
	};

	private final boolean automatic;
	private OcclusionMode preferred;
	private OcclusionMode candidate;
	private int probeIndex;
	private int interval;
	private int held;
	private int probeFrame;
	private long sum;
	private int samples;
	private double reference = Double.MAX_VALUE;
	private long lastGpuBusyNanos;
	private boolean gpuCounterValid;
	private int width;
	private int height;

	CullTuner(int initialInterval, OcclusionMode initialMode, boolean automatic) {
		interval = clampInterval(initialInterval);
		preferred = initialMode == OcclusionMode.AUTO ? OcclusionMode.OFF : initialMode;
		this.automatic = automatic;
		candidate = nextCandidate();
	}

	OcclusionMode preferred() {
		return preferred;
	}

	OcclusionMode next(long frameNanos, long gpuBusyNanos) {
		long score = score(frameNanos, gpuBusyNanos);
		OcclusionMode active = candidate == null ? preferred : candidate;
		if (!automatic || candidate == null) {
			return preferred;
		}
		if (probeFrame > 0) {
			probeFrame++;
			if (probeFrame > WARMUP) {
				sum += score;
				samples++;
			}
			if (probeFrame >= WARMUP + PROBE) {
				finishProbe();
			}
			return active;
		}
		held++;
		sum += score;
		samples++;
		if (held >= Math.max(MIN_HOLD, interval)) {
			reference = samples == 0 ? Double.MAX_VALUE : sum / (double) samples;
			sum = 0;
			samples = 0;
			held = 0;
			probeFrame = 1;
		}
		return preferred;
	}

	void resolution(int width, int height) {
		if (this.width == width && this.height == height) {
			return;
		}
		this.width = width;
		this.height = height;
		gpuCounterValid = false;
		candidate = nextCandidate();
		probeFrame = 0;
		held = 0;
		sum = 0;
		samples = 0;
		reference = Double.MAX_VALUE;
	}

	private void finishProbe() {
		double average = samples == 0 ? Double.MAX_VALUE : sum / (double) samples;
		if (average < reference * MARGIN) {
			preferred = candidate;
			interval = clampInterval(WARMUP + PROBE + 1);
		}
		probeIndex = (probeIndex + 1) % PROBES.length;
		candidate = nextCandidate();
		probeFrame = 0;
		sum = 0;
		samples = 0;
		held = 0;
	}

	private OcclusionMode nextCandidate() {
		for (int i = 0; i < PROBES.length; i++) {
			OcclusionMode mode = PROBES[(probeIndex + i) % PROBES.length];
			if (mode != preferred) {
				probeIndex = (probeIndex + i) % PROBES.length;
				return mode;
			}
		}
		return null;
	}

	private long score(long frameNanos, long gpuBusyNanos) {
		long frame = Math.max(0L, frameNanos);
		long gpu = gpuDelta(gpuBusyNanos);
		long threshold = frame / 4L * 3L + frame % 4L * 3L / 4L;
		long contested = gpu > threshold ? gpu - threshold : 0L;
		long extra = contested / 2L;
		return Long.MAX_VALUE - frame < extra ? Long.MAX_VALUE : frame + extra;
	}

	private long gpuDelta(long current) {
		if (current < 0L || !gpuCounterValid || current < lastGpuBusyNanos) {
			lastGpuBusyNanos = Math.max(0L, current);
			gpuCounterValid = current >= 0L;
			return 0L;
		}
		long delta = current - lastGpuBusyNanos;
		lastGpuBusyNanos = current;
		return delta;
	}

	private static int clampInterval(int value) {
		return Math.max(WARMUP + PROBE + 1, Math.min(value, MAX_INTERVAL));
	}
}
