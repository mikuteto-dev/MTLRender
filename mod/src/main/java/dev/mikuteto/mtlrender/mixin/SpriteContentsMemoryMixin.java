package dev.mikuteto.mtlrender.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SpriteContents.class)
abstract class SpriteContentsMemoryMixin implements SpriteContentsMemoryAccess {
	@Shadow
	private NativeImage[] byMipLevel;

	private boolean mtlrender$released;

	@Override
	public void mtlrender$releaseCpuImages() {
		if (mtlrender$released) {
			return;
		}
		mtlrender$released = true;
		for (NativeImage image : byMipLevel) {
			image.close();
		}
		byMipLevel = new NativeImage[0];
	}
}
