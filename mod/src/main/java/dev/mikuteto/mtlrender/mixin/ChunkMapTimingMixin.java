package dev.mikuteto.mtlrender.mixin;

import java.util.concurrent.CompletableFuture;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mikuteto.mtlrender.debug.ChunkTiming;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkMap.class)
abstract class ChunkMapTimingMixin {
	@WrapMethod(method = "scheduleChunkLoad")
	private CompletableFuture<ChunkAccess> mtlrender$timeChunkLoad(
		ChunkPos pos,
		Operation<CompletableFuture<ChunkAccess>> original
	) {
		long startedAt = System.nanoTime();
		CompletableFuture<ChunkAccess> future;
		try {
			future = original.call(pos);
		} catch (Throwable throwable) {
			ChunkTiming.recordDuration(ChunkTiming.Stage.SERVER_DISK_LOAD, pos.pack(), startedAt, System.nanoTime());
			throw throwable;
		}
		future.whenComplete((ignored, throwable) -> ChunkTiming.recordDuration(
			ChunkTiming.Stage.SERVER_DISK_LOAD, pos.pack(), startedAt, System.nanoTime()));
		return future;
	}

	@WrapMethod(method = "applyStep")
	private CompletableFuture<ChunkAccess> mtlrender$timeStatusStep(
		GenerationChunkHolder chunkHolder,
		ChunkStep step,
		StaticCache2D<GenerationChunkHolder> cache,
		Operation<CompletableFuture<ChunkAccess>> original
	) {
		long chunkKey = chunkHolder.getPos().pack();
		if (step.targetStatus() == ChunkStatus.EMPTY) {
			return original.call(chunkHolder, step, cache);
		}
		long startedAt = System.nanoTime();
		CompletableFuture<ChunkAccess> future;
		try {
			future = original.call(chunkHolder, step, cache);
		} catch (Throwable throwable) {
			ChunkTiming.recordDuration(ChunkTiming.Stage.SERVER_GENERATION_STATUS, chunkKey, startedAt, System.nanoTime());
			throw throwable;
		}
		future.whenComplete((ignored, throwable) -> ChunkTiming.recordDuration(
			ChunkTiming.Stage.SERVER_GENERATION_STATUS, chunkKey, startedAt, System.nanoTime()));
		return future;
	}

	@WrapOperation(
		method = "runGenerationTask",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkGenerationTask;runUntilWait()Ljava/util/concurrent/CompletableFuture;")
	)
	private CompletableFuture<?> mtlrender$timeGenerationTask(
		ChunkGenerationTask task,
		Operation<CompletableFuture<?>> original
	) {
		long startedAt = System.nanoTime();
		long chunkKey = task.getCenter().getPos().pack();
		CompletableFuture<?> future;
		try {
			future = original.call(task);
		} catch (Throwable throwable) {
			ChunkTiming.recordDuration(ChunkTiming.Stage.SERVER_GENERATION_STATUS, chunkKey, startedAt, System.nanoTime());
			throw throwable;
		}
		if (future == null) {
			ChunkTiming.recordDuration(ChunkTiming.Stage.SERVER_GENERATION_STATUS, chunkKey, startedAt, System.nanoTime());
		} else {
			future.whenComplete((ignored, throwable) -> ChunkTiming.recordDuration(
				ChunkTiming.Stage.SERVER_GENERATION_STATUS, chunkKey, startedAt, System.nanoTime()));
		}
		return future;
	}

	@WrapMethod(method = "prepareTickingChunk")
	private CompletableFuture<ChunkResult<LevelChunk>> mtlrender$timeChunkReadiness(
		ChunkHolder chunkHolder,
		Operation<CompletableFuture<ChunkResult<LevelChunk>>> original
	) {
		long startedAt = System.nanoTime();
		long chunkKey = chunkHolder.getPos().pack();
		CompletableFuture<ChunkResult<LevelChunk>> future;
		try {
			future = original.call(chunkHolder);
		} catch (Throwable throwable) {
			ChunkTiming.recordDuration(ChunkTiming.Stage.SERVER_SEND, chunkKey, startedAt, System.nanoTime());
			throw throwable;
		}
		future.whenComplete((ignored, throwable) -> ChunkTiming.recordDuration(
			ChunkTiming.Stage.SERVER_SEND, chunkKey, startedAt, System.nanoTime()));
		return future;
	}
}
