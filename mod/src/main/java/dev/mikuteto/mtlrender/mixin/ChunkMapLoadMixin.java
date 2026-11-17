package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mikuteto.mtlrender.chunk.ChunkLoadMarkers;
import dev.mikuteto.mtlrender.chunk.ChunkLoadParallelism;
import dev.mikuteto.mtlrender.chunk.ServerChunkMemoryLedger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkMap.class)
abstract class ChunkMapLoadMixin {
	@Shadow
	private ServerLevel level;

	@Inject(method = "onFullChunkStatusChange", at = @At("TAIL"))
	private void mtlrender$recordStatus(ChunkPos pos, FullChunkStatus status, CallbackInfo callbackInfo) {
		if (enabled()) {
			ServerChunkMemoryLedger.global().observeStatus(dimension(), pos.pack(), status);
		}
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void mtlrender$recordTick(java.util.function.BooleanSupplier haveTime, CallbackInfo callbackInfo) {
		observe();
	}

	@Inject(method = "processUnloads", at = @At("TAIL"))
	private void mtlrender$recordUnloads(java.util.function.BooleanSupplier haveTime, CallbackInfo callbackInfo) {
		observe();
	}

	@Inject(method = "scheduleUnload", at = @At("HEAD"))
	private void mtlrender$recordScheduledUnload(long pos, ChunkHolder chunkHolder, CallbackInfo callbackInfo) {
		if (enabled()) {
			ServerChunkMemoryLedger.global().unloadCurrent(dimension(), pos);
		}
	}

	@Inject(method = "onChunkReadyToSend", at = @At("TAIL"))
	private void mtlrender$recordReady(ChunkHolder chunkHolder, net.minecraft.world.level.chunk.LevelChunk chunk, CallbackInfo callbackInfo) {
		observe();
	}

	@Inject(method = "saveChunkIfNeeded", at = @At("RETURN"))
	private void mtlrender$recordConditionalSave(ChunkHolder chunkHolder, long now, CallbackInfoReturnable<Boolean> callbackInfo) {
		observe();
	}

	@Inject(method = "save", at = @At("RETURN"))
	private void mtlrender$recordSave(ChunkAccess chunk, CallbackInfoReturnable<Boolean> callbackInfo) {
		observe();
	}

	private boolean enabled() {
		return Boolean.getBoolean("mtlrender.memory.integratedServer") && level.getServer() instanceof IntegratedServer;
	}

	private String dimension() {
		return level.dimension().identifier().toString();
	}

	private void observe() {
		if (enabled()) {
			ServerChunkMemoryLedger.global().snapshot();
		}
	}
	@WrapOperation(
		method = "getChunkRangeFuture",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/util/Util;sequence(Ljava/util/List;)Ljava/util/concurrent/CompletableFuture;"
		)
	)
	private CompletableFuture<List<ChunkResult<ChunkAccess>>> mtlrender$parallelizeRangeFanIn(
		List<CompletableFuture<ChunkResult<ChunkAccess>>> futures,
		Operation<CompletableFuture<List<ChunkResult<ChunkAccess>>>> original
	) {
		List<Callable<ChunkResult<ChunkAccess>>> jobs = new ArrayList<>(futures.size());
		for (CompletableFuture<ChunkResult<ChunkAccess>> future : futures) {
			jobs.add(() -> future == null ? null : future.join());
		}
		CompletableFuture<List<ChunkResult<ChunkAccess>>> aggregated = ChunkLoadParallelism.shared().submitBatch(jobs);
		return ChunkLoadMarkers.observe(ChunkLoadMarkers.Phase.RANGE_FAN_IN, futures.size(), aggregated);
	}
}
