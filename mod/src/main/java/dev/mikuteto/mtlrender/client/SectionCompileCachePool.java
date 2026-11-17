package dev.mikuteto.mtlrender.client;

import dev.mikuteto.mtlrender.mixin.RenderRegionCacheAccess;
import net.minecraft.client.renderer.chunk.RenderRegionCache;

public final class SectionCompileCachePool {
	private static final SectionCompileAllocationPool POOL = new SectionCompileAllocationPool(2, 4096);
	private static volatile RenderRegionCache current;

	private SectionCompileCachePool() {
	}

	public static SectionCompileAllocationPool.Lease acquire() {
		return POOL.acquire();
	}

	public static void use(RenderRegionCache cache) {
		current = cache;
	}

	public static void invalidateSection(long sectionKey) {
		RenderRegionCache cache = current;
		if (cache != null) {
			((RenderRegionCacheAccess) (Object) cache).mtlrender$invalidateSection(sectionKey);
		}
		POOL.invalidateSection(sectionKey);
	}

	public static void invalidateRange(int minSectionX, int minSectionY, int minSectionZ,
		int maxSectionX, int maxSectionY, int maxSectionZ) {
		RenderRegionCache cache = current;
		if (cache != null) {
			((RenderRegionCacheAccess) (Object) cache).mtlrender$invalidateRange(
				minSectionX, minSectionY, minSectionZ, maxSectionX, maxSectionY, maxSectionZ);
		}
		POOL.invalidateRange(minSectionX, minSectionY, minSectionZ, maxSectionX, maxSectionY, maxSectionZ);
	}

	public static void reset() {
		POOL.retireCacheGenerations();
		RenderRegionCache cache = current;
		if (cache != null) {
			((RenderRegionCacheAccess) (Object) cache).mtlrender$resetCacheGeneration();
			current = null;
		}
	}
}
