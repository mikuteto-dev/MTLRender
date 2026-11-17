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

class ClientChunkMemoryAccountingTest {
	@BeforeAll
	static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	@Test
	void emptyAndSingleValuePalettesReportOnlyTheirStorage() {
		PalettedContainer<?> palette = new PalettedContainer<>(
			Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY));

		ClientChunkMemorySnapshot.Palette result = ClientChunkMemoryAccounting.palette(palette, true);

		assertEquals(ClientChunkMemorySnapshot.Mode.SINGLE_VALUE, result.mode());
		assertEquals(1L, result.entryCount());
		assertEquals(0L, result.storageBytes());
	}

	@Test
	void multipleBlockValuesReportLinearPaletteAndBitStorage() {
		PalettedContainer<net.minecraft.world.level.block.state.BlockState> palette = new PalettedContainer<>(
			Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY));
		palette.set(0, 0, 0, Blocks.STONE.defaultBlockState());
		palette.set(1, 0, 0, Blocks.DIRT.defaultBlockState());

		ClientChunkMemorySnapshot.Palette result = ClientChunkMemoryAccounting.palette(palette, true);

		assertEquals(ClientChunkMemorySnapshot.Mode.LINEAR, result.mode());
		assertEquals(3L, result.entryCount());
		assertEquals(2048L, result.storageBytes());
	}

	@Test
	void rawHeightmapAccountingUsesPackedLongArraySize() {
		assertEquals(16L, ClientChunkMemoryAccounting.rawArrayBytes(new long[2]));
	}

	@Test
	void homogeneousLightRemainsUnmaterializedAfterSampling() {
		DataLayer layer = new DataLayer(15);

		assertTrue(layer.isDefinitelyHomogenous());
		assertEquals(ClientChunkMemorySnapshot.UNKNOWN, ClientChunkMemoryAccounting.dataLayerBytes(layer));
		assertTrue(layer.isDefinitelyHomogenous());
	}

	@Test
	void materializedLightReportsBytes() {
		DataLayer layer = new DataLayer();
		layer.set(0, 0, 0, 1);

		assertFalse(layer.isDefinitelyHomogenous());
		assertEquals(DataLayer.SIZE, ClientChunkMemoryAccounting.dataLayerBytes(layer));
	}

	@Test
	void blockEntityCountIsBoundedBySection() {
		HashMap<BlockPos, Object> entities = new HashMap<>();
		entities.put(new BlockPos(0, 0, 0), new Object());
		entities.put(new BlockPos(0, 16, 0), new Object());
		entities.put(new BlockPos(0, -1, 0), new Object());

		assertEquals(1L, ClientChunkMemoryAccounting.blockEntityCount(entities, 0));
		assertEquals(1L, ClientChunkMemoryAccounting.blockEntityCount(entities, -1));
		assertEquals(1L, ClientChunkMemoryAccounting.blockEntityCount(entities, 1));
	}
}
