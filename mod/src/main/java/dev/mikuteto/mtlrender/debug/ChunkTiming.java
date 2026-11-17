package dev.mikuteto.mtlrender.debug;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

public final class ChunkTiming {
	public enum Stage {
		SERVER_DISK_LOAD("server_disk_load"),
		SERVER_GENERATION_STATUS("server_generation_status"),
		SERVER_SEND("server_send"),
		SERVER_COLLECT("server_collect"),
		SERVER_BATCH("server_batch"),
		CLIENT_PACKET("client_packet"),
		CLIENT_CHUNK_CONSTRUCTION("client_chunk_construction"),
		CLIENT_LIGHT_QUEUE_UPDATE("client_light_queue_update"),
		SECTION_QUEUE_WAIT("section_queue_wait"),
		SECTION_QUEUE_WAIT_INITIAL("section_queue_wait_initial"),
		SECTION_QUEUE_WAIT_RECOMPILE("section_queue_wait_recompile"),
		SECTION_COMPILE_TOTAL("section_compile_total"),
		VANILLA_COMPILE("vanilla_compile"),
		RUST_COMPILE("rust_compile"),
		RUST_MESHER("rust_mesher"),
		RUST_BATCH_OVERHEAD("rust_batch_overhead"),
		HYBRID_JAVA_COMPILE("hybrid_java_compile"),
		JAVA_DYNAMIC_COMPILE("java_dynamic_compile"),
		FINAL_ASSEMBLY("final_assembly"),
		GPU_UPLOAD("gpu_upload"),
		GPU_PUBLISH("gpu_publish"),
		FIRST_FRAME("first_frame");

		private final String key;

		Stage(String key) {
			this.key = key;
		}

		public String key() {
			return key;
		}
	}

	private static final long NANOS_PER_SECOND = 1_000_000_000L;
	private static final long NANOS_PER_MILLI = 1_000_000L;
	private static final long NO_TIMESTAMP = Long.MAX_VALUE;
	private static final ChunkTiming INSTANCE = new ChunkTiming();

	private final EnumMap<Stage, LongArrayList> durations = new EnumMap<>(Stage.class);
	private final EnumMap<Stage, Long2ObjectOpenHashMap<LongArrayList>> sectionDurations = new EnumMap<>(Stage.class);
	private final EnumMap<Stage, Long2LongOpenHashMap> pending = new EnumMap<>(Stage.class);
	private final Long2LongOpenHashMap completedChunks = new Long2LongOpenHashMap();
	private final Long2LongOpenHashMap firstDrawnSections = new Long2LongOpenHashMap();
	private final Long2ObjectOpenHashMap<LongArrayList> lightQueuePending = new Long2ObjectOpenHashMap<>();
	private final Long2LongOpenHashMap chunkBins = new Long2LongOpenHashMap();
	private final LongArrayList batchSizes = new LongArrayList();
	private final LongArrayList batchDurations = new LongArrayList();
	private final LongArrayList mesherBatchSizes = new LongArrayList();
	private final LongArrayList mesherBatchDurations = new LongArrayList();
	private long mesherFlushedBatches;
	private long mesherSingleSectionFallbacks;
	private long mesherFailedItems;
	private long mesherMismatches;
	private final LongArrayList acknowledgements = new LongArrayList();
	private final LongArrayList desiredChunksPerTick = new LongArrayList();
	private long runStart;
	private long calculatorBatchStarts;
	private long calculatorBatchFinishes;
	private long frameCount;
	private boolean active;
	private long firstFrame = NO_TIMESTAMP;

	private ChunkTiming() {
		for (Stage stage : Stage.values()) {
			durations.put(stage, new LongArrayList());
			sectionDurations.put(stage, new Long2ObjectOpenHashMap<>());
			pending.put(stage, new Long2LongOpenHashMap());
		}
	}

	public static boolean enabled() {
		return Benchmark.enabled() || Boolean.getBoolean("mtlrender.debug.chunkTiming");
	}

