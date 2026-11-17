package dev.mikuteto.mtlrender.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.Test;

class ClientLightBatchTest {
	@Test
	void decodesDataAndEmptySectionsForBothLayers() {
		BitSet skyData = bits(0);
		BitSet skyEmpty = bits(1);
		BitSet blockData = bits(2);
		BitSet blockEmpty = bits(0);
		ClientboundLightUpdatePacketData packet = packet(
			skyData, blockData, skyEmpty, blockEmpty,
			List.of(layer((byte)0xA5)), List.of(layer((byte)0x3C))
		);
		ClientLightBatch batch = new ClientLightBatch(Runnable::run, 8);

		assertTrue(batch.submit(3, 4, packet, -1, 3, true, false));
		List<LightLayer> layers = new ArrayList<>();
		List<Integer> values = new ArrayList<>();
		List<Boolean> empty = new ArrayList<>();
		assertEquals(1, batch.drain(1, result -> {
			assertTrue(result.scheduleRebuild());
			assertFalse(result.enableChunkLight());
			for (int index = 0; index < result.sectionCount(); index++) {
				DataLayer data = result.section(index).data();
				layers.add(result.section(index).layer());
				values.add(data.get(0, 0, 0));
				empty.add(data.isEmpty());
			}
		}));

		assertEquals(4, layers.size());
		assertEquals(LightLayer.SKY, layers.get(0));
		assertEquals(LightLayer.SKY, layers.get(1));
		assertEquals(LightLayer.BLOCK, layers.get(2));
		assertEquals(LightLayer.BLOCK, layers.get(3));
		assertEquals(5, values.get(0));
		assertTrue(empty.get(1));
		assertTrue(empty.get(2));
		assertEquals(12, values.get(3));
		batch.close();
	}

	@Test
	void preservesMixedChunkPacketOrder() {
		ClientLightBatch batch = new ClientLightBatch(Runnable::run, 8);
		assertTrue(batch.submit(1, 2, packet(bits(0), new BitSet(), new BitSet(), new BitSet(), List.of(layer((byte)1)), List.of()), 0, 1, true, true));
		assertTrue(batch.submit(5, 6, packet(new BitSet(), bits(0), new BitSet(), new BitSet(), List.of(), List.of(layer((byte)2))), 0, 1, true, false));
		List<Long> chunks = new ArrayList<>();
		List<Boolean> initial = new ArrayList<>();
		assertEquals(2, batch.drain(2, result -> {
			chunks.add(net.minecraft.world.level.ChunkPos.pack(result.chunkX(), result.chunkZ()));
			initial.add(result.enableChunkLight());
		}));
		assertEquals(List.of(net.minecraft.world.level.ChunkPos.pack(1, 2), net.minecraft.world.level.ChunkPos.pack(5, 6)), chunks);
		assertEquals(List.of(true, false), initial);
		batch.close();
	}

	@Test
	void handlesEmptyMasksAndResetGeneration() {
		ClientLightBatch batch = new ClientLightBatch(Runnable::run, 4);
		ClientboundLightUpdatePacketData empty = packet(new BitSet(), new BitSet(), new BitSet(), new BitSet(), List.of(), List.of());
		assertTrue(batch.submit(0, 0, empty, 0, 2, true, false));
		assertEquals(1, batch.drain(1, result -> assertEquals(0, result.sectionCount())));
		long oldGeneration = batch.generation();
		batch.reset();
		assertTrue(batch.generation() != oldGeneration);
		assertEquals(0, batch.pending());
		assertTrue(batch.submit(7, 8, empty, 0, 2, false, false));
		assertEquals(1, batch.drain(1, result -> assertEquals(7, result.chunkX())));
		batch.close();
	}

	private static ClientboundLightUpdatePacketData packet(
		BitSet skyData,
		BitSet blockData,
		BitSet skyEmpty,
		BitSet blockEmpty,
		List<byte[]> skyUpdates,
		List<byte[]> blockUpdates
	) {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		buffer.writeBitSet(skyData);
		buffer.writeBitSet(blockData);
		buffer.writeBitSet(skyEmpty);
		buffer.writeBitSet(blockEmpty);
		buffer.writeCollection(skyUpdates, (out, bytes) -> out.writeByteArray(bytes));
		buffer.writeCollection(blockUpdates, (out, bytes) -> out.writeByteArray(bytes));
		ClientboundLightUpdatePacketData packet = new ClientboundLightUpdatePacketData(buffer, 0, 0);
		buffer.release();
		return packet;
	}

	private static BitSet bits(int index) {
		BitSet bits = new BitSet();
		bits.set(index);
		return bits;
	}

	private static byte[] layer(byte value) {
		byte[] data = new byte[DataLayer.SIZE];
		data[0] = value;
		return data;
	}
}
