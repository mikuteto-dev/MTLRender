package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.client.SectionCompileCachePool;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.Consumer;

@Mixin(ClientChunkCache.class)
abstract class ClientChunkCacheRegionInvalidationMixin {
	@Inject(method = "replaceWithPacketData", at = @At("RETURN"))
	private void mtlrender$invalidatePacketChunk(
		int chunkX,
		int chunkZ,
		FriendlyByteBuf readBuffer,
		Map<Heightmap.Types, long[]> heightmaps,
		Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities,
		CallbackInfoReturnable<LevelChunk> info
	) {
		LevelChunk chunk = info.getReturnValue();
		if (chunk != null) {
			mtlrender$invalidateChunk(chunk);
		}
	}

	@Inject(method = "replaceBiomes", at = @At("RETURN"))
	private void mtlrender$invalidateBiomeChunk(int chunkX, int chunkZ, FriendlyByteBuf readBuffer, CallbackInfo info) {
		ClientChunkCache cache = (ClientChunkCache) (Object) this;
		LevelChunk chunk = cache.getChunk(chunkX, chunkZ, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
		if (chunk != null) {
			mtlrender$invalidateChunk(chunk);
		}
	}

	@Inject(method = "drop", at = @At("HEAD"))
	private void mtlrender$invalidateDroppedChunk(ChunkPos pos, CallbackInfo info) {
		ClientChunkCache cache = (ClientChunkCache) (Object) this;
		LevelChunk chunk = cache.getChunk(pos.x(), pos.z(), net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
		if (chunk != null) {
			mtlrender$invalidateChunk(chunk);
		}
	}

	@Inject(method = "updateViewRadius", at = @At("HEAD"))
	private void mtlrender$invalidateViewChange(int viewRange, CallbackInfo info) {
		SectionCompileCachePool.reset();
	}

	@Unique
	private static void mtlrender$invalidateChunk(LevelChunk chunk) {
		ChunkPos pos = chunk.getPos();
		net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
		for (int index = 0; index < sections.length; index++) {
			SectionCompileCachePool.invalidateSection(
				net.minecraft.core.SectionPos.asLong(pos.x(), chunk.getSectionYFromSectionIndex(index), pos.z())
			);
		}
	}
}
