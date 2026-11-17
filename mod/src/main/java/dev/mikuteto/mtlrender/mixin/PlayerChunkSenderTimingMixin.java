package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mikuteto.mtlrender.debug.ChunkTiming;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerChunkSender.class)
abstract class PlayerChunkSenderTimingMixin {
	@Unique
	private long mtlrender$batchStart;

	@Unique
	private long mtlrender$collectStart;

	@WrapOperation(
		method = "sendNextChunks",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/PlayerChunkSender;sendChunk(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;)V")
	)
	private void mtlrender$timeChunk(
		ServerGamePacketListenerImpl connection,
		ServerLevel level,
		LevelChunk chunk,
		Operation<Void> original
	) {
		long start = System.nanoTime();
		original.call(connection, level, chunk);
		ChunkTiming.recordChunkDuration(ChunkTiming.Stage.SERVER_SEND, chunk.getPos().pack(), System.nanoTime() - start);
	}

	@WrapOperation(
		method = "sendNextChunks",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerCommonPacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V")
	)
	private void mtlrender$timeBatch(
		ServerCommonPacketListenerImpl connection,
		Packet<?> packet,
		Operation<Void> original
	) {
		if (packet instanceof ClientboundChunkBatchStartPacket) {
			mtlrender$batchStart = System.nanoTime();
		}
		if (packet instanceof ClientboundChunkBatchFinishedPacket finished) {
			ChunkTiming.recordBatch(finished.batchSize(), System.nanoTime() - mtlrender$batchStart);
		}
		original.call(connection, packet);
	}

	@Inject(method = "collectChunksToSend", at = @At("HEAD"))
	private void mtlrender$startCollect(ChunkMap chunkMap, ChunkPos playerPos, CallbackInfo callbackInfo) {
		mtlrender$collectStart = System.nanoTime();
	}

	@Inject(method = "collectChunksToSend", at = @At("RETURN"))
	private void mtlrender$finishCollect(ChunkMap chunkMap, ChunkPos playerPos, CallbackInfo callbackInfo) {
		ChunkTiming.recordChunkDuration(ChunkTiming.Stage.SERVER_COLLECT, 0L, System.nanoTime() - mtlrender$collectStart);
		mtlrender$collectStart = 0L;
	}

	@Inject(method = "onChunkBatchReceivedByClient", at = @At("HEAD"))
	private void mtlrender$recordAcknowledgement(float desiredChunksPerTick, CallbackInfo callbackInfo) {
		ChunkTiming.recordAcknowledgement(desiredChunksPerTick);
	}
}
