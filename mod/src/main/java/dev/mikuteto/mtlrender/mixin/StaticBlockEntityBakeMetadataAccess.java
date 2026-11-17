package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.blockentity.StaticBlockEntityBakeMetadata;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(CompiledSectionMesh.class)
public interface StaticBlockEntityBakeMetadataAccess {
	StaticBlockEntityBakeMetadata mtlrender$staticBlockEntityBakeMetadata();
}
