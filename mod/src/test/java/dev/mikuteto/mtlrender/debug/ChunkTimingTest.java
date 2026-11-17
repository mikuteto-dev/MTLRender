package dev.mikuteto.mtlrender.debug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChunkTimingTest {
	@BeforeEach
	void enableTiming() {
		System.setProperty("mtlrender.debug.chunkTiming", "true");
	}

	@AfterEach
	void disableTiming() {
		System.clearProperty("mtlrender.debug.chunkTiming");
	}

	@Test
	void emptyStagesHaveFiniteZeroValues() {
		ChunkTiming.reset(100);
		String summary = summary(100);

		assertTrue(summary.contains("chunk_timing.server_disk_load.count=0"));
		assertTrue(summary.contains("chunk_timing.server_disk_load.p95_ms=0.000"));
		assertTrue(summary.contains("chunk_timing.chunks_per_second=\n"));
		assertFalse(summary.contains("NaN"));
		assertFalse(summary.contains("Infinity"));
	}

	@Test
	void eventsBeforeResetAreIgnored() {
		ChunkTiming.recordDuration(ChunkTiming.Stage.SERVER_DISK_LOAD, 7, 0, 10_000_000);
		ChunkTiming.reset(100);
		ChunkTiming.recordDuration(ChunkTiming.Stage.SERVER_DISK_LOAD, 7, 50, 60);
		String summary = summary(200);

		assertTrue(summary.contains("chunk_timing.server_disk_load.count=0"));
	}

	@Test
	void oneSampleUsesTheSampleAtEveryPercentileBoundary() {
		ChunkTiming.reset(0);
		ChunkTiming.recordChunkDuration(ChunkTiming.Stage.SERVER_DISK_LOAD, 7, 7_000_000);

		String summary = summary(1_000_000_000L);

		assertTrue(summary.contains("chunk_timing.server_disk_load.count=1"));
		assertTrue(summary.contains("chunk_timing.server_disk_load.p50_ms=7.000"));
		assertTrue(summary.contains("chunk_timing.server_disk_load.p95_ms=7.000"));
		assertTrue(summary.contains("chunk_timing.server_disk_load.p99_ms=7.000"));
		assertTrue(summary.contains("chunk_timing.server_disk_load.max_ms=7.000"));
	}

	@Test
	void repeatedKeysRemainSeparateDurationSamplesAndPendingWorkCompletes() {
		ChunkTiming.reset(0);
		ChunkTiming.recordChunkDuration(ChunkTiming.Stage.SERVER_DISK_LOAD, 7, 1_000_000);
		ChunkTiming.recordChunkDuration(ChunkTiming.Stage.SERVER_DISK_LOAD, 7, 2_000_000);
		ChunkTiming.recordSectionDuration(ChunkTiming.Stage.SERVER_DISK_LOAD, 9, 3_000_000);
		ChunkTiming.recordSectionDuration(ChunkTiming.Stage.SERVER_DISK_LOAD, 9, 4_000_000);
		ChunkTiming.recordPending(ChunkTiming.Stage.SERVER_SEND, 12, 1_000_000_000);
		ChunkTiming.completePending(ChunkTiming.Stage.SERVER_SEND, 12, 3_000_000_000L);

		String summary = summary(4_000_000_000L);

		assertTrue(summary.contains("chunk_timing.server_disk_load.count=4"));
		assertTrue(summary.contains("chunk_timing.server_disk_load.p50_ms=3.000"));
		assertTrue(summary.contains("chunk_timing.server_disk_load.p95_ms=4.000"));
		assertTrue(summary.contains("chunk_timing.server_disk_load.p99_ms=4.000"));
		assertTrue(summary.contains("chunk_timing.server_disk_load.max_ms=4.000"));
		assertTrue(summary.contains("chunk_timing.server_send.count=1"));
		assertTrue(summary.contains("chunk_timing.server_send.p50_ms=2000.000"));
	}

	@Test
	void percentileBoundariesUseSortedSamples() {
		ChunkTiming.reset(0);
		ChunkTiming.recordChunkDuration(ChunkTiming.Stage.RUST_COMPILE, 1, 1_000_000);
		ChunkTiming.recordChunkDuration(ChunkTiming.Stage.RUST_COMPILE, 2, 2_000_000);
		ChunkTiming.recordChunkDuration(ChunkTiming.Stage.RUST_COMPILE, 3, 3_000_000);
		ChunkTiming.recordChunkDuration(ChunkTiming.Stage.RUST_COMPILE, 4, 4_000_000);

		String summary = summary(5_000_000_000L);

		assertTrue(summary.contains("chunk_timing.rust_compile.p50_ms=3.000"));
		assertTrue(summary.contains("chunk_timing.rust_compile.p95_ms=4.000"));
		assertTrue(summary.contains("chunk_timing.rust_compile.p99_ms=4.000"));
	}

	@Test
	void timelineUsesOneSecondBinsAndIgnoresDuplicateCompletions() {
		ChunkTiming.reset(0);
		ChunkTiming.markChunkComplete(1, 900_000_000L);
		ChunkTiming.markChunkComplete(2, 1_100_000_000L);
		ChunkTiming.markChunkComplete(3, 2_200_000_000L);
		ChunkTiming.markChunkComplete(4, 3_300_000_000L);
		ChunkTiming.markChunkComplete(2, 1_200_000_000L);
		ChunkTiming.markChunkComplete(3, 1_800_000_000L);
		ChunkTiming.markChunkComplete(4, 400_000_000L);

		String summary = summary(4_000_000_000L);

		assertTrue(summary.contains("chunk_timing.chunks_per_second=0:2,1:2"));
	}

	@Test
	void recordsOutOfOrderTimelineBinsAndFirstDrawnMarksOnce() {
		ChunkTiming.reset(0);
		ChunkTiming.markChunkComplete(1, 2_900_000_000L);
		ChunkTiming.markChunkComplete(1, 2_800_000_000L);
		ChunkTiming.markChunkComplete(2, 2_100_000_000L);
		ChunkTiming.markChunkComplete(1, 3_100_000_000L);
		ChunkTiming.markSectionFirstDrawn(10, 3_000_000_000L);
		ChunkTiming.markSectionFirstDrawn(10, 1_000_000_000L);
		ChunkTiming.markSectionFirstDrawn(11, 2_000_000_000L);

		String summary = summary(4_000_000_000L);

		assertTrue(summary.contains("chunk_timing.chunks_per_second=2:2"));
		assertTrue(summary.contains("chunk_timing.section_first_drawn.count=2"));
		assertTrue(summary.contains("chunk_timing.section_first_drawn.p50_ms=2000.000"));
	}

	@Test
	void sectionFirstDrawnMarksKeepTheEarliestTimestamp() {
		ChunkTiming.reset(0);
		ChunkTiming.markSectionFirstDrawn(10, 3_000_000_000L);
		ChunkTiming.markSectionFirstDrawn(10, 1_000_000_000L);
		ChunkTiming.markSectionFirstDrawn(10, 2_000_000_000L);
		ChunkTiming.markSectionFirstDrawn(11, 2_000_000_000L);

		String summary = summary(4_000_000_000L);

		assertTrue(summary.contains("chunk_timing.section_first_drawn.count=2"));
		assertTrue(summary.contains("chunk_timing.section_first_drawn.p50_ms=2000.000"));
	}

	@Test
	void mesherCountersKeepEmptyBatchesEmptyAndCountItemBoundaries() {
		ChunkTiming.reset(1_000_000_000L);
		ChunkTiming.recordMesherBatch(0, 0);
		ChunkTiming.recordMesherBatch(3, 2_000_000L);
		ChunkTiming.recordMesherFallback();
		ChunkTiming.recordMesherFailedItem();

		String summary = summary(3_000_000_000L);

		assertTrue(summary.contains("chunk_timing.rust_mesher.batch.flushed=1"));
		assertTrue(summary.contains("chunk_timing.rust_mesher.single_section_fallbacks=1"));
		assertTrue(summary.contains("chunk_timing.rust_mesher.failed_items=1"));
		assertTrue(summary.contains("chunk_timing.rust_mesher.batch.size_p50=3.000"));
	}

	@Test
	void framesProvideFirstFrameTimingAndBatchRates() {
		ChunkTiming.reset(1_000_000_000L);
		ChunkTiming.onFrame(1_500_000_000L);
		ChunkTiming.onFrame(2_000_000_000L);
		ChunkTiming.recordBatch(2, 10_000_000L);
		ChunkTiming.recordBatch(4, 20_000_000L);

		String summary = summary(3_000_000_000L);

		assertTrue(summary.contains("chunk_timing.frames=2"));
		assertTrue(summary.contains("chunk_timing.first_frame_ms=500.000"));
		assertTrue(summary.contains("chunk_timing.batch.size_p50=4.000"));
		assertTrue(summary.contains("chunk_timing.batch.rate=1.000"));
		assertTrue(summary.contains("chunk_timing.batch.item_rate=3.000"));
	}

	private static String summary(long endNanos) {
		StringBuilder summary = new StringBuilder();
		ChunkTiming.appendSummary(summary, endNanos);
		return summary.toString();
	}
}
