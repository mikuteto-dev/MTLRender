package dev.mikuteto.mtlrender.gui;

import dev.mikuteto.mtlrender.config.Config;

public record GuiCachePolicy(
	long resourceGeneration,
	int scale,
	boolean itemAtlas,
	boolean mapAtlas,
	boolean tooltipCache,
	boolean nativeResolution,
	boolean lowFrequency
) {
	public static GuiCachePolicy from(final Config.Gui config, final long resourceGeneration, final int scale) {
		return new GuiCachePolicy(
			resourceGeneration,
			scale,
			config.itemAtlas,
			config.mapAtlas,
			config.tooltipCache,
			config.nativeResolution,
			config.lowFrequency
		);
	}
}
