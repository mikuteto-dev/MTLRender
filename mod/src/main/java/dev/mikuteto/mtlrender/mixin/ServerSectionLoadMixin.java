package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.chunk.ServerSectionAnalysis;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SerializableChunkData.class)
abstract class ServerSectionLoadMixin {
	@Inject(method = "parse", at = @At("RETURN"))
	private static void mtlrender$publishSectionAnalysis(
		CallbackInfoReturnable<SerializableChunkData> callback
	) {
		SerializableChunkData data = callback.getReturnValue();
		if (data != null) {
			ServerSectionAnalysis.prepare(data);
		}
	}

	@Inject(method = "read", at = @At("HEAD"))
	private void mtlrender$consumeSectionAnalysis(CallbackInfoReturnable<?> callback) {
		ServerSectionAnalysis.require((SerializableChunkData) (Object) this);
	}

	@Inject(method = "read", at = @At("RETURN"))
	private void mtlrender$finishSectionAnalysis(CallbackInfoReturnable<?> callback) {
		ServerSectionAnalysis.finish((SerializableChunkData) (Object) this);
	}
}
