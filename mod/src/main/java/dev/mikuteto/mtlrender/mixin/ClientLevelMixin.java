package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.debug.ClientChunkMemorySource;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void mtlrender$sampleClientChunkMemory(java.util.function.BooleanSupplier haveTime, CallbackInfo callbackInfo) {
		ClientChunkMemorySource.global().maybeSample((ClientLevel)(Object)this);
	}
}
