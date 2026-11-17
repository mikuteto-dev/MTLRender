package dev.mikuteto.mtlrender.mixin;

import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$SectionTask")
public interface SectionTaskAccess {
	@Accessor("isCancelled")
	AtomicBoolean mtlrender$isCancelled();

	@Accessor("isCompleted")
	AtomicBoolean mtlrender$isCompleted();
}
