package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mikuteto.mtlrender.client.SectionCompileAllocationPool;
import dev.mikuteto.mtlrender.client.SectionCompileCachePool;
import dev.mikuteto.mtlrender.client.SectionCompileRegionLeases;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
abstract class LevelExtractorRegionCacheMixin {
	@Unique
	private SectionCompileAllocationPool.Lease mtlrender$extractLease;
	@Unique
	private RenderRegionCache mtlrender$cache;

	@Redirect(
		method = "extract",
		at = @At(value = "NEW", target = "net/minecraft/client/renderer/chunk/RenderRegionCache")
	)
	private RenderRegionCache mtlrender$acquireCache() {
		SectionCompileAllocationPool.Lease lease = SectionCompileCachePool.acquire();
		Object generation = lease.cacheGeneration();
		RenderRegionCache cache;
		if (generation instanceof RenderRegionCache existing) {
			cache = existing;
		} else {
			cache = new RenderRegionCache();
			lease.setCacheGeneration(cache);
		}
		mtlrender$extractLease = lease;
		mtlrender$cache = cache;
		SectionCompileCachePool.use(cache);
		return cache;
	}

	@WrapOperation(
		method = "extract",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/RenderRegionCache;createRegion(Lnet/minecraft/client/multiplayer/ClientLevel;J)Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;"
		)
	)
	private RenderSectionRegion mtlrender$retainRegion(
		RenderRegionCache cache,
		ClientLevel level,
		long sectionNode,
		Operation<RenderSectionRegion> original
	) {
		RenderSectionRegion region = original.call(cache, level, sectionNode);
		SectionCompileAllocationPool.Lease lease = mtlrender$extractLease;
		if (lease != null && cache == mtlrender$cache && lease.pooled()) {
			if (SectionCompileRegionLeases.record(lease, sectionNode, region)) {
				return region;
			}
			lease.release();
			mtlrender$extractLease = null;
			RenderRegionCache fallback = new RenderRegionCache();
			mtlrender$cache = fallback;
			return original.call(fallback, level, sectionNode);
		}
		return region;
	}

	@Inject(method = "extract", at = @At("RETURN"))
	private void mtlrender$releaseExtractionLease(CallbackInfo info) {
		if (mtlrender$extractLease != null) {
			mtlrender$extractLease.release();
			mtlrender$extractLease = null;
		}
	}

	@Inject(method = "setLevel", at = @At("HEAD"))
	private void mtlrender$retireOnLevelChange(@Nullable ClientLevel level, CallbackInfo info) {
		mtlrender$retireCaches();
	}

	@Inject(method = "allChanged", at = @At("HEAD"))
	private void mtlrender$retireOnAllChanged(CallbackInfo info) {
		mtlrender$retireCaches();
	}

	@Inject(method = "onResourceManagerReload", at = @At("HEAD"))
	private void mtlrender$retireOnResourceReload(ResourceManager resourceManager, CallbackInfo info) {
		mtlrender$retireCaches();
	}

	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
	private void mtlrender$invalidateDirtySection(int sectionX, int sectionY, int sectionZ, boolean playerChanged, CallbackInfo info) {
		SectionCompileCachePool.invalidateRange(
			sectionX - 1, sectionY - 1, sectionZ - 1,
			sectionX + 1, sectionY + 1, sectionZ + 1
		);
	}

	@Unique
	private void mtlrender$retireCaches() {
		SectionCompileCachePool.reset();
		mtlrender$cache = null;
	}
}
