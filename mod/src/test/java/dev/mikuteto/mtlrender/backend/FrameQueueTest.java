package dev.mikuteto.mtlrender.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class FrameQueueTest {
	@Test
	void retainsEntryAtCompletedBoundary() {
		FrameQueue queue = new FrameQueue();
		AtomicInteger calls = new AtomicInteger();
		queue.add(4L, calls::incrementAndGet);
		queue.runCompleted(4L);
		assertEquals(0, calls.get());
		queue.runCompleted(5L);
		assertEquals(1, calls.get());
	}

	@Test
	void drainsAllEntriesIncludingMaximumFrame() {
		FrameQueue queue = new FrameQueue();
		AtomicInteger calls = new AtomicInteger();
		queue.add(0L, calls::incrementAndGet);
		queue.add(Long.MAX_VALUE, calls::incrementAndGet);
		queue.runAll();
		queue.runAll();
		assertEquals(2, calls.get());
	}
}
