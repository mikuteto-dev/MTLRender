package dev.mikuteto.mtlrender.client;

import dev.mikuteto.mtlrender.mixin.RenderRegionCacheAccess;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class SectionCompileAllocationPool {
	private static final int BUSY = -1;
	private final Slot[] slots;
	private final int workCapacity;
	private final AtomicLong acquisitions = new AtomicLong();
	private final AtomicLong reuses = new AtomicLong();
	private final AtomicLong fallbacks = new AtomicLong();
	private final AtomicLong resets = new AtomicLong();

	public SectionCompileAllocationPool(int capacity, int workCapacity) {
		if (capacity < 1 || workCapacity < 1) {
			throw new IllegalArgumentException("Pool capacities must be positive");
		}
		this.slots = new Slot[capacity];
		for (int index = 0; index < capacity; index++) {
			this.slots[index] = new Slot(workCapacity, true);
		}
		this.workCapacity = workCapacity;
	}

	public Lease acquire() {
		acquisitions.incrementAndGet();
		for (Slot slot : slots) {
			Lease lease = slot.acquire();
			if (lease != null) {
				if (lease.reused()) {
					reuses.incrementAndGet();
				}
				return lease;
			}
		}
		fallbacks.incrementAndGet();
		return new Lease(new Slot(workCapacity, false), 1L, false);
	}

	public void retireCacheGenerations() {
		for (Slot slot : slots) {
			slot.cacheGeneration = null;
		}
	}

	public void invalidateSection(long sectionKey) {
		for (Slot slot : slots) {
			if (slot.cacheGeneration instanceof RenderRegionCache cache) {
				((RenderRegionCacheAccess) (Object) cache).mtlrender$invalidateSection(sectionKey);
			}
		}
	}

	public void invalidateRange(int minSectionX, int minSectionY, int minSectionZ,
		int maxSectionX, int maxSectionY, int maxSectionZ) {
		if (minSectionX > maxSectionX || minSectionY > maxSectionY || minSectionZ > maxSectionZ) {
			throw new IllegalArgumentException("Section range is inverted");
		}
		for (Slot slot : slots) {
			if (slot.cacheGeneration instanceof RenderRegionCache cache) {
				((RenderRegionCacheAccess) (Object) cache).mtlrender$invalidateRange(
					minSectionX, minSectionY, minSectionZ, maxSectionX, maxSectionY, maxSectionZ);
			}
		}
	}

	public Snapshot snapshot() {
		return new Snapshot(acquisitions.get(), reuses.get(), fallbacks.get(), resets.get());
	}

	public record Snapshot(long acquisitions, long reuses, long fallbacks, long resets) {
	}

	public final class Lease {
		private final Slot slot;
		private long generation;
		private final boolean reused;
		private final AtomicBoolean released = new AtomicBoolean();

		private Lease(Slot slot, long generation, boolean reused) {
			this.slot = slot;
			this.generation = generation;
			this.reused = reused;
		}

		public long generation() {
			return generation;
		}

		public boolean pooled() {
			return slot.pooled;
		}

		public boolean fallback() {
			return !pooled();
		}

		public boolean reused() {
			return reused;
		}

		public Object cacheGeneration() {
			return slot.cacheGeneration;
		}

		public boolean setCacheGeneration(Object cacheGeneration) {
			if (!slot.owns(generation)) {
				return false;
			}
			slot.cacheGeneration = cacheGeneration;
			return true;
		}

		public Lease retain() {
			if (!slot.retain(generation)) {
				return null;
			}
			return new Lease(slot, generation, false);
		}

		public WorkLease recordWork(long sectionKey, Object region) {
			if (!slot.owns(generation)) {
				return null;
			}
			return slot.record(generation, sectionKey, region);
		}

		public boolean reset() {
			if (!slot.reset(generation)) {
				return false;
			}
			generation++;
			resets.incrementAndGet();
			return true;
		}

		public void release() {
			if (released.compareAndSet(false, true)) {
				slot.release(generation);
			}
		}
	}

	public final class WorkLease {
		private final Slot slot;
		private final long generation;
		private final Work work;
		private final AtomicBoolean released = new AtomicBoolean();

		private WorkLease(Slot slot, long generation, Work work) {
			this.slot = slot;
			this.generation = generation;
			this.work = work;
		}

		public long sectionKey() {
			return work.sectionKey;
		}

		public Object region() {
			return work.region;
		}

		public void release() {
			if (released.compareAndSet(false, true)) {
				slot.releaseWork(generation, work);
			}
		}
	}

	private final class Slot {
		private final Work[] works;
		private final boolean pooled;
		private final AtomicLong state = new AtomicLong(pack(1L, 0));
		private volatile Object cacheGeneration;
		private boolean used;

		private Slot(int workCapacity, boolean pooled) {
			this.works = new Work[workCapacity];
			this.pooled = pooled;
			for (int index = 0; index < workCapacity; index++) {
				this.works[index] = new Work();
			}
		}

		private Lease acquire() {
			while (true) {
				long observed = state.get();
				if (count(observed) != 0) {
					return null;
				}
				long claimed = pack(generation(observed), 1);
				if (state.compareAndSet(observed, claimed)) {
					boolean reused = used;
					used = true;
					clear();
					return new Lease(this, generation(claimed), reused);
				}
			}
		}

		private boolean owns(long expectedGeneration) {
			long observed = state.get();
			return generation(observed) == expectedGeneration && count(observed) > 0;
		}

		private boolean retain(long expectedGeneration) {
			while (true) {
				long observed = state.get();
				if (generation(observed) != expectedGeneration || count(observed) <= 0 || count(observed) == Integer.MAX_VALUE) {
					return false;
				}
				if (state.compareAndSet(observed, pack(expectedGeneration, count(observed) + 1))) {
					return true;
				}
			}
		}

		private WorkLease record(long expectedGeneration, long sectionKey, Object region) {
			for (Work work : works) {
				if (work.active && work.generation == expectedGeneration && work.sectionKey == sectionKey && work.region == region) {
					if (retain(expectedGeneration)) {
						work.references++;
						return new WorkLease(this, expectedGeneration, work);
					}
					return null;
				}
			}
			if (!retain(expectedGeneration)) {
				return null;
			}
			for (Work work : works) {
				if (!work.active) {
					work.generation = expectedGeneration;
					work.sectionKey = sectionKey;
					work.region = region;
					work.references = 1;
					work.active = true;
					return new WorkLease(this, expectedGeneration, work);
				}
			}
			release(expectedGeneration);
			return null;
		}

		private boolean reset(long expectedGeneration) {
			long observed = state.get();
			if (generation(observed) != expectedGeneration || count(observed) != 1) {
				return false;
			}
			if (!state.compareAndSet(observed, pack(expectedGeneration, BUSY))) {
				return false;
			}
			clear();
			state.set(pack(expectedGeneration + 1, 1));
			return true;
		}

		private void release(long expectedGeneration) {
			while (true) {
				long observed = state.get();
				int leases = count(observed);
				if (generation(observed) != expectedGeneration || leases <= 0) {
					return;
				}
				if (leases > 1) {
					if (state.compareAndSet(observed, pack(expectedGeneration, leases - 1))) {
						return;
					}
				} else if (state.compareAndSet(observed, pack(expectedGeneration, BUSY))) {
					clear();
					state.set(pack(expectedGeneration + 1, 0));
					return;
				}
			}
		}

		private void releaseWork(long expectedGeneration, Work work) {
			if (work.generation == expectedGeneration && work.active && work.references > 0) {
				work.references--;
				if (work.references == 0) {
					work.active = false;
					work.region = null;
				}
				release(expectedGeneration);
			}
		}

		private void clear() {
			cacheGeneration = null;
			for (Work work : works) {
				work.active = false;
				work.references = 0;
				work.region = null;
			}
		}
	}

	private static final class Work {
		private long generation;
		private long sectionKey;
		private Object region;
		private int references;
		private boolean active;
	}

	private static long pack(long generation, int count) {
		return (generation << 32) | (count & 0xffffffffL);
	}

	private static long generation(long state) {
		return state >>> 32;
	}

	private static int count(long state) {
		return (int) state;
	}
}
