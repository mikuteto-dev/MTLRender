package dev.mikuteto.mtlrender.mixin;

import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MultiPartModel.class)
public interface MultiPartModelAccess {
	@Accessor("shared")
	Object mtlrender$shared();

	@Accessor("blockState")
	BlockState mtlrender$blockState();
}
