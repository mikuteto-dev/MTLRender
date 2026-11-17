package dev.mikuteto.mtlrender.server.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class ParallelRegionReaderCoreTest {
	@Test
	void overlapsIndependentRegions() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch started = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		try (ParallelRegionReader reader = new ParallelRegionReader(executor, 4, ignored -> new ParallelRegionReader.Owner() {
			@Override
			public net.minecraft.nbt.CompoundTag read(ChunkPos pos) throws Exception {
				started.countDown();
				if (!started.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("second region did not start");
				}
				release.await();
				return null;
			}

			@Override
			public void close() {
			}
		})) {
			var first = reader.loadAsync(new ChunkPos(0, 0));
			var second = reader.loadAsync(new ChunkPos(32, 0));
			if (!started.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("independent reads did not overlap");
			}
			release.countDown();
			first.join();
			second.join();
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void serializesOneRegionInFifoOrder() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		List<Integer> order = new ArrayList<>();
		AtomicInteger next = new AtomicInteger();
		try (ParallelRegionReader reader = new ParallelRegionReader(executor, 4, ignored -> new ParallelRegionReader.Owner() {
			@Override
			public net.minecraft.nbt.CompoundTag read(ChunkPos pos) {
				return null;
			}

			@Override
			public void close() {
			}
		})) {
			var first = reader.coordinate(new ChunkPos(0, 0), () -> {
				order.add(next.incrementAndGet());
				return null;
			});
			var second = reader.coordinate(new ChunkPos(1, 1), () -> {
				order.add(next.incrementAndGet());
				return null;
			});
			first.join();
			second.join();
			assertEquals(List.of(1, 2), order);
		} finally {
			executor.shutdownNow();
		}
	}
}
