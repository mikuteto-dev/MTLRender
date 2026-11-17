package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.gui.TooltipComponentKeyAccess;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ClientTooltipComponent.class)
public interface ClientTooltipComponentAccess extends TooltipComponentKeyAccess {
	@Override
	@Unique
	default Object mtlrender$layoutKey() {
		return null;
	}
}
