package dev.mikuteto.mtlrender.mixin;

import java.util.Map;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.BlockModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockStateModelSet.class)
interface BlockStateModelSetAccess {
	@Accessor("modelByState") Map<BlockState, BlockStateModel> mtlrender$models();
}

@Mixin(BlockModelSet.class)
interface BlockModelSetAccess {
	@Accessor("blockModelByStateCache") Map<BlockState, BlockModel> mtlrender$models();
}

@Mixin(FluidStateModelSet.class)
interface FluidStateModelSetAccess {
	@Accessor("modelByFluid") Map<Fluid, FluidModel> mtlrender$models();
}
