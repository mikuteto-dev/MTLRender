package dev.mikuteto.mtlrender.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

public final class GlyphLayoutCache {
	private static final int MAX_ENTRIES = 512;
	private static final Map<LayoutKey, Font.PreparedText> ENTRIES = new LinkedHashMap<>(MAX_ENTRIES, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(final Map.Entry<LayoutKey, Font.PreparedText> eldest) {
			return size() > MAX_ENTRIES;
		}
	};

	private GlyphLayoutCache() {
	}

	public static synchronized Font.PreparedText get(final LayoutKey key) {
		return ENTRIES.get(key);
	}

	public static synchronized void put(final LayoutKey key, final Font.PreparedText value) {
		ENTRIES.put(key, value);
	}

	public static synchronized void clear() {
		ENTRIES.clear();
	}

	public static synchronized int size() {
		return ENTRIES.size();
	}

	public static boolean cacheable(final FormattedCharSequence text) {
		final boolean[] cacheable = {true};
		text.accept((position, style, codepoint) -> {
			if (style.isObfuscated()) {
				cacheable[0] = false;
				return false;
			}
			return true;
		});
		return cacheable[0];
	}

	public static LayoutKey sequenceKey(
		final Font font,
		final FormattedCharSequence text,
		final float x,
		final float y,
		final int color,
		final boolean shadow,
		final boolean includeEmpty,
		final int background,
		final Font.DisplayMode displayMode,
		final int outlineColor
	) {
		List<Glyph> glyphs = new ArrayList<>();
		text.accept((position, style, codepoint) -> {
			glyphs.add(new Glyph(position, codepoint, style));
			return true;
		});
		return new LayoutKey(font, GuiRenderSnapshot.resourceGeneration(), List.copyOf(glyphs), Float.floatToIntBits(x), Float.floatToIntBits(y), color, shadow, includeEmpty, background, displayMode, outlineColor);
	}

	public static LayoutKey stringKey(
		final Font font,
		final String text,
		final float x,
		final float y,
		final int color,
		final boolean shadow,
		final int background
	) {
		return new LayoutKey(font, GuiRenderSnapshot.resourceGeneration(), text, Float.floatToIntBits(x), Float.floatToIntBits(y), color, shadow, false, background, Font.DisplayMode.NORMAL, 0);
	}

	public record Glyph(int position, int codepoint, Style style) {
	}

	public static final class LayoutKey {
		private final Font font;
		private final long resourceGeneration;
		private final Object content;
		private final int x;
		private final int y;
		private final int color;
		private final boolean shadow;
		private final boolean includeEmpty;
		private final int background;
		private final Font.DisplayMode displayMode;
		private final int outlineColor;

		private LayoutKey(
			final Font font,
			final long resourceGeneration,
			final Object content,
			final int x,
			final int y,
			final int color,
			final boolean shadow,
			final boolean includeEmpty,
			final int background,
			final Font.DisplayMode displayMode,
			final int outlineColor
		) {
			this.font = font;
			this.resourceGeneration = resourceGeneration;
			this.content = content;
			this.x = x;
			this.y = y;
			this.color = color;
			this.shadow = shadow;
			this.includeEmpty = includeEmpty;
			this.background = background;
			this.displayMode = displayMode;
			this.outlineColor = outlineColor;
		}

		@Override
		public boolean equals(final Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof LayoutKey key)) {
				return false;
			}
			return this.font == key.font
				&& this.resourceGeneration == key.resourceGeneration
				&& this.x == key.x
				&& this.y == key.y
				&& this.color == key.color
				&& this.shadow == key.shadow
				&& this.includeEmpty == key.includeEmpty
				&& this.background == key.background
				&& this.displayMode == key.displayMode
				&& this.outlineColor == key.outlineColor
				&& this.content.equals(key.content);
		}

		@Override
		public int hashCode() {
			int result = System.identityHashCode(this.font);
			result = 31 * result + Long.hashCode(this.resourceGeneration);
			result = 31 * result + this.content.hashCode();
			result = 31 * result + this.x;
			result = 31 * result + this.y;
			result = 31 * result + this.color;
			result = 31 * result + Boolean.hashCode(this.shadow);
			result = 31 * result + Boolean.hashCode(this.includeEmpty);
			result = 31 * result + this.background;
			result = 31 * result + this.displayMode.hashCode();
			return 31 * result + this.outlineColor;
		}
	}
}
