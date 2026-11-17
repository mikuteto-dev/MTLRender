package dev.mikuteto.mtlrender.mixin;

import java.util.List;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(net.minecraft.client.renderer.texture.TextureAtlas.class)
public interface TextureAtlasMemoryAccess {
	@Accessor("sprites")
	List<TextureAtlasSprite> mtlrender$sprites();

	@Accessor("width")
	int mtlrender$width();

	@Accessor("height")
	int mtlrender$height();

	@Accessor("mipLevelCount")
	int mtlrender$mipLevelCount();
}
