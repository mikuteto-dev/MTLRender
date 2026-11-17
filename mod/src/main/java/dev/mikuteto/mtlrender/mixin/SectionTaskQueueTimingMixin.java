package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.debug.ChunkTiming;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionTaskDynamicQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionTaskDynamicQueue.class)
abstract class SectionTaskQueueTimingMixin {
	@Inject(method = "add", at = @At("HEAD"))
	private void mtlrender$onAdd(SectionRenderDispatcher.RenderSection.SectionTask task, CallbackInfo info) {
		if (!ChunkTiming.enabled()) {
			return;
		}
		SectionTaskTimingAccess timing = (SectionTaskTimingAccess) task;
		BlockPos origin = task.getRenderOrigin();
		timing.mtlrender$setQueuedAtNanos(System.nanoTime());
		timing.mtlrender$setSectionKey(origin.asLong());
	}

	@Inject(method = "poll", at = @At("RETURN"))
	private void mtlrender$onPoll(Vec3 cameraPos, CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask> callback) {
		SectionRenderDispatcher.RenderSection.SectionTask task = callback.getReturnValue();
		if (task == null || !ChunkTiming.enabled()) {
			return;
		}
		SectionTaskTimingAccess timing = (SectionTaskTimingAccess) task;
		long queuedAt = timing.mtlrender$queuedAtNanos();
		if (queuedAt == 0L) {
			return;
		}
		if (timing.mtlrender$isCancelled().get() || timing.mtlrender$isCompleted().get()) {
			timing.mtlrender$setQueuedAtNanos(0L);
			return;
		}
		ChunkTiming.recordSectionQueueWait(
			timing.mtlrender$sectionKey(), timing.mtlrender$recompile(), System.nanoTime() - queuedAt
		);
		timing.mtlrender$setQueuedAtNanos(0L);
	}

}
