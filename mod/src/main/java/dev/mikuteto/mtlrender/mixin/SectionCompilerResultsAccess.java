package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.blockentity.StaticBlockEntityBakeMetadata;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(SectionCompiler.Results.class)
public interface SectionCompilerResultsAccess {
	StaticBlockEntityBakeMetadata mtlrender$staticBlockEntityBakeMetadata();

	void mtlrender$setStaticBlockEntityBakeMetadata(StaticBlockEntityBakeMetadata metadata);

	Object mtlrender$packTerrainMetadata(ChunkSectionLayer layer);

	void mtlrender$setPackTerrainMetadata(ChunkSectionLayer layer, Object metadata);
}
