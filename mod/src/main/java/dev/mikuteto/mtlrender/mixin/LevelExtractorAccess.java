package dev.mikuteto.mtlrender.mixin;

import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelExtractor.class)
public interface LevelExtractorAccess {
	@Accessor("sectionUpdateTracker")
	@Nullable SectionUpdateTracker mtlrender$sectionUpdateTracker();

	@Accessor("level")
	@Nullable ClientLevel mtlrender$level();
}
