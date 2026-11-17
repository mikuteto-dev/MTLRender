package dev.mikuteto.mtlrender.mixin;

import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.client.renderer.chunk.SectionCompiler;

@Mixin(SectionCompiler.class)
public interface SectionCompilerAccess {
	@Accessor("ambientOcclusion")
	boolean mtlrender$ambientOcclusion();

	@Accessor("cutoutLeaves")
	boolean mtlrender$cutoutLeaves();

	@Accessor("blockModelSet")
	BlockStateModelSet mtlrender$blockModelSet();

	@Accessor("fluidModelSet")
	FluidStateModelSet mtlrender$fluidModelSet();

	@Accessor("blockColors")
	BlockColors mtlrender$blockColors();
}
