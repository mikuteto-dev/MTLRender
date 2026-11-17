package dev.mikuteto.mtlrender.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(net.minecraft.client.renderer.texture.SpriteContents.class)
public interface SpriteContentsMemoryInfo {
	@Accessor("byMipLevel")
	NativeImage[] mtlrender$mipImages();
}
