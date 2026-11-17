package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.server.ChunkLoadPhase;
import dev.mikuteto.mtlrender.server.ChunkLoadTrace;
import dev.mikuteto.mtlrender.server.io.ParallelRegionReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.util.thread.PriorityConsecutiveExecutor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;

@Mixin(IOWorker.class)
abstract class IOWorkerParallelReadMixin {
	@Shadow @Final private AtomicBoolean shutdownRequested;
	@Shadow @Final private PriorityConsecutiveExecutor consecutiveExecutor;
	@Shadow @Final private RegionFileStorage storage;
	@Shadow @Final private SequencedMap<ChunkPos, PendingStore> pendingWrites;
	@Shadow @Final private static Logger LOGGER;

	@Shadow
	abstract <T> CompletableFuture<T> submitThrowingTask(ThrowingSupplier<T> task);

	@Shadow
	abstract void runStore(ChunkPos pos, PendingStore write);

	@Shadow
	abstract void tellStorePending();

	@Shadow
	public abstract void close() throws IOException;

	@Unique
	private ParallelRegionReader mtlrender$parallelReader;
	@Unique
	private boolean mtlrender$parallelReaderDisabled = true;
	@Unique
	private boolean mtlrender$parallelReaderClosed;

	@Inject(
		method = "loadAsync",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/chunk/storage/IOWorker;submitThrowingTask(Lnet/minecraft/world/level/chunk/storage/IOWorker$ThrowingSupplier;)Ljava/util/concurrent/CompletableFuture;"
		),
		cancellable = true
	)
	private void mtlrender$submitRead(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> callbackInfo) {
		if (this.mtlrender$parallelReaderDisabled || this.mtlrender$parallelReader == null) {
			return;
		}
		CompletableFuture<Optional<CompoundTag>> result = this.consecutiveExecutor.scheduleWithResult(0, future -> {
			if (this.shutdownRequested.get()) {
				return;
			}
			PendingStore pendingStore = this.pendingWrites.get(pos);
			if (pendingStore != null) {
				future.complete(Optional.ofNullable(pendingStore.copyData()));
				this.tellStorePending();
				return;
			}
			try {
				ChunkLoadTrace.Scope scope = ChunkLoadTrace.begin(pos, ChunkLoadPhase.REGION_READ);
				CompletableFuture<Optional<CompoundTag>> read = this.mtlrender$parallelReader.loadAsync(pos);
				read.whenComplete((value, failure) -> {
					scope.close();
					if (failure == null) {
						future.complete(value);
					} else {
						Throwable cause = failure instanceof CompletionException && failure.getCause() != null
							? failure.getCause() : failure;
						LOGGER.warn("Failed to read chunk {}", pos, cause);
						future.completeExceptionally(cause);
					}
				});
			} catch (Throwable failure) {
				LOGGER.warn("Failed to read chunk {}", pos, failure);
				future.completeExceptionally(failure);
			}
			this.tellStorePending();
		});
		callbackInfo.setReturnValue(result);
	}

	@Redirect(
		method = "runStore(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/storage/IOWorker$PendingStore;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/chunk/storage/RegionFileStorage;write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)V"
		)
	)
	private void mtlrender$coordinateStore(
		RegionFileStorage storage,
		ChunkPos pos,
		CompoundTag data
	) throws IOException {
		if (this.mtlrender$parallelReaderDisabled || this.mtlrender$parallelReader == null) {
			storage.write(pos, data);
			return;
		}
		try {
			this.mtlrender$parallelReader.coordinateChecked(pos, () -> {
				storage.write(pos, data);
				return null;
			}).join();
		} catch (CompletionException failure) {
			Throwable cause = failure.getCause();
			if (cause instanceof IOException io) {
				throw io;
			}
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw failure;
		}
	}

	@Inject(
		method = "close",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/chunk/storage/RegionFileStorage;close()V"
		)
	)
	private void mtlrender$closeReaderBeforeStorage(CallbackInfo callbackInfo) {
		if (!this.mtlrender$parallelReaderClosed && this.mtlrender$parallelReader != null) {
			this.mtlrender$parallelReaderClosed = true;
			try {
				this.mtlrender$parallelReader.close();
			} catch (IOException failure) {
				LOGGER.error("Failed to close storage", failure);
			}
		}
	}

	@Inject(method = "<init>(Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Ljava/nio/file/Path;Z)V", at = @At("RETURN"))
	private void mtlrender$initializeReader(
		RegionStorageInfo info,
		Path folder,
		boolean sync,
		CallbackInfo callbackInfo
	) {
		try {
			this.mtlrender$parallelReader = new ParallelRegionReader(info, folder, sync);
			this.mtlrender$parallelReaderDisabled = false;
		} catch (RuntimeException | LinkageError failure) {
			this.mtlrender$parallelReader = null;
			this.mtlrender$parallelReaderDisabled = true;
		}
	}

	private abstract static class PendingStore {
		@Shadow
		abstract CompoundTag copyData();
	}

	@FunctionalInterface
	private interface ThrowingSupplier<T> {
		T get() throws Exception;
	}
}
