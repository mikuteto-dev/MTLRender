package dev.mikuteto.mtlrender.mixin;

import java.util.Deque;

import dev.mikuteto.mtlrender.client.ClientChunkCompileScheduler;
import dev.mikuteto.mtlrender.client.ClientLightBatch;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class ClientLevelLightBatchMixin {
	@Shadow @Final private Deque<Runnable> lightUpdateQueue;

	@Inject(method = "pollLightUpdates", at = @At("HEAD"), cancellable = true)
	private void mtlrender$pollLightUpdates(CallbackInfo callbackInfo) {
		ClientLightBatch batch = ClientLightBatch.current();
		int size = this.lightUpdateQueue.size() + batch.pending();
		int lightUpdatesPerFrame = size < 1000 ? Math.max(10, size / 10) : size;
		for (int index = 0; index < lightUpdatesPerFrame; index++) {
			Runnable update = this.lightUpdateQueue.poll();
			if (update != null) {
				update.run();
			} else if (batch.drain(1, this::mtlrender$applyLightBatch) == 0) {
				break;
			}
		}
		callbackInfo.cancel();
	}

	private void mtlrender$applyLightBatch(ClientLightBatch.Result result) {
		ClientLevel level = (ClientLevel)(Object)this;
		LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
		for (int index = 0; index < result.sectionCount(); index++) {
			ClientLightBatch.LightSection section = result.section(index);
			SectionPos sectionPos = SectionPos.of(section.sectionKey());
			lightEngine.queueSectionData(section.layer(), sectionPos, section.data());
			if (result.scheduleRebuild()) {
				level.setSectionDirtyWithNeighbors(result.chunkX(), sectionPos.getY(), result.chunkZ());
			}
		}
		lightEngine.setLightEnabled(new ChunkPos(result.chunkX(), result.chunkZ()), true);
		if (result.enableChunkLight()) {
			this.mtlrender$enableChunkLight(level, lightEngine, result.chunkX(), result.chunkZ());
		}
		ClientChunkCompileScheduler.onChunkReady(result.chunkX(), result.chunkZ());
	}

	private void mtlrender$enableChunkLight(
		ClientLevel level,
		LevelLightEngine lightEngine,
		int chunkX,
		int chunkZ
	) {
		ClientChunkCache chunkSource = level.getChunkSource();
		LevelChunk chunk = chunkSource.getChunk(chunkX, chunkZ, false);
		if (chunk == null) {
			return;
		}
		net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
		for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
			net.minecraft.world.level.chunk.LevelChunkSection section = sections[sectionIndex];
			int sectionY = level.getSectionYFromSectionIndex(sectionIndex);
			lightEngine.updateSectionStatus(SectionPos.of(chunkX, sectionY, chunkZ), section.hasOnlyAir());
		}
		level.setSectionRangeDirty(
			chunkX - 1,
			level.getMinSectionY(),
			chunkZ - 1,
			chunkX + 1,
			level.getMaxSectionY(),
			chunkZ + 1
		);
	}
}
