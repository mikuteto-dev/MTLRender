package dev.mikuteto.mtlrender.mixin;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SoundBufferLibrary.class)
public interface SoundBufferLibraryAccess {
	@Accessor("cache")
	Map<Identifier, CompletableFuture<SoundBuffer>> mtlrender$cache();
}
