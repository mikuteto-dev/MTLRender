package dev.mikuteto.mtlrender.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SectionCompileAllocationPoolTest {
	@Test
	void acquiresEmptyPooledRecord() {
		SectionCompileAllocationPool pool = new SectionCompileAllocationPool(1, 2);
		SectionCompileAllocationPool.Lease lease = pool.acquire();

		assertTrue(lease.pooled());
		assertFalse(lease.fallback());
		assertEquals(1L, lease.generation());
		assertNull(lease.cacheGeneration());
		lease.release();
	}

	@Test
	void fallsBackWhenBoundedPoolIsExhausted() {
		SectionCompileAllocationPool pool = new SectionCompileAllocationPool(1, 1);
		SectionCompileAllocationPool.Lease pooled = pool.acquire();
		SectionCompileAllocationPool.Lease fallback = pool.acquire();

		assertTrue(fallback.fallback());
		assertFalse(fallback.pooled());
		pooled.release();
		fallback.release();
		assertEquals(1L, pool.snapshot().fallbacks());
	}

	@Test
	void reusesAfterAllLeasesRelease() {
		SectionCompileAllocationPool pool = new SectionCompileAllocationPool(1, 1);
		SectionCompileAllocationPool.Lease first = pool.acquire();
		first.setCacheGeneration("generation");
		long firstGeneration = first.generation();
		first.release();
		SectionCompileAllocationPool.Lease second = pool.acquire();

		assertTrue(second.reused());
		assertTrue(second.generation() > firstGeneration);
		assertEquals(1L, pool.snapshot().reuses());
		second.release();
	}

	@Test
	void cancellationStyleReleaseIsIdempotent() {
		SectionCompileAllocationPool pool = new SectionCompileAllocationPool(1, 1);
		SectionCompileAllocationPool.Lease lease = pool.acquire();
		SectionCompileAllocationPool.Lease taskLease = lease.retain();

		assertNotNull(taskLease);
		taskLease.release();
		taskLease.release();
		lease.release();
		lease.release();
		SectionCompileAllocationPool.Lease next = pool.acquire();
		assertTrue(next.pooled());
		next.release();
	}

	@Test
	void liveRegionLeasePreventsRecycling() {
		SectionCompileAllocationPool pool = new SectionCompileAllocationPool(1, 1);
		SectionCompileAllocationPool.Lease owner = pool.acquire();
		owner.setCacheGeneration("cache");
		SectionCompileAllocationPool.WorkLease region = owner.recordWork(42L, "region");
		owner.release();
		SectionCompileAllocationPool.Lease fallback = pool.acquire();

		assertTrue(fallback.fallback());
		assertEquals("cache", owner.cacheGeneration());
		region.release();
		fallback.release();
		SectionCompileAllocationPool.Lease reused = pool.acquire();
		assertTrue(reused.pooled());
		assertNull(reused.cacheGeneration());
		reused.release();
	}

	@Test
	void recordsAndRetainsRegionWorkUntilTaskRelease() {
		SectionCompileAllocationPool pool = new SectionCompileAllocationPool(1, 2);
		SectionCompileAllocationPool.Lease owner = pool.acquire();
		SectionCompileAllocationPool.WorkLease work = owner.recordWork(9L, "snapshot");
		SectionCompileAllocationPool.Lease task = owner.retain();

		assertNotNull(work);
		assertEquals(9L, work.sectionKey());
		assertSame("snapshot", work.region());
		owner.release();
		assertTrue(task.reset() == false);
		work.release();
		task.release();
		SectionCompileAllocationPool.Lease next = pool.acquire();
		assertTrue(next.pooled());
		next.release();
	}

	@Test
	void resetAdvancesGenerationOnlyWhenNoWorkIsLive() {
		SectionCompileAllocationPool pool = new SectionCompileAllocationPool(1, 1);
		SectionCompileAllocationPool.Lease lease = pool.acquire();
		long generation = lease.generation();
		SectionCompileAllocationPool.WorkLease work = lease.recordWork(5L, "snapshot");

		assertFalse(lease.reset());
		work.release();
		assertTrue(lease.reset());
		assertEquals(generation + 1L, lease.generation());
		assertNull(lease.cacheGeneration());
		lease.release();
	}

	@Test
	void duplicateTaskLeasesReleaseExactlyOnce() {
		SectionCompileAllocationPool pool = new SectionCompileAllocationPool(1, 1);
		SectionCompileAllocationPool.Lease owner = pool.acquire();
		SectionCompileAllocationPool.WorkLease first = owner.recordWork(7L, "region");
		SectionCompileAllocationPool.WorkLease second = owner.recordWork(7L, "region");

		assertNotNull(first);
		assertNotNull(second);
		owner.release();
		first.release();
		SectionCompileAllocationPool.Lease blocked = pool.acquire();
		assertTrue(blocked.fallback());
		second.release();
		blocked.release();

		SectionCompileAllocationPool.Lease reused = pool.acquire();
		assertTrue(reused.reused());
		reused.release();
	}

	@Test
	void concurrentTerminalReleaseDoesNotLeakOrDoubleRelease() throws InterruptedException {
		SectionCompileAllocationPool pool = new SectionCompileAllocationPool(1, 1);
		SectionCompileAllocationPool.Lease owner = pool.acquire();
		SectionCompileAllocationPool.WorkLease work = owner.recordWork(8L, "region");
		Thread cancel = new Thread(work::release);
		Thread complete = new Thread(work::release);
		cancel.start();
		complete.start();
		cancel.join();
		complete.join();
		owner.release();

		SectionCompileAllocationPool.Lease reused = pool.acquire();
		assertTrue(reused.pooled());
		reused.release();
	}

	@Test
	void refusesWorkPastFixedCapacity() {
		SectionCompileAllocationPool pool = new SectionCompileAllocationPool(1, 1);
		SectionCompileAllocationPool.Lease lease = pool.acquire();
		SectionCompileAllocationPool.WorkLease first = lease.recordWork(1L, "one");
		SectionCompileAllocationPool.WorkLease second = lease.recordWork(2L, "two");

		assertNotNull(first);
		assertNull(second);
		first.release();
		lease.release();
	}
}
