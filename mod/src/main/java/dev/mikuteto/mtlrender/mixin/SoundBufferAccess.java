package dev.mikuteto.mtlrender.mixin;

import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(com.mojang.blaze3d.audio.SoundBuffer.class)
public interface SoundBufferAccess {
	@Accessor("data")
	ByteBuffer mtlrender$data();

	@Accessor("hasAlBuffer")
	boolean mtlrender$hasAlBuffer();

	@Accessor("size")
	int mtlrender$size();
}
