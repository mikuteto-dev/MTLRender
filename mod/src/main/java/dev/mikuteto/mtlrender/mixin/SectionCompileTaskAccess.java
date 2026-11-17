package dev.mikuteto.mtlrender.mixin;

import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public interface SectionCompileTaskAccess {
	@Accessor("region")
	RenderSectionRegion mtlrender$region();
}
