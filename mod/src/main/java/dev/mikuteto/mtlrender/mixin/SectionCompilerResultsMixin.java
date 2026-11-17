package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.blockentity.StaticBlockEntityBakeMetadata;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import java.util.EnumMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SectionCompiler.Results.class)
abstract class SectionCompilerResultsMixin implements SectionCompilerResultsAccess {
	@Unique
	private StaticBlockEntityBakeMetadata mtlrender$staticBlockEntityBakeMetadata = StaticBlockEntityBakeMetadata.empty();
	@Unique
	private final EnumMap<ChunkSectionLayer, Object> mtlrender$packTerrainMetadata = new EnumMap<>(ChunkSectionLayer.class);

	@Override
	public StaticBlockEntityBakeMetadata mtlrender$staticBlockEntityBakeMetadata() {
		return mtlrender$staticBlockEntityBakeMetadata;
	}

	@Override
	public void mtlrender$setStaticBlockEntityBakeMetadata(StaticBlockEntityBakeMetadata metadata) {
		mtlrender$staticBlockEntityBakeMetadata = metadata;
	}

	@Override
	public Object mtlrender$packTerrainMetadata(ChunkSectionLayer layer) {
		return mtlrender$packTerrainMetadata.get(layer);
	}

	@Override
	public void mtlrender$setPackTerrainMetadata(ChunkSectionLayer layer, Object metadata) {
		mtlrender$packTerrainMetadata.put(layer, metadata);
	}
}
