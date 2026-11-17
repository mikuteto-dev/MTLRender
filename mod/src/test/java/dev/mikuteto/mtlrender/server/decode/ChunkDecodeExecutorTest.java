package dev.mikuteto.mtlrender.server.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikuteto.mtlrender.server.ChunkLoadPhase;
import dev.mikuteto.mtlrender.server.ChunkLoadTrace;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChunkDecodeExecutorTest {
	@Test
	void sizesWorkersFromAvailableProcessorsAndConfiguration() {
		assertEquals(1, ChunkDecodeExecutor.threadCount(1, null));
		assertEquals(6, ChunkDecodeExecutor.threadCount(8, null));
		assertEquals(3, ChunkDecodeExecutor.threadCount(8, 3));
		assertEquals(8, ChunkDecodeExecutor.threadCount(32, 99));
	}

	@Test
	void namesDecodeThreadsAndDisablesTimingWithoutTheDebugFlag() throws Exception {
		String oldFlag = System.getProperty("mtlrender.debug.chunkTiming");
		System.clearProperty("mtlrender.debug.chunkTiming");
		AtomicReference<ChunkLoadTrace.Event> event = new AtomicReference<>();
		ChunkLoadTrace.setSink(event::set);
		try (ChunkDecodeExecutor executor = new ChunkDecodeExecutor(1)) {
			CountDownLatch finished = new CountDownLatch(1);
			AtomicReference<String> name = new AtomicReference<>();
			executor.executor().execute(() -> {
				name.set(Thread.currentThread().getName());
				finished.countDown();
			});
			assertTrue(finished.await(1, TimeUnit.SECONDS));
			try (ChunkLoadTrace.Scope ignored = ChunkLoadTrace.begin(ChunkLoadPhase.NBT_DECODE)) {
			}
			assertTrue(name.get().startsWith("MTLRender-ChunkDecode-"));
			assertEquals(null, event.get());
		} finally {
			ChunkLoadTrace.clearSink();
			if (oldFlag == null) {
				System.clearProperty("mtlrender.debug.chunkTiming");
			} else {
				System.setProperty("mtlrender.debug.chunkTiming", oldFlag);
			}
		}
	}

	@Test
	void shutsDownWithoutLeavingDecodeThreads() throws Exception {
		ChunkDecodeExecutor executor = new ChunkDecodeExecutor(1);
		CountDownLatch started = new CountDownLatch(1);
		executor.executor().execute(started::countDown);
		assertTrue(started.await(1, TimeUnit.SECONDS));
		executor.close();
		assertTrue(executor.isShutdown());
		java.util.concurrent.ExecutorService service = (java.util.concurrent.ExecutorService) executor.executor();
		assertTrue(service.isShutdown());
		assertTrue(service.awaitTermination(1, TimeUnit.SECONDS));
	}
}
