package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.chunk.ClientChunkMemoryLedger;
import dev.mikuteto.mtlrender.debug.ClientChunkMemorySource;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkCache.class)
abstract class ClientChunkCacheMixin implements ClientChunkMemoryLedgerAccess {
	@Unique
	private final ClientChunkMemoryLedger mtlrender$clientChunkMemoryLedger = new ClientChunkMemoryLedger();
	@Unique
	private int mtlrender$viewCenterX;
	@Unique
	private int mtlrender$viewCenterZ;

	@Override
	public ClientChunkMemoryLedger mtlrender$clientChunkMemoryLedger() {
		return mtlrender$clientChunkMemoryLedger;
	}

	@Inject(method = "drop", at = @At("HEAD"))
	private void mtlrender$drop(ChunkPos pos, CallbackInfo callbackInfo) {
		ClientChunkCache cache = (ClientChunkCache)(Object)this;
		if (cache.getChunk(pos.x(), pos.z(), net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) != null) {
			mtlrender$clientChunkMemoryLedger.removeChunk(pos.pack());
		}
	}

	@Inject(method = "replaceWithPacketData", at = @At("HEAD"))
	private void mtlrender$beginPacketMemorySample(
		int chunkX,
		int chunkZ,
		FriendlyByteBuf readBuffer,
		java.util.Map<net.minecraft.world.level.levelgen.Heightmap.Types, long[]> heightmaps,
		java.util.function.Consumer<net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities,
		CallbackInfoReturnable<LevelChunk> callbackInfo
	) {
		long heightmapBytes = 0L;
		for (long[] values : heightmaps.values()) {
			if (values != null && Long.MAX_VALUE - heightmapBytes >= (long)values.length * Long.BYTES) {
				heightmapBytes += (long)values.length * Long.BYTES;
			}
		}
		ClientChunkMemorySource.global().recordPacketTransient(readBuffer.readableBytes(), heightmapBytes);
	}

	@Inject(method = "replaceWithPacketData", at = @At("RETURN"))
	private void mtlrender$replaceWithPacketData(
		int chunkX,
		int chunkZ,
		FriendlyByteBuf readBuffer,
		java.util.Map<net.minecraft.world.level.levelgen.Heightmap.Types, long[]> heightmaps,
		java.util.function.Consumer<net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities,
		CallbackInfoReturnable<LevelChunk> callbackInfo
	) {
		LevelChunk chunk = callbackInfo.getReturnValue();
		if (chunk != null) {
			mtlrender$replaceChunk(chunk);
		}
	}

	@Inject(method = "replaceBiomes", at = @At("HEAD"))
	private void mtlrender$replaceBiomes(int chunkX, int chunkZ, FriendlyByteBuf readBuffer, CallbackInfo callbackInfo) {
		ClientChunkCache cache = (ClientChunkCache)(Object)this;
		LevelChunk chunk = cache.getChunk(chunkX, chunkZ, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
		if (chunk != null) {
			mtlrender$replaceSections(chunk);
		}
	}

	@Inject(method = "updateViewCenter", at = @At("HEAD"))
	private void mtlrender$updateViewCenter(int x, int z, CallbackInfo callbackInfo) {
		mtlrender$viewCenterX = x;
		mtlrender$viewCenterZ = z;
	}

	@Inject(method = "updateViewRadius", at = @At("HEAD"))
	private void mtlrender$updateViewRadius(int viewRange, CallbackInfo callbackInfo) {
		int radius = Math.max(2, viewRange) + 3;
		mtlrender$clientChunkMemoryLedger.evictOutside(mtlrender$viewCenterX, mtlrender$viewCenterZ, radius);
	}

	@Unique
	private void mtlrender$replaceChunk(LevelChunk chunk) {
		ChunkPos pos = chunk.getPos();
		mtlrender$clientChunkMemoryLedger.replaceChunk(pos.pack());
		mtlrender$replaceSections(chunk);
	}

	@Unique
	private void mtlrender$replaceSections(LevelChunk chunk) {
		ChunkPos pos = chunk.getPos();
		net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
		for (int index = 0; index < sections.length; index++) {
			mtlrender$clientChunkMemoryLedger.replaceSection(
				SectionPos.asLong(pos.x(), chunk.getSectionYFromSectionIndex(index), pos.z()), pos.pack());
		}
	}
}
