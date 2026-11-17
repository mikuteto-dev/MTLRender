package dev.mikuteto.mtlrender.server.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class ParallelRegionReaderSafetyTest {
	@Test
	void pendingWritePrecedesSource() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		AtomicInteger opened = new AtomicInteger();
		CompoundTag pending = new CompoundTag();
		ChunkPos pos = new ChunkPos(4, 5);
		try (ParallelRegionReader reader = new ParallelRegionReader(executor, 4, ignored -> {
			opened.incrementAndGet();
			throw new AssertionError("source was opened for a pending write");
		})) {
			Optional<CompoundTag> result = reader.loadAsync(pos, candidate -> Optional.of(pending)).join();
			assertTrue(result.isPresent());
			assertSame(pending, result.get());
			assertEquals(0, opened.get());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void preservesFifoAndAllowsDifferentRegionsToOverlap() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(3);
		List<Integer> order = Collections.synchronizedList(new java.util.ArrayList<>());
		CountDownLatch firstStarted = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch differentStarted = new CountDownLatch(1);
		AtomicInteger sameRegionStarted = new AtomicInteger();
		try (ParallelRegionReader reader = new ParallelRegionReader(executor, 4, ignored -> new EmptyOwner())) {
			CompletableFuture<Void> first = reader.coordinateChecked(new ChunkPos(0, 0), () -> {
				order.add(1);
				firstStarted.countDown();
				releaseFirst.await();
				return null;
			});
			CompletableFuture<Void> sameRegion = reader.coordinate(new ChunkPos(1, 1), () -> {
				sameRegionStarted.incrementAndGet();
				order.add(2);
				return null;
			});
			CompletableFuture<Void> differentRegion = reader.coordinateChecked(new ChunkPos(32, 0), () -> {
				firstStarted.await();
				order.add(3);
				differentStarted.countDown();
				return null;
			});
			firstStarted.await();
			differentStarted.await();
			assertEquals(0, sameRegionStarted.get());
			releaseFirst.countDown();
			first.join();
			sameRegion.join();
			differentRegion.join();
			assertTrue(order.indexOf(1) < order.indexOf(2));
			assertEquals(3, order.size());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void closeDrainsBlockedWorkAndClosesOwnerOnce() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch readStarted = new CountDownLatch(1);
		CountDownLatch releaseRead = new CountDownLatch(1);
		AtomicInteger closeCount = new AtomicInteger();
		AtomicReference<Throwable> closeFailure = new AtomicReference<>();
		ParallelRegionReader reader = new ParallelRegionReader(executor, 4, ignored -> new ParallelRegionReader.Owner() {
			@Override
			public CompoundTag read(ChunkPos pos) throws Exception {
				readStarted.countDown();
				releaseRead.await();
				return null;
			}

			@Override
			public void close() {
				closeCount.incrementAndGet();
			}
		});
		try {
			CompletableFuture<Optional<CompoundTag>> read = reader.loadAsync(new ChunkPos(0, 0));
			readStarted.await();
			Thread closer = new Thread(() -> {
				try {
					reader.close();
				} catch (Throwable failure) {
					closeFailure.set(failure);
				}
			});
			closer.start();
			releaseRead.countDown();
			closer.join();
			read.join();
			assertNull(closeFailure.get());
			assertEquals(1, closeCount.get());
			reader.close();
			assertEquals(1, closeCount.get());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void rejectsWorkAfterClose() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(1);
		ParallelRegionReader reader = new ParallelRegionReader(executor, 4, ignored -> new EmptyOwner());
		try {
			reader.close();
			CompletableFuture<Void> rejected = reader.coordinate(new ChunkPos(0, 0), () -> null);
			CompletionException failure = assertThrows(CompletionException.class, rejected::join);
			assertTrue(failure.getCause() instanceof java.util.concurrent.RejectedExecutionException);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void propagatesFailureAndReleasesLane() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		RuntimeException cause = new RuntimeException("read failed");
		try (ParallelRegionReader reader = new ParallelRegionReader(executor, 4, ignored -> new EmptyOwner())) {
			CompletableFuture<Void> failed = reader.coordinateChecked(new ChunkPos(0, 0), () -> {
				throw cause;
			});
			CompletionException failure = assertThrows(CompletionException.class, failed::join);
			assertSame(cause, failure.getCause());
			assertEquals("ok", reader.coordinate(new ChunkPos(1, 1), () -> "ok").join());
			assertEquals("other", reader.coordinate(new ChunkPos(32, 0), () -> "other").join());
		} finally {
			executor.shutdownNow();
		}
	}

	private static final class EmptyOwner implements ParallelRegionReader.Owner {
		@Override
		public CompoundTag read(ChunkPos pos) {
			return null;
		}

		@Override
		public void close() {
		}
	}
}
