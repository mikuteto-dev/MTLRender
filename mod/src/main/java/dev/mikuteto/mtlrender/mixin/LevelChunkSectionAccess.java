package dev.mikuteto.mtlrender.mixin;

import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LevelChunkSection.class)
public interface LevelChunkSectionAccess {
	@Invoker("recalcBlockCounts")
	void mtlrender$recalcBlockCounts();
}
