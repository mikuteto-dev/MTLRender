package dev.mikuteto.mtlrender.mixin;

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SingleVariant.class)
public interface SingleVariantAccess {
	@Accessor("model")
	BlockStateModelPart mtlrender$model();
}
