package dev.mikuteto.mtlrender.mixin;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(net.minecraft.client.renderer.texture.SpriteContents.class)
public interface SpriteContentsMemoryAccess {
	void mtlrender$releaseCpuImages();
}
