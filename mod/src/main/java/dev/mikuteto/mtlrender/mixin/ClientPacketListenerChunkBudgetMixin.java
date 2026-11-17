package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mikuteto.mtlrender.debug.LocalChunkSendBudget;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerChunkBudgetMixin {
	@WrapMethod(method = "handleLevelChunkWithLight")
	private void mtlrender$measureChunkHandler(
		ClientboundLevelChunkWithLightPacket packet,
		Operation<Void> original
	) {
		long startedAtNanos = LocalChunkSendBudget.beginChunkSample((ClientPacketListener) (Object) this);
		try {
			original.call(packet);
		} finally {
			LocalChunkSendBudget.endChunkSample(startedAtNanos);
		}
	}
}
