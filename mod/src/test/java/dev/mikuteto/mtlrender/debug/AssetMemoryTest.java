package dev.mikuteto.mtlrender.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AssetMemoryTest {
	@Test
	void recordsIndependentAssetCategories() {
		AssetMemory memory = new AssetMemory();
		memory.atlas(100, 20, 10, 4, 1);
		memory.models(3, 8, 2, 5);
		memory.rustModelTable(90);
		AssetMemory.Snapshot snapshot = memory.snapshot();
		assertEquals(100, snapshot.atlasGpuBytes());
		assertEquals(20, snapshot.cpuImageBytes());
		assertEquals(10, snapshot.cpuMipmapBytes());
		assertEquals(4, snapshot.spriteCount());
		assertEquals(1, snapshot.animatedSpriteCount());
		assertEquals(3, snapshot.bakedItemMapEntries());
		assertEquals(8, snapshot.bakedBlockMapEntries());
		assertEquals(2, snapshot.bakedFluidMapEntries());
		assertEquals(5, snapshot.entityModelLayers());
		assertEquals(90, snapshot.rustModelTableBytes());
	}
}