	public static void reset(long timestampNanos) {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			INSTANCE.clear();
			INSTANCE.runStart = timestampNanos;
			INSTANCE.active = true;
		}
	}

	public static void recordChunkDuration(Stage stage, long chunkKey, long durationNanos) {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			if (INSTANCE.active) {
				INSTANCE.addDuration(stage, durationNanos);
			}
		}
	}

	public static void recordSectionDuration(Stage stage, long sectionKey, long durationNanos) {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			if (INSTANCE.active) {
				INSTANCE.addDuration(stage, durationNanos);
				Long2ObjectOpenHashMap<LongArrayList> bySection = INSTANCE.sectionDurations.get(stage);
				LongArrayList values = bySection.get(sectionKey);
				if (values == null) {
					values = new LongArrayList();
					bySection.put(sectionKey, values);
				}
				values.add(nonNegative(durationNanos));
			}
		}
	}

	public static long runStartNanos() {
		if (!enabled()) {
			return 0;
		}
		synchronized (INSTANCE) {
			return INSTANCE.active ? INSTANCE.runStart : 0;
		}
	}

	public static void recordSectionQueueWait(long sectionKey, boolean recompile, long durationNanos) {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			if (INSTANCE.active) {
				INSTANCE.addDuration(Stage.SECTION_QUEUE_WAIT, durationNanos);
				Stage subtype = recompile ? Stage.SECTION_QUEUE_WAIT_RECOMPILE : Stage.SECTION_QUEUE_WAIT_INITIAL;
				INSTANCE.addDuration(subtype, durationNanos);
				INSTANCE.addSectionDuration(Stage.SECTION_QUEUE_WAIT, sectionKey, durationNanos);
				INSTANCE.addSectionDuration(subtype, sectionKey, durationNanos);
			}
		}
	}

	public static void recordDuration(Stage stage, long chunkKey, long startedAtNanos, long completedAtNanos) {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			if (INSTANCE.active && startedAtNanos >= INSTANCE.runStart) {
				INSTANCE.addDuration(stage, elapsed(completedAtNanos, startedAtNanos));
			}
		}
	}

	public static void recordPoint(Stage stage, long timestampNanos) {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			if (!INSTANCE.active) {
				return;
			}
			long elapsed = elapsed(timestampNanos, INSTANCE.runStart);
			INSTANCE.addDuration(stage, elapsed);
			if (stage == Stage.FIRST_FRAME && elapsed < INSTANCE.firstFrame) {
				INSTANCE.firstFrame = elapsed;
			}
		}
	}

	public static void recordBatch(long size, long durationNanos) {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			if (!INSTANCE.active) {
				return;
			}
			INSTANCE.addDuration(Stage.SERVER_BATCH, durationNanos);
			INSTANCE.batchSizes.add(nonNegative(size));
			INSTANCE.batchDurations.add(nonNegative(durationNanos));
		}
	}

	public static void recordMesherBatch(long size, long durationNanos) {
		recordMesherBatch(size, durationNanos, durationNanos, null);
	}

	public static void recordMesherBatch(long size, long durationNanos, long nativeDurationNanos, long[] sectionKeys) {
		if (!enabled() || size <= 0) {
			return;
		}
		synchronized (INSTANCE) {
			if (!INSTANCE.active) {
				return;
			}
			INSTANCE.mesherFlushedBatches++;
			INSTANCE.mesherBatchSizes.add(nonNegative(size));
			INSTANCE.mesherBatchDurations.add(nonNegative(durationNanos));
			long nativeDuration = Math.min(nonNegative(nativeDurationNanos), nonNegative(durationNanos));
			long overhead = nonNegative(durationNanos) - nativeDuration;
			INSTANCE.addDuration(Stage.RUST_BATCH_OVERHEAD, overhead);
			long perSection = size == 0 ? 0 : nativeDuration / size;
			if (sectionKeys == null) {
				for (long index = 0; index < size; index++) {
					INSTANCE.addDuration(Stage.RUST_MESHER, perSection);
					INSTANCE.addDuration(Stage.RUST_COMPILE, perSection);
				}
			} else {
				for (int index = 0; index < sectionKeys.length; index++) {
					INSTANCE.addDuration(Stage.RUST_MESHER, perSection);
					INSTANCE.addDuration(Stage.RUST_COMPILE, perSection);
					INSTANCE.addSectionDuration(Stage.RUST_MESHER, sectionKeys[index], perSection);
					INSTANCE.addSectionDuration(Stage.RUST_COMPILE, sectionKeys[index], perSection);
				}
			}
		}
	}

	public static void recordMesherFallback() {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			if (INSTANCE.active) {
				INSTANCE.mesherSingleSectionFallbacks++;
			}
		}
	}

	public static void recordMesherFailedItem() {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			if (INSTANCE.active) {
				INSTANCE.mesherFailedItems++;
			}
		}
	}

	public static void recordMesherMismatch() {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			if (INSTANCE.active) {
				INSTANCE.mesherMismatches++;
			}
		}
	}

	public static void recordAcknowledgement(float desired) {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			INSTANCE.acknowledgements.add(Float.floatToIntBits(desired));
		}
	}

	public static void recordCalculatorBatchStart() {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			INSTANCE.calculatorBatchStarts++;
		}
	}

	public static void recordCalculatorBatchFinished(int batchSize) {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			INSTANCE.calculatorBatchFinishes++;
		}
	}

	public static void recordDesiredChunksPerTick(float desired) {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			INSTANCE.desiredChunksPerTick.add(Float.floatToIntBits(desired));
		}
	}

	public static void recordPending(Stage stage, long key, long queuedAtNanos) {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			if (INSTANCE.active) {
				INSTANCE.pending.get(stage).put(key, queuedAtNanos);
			}
		}
	}

	public static void completePending(Stage stage, long key, long completedAtNanos) {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			if (!INSTANCE.active) {
				return;
			}
			Long2LongOpenHashMap queued = INSTANCE.pending.get(stage);
			if (!queued.containsKey(key)) {
				return;
			}
			long queuedAtNanos = queued.remove(key);
			INSTANCE.addDuration(stage, elapsed(completedAtNanos, queuedAtNanos));
		}
	}

	public static void recordLightQueue(long sectionKey, long queuedAtNanos) {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			INSTANCE.ensureRun(queuedAtNanos);
			LongArrayList values = INSTANCE.lightQueuePending.get(sectionKey);
			if (values == null) {
				values = new LongArrayList();
				INSTANCE.lightQueuePending.put(sectionKey, values);
			}
			values.add(queuedAtNanos);
		}
	}

	public static void completeLightQueue(long sectionKey, long completedAtNanos) {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			LongArrayList values = INSTANCE.lightQueuePending.get(sectionKey);
			if (values == null || values.isEmpty()) {
				return;
			}
			long queuedAtNanos = values.removeLong(0);
			if (values.isEmpty()) {
				INSTANCE.lightQueuePending.remove(sectionKey);
			}
			long duration = elapsed(completedAtNanos, queuedAtNanos);
			INSTANCE.addDuration(Stage.SECTION_QUEUE_WAIT, duration);
			INSTANCE.addSectionDuration(Stage.SECTION_QUEUE_WAIT, sectionKey, duration);
		}
	}

	public static void recordLightUpdates(long durationNanos) {
		if (enabled()) {
			synchronized (INSTANCE) {
				INSTANCE.addDuration(Stage.CLIENT_LIGHT_QUEUE_UPDATE, durationNanos);
			}
		}
	}

	public static void markChunkComplete(long chunkKey, long timestampNanos) {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			if (!INSTANCE.active) {
				return;
			}
			long previous = INSTANCE.completedChunks.getOrDefault(chunkKey, NO_TIMESTAMP);
			if (timestampNanos < previous) {
				if (previous != NO_TIMESTAMP) {
					INSTANCE.addChunkBin(previous, -1);
				}
				INSTANCE.completedChunks.put(chunkKey, timestampNanos);
				INSTANCE.addChunkBin(timestampNanos, 1);
			}
		}
	}

	public static void markSectionFirstDrawn(long sectionKey, long timestampNanos) {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			if (!INSTANCE.active) {
				return;
			}
			long previous = INSTANCE.firstDrawnSections.getOrDefault(sectionKey, NO_TIMESTAMP);
			if (timestampNanos < previous) {
				INSTANCE.firstDrawnSections.put(sectionKey, timestampNanos);
			}
		}
	}

	public static void onFrame(long timestampNanos) {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			if (!INSTANCE.active) {
				return;
			}
			INSTANCE.frameCount++;
			if (INSTANCE.firstFrame == NO_TIMESTAMP) {
				INSTANCE.firstFrame = elapsed(timestampNanos, INSTANCE.runStart);
				INSTANCE.addDuration(Stage.FIRST_FRAME, INSTANCE.firstFrame);
			}
		}
	}

	public static void appendSummary(StringBuilder summary) {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			INSTANCE.appendSummaryValues(summary, System.nanoTime());
		}
	}

	public static void appendSummary(StringBuilder summary, long endNanos) {
		if (!enabled()) {
			return;
		}
		synchronized (INSTANCE) {
			INSTANCE.appendSummaryValues(summary, endNanos);
		}
	}

	private void appendSummaryValues(StringBuilder summary, long endNanos) {
		for (Stage stage : Stage.values()) {
			long[] values = durations.get(stage).toLongArray();
			appendStats(summary, "chunk_timing." + stage.key(), values);
		}
		long[] sectionKeys = sectionKeys();
		for (long sectionKey : sectionKeys) {
			for (Stage stage : Stage.values()) {
				LongArrayList recorded = sectionDurations.get(stage).get(sectionKey);
				if (recorded != null) {
					appendStats(summary, "chunk_timing.section." + sectionKey + "." + stage.key(), recorded.toLongArray());
				}
			}
		}

		long[] sizes = batchSizes.toLongArray();
		Arrays.sort(sizes);
		long[] batchTimes = batchDurations.toLongArray();
		Arrays.sort(batchTimes);
		double elapsedSeconds = Math.max(0, elapsed(endNanos, runStart)) / (double) NANOS_PER_SECOND;
		summary.append("chunk_timing.batch.count=").append(sizes.length).append('\n');
		summary.append(String.format(Locale.ROOT, "chunk_timing.batch.size_avg=%.3f%n", average(sizes)));
		summary.append(String.format(Locale.ROOT, "chunk_timing.batch.size_p50=%.3f%n", percentileValue(sizes, 0.50)));
		summary.append(String.format(Locale.ROOT, "chunk_timing.batch.size_p95=%.3f%n", percentileValue(sizes, 0.95)));
		summary.append(String.format(Locale.ROOT, "chunk_timing.batch.size_p99=%.3f%n", percentileValue(sizes, 0.99)));
		summary.append(String.format(Locale.ROOT, "chunk_timing.batch.size_max=%.3f%n", maxValue(sizes)));
		summary.append(String.format(Locale.ROOT, "chunk_timing.batch.duration_avg_ms=%.3f%n", average(batchTimes) / NANOS_PER_MILLI));
		summary.append(String.format(Locale.ROOT, "chunk_timing.batch.duration_p50_ms=%.3f%n", percentileValue(batchTimes, 0.50) / NANOS_PER_MILLI));
		summary.append(String.format(Locale.ROOT, "chunk_timing.batch.duration_p95_ms=%.3f%n", percentileValue(batchTimes, 0.95) / NANOS_PER_MILLI));
		summary.append(String.format(Locale.ROOT, "chunk_timing.batch.duration_p99_ms=%.3f%n", percentileValue(batchTimes, 0.99) / NANOS_PER_MILLI));
		summary.append(String.format(Locale.ROOT, "chunk_timing.batch.duration_max_ms=%.3f%n", maxValue(batchTimes) / NANOS_PER_MILLI));
		summary.append(String.format(Locale.ROOT, "chunk_timing.batch.rate=%.3f%n", rate(sizes.length, elapsedSeconds)));
		summary.append(String.format(Locale.ROOT, "chunk_timing.batch.item_rate=%.3f%n", rate(sum(sizes), elapsedSeconds)));
		long[] mesherSizes = mesherBatchSizes.toLongArray();
		Arrays.sort(mesherSizes);
		long[] mesherTimes = mesherBatchDurations.toLongArray();
		Arrays.sort(mesherTimes);
		summary.append("chunk_timing.rust_mesher.batch.flushed=").append(mesherFlushedBatches).append('\n');
		summary.append(String.format(Locale.ROOT, "chunk_timing.rust_mesher.batch.size_avg=%.3f%n", average(mesherSizes)));
		summary.append(String.format(Locale.ROOT, "chunk_timing.rust_mesher.batch.size_p50=%.3f%n", percentileValue(mesherSizes, 0.50)));
		summary.append(String.format(Locale.ROOT, "chunk_timing.rust_mesher.batch.size_p95=%.3f%n", percentileValue(mesherSizes, 0.95)));
		summary.append(String.format(Locale.ROOT, "chunk_timing.rust_mesher.batch.size_p99=%.3f%n", percentileValue(mesherSizes, 0.99)));
		summary.append(String.format(Locale.ROOT, "chunk_timing.rust_mesher.batch.size_max=%.3f%n", maxValue(mesherSizes)));
		summary.append(String.format(Locale.ROOT, "chunk_timing.rust_mesher.batch.duration_avg_ms=%.3f%n", average(mesherTimes) / NANOS_PER_MILLI));
		summary.append(String.format(Locale.ROOT, "chunk_timing.rust_mesher.batch.duration_p50_ms=%.3f%n", percentileValue(mesherTimes, 0.50) / NANOS_PER_MILLI));
		summary.append(String.format(Locale.ROOT, "chunk_timing.rust_mesher.batch.duration_p95_ms=%.3f%n", percentileValue(mesherTimes, 0.95) / NANOS_PER_MILLI));
		summary.append(String.format(Locale.ROOT, "chunk_timing.rust_mesher.batch.duration_p99_ms=%.3f%n", percentileValue(mesherTimes, 0.99) / NANOS_PER_MILLI));
		summary.append(String.format(Locale.ROOT, "chunk_timing.rust_mesher.batch.duration_max_ms=%.3f%n", maxValue(mesherTimes) / NANOS_PER_MILLI));
		summary.append("chunk_timing.rust_mesher.single_section_fallbacks=").append(mesherSingleSectionFallbacks).append('\n');
		summary.append("chunk_timing.rust_mesher.failed_items=").append(mesherFailedItems).append('\n');
		summary.append("chunk_timing.rust_mesher.mismatches=").append(mesherMismatches).append('\n');
		appendFloatSamples(summary, "acknowledgement", acknowledgements);
		appendFloatSamples(summary, "desired_chunks_per_tick", desiredChunksPerTick);
		summary.append("chunk_timing.calculator.batch_starts=").append(calculatorBatchStarts).append('\n');
		summary.append("chunk_timing.calculator.batch_finishes=").append(calculatorBatchFinishes).append('\n');
		summary.append("chunk_timing.frames=").append(frameCount).append('\n');
		summary.append(String.format(Locale.ROOT, "chunk_timing.first_frame_ms=%.3f%n", firstFrame == NO_TIMESTAMP ? 0 : firstFrame / (double) NANOS_PER_MILLI));

		long[] firstDrawn = firstDrawnSections.values().toLongArray();
		for (int i = 0; i < firstDrawn.length; i++) {
			firstDrawn[i] = elapsed(firstDrawn[i], runStart);
		}
		Arrays.sort(firstDrawn);
		summary.append("chunk_timing.section_first_drawn.count=").append(firstDrawn.length).append('\n');
		summary.append(String.format(Locale.ROOT, "chunk_timing.section_first_drawn.p50_ms=%.3f%n", percentileValue(firstDrawn, 0.50) / NANOS_PER_MILLI));
		summary.append(String.format(Locale.ROOT, "chunk_timing.section_first_drawn.p95_ms=%.3f%n", percentileValue(firstDrawn, 0.95) / NANOS_PER_MILLI));
		summary.append(String.format(Locale.ROOT, "chunk_timing.section_first_drawn.p99_ms=%.3f%n", percentileValue(firstDrawn, 0.99) / NANOS_PER_MILLI));
		summary.append(String.format(Locale.ROOT, "chunk_timing.section_first_drawn.max_ms=%.3f%n", maxValue(firstDrawn) / NANOS_PER_MILLI));

		long[] bins = chunkBins.keySet().toLongArray();
		Arrays.sort(bins);
		summary.append("chunk_timing.chunks_per_second=");
		for (int i = 0; i < bins.length; i++) {
			if (i > 0) {
				summary.append(',');
			}
			summary.append(bins[i]).append(':').append(chunkBins.get(bins[i]));
		}
		summary.append('\n');
		clear();
		active = false;
	}

	private void addDuration(Stage stage, long durationNanos) {
		ensureRun(System.nanoTime());
		durations.get(stage).add(nonNegative(durationNanos));
	}

	private void addSectionDuration(Stage stage, long sectionKey, long durationNanos) {
		Long2ObjectOpenHashMap<LongArrayList> bySection = sectionDurations.get(stage);
		LongArrayList values = bySection.get(sectionKey);
		if (values == null) {
			values = new LongArrayList();
			bySection.put(sectionKey, values);
		}
		values.add(nonNegative(durationNanos));
	}

	private long[] sectionKeys() {
		LongArrayList keys = new LongArrayList();
		for (Long2ObjectOpenHashMap<LongArrayList> values : sectionDurations.values()) {
			for (long key : values.keySet()) {
				if (!keys.contains(key)) {
					keys.add(key);
				}
			}
		}
		long[] result = keys.toLongArray();
		Arrays.sort(result);
		return result;
	}

	private static void appendStats(StringBuilder summary, String prefix, long[] values) {
		Arrays.sort(values);
		summary.append(prefix).append(".count=").append(values.length).append('\n');
		summary.append(String.format(Locale.ROOT, "%s.p50_ms=%.3f%n", prefix, percentileMs(values, 0.50)));
		summary.append(String.format(Locale.ROOT, "%s.p95_ms=%.3f%n", prefix, percentileMs(values, 0.95)));
		summary.append(String.format(Locale.ROOT, "%s.p99_ms=%.3f%n", prefix, percentileMs(values, 0.99)));
		summary.append(String.format(Locale.ROOT, "%s.max_ms=%.3f%n", prefix, maxMs(values)));
	}

	private void ensureRun(long timestampNanos) {
		if (!active) {
			clear();
			runStart = timestampNanos;
			active = true;
		}
	}

	private void addChunkBin(long timestampNanos, long amount) {
		long bin = elapsed(timestampNanos, runStart) / NANOS_PER_SECOND;
		long value = chunkBins.getOrDefault(bin, 0) + amount;
		if (value == 0) {
			chunkBins.remove(bin);
		} else {
			chunkBins.put(bin, value);
		}
	}

	private void clear() {
		for (LongArrayList values : durations.values()) {
			values.clear();
		}
		for (Long2LongOpenHashMap values : pending.values()) {
			values.clear();
		}
		for (Long2ObjectOpenHashMap<LongArrayList> values : sectionDurations.values()) {
			values.clear();
		}
		completedChunks.clear();
		firstDrawnSections.clear();
		lightQueuePending.clear();
		chunkBins.clear();
		batchSizes.clear();
		batchDurations.clear();
		mesherBatchSizes.clear();
		mesherBatchDurations.clear();
		mesherFlushedBatches = 0;
		mesherSingleSectionFallbacks = 0;
		mesherFailedItems = 0;
		mesherMismatches = 0;
		acknowledgements.clear();
		desiredChunksPerTick.clear();
		calculatorBatchStarts = 0;
		calculatorBatchFinishes = 0;
		frameCount = 0;
		firstFrame = NO_TIMESTAMP;
	}

	private static long elapsed(long timestampNanos, long startNanos) {
		return timestampNanos <= startNanos ? 0 : timestampNanos - startNanos;
	}

	private static long nonNegative(long value) {
		return Math.max(0, value);
	}

	private static void appendFloatSamples(StringBuilder summary, String name, LongArrayList values) {
		float[] samples = new float[values.size()];
		for (int i = 0; i < samples.length; i++) {
			samples[i] = Float.intBitsToFloat((int) values.getLong(i));
		}
		Arrays.sort(samples);
		summary.append("chunk_timing.").append(name).append(".count=").append(samples.length).append('\n');
		summary.append(String.format(Locale.ROOT, "chunk_timing.%s.p50=%.3f%n", name, percentileFloat(samples, 0.50)));
		summary.append(String.format(Locale.ROOT, "chunk_timing.%s.p95=%.3f%n", name, percentileFloat(samples, 0.95)));
		summary.append(String.format(Locale.ROOT, "chunk_timing.%s.p99=%.3f%n", name, percentileFloat(samples, 0.99)));
		summary.append(String.format(Locale.ROOT, "chunk_timing.%s.max=%.3f%n", name, samples.length == 0 ? 0 : samples[samples.length - 1]));
	}

	private static double percentileFloat(float[] values, double fraction) {
		if (values.length == 0) {
			return 0;
		}
		int index = (int) Math.min(values.length - 1, Math.round(fraction * (values.length - 1)));
		return values[index];
	}

	private static double percentileMs(long[] values, double fraction) {
		return percentileValue(values, fraction) / (double) NANOS_PER_MILLI;
	}

	private static double percentileValue(long[] values, double fraction) {
		if (values.length == 0) {
			return 0;
		}
		int index = (int) Math.min(values.length - 1, Math.round(fraction * (values.length - 1)));
		return values[index];
	}

	private static double maxMs(long[] values) {
		return maxValue(values) / (double) NANOS_PER_MILLI;
	}

	private static double maxValue(long[] values) {
		return values.length == 0 ? 0 : values[values.length - 1];
	}

	private static double average(long[] values) {
		return values.length == 0 ? 0 : sum(values) / (double) values.length;
	}

	private static long sum(long[] values) {
		long sum = 0;
		for (long value : values) {
			sum += value;
		}
		return sum;
	}

	private static double rate(long count, double elapsedSeconds) {
		return elapsedSeconds <= 0 ? 0 : count / elapsedSeconds;
	}
}
