package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mikuteto.mtlrender.debug.LocalChunkSendBudget;
import java.util.Queue;

import net.minecraft.network.PacketProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketProcessor.class)
abstract class PacketProcessorBudgetMixin {
	@Inject(method = "processQueuedPackets", at = @At("HEAD"))
	private void mtlrender$beginDrain(CallbackInfo callbackInfo) {
		LocalChunkSendBudget.beginDrain((PacketProcessor) (Object) this);
	}

	@Inject(method = "processQueuedPackets", at = @At("RETURN"))
	private void mtlrender$endDrain(CallbackInfo callbackInfo) {
		LocalChunkSendBudget.endDrain();
	}

	@WrapOperation(
		method = "processQueuedPackets",
		at = @At(value = "INVOKE", target = "Ljava/util/Queue;isEmpty()Z")
	)
	private boolean mtlrender$stopAfterBudget(Queue<?> queue, Operation<Boolean> original) {
		if (LocalChunkSendBudget.shouldYield()) {
			return true;
		}
		return original.call(queue);
	}
}
