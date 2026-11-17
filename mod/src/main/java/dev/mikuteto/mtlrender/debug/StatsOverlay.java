package dev.mikuteto.mtlrender.debug;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class StatsOverlay {
	private static final int MARGIN = 2;
	private static final int PADDING = 1;
	private static final int BACKGROUND = 0x90000000;
	private static final int TEXT = 0xFFE0E0E0;

	private StatsOverlay() {
	}

	public static void draw(GuiGraphicsExtractor graphics) {
		FrameStats stats = FrameStats.get();
		List<String> lines = stats.overlayLines();
		if (!stats.overlayEnabled() || lines.isEmpty()) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		int lineHeight = font.lineHeight + PADDING;
		int y = graphics.guiHeight() - MARGIN - lines.size() * lineHeight;
		for (String line : lines) {
			int width = font.width(line);
			int x = graphics.guiWidth() - MARGIN - width;
			graphics.fill(x - PADDING, y - PADDING, x + width + PADDING, y + font.lineHeight, BACKGROUND);
			graphics.text(font, line, x, y, TEXT, false);
			y += lineHeight;
		}
	}
}
