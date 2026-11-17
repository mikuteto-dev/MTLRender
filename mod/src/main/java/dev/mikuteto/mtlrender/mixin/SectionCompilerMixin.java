package dev.mikuteto.mtlrender.mixin;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.mikuteto.mtlrender.terrain.TerrainRenderer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SectionCompiler.class)
abstract class SectionCompilerMixin {
	@Redirect(
		method = "compile",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/MeshData;sortQuads(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder;Lcom/mojang/blaze3d/vertex/VertexSorting;)Lcom/mojang/blaze3d/vertex/MeshData$SortState;"
		)
	)
	private MeshData.SortState mtlrender$identityTranslucentSort(MeshData mesh, ByteBufferBuilder target, VertexSorting sorting) {
		TerrainRenderer renderer = TerrainRenderer.get();
		return renderer.translucentSortCompile() ? renderer.identityTranslucentSort(mesh, target, sorting) : mesh.sortQuads(target, sorting);
	}
}
