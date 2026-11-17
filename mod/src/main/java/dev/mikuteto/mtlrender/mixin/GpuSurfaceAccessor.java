package dev.mikuteto.mtlrender.mixin;

import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GpuSurface.class)
interface GpuSurfaceAccessor {
	@Accessor("backend")
	GpuSurfaceBackend mtlrender$backend();
}
