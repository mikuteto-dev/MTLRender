package dev.mikuteto.mtlrender.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(RenderRegionCache.class)
public class RenderRegionCacheReuseMixin implements RenderRegionCacheAccess {
	@Shadow @Final
	private Long2ObjectMap<SectionCopy> sectionCopyCache;

	@Override
	public void mtlrender$resetCacheGeneration() {
		this.sectionCopyCache.clear();
	}

	@Override
	public void mtlrender$invalidateSection(long sectionKey) {
		this.sectionCopyCache.remove(sectionKey);
	}

	@Override
	public void mtlrender$invalidateRange(
		int minSectionX,
		int minSectionY,
		int minSectionZ,
		int maxSectionX,
		int maxSectionY,
		int maxSectionZ
	) {
		if (minSectionX > maxSectionX || minSectionY > maxSectionY || minSectionZ > maxSectionZ) {
			throw new IllegalArgumentException("Section range is inverted");
		}
		for (int sectionZ = minSectionZ; ; sectionZ++) {
			for (int sectionY = minSectionY; ; sectionY++) {
				for (int sectionX = minSectionX; ; sectionX++) {
					this.sectionCopyCache.remove(SectionPos.asLong(sectionX, sectionY, sectionZ));
					if (sectionX == maxSectionX) {
						break;
					}
				}
				if (sectionY == maxSectionY) {
					break;
				}
			}
			if (sectionZ == maxSectionZ) {
				break;
			}
		}
	}
}
