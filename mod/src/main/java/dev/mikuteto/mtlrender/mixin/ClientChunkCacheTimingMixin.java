package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mikuteto.mtlrender.debug.ChunkTiming;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
abstract class ClientChunkCacheTimingMixin {
	@WrapOperation(
		method = "replaceWithPacketData",
		at = @At(value = "NEW", target = "net/minecraft/world/level/chunk/LevelChunk")
	)
	private LevelChunk mtlrender$timeConstruction(Level level, ChunkPos pos, Operation<LevelChunk> original) {
		long start = System.nanoTime();
		LevelChunk chunk = original.call(level, pos);
		ChunkTiming.recordChunkDuration(ChunkTiming.Stage.CLIENT_CHUNK_CONSTRUCTION, pos.pack(), System.nanoTime() - start);
		return chunk;
	}

	@Inject(method = "onLightUpdate", at = @At("HEAD"))
	private void mtlrender$completeLightUpdate(LightLayer layer, SectionPos pos, CallbackInfo callbackInfo) {
		ChunkTiming.completeLightQueue(pos.asLong(), System.nanoTime());
	}
}
