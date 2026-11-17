package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.client.ClientLightBatch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerLightBatchMixin {
	@Shadow @Final protected Minecraft minecraft;
	@Shadow private ClientLevel level;
	@Shadow abstract void updateLevelChunk(int chunkX, int chunkZ, ClientboundLevelChunkPacketData chunkData);

	@Unique
	private final ClientLightBatch mtlrender$lightBatch = ClientLightBatch.current();

	@Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"), cancellable = true)
	private void mtlrender$batchChunkLight(ClientboundLevelChunkWithLightPacket packet, CallbackInfo callbackInfo) {
		if (!this.minecraft.packetProcessor().isSameThread() || this.level == null) {
			return;
		}
		int chunkX = packet.getX();
		int chunkZ = packet.getZ();
		if (this.mtlrender$lightBatch.submitInitial(
			chunkX,
			chunkZ,
			packet.getLightData(),
			this.level.getChunkSource().getLightEngine().getMinLightSection(),
			this.level.getChunkSource().getLightEngine().getLightSectionCount(),
			() -> this.updateLevelChunk(chunkX, chunkZ, packet.getChunkData())
		)) {
			callbackInfo.cancel();
		}
	}

	@Inject(method = "handleLightUpdatePacket", at = @At("HEAD"), cancellable = true)
	private void mtlrender$batchLightUpdate(ClientboundLightUpdatePacket packet, CallbackInfo callbackInfo) {
		if (!this.minecraft.packetProcessor().isSameThread() || this.level == null) {
			return;
		}
		if (this.mtlrender$lightBatch.submit(
			packet.getX(),
			packet.getZ(),
			packet.getLightData(),
			this.level.getChunkSource().getLightEngine().getMinLightSection(),
			this.level.getChunkSource().getLightEngine().getLightSectionCount(),
			true,
			false
		)) {
			callbackInfo.cancel();
		}
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void mtlrender$resetLightBatch(CallbackInfo callbackInfo) {
		this.mtlrender$lightBatch.reset();
	}
}
