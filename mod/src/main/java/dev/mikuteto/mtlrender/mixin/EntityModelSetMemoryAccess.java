package dev.mikuteto.mtlrender.mixin;

import java.util.Map;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.ModelLayerLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityModelSet.class)
public interface EntityModelSetMemoryAccess {
	@Accessor("roots")
	Map<ModelLayerLocation, LayerDefinition> mtlrender$roots();
}
