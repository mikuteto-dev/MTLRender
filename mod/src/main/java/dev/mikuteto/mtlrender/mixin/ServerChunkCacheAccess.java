package dev.mikuteto.mtlrender.mixin;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheAccess {
	@Accessor("ticketStorage")
	TicketStorage mtlrender$ticketStorage();

	@Invoker("runDistanceManagerUpdates")
	void mtlrender$runDistanceManagerUpdates();
}
