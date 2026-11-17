package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.gui.TooltipComponentKey;
import dev.mikuteto.mtlrender.gui.TooltipComponentKeyAccess;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ClientTextTooltip.class)
abstract class ClientTextTooltipMixin implements TooltipComponentKeyAccess {
	@Shadow @Final private FormattedCharSequence text;

	@Override
	@Unique
	public Object mtlrender$layoutKey() {
		return TooltipComponentKey.textKey(this.text);
	}
}
