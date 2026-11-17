package dev.mikuteto.mtlrender.mixin;

import net.minecraft.server.network.PlayerChunkSender;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PlayerChunkSender.class)
abstract class PlayerChunkSenderLocalMixin {
	@Shadow @Final
	private boolean memoryConnection;

	@Shadow
	private int maxUnacknowledgedBatches;

	@Redirect(
		method = "sendNextChunks",
		at = @At(
			value = "FIELD",
			target = "Lnet/minecraft/server/network/PlayerChunkSender;maxUnacknowledgedBatches:I",
			opcode = Opcodes.GETFIELD
		)
	)
	private int mtlrender$localMaxUnacknowledgedBatches(PlayerChunkSender sender) {
		return this.memoryConnection ? Integer.MAX_VALUE : this.maxUnacknowledgedBatches;
	}
}
