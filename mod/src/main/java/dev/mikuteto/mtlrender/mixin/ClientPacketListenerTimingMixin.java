package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.debug.ChunkTiming;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerTimingMixin {
	@Unique
	private long mtlrender$packetStart;

	@Unique
	private long mtlrender$packetKey;

	@Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"))
	private void mtlrender$startPacket(ClientboundLevelChunkWithLightPacket packet, CallbackInfo callbackInfo) {
		mtlrender$packetStart = System.nanoTime();
		mtlrender$packetKey = new net.minecraft.world.level.ChunkPos(packet.getX(), packet.getZ()).pack();
	}

	@Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
	private void mtlrender$finishPacket(ClientboundLevelChunkWithLightPacket packet, CallbackInfo callbackInfo) {
		ChunkTiming.recordChunkDuration(ChunkTiming.Stage.CLIENT_PACKET, mtlrender$packetKey, System.nanoTime() - mtlrender$packetStart);
		mtlrender$packetStart = 0L;
	}
}
