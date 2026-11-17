package dev.mikuteto.mtlrender.mixin;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(net.minecraft.client.renderer.chunk.RenderRegionCache.class)
public interface RenderRegionCacheAccess {
	void mtlrender$resetCacheGeneration();

	void mtlrender$invalidateSection(long sectionKey);

	void mtlrender$invalidateRange(
		int minSectionX,
		int minSectionY,
		int minSectionZ,
		int maxSectionX,
		int maxSectionY,
		int maxSectionZ
	);
}
