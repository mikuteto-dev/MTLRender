package dev.mikuteto.mtlrender.mixin;

import java.util.List;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel$SharedBakedState")
public interface MultiPartSharedBakedStateAccess {
	@Accessor("selectors")
	List<MultiPartModel.Selector<BlockStateModel>> mtlrender$selectors();
}
