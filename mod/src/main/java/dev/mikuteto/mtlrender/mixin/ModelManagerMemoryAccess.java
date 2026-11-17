package dev.mikuteto.mtlrender.mixin;

import java.util.Map;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.block.BlockModelSet;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.item.ClientItem;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ModelManager.class)
public interface ModelManagerMemoryAccess {
	@Accessor("bakedItemStackModels")
	Map<Identifier, ItemModel> mtlrender$itemModels();

	@Accessor("blockStateModelSet")
	BlockStateModelSet mtlrender$blockModels();

	@Accessor("blockModelSet")
	BlockModelSet mtlrender$bakedBlockModels();

	@Accessor("fluidStateModelSet")
	FluidStateModelSet mtlrender$fluidModels();

	@Accessor("entityModelSet")
	EntityModelSet mtlrender$entityModels();

	@Accessor("atlasManager")
	net.minecraft.client.resources.model.sprite.AtlasManager mtlrender$atlasManager();
}
