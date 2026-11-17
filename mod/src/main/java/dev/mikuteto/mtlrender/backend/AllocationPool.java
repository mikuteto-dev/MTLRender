package dev.mikuteto.mtlrender.backend;

import java.util.ArrayDeque;
import java.util.Map;

import dev.mikuteto.mtlrender.tune.MemoryBudget;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

final class AllocationPool {
	private record Retired(NativeGpu.Allocation allocation, long frame) {
	}

	private final NativeGpu gpu;
	private final long cap;
	private final boolean compact;
	private final Long2ObjectOpenHashMap<ArrayDeque<Retired>> free = new Long2ObjectOpenHashMap<>();
	private long pooledBytes;

	AllocationPool(NativeGpu gpu, MemoryBudget budget) {
		this.gpu = gpu;
		this.cap = budget.poolCap();
		this.compact = Boolean.parseBoolean(System.getProperty("mtlrender.memory.compact", "true"));
	}

	NativeGpu.Allocation take(long size) {
		reclaimCompleted();
		ArrayDeque<Retired> retired = free.get(size);
		if (retired != null && !retired.isEmpty()) {
			pooledBytes -= size;
			return retired.removeFirst().allocation();
		}
		return gpu.allocate(size);
	}

	void retire(NativeGpu.Allocation allocation, long size) {
		if (!compact || size > cap) {
			gpu.destroyAllocation(allocation);
			return;
		}
		reclaimCompleted();
		while (pooledBytes + size > cap && reclaimOldestCompleted()) {
		}
		if (pooledBytes + size > cap) {
			gpu.destroyAllocation(allocation);
			return;
		}
		free.computeIfAbsent(size, ignored -> new ArrayDeque<>()).addLast(new Retired(allocation, gpu.submittedFrames()));
		pooledBytes += size;
	}

	long pooledBytes() {
		return pooledBytes;
	}

	long cap() {
		return cap;
	}

	long liveBytes() {
		return gpu.accountedBytes();
	}

	void clear() {
		reclaimCompleted();
	}

	void close() {
		for (ArrayDeque<Retired> retired : free.values()) {
			for (Retired entry : retired) {
				gpu.destroyAllocation(entry.allocation());
			}
		}
		free.clear();
		pooledBytes = 0;
	}

	private void reclaimCompleted() {
		long completed = gpu.refreshCompletedFrames();
		for (ArrayDeque<Retired> retired : free.values()) {
			while (!retired.isEmpty() && retired.peekFirst().frame() < completed) {
				gpu.destroyAllocation(retired.removeFirst().allocation());
			}
		}
		free.values().removeIf(ArrayDeque::isEmpty);
		pooledBytes = 0;
		for (Map.Entry<Long, ArrayDeque<Retired>> bucket : free.long2ObjectEntrySet()) {
			pooledBytes += bucket.getKey() * bucket.getValue().size();
		}
	}

	private boolean reclaimOldestCompleted() {
		long completed = gpu.completedFrames();
		long oldest = Long.MAX_VALUE;
		ArrayDeque<Retired> selected = null;
		for (ArrayDeque<Retired> retired : free.values()) {
			Retired first = retired.peekFirst();
			if (first != null && first.frame() < completed && first.frame() < oldest) {
				oldest = first.frame();
				selected = retired;
			}
		}
		if (selected == null) {
			return false;
		}
		Retired entry = selected.removeFirst();
		pooledBytes -= entry.allocation().contents().byteSize();
		gpu.destroyAllocation(entry.allocation());
		return true;
	}
}
