package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.debug.ChunkTiming;
import net.minecraft.client.multiplayer.ChunkBatchSizeCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkBatchSizeCalculator.class)
abstract class ChunkBatchSizeCalculatorTimingMixin {
	@Inject(method = "onBatchStart", at = @At("HEAD"))
	private void mtlrender$onBatchStart(CallbackInfo callbackInfo) {
		ChunkTiming.recordCalculatorBatchStart();
	}

	@Inject(method = "onBatchFinished", at = @At("HEAD"))
	private void mtlrender$onBatchFinished(int batchSize, CallbackInfo callbackInfo) {
		ChunkTiming.recordCalculatorBatchFinished(batchSize);
	}

	@Inject(method = "getDesiredChunksPerTick", at = @At("RETURN"))
	private void mtlrender$getDesiredChunksPerTick(CallbackInfoReturnable<Float> callbackInfo) {
		ChunkTiming.recordDesiredChunksPerTick(callbackInfo.getReturnValue());
	}
}
