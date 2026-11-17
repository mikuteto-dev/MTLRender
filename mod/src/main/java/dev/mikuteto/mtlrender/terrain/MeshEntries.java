package dev.mikuteto.mtlrender.terrain;

import dev.mikuteto.mtlrender.blockentity.StaticBlockEntityBakePublication;
import dev.mikuteto.mtlrender.pathtracing.VoxelBrickSet;

public interface MeshEntries {
	DirectUberBuffer.Entry mtlrender$entry(int layer);

	void mtlrender$setEntry(int layer, DirectUberBuffer.Entry entry);

	default PackTerrainJavaMesh mtlrender$packTerrainMetadata(int layer) {
		return null;
	}

	default void mtlrender$setPackTerrainMetadata(int layer, PackTerrainJavaMesh metadata) {
	}

	default StaticBlockEntityBakePublication mtlrender$staticBlockEntityBakePublication() {
		return null;
	}

	VoxelBrickSet mtlrender$voxelBricks();

	void mtlrender$setVoxelBricks(VoxelBrickSet bricks);

	boolean mtlrender$acceptUploadCallback(boolean sortedIndexBuffer);

	boolean mtlrender$isCurrent();

	int mtlrender$section();

	void mtlrender$setSection(int index);
}
