package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.chunk.ServerChunkMemoryLedger;
import dev.mikuteto.mtlrender.debug.ServerChunkMemorySource;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheMixin {
	@Shadow
	private ServerLevel level;

	@Inject(method = "tick", at = @At("TAIL"))
	private void mtlrender$recordTick(BooleanSupplier haveTime, boolean tickChunks, CallbackInfo callbackInfo) {
		observe();
		ServerChunkMemorySource.global().maybeSample(level);
	}

	@Inject(method = "save", at = @At("TAIL"))
	private void mtlrender$recordSave(boolean flushStorage, CallbackInfo callbackInfo) {
		observe();
	}

	@Inject(method = "close", at = @At("TAIL"))
	private void mtlrender$recordClose(CallbackInfo callbackInfo) throws IOException {
		observe();
	}

	@Inject(method = "onChunkReadyToSend", at = @At("TAIL"))
	private void mtlrender$recordChunkReady(ChunkHolder chunk, CallbackInfo callbackInfo) {
		observe();
	}

	@Inject(method = "getPendingTasksCount", at = @At("RETURN"))
	private void mtlrender$recordPendingTasks(CallbackInfoReturnable<Integer> callbackInfo) {
		observe();
	}

	@Inject(method = "getLoadedChunksCount", at = @At("RETURN"))
	private void mtlrender$recordLoadedChunks(CallbackInfoReturnable<Integer> callbackInfo) {
		observe();
	}

	private void observe() {
		if (Boolean.getBoolean("mtlrender.memory.integratedServer") && level.getServer() instanceof IntegratedServer) {
			ServerChunkMemoryLedger.global().snapshot();
		}
	}
}
