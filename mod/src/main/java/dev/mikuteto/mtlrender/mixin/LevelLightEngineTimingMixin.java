package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.debug.ChunkTiming;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelLightEngine.class)
abstract class LevelLightEngineTimingMixin {
	@Unique
	private long mtlrender$lightUpdatesStart;

	@Inject(method = "queueSectionData", at = @At("HEAD"))
	private void mtlrender$recordQueue(
		net.minecraft.world.level.LightLayer layer,
		SectionPos pos,
		DataLayer data,
		CallbackInfo callbackInfo
	) {
		ChunkTiming.recordLightQueue(pos.asLong(), System.nanoTime());
	}

	@Inject(method = "runLightUpdates", at = @At("HEAD"))
	private void mtlrender$startUpdates(CallbackInfoReturnable<Integer> callbackInfo) {
		mtlrender$lightUpdatesStart = System.nanoTime();
	}

	@Inject(method = "runLightUpdates", at = @At("RETURN"))
	private void mtlrender$finishUpdates(CallbackInfoReturnable<Integer> callbackInfo) {
		if (callbackInfo.getReturnValue() > 0) {
			ChunkTiming.recordLightUpdates(System.nanoTime() - mtlrender$lightUpdatesStart);
		}
		mtlrender$lightUpdatesStart = 0L;
	}
}
