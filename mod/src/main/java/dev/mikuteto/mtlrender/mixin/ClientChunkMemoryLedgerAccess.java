package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.chunk.ClientChunkMemoryLedger;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(net.minecraft.client.multiplayer.ClientChunkCache.class)
public interface ClientChunkMemoryLedgerAccess {
	ClientChunkMemoryLedger mtlrender$clientChunkMemoryLedger();
}
