package dev.mikuteto.mtlrender.mixin;

import net.minecraft.client.gui.render.DynamicAtlasAllocator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DynamicAtlasAllocator.Slot.class)
public interface DynamicAtlasAllocatorSlotAccess {
	@Accessor("externalState")
	void mtlrender$setExternalState(DynamicAtlasAllocator.SlotState state);
}
