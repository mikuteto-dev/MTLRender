package dev.mikuteto.mtlrender.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$SectionTask")
abstract class SectionTaskTimingMixin {
	@Unique
	private long mtlrender$queuedAtNanos;

	@Unique
	private long mtlrender$sectionKey;

	@Inject(method = "<init>(Z)V", at = @At("TAIL"))
	private void mtlrender$onConstruct(boolean recompile, CallbackInfo info) {
		SectionTaskTimingAccess timing = (SectionTaskTimingAccess) this;
		timing.mtlrender$setQueuedAtNanos(0L);
		timing.mtlrender$setSectionKey(0L);
	}
}
