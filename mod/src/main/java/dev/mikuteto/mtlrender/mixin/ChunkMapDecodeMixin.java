package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.server.ChunkLoadPhase;
import dev.mikuteto.mtlrender.server.ChunkLoadTrace;
import dev.mikuteto.mtlrender.server.decode.ChunkDecodeExecutor;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
abstract class ChunkMapDecodeMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@ModifyArg(
		method = "scheduleChunkLoad",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/concurrent/CompletableFuture;thenApplyAsync(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
			ordinal = 0
		),
		index = 0,
		require = 0
	)
	private Function<Optional<SerializableChunkData>, Optional<SerializableChunkData>> mtlrender$timeDecode(
		Function<Optional<SerializableChunkData>, Optional<SerializableChunkData>> function
	) {
		return data -> {
			try (ChunkLoadTrace.Scope ignored = ChunkLoadTrace.begin(ChunkLoadPhase.NBT_DECODE)) {
				return function.apply(data);
			}
		};
	}

	@ModifyArg(
		method = "scheduleChunkLoad",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/concurrent/CompletableFuture;thenApplyAsync(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
			ordinal = 0
		),
		index = 1,
		require = 0
	)
	private Executor mtlrender$decodeExecutor(Executor vanilla) {
		return ChunkDecodeExecutor.forChunkMap((ChunkMap) (Object) this, this.level.getServer());
	}

	@ModifyArg(
		method = "scheduleChunkLoad",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/concurrent/CompletableFuture;thenApplyAsync(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
			ordinal = 1
		),
		index = 0,
		require = 0
	)
	private Function<Optional<SerializableChunkData>, ChunkAccess> mtlrender$timeMainApply(
		Function<Optional<SerializableChunkData>, ChunkAccess> function
	) {
		return data -> {
			ChunkLoadTrace.Scope scope = ChunkLoadTrace.begin(ChunkLoadPhase.MAIN_THREAD_APPLY);
			try {
				return function.apply(data);
			} finally {
				scope.close();
			}
		};
	}

	@Inject(method = "close", at = @At("TAIL"), require = 0)
	private void mtlrender$closeDecodeExecutor(CallbackInfo ci) {
		ChunkDecodeExecutor.release((ChunkMap) (Object) this, this.level.getServer());
	}
}
