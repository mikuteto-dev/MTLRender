package dev.mikuteto.mtlrender.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

public final class TooltipComponentKey {
	private TooltipComponentKey() {
	}

	public static Object key(final ClientTooltipComponent component) {
		if (component instanceof TooltipComponentKeyAccess access) {
			return access.mtlrender$layoutKey();
		}
		return null;
	}

	public static Object textKey(final FormattedCharSequence text) {
		List<Glyph> glyphs = new ArrayList<>();
		text.accept((position, style, codepoint) -> {
			glyphs.add(new Glyph(position, codepoint, style));
			return true;
		});
		return List.copyOf(glyphs);
	}

	private record Glyph(int position, int codepoint, Style style) {
	}
}

