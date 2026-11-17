package dev.mikuteto.mtlrender.debug;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

import dev.mikuteto.mtlrender.mixin.SpriteContentsMemoryAccess;
import dev.mikuteto.mtlrender.mixin.SpriteContentsMemoryInfo;
import dev.mikuteto.mtlrender.mixin.TextureAtlasMemoryAccess;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

public final class AssetMemory {
	private static final AssetMemory GLOBAL = new AssetMemory();
	private static final MemoryDiagnostics.Registration REGISTRATION = MemoryDiagnostics.global().register("assets", GLOBAL::memorySource);

	private final AtomicLong atlasGpuBytes = new AtomicLong();
	private final AtomicLong cpuImageBytes = new AtomicLong();
	private final AtomicLong cpuMipmapBytes = new AtomicLong();
	private final AtomicLong spriteCount = new AtomicLong();
	private final AtomicLong animatedSpriteCount = new AtomicLong();
	private final AtomicLong bakedItemMapEntries = new AtomicLong();
	private final AtomicLong bakedBlockMapEntries = new AtomicLong();
	private final AtomicLong bakedFluidMapEntries = new AtomicLong();
	private final AtomicLong entityModelLayers = new AtomicLong();
	private final AtomicLong rustModelTableBytes = new AtomicLong();

	public static AssetMemory global() {
		return GLOBAL;
	}

	public void resetAtlases() {
		atlasGpuBytes.set(0L);
		cpuImageBytes.set(0L);
		cpuMipmapBytes.set(0L);
		spriteCount.set(0L);
		animatedSpriteCount.set(0L);
	}

	public void inspectAtlas(TextureAtlas atlas, boolean releaseCpuImages) {
		TextureAtlasMemoryAccess access = (TextureAtlasMemoryAccess) (Object) atlas;
		long imageBytes = 0L;
		long mipmapBytes = 0L;
		long animated = 0L;
		for (TextureAtlasSprite sprite : access.mtlrender$sprites()) {
			if (sprite.isAnimated()) {
				animated++;
			}
			NativeImage[] images = ((SpriteContentsMemoryInfo) (Object) sprite.contents()).mtlrender$mipImages();
			for (int level = 0; level < images.length; level++) {
				long bytes = (long) images[level].getWidth() * images[level].getHeight() * 4L;
				if (level == 0) {
					imageBytes += bytes;
				} else {
					mipmapBytes += bytes;
				}
			}
			if (releaseCpuImages) {
				((SpriteContentsMemoryAccess) (Object) sprite.contents()).mtlrender$releaseCpuImages();
			}
		}
		long gpuBytes = 0L;
		for (int level = 0; level < access.mtlrender$mipLevelCount(); level++) {
			gpuBytes += (long) Math.max(1, access.mtlrender$width() >> level) * Math.max(1, access.mtlrender$height() >> level) * 4L;
		}
		atlas(gpuBytes, releaseCpuImages ? 0L : imageBytes, releaseCpuImages ? 0L : mipmapBytes, access.mtlrender$sprites().size(), animated);
	}

	public void atlas(long gpuBytes, long imageBytes, long mipmapBytes, long sprites, long animatedSprites) {
		atlasGpuBytes.addAndGet(gpuBytes);
		cpuImageBytes.addAndGet(imageBytes);
		cpuMipmapBytes.addAndGet(mipmapBytes);
		spriteCount.addAndGet(sprites);
		animatedSpriteCount.addAndGet(animatedSprites);
	}

	public void models(long items, long blocks, long fluids, long entities) {
		bakedItemMapEntries.set(items);
		bakedBlockMapEntries.set(blocks);
		bakedFluidMapEntries.set(fluids);
		entityModelLayers.set(entities);
	}

	public void rustModelTable(long bytes) {
		rustModelTableBytes.set(bytes);
	}

	public Snapshot snapshot() {
		return new Snapshot(atlasGpuBytes.get(), cpuImageBytes.get(), cpuMipmapBytes.get(), spriteCount.get(), animatedSpriteCount.get(), bakedItemMapEntries.get(), bakedBlockMapEntries.get(), bakedFluidMapEntries.get(), entityModelLayers.get(), rustModelTableBytes.get());
	}

	public MemorySource.Values memorySource() {
		Snapshot value = snapshot();
		long total = value.atlasGpuBytes() + value.cpuImageBytes() + value.cpuMipmapBytes() + value.rustModelTableBytes();
		return new MemorySource.Values(OptionalLong.of(total), OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty());
	}

	public record Snapshot(long atlasGpuBytes, long cpuImageBytes, long cpuMipmapBytes, long spriteCount, long animatedSpriteCount, long bakedItemMapEntries, long bakedBlockMapEntries, long bakedFluidMapEntries, long entityModelLayers, long rustModelTableBytes) {
	}
}
