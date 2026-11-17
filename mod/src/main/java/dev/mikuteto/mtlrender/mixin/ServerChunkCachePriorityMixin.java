package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.server.ChunkLoadPriority;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
abstract class ServerChunkCachePriorityMixin {
	@Inject(method = "move(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("TAIL"))
	private void mtlrender$updatePlayerPriority(final ServerPlayer player, final CallbackInfo callbackInfo) {
		ChunkLoadPriority priority = ChunkLoadPriority.shared();
		if (player.isRemoved()) {
			priority.remove(player.getId());
		} else {
			priority.register(player.getId(), ChunkPos.containing(player.blockPosition()));
		}
	}

	@Inject(method = "removeEntity(Lnet/minecraft/world/entity/Entity;)V", at = @At("TAIL"))
	private void mtlrender$removePlayerPriority(final Entity entity, final CallbackInfo callbackInfo) {
		if (entity instanceof ServerPlayer player) {
			ChunkLoadPriority.shared().remove(player.getId());
		}
	}
}
