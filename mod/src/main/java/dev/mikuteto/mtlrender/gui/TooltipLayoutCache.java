package dev.mikuteto.mtlrender.gui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.resources.Identifier;

public final class TooltipLayoutCache {
	private static final int MAX_ENTRIES = 256;
	private static final Map<Key, Dimensions> ENTRIES = new LinkedHashMap<>(MAX_ENTRIES, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(final Map.Entry<Key, Dimensions> eldest) {
			return size() > MAX_ENTRIES;
		}
	};
	private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();

	private TooltipLayoutCache() {
	}

	public static void begin(final int width, final int height, final int scale, final Identifier style, final long generation) {
		CONTEXT.set(new Context(width, height, scale, style, generation));
	}

	public static void end() {
		CONTEXT.remove();
	}

	public static int width(final ClientTooltipComponent component, final Font font, final IntSupplier calculation) {
		return dimension(component, font, calculation, true);
	}

	public static int height(final ClientTooltipComponent component, final Font font, final IntSupplier calculation) {
		return dimension(component, font, calculation, false);
	}

	public static synchronized void clear() {
		ENTRIES.clear();
	}

	public static synchronized int size() {
		return ENTRIES.size();
	}

	private static int dimension(final ClientTooltipComponent component, final Font font, final IntSupplier calculation, final boolean width) {
		Context context = CONTEXT.get();
		Object componentKey = TooltipComponentKey.key(component);
		if (context == null || componentKey == null) {
			return calculation.getAsInt();
		}
		Key key = new Key(componentKey, font, context.generation, context.width, context.height, context.scale, context.style);
		Dimensions existing;
		synchronized (TooltipLayoutCache.class) {
			existing = ENTRIES.get(key);
			if (existing != null && (width ? existing.width >= 0 : existing.height >= 0)) {
				return width ? existing.width : existing.height;
			}
		}
		int value = calculation.getAsInt();
		synchronized (TooltipLayoutCache.class) {
			Dimensions previous = ENTRIES.get(key);
			int cachedWidth = previous == null ? -1 : previous.width;
			int cachedHeight = previous == null ? -1 : previous.height;
			ENTRIES.put(key, width ? new Dimensions(value, cachedHeight) : new Dimensions(cachedWidth, value));
		}
		return value;
	}

	private record Context(int width, int height, int scale, Identifier style, long generation) {
	}

	private record Key(Object component, Font font, long generation, int width, int height, int scale, Identifier style) {
	}

	private record Dimensions(int width, int height) {
	}
}
