package dev.mikuteto.mtlrender.mixin;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
abstract class ConnectionLocalTransportMixin {
	@Shadow
	private Channel channel;

	@Inject(method = "setupCompression", at = @At("HEAD"), cancellable = true)
	private void mtlrender$disableLocalCompression(int threshold, boolean validateDecompressed, CallbackInfo ci) {
		if (!((Connection) (Object) this).isMemoryConnection()) {
			return;
		}

		ChannelPipeline pipeline = this.channel.pipeline();
		if (pipeline.get("decompress") != null) {
			pipeline.remove("decompress");
		}
		if (pipeline.get("compress") != null) {
			pipeline.remove("compress");
		}
		ci.cancel();
	}
}
