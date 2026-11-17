package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.client.ClientChunkCompileScheduler;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerCompileMixin {
	@Inject(method = "applyLightData", at = @At("RETURN"))
	private void mtlrender$lightDataApplied(
		int chunkX,
		int chunkZ,
		ClientboundLightUpdatePacketData lightData,
		boolean scheduleRebuild,
		CallbackInfo callbackInfo
	) {
		ClientChunkCompileScheduler.onChunkReady(chunkX, chunkZ);
	}

	@Inject(method = "enableChunkLight", at = @At("RETURN"))
	private void mtlrender$chunkLightEnabled(
		LevelChunk chunk,
		int chunkX,
		int chunkZ,
		CallbackInfo callbackInfo
	) {
		ClientChunkCompileScheduler.onChunkReady(chunkX, chunkZ);
	}
}
