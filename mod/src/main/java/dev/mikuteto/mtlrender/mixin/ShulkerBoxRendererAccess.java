package dev.mikuteto.mtlrender.mixin;

import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.blockentity.ShulkerBoxRenderer;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ShulkerBoxRenderer.class)
public interface ShulkerBoxRendererAccess {
	@Accessor("model")
	Model<?> mtlrender$model();

	@Accessor("sprites")
	SpriteGetter mtlrender$sprites();
}
