package dev.mikuteto.mtlrender.mixin;

import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$SectionTask")
public interface SectionTaskTimingAccess {
	@Accessor("mtlrender$queuedAtNanos")
	long mtlrender$queuedAtNanos();

	@Accessor("mtlrender$queuedAtNanos")
	void mtlrender$setQueuedAtNanos(long timestampNanos);

	@Accessor("mtlrender$sectionKey")
	long mtlrender$sectionKey();

	@Accessor("mtlrender$sectionKey")
	void mtlrender$setSectionKey(long sectionKey);

	@Accessor("isRecompile")
	boolean mtlrender$recompile();

	@Accessor("isCancelled")
	AtomicBoolean mtlrender$isCancelled();

	@Accessor("isCompleted")
	AtomicBoolean mtlrender$isCompleted();
}
