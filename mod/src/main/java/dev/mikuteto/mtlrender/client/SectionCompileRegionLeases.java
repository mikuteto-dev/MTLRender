package dev.mikuteto.mtlrender.client;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.client.renderer.chunk.RenderSectionRegion;

public final class SectionCompileRegionLeases {
	private static final Map<RenderSectionRegion, ArrayDeque<SectionCompileAllocationPool.WorkLease>> pending = new IdentityHashMap<>();

	private SectionCompileRegionLeases() {
	}

	public static boolean record(
		SectionCompileAllocationPool.Lease lease,
		long sectionKey,
		RenderSectionRegion region
	) {
		SectionCompileAllocationPool.WorkLease work = lease.recordWork(sectionKey, region);
		if (work == null) {
			return false;
		}
		synchronized (pending) {
			pending.computeIfAbsent(region, ignored -> new ArrayDeque<>()).addLast(work);
		}
		return true;
	}

	public static SectionCompileAllocationPool.WorkLease claim(RenderSectionRegion region) {
		synchronized (pending) {
			ArrayDeque<SectionCompileAllocationPool.WorkLease> leases = pending.get(region);
			if (leases == null) {
				return null;
			}
			SectionCompileAllocationPool.WorkLease work = leases.pollFirst();
			if (leases.isEmpty()) {
				pending.remove(region);
			}
			return work;
		}
	}

	public static void release(SectionCompileAllocationPool.WorkLease work) {
		if (work != null) {
			work.release();
		}
	}
}
