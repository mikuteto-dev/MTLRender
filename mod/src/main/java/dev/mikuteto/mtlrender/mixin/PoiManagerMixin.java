package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mikuteto.mtlrender.chunk.ServerSectionAnalysis;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PoiManager.class)
abstract class PoiManagerMixin {
	@WrapOperation(
		method = "checkConsistencyWithBlocks",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;maybeHas(Ljava/util/function/Predicate;)Z")
	)
	private boolean mtlrender$useSectionAnalysis(
		LevelChunkSection section,
		java.util.function.Predicate<?> predicate,
		Operation<Boolean> original
	) {
		return ServerSectionAnalysis.mayHavePoi(section, original.call(section, predicate));
	}
}
