package dev.mikuteto.mtlrender.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ServerChunkMemoryAccountingTest {
	@BeforeAll
	static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	@Test
	void emptyPaletteHasZeroStorageAndOneValue() {
		PalettedContainer<?> palette = new PalettedContainer<>(
			Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY));

		assertEquals(1L, ServerChunkMemoryAccounting.paletteEntryCount(palette));
		assertEquals(0, ServerChunkMemoryAccounting.paletteBitsPerEntry(palette));
		assertEquals(2L, ServerChunkMemoryAccounting.paletteSerializedBytes(palette));
	}

	@Test
	void multiplePaletteValuesReportSerializedEstimate() {
		PalettedContainer<net.minecraft.world.level.block.state.BlockState> palette = new PalettedContainer<>(
			Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY));
		palette.set(0, 0, 0, Blocks.STONE.defaultBlockState());
		palette.set(1, 0, 0, Blocks.DIRT.defaultBlockState());

		assertEquals(3L, ServerChunkMemoryAccounting.paletteEntryCount(palette));
		assertEquals(4, ServerChunkMemoryAccounting.paletteBitsPerEntry(palette));
		assertEquals(2053L, ServerChunkMemoryAccounting.paletteSerializedBytes(palette));
	}

	@Test
	void homogeneousLightIsUnknownWithoutMaterializingData() {
		DataLayer layer = new DataLayer(15);

		assertTrue(layer.isDefinitelyHomogenous());
		assertEquals(ServerChunkMemoryAccounting.unknown(), ServerChunkMemoryAccounting.dataLayerBytes(layer));
		assertTrue(layer.isDefinitelyHomogenous());
	}

	@Test
	void materializedLightReportsLayerSize() {
		DataLayer layer = new DataLayer();
		layer.set(0, 0, 0, 1);

		assertFalse(layer.isDefinitelyHomogenous());
		assertEquals(DataLayer.SIZE, ServerChunkMemoryAccounting.dataLayerBytes(layer));
	}

	@Test
	void rawHeightmapStorageUsesLongArraySize() {
		assertEquals(16L, ServerChunkMemoryAccounting.rawArrayBytes(new long[2]));
	}

	@Test
	void blockEntityCountUsesSectionBoundaries() {
		HashMap<BlockPos, Object> entities = new HashMap<>();
		entities.put(new BlockPos(0, 0, 0), new Object());
		entities.put(new BlockPos(0, 16, 0), new Object());
		entities.put(new BlockPos(0, -1, 0), new Object());

		assertEquals(1L, ServerChunkMemoryAccounting.blockEntityCount(entities, 0));
		assertEquals(1L, ServerChunkMemoryAccounting.blockEntityCount(entities, -1));
		assertEquals(1L, ServerChunkMemoryAccounting.blockEntityCount(entities, 1));
	}

	@Test
	void poiRecordsUseTheAlreadyLoadedCollectionOnly() {
		assertEquals(2L, ServerChunkMemoryAccounting.poiRecordCount(java.util.List.of(new Object(), new Object())));
		assertEquals(ServerChunkMemoryAccounting.unknown(), ServerChunkMemoryAccounting.poiCountUnavailable());
	}
}
