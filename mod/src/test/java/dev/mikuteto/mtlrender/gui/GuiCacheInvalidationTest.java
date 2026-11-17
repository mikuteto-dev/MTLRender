package dev.mikuteto.mtlrender.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class GuiCacheInvalidationTest {
	@AfterEach
	void clearTooltipCache() {
		TooltipLayoutCache.end();
		TooltipLayoutCache.clear();
	}

	@Test
	void itemKeysSeparateModelAnimationAndResourceGenerations() {
		Object model = new Object();
		GuiItemCacheKey first = GuiItemCacheKey.of(List.of(model, "base"), false, 1L, 4L);
		GuiItemCacheKey same = GuiItemCacheKey.of(List.of(model, "base"), false, 99L, 4L);
		GuiItemCacheKey animated = GuiItemCacheKey.of(List.of(model, "base"), true, 2L, 4L);
		GuiItemCacheKey nextAnimation = GuiItemCacheKey.of(List.of(model, "base"), true, 3L, 4L);
		GuiItemCacheKey reloaded = GuiItemCacheKey.of(List.of(model, "base"), false, 1L, 5L);
		GuiItemCacheKey differentModel = GuiItemCacheKey.of(List.of(new Object(), "base"), false, 1L, 4L);

		assertEquals(first, same);
		assertNotEquals(animated, nextAnimation);
		assertNotEquals(first, animated);
		assertNotEquals(first, reloaded);
		assertNotEquals(first, differentModel);
	}

	@Test
	void mapVersionsSeparateReplacementAndForcedUpload() {
		byte[] colors = new byte[GuiMapAtlas.MAP_SIZE * GuiMapAtlas.MAP_SIZE];
		Object data = new Object();

		assertEquals(GuiMapAtlas.version(data, colors, 0L), GuiMapAtlas.version(data, colors, 0L));
		assertNotEquals(GuiMapAtlas.version(data, colors, 0L), GuiMapAtlas.version(new Object(), colors, 0L));
		assertNotEquals(GuiMapAtlas.version(data, colors, 0L), GuiMapAtlas.version(data, colors, 1L));
		colors[0] = 1;
		assertNotEquals(GuiMapAtlas.version(data, colors, 0L), GuiMapAtlas.version(data, new byte[colors.length], 0L));
	}

	@Test
	void dynamicTooltipComponentsNeverReuseLayout() {
		ClientTooltipComponent component = new DynamicComponent();
		AtomicInteger calls = new AtomicInteger();
		TooltipLayoutCache.begin(320, 240, 2, Identifier.withDefaultNamespace("default"), 1L);

		TooltipLayoutCache.width(component, null, calls::incrementAndGet);
		TooltipLayoutCache.width(component, null, calls::incrementAndGet);
		assertEquals(2, calls.get());
	}

	@Test
	void staticTooltipTextReusesOnlyItsLayoutKey() {
		ClientTooltipComponent first = new StaticComponent("stone");
		ClientTooltipComponent same = new StaticComponent("stone");
		ClientTooltipComponent changed = new StaticComponent("dirt");
		AtomicInteger calls = new AtomicInteger();
		TooltipLayoutCache.begin(320, 240, 2, null, 1L);

		TooltipLayoutCache.width(first, null, () -> calls.incrementAndGet());
		TooltipLayoutCache.width(same, null, () -> calls.incrementAndGet());
		TooltipLayoutCache.width(changed, null, () -> calls.incrementAndGet());
		assertEquals(2, calls.get());
	}

	private static final class StaticComponent implements ClientTooltipComponent, TooltipComponentKeyAccess {
		private final String key;

		private StaticComponent(final String key) {
			this.key = key;
		}

		@Override
		public int getWidth(final Font font) {
			return 0;
		}

		@Override
		public int getHeight(final Font font) {
			return 0;
		}

		@Override
		public Object mtlrender$layoutKey() {
			return this.key;
		}
	}

	private static final class DynamicComponent implements ClientTooltipComponent {
		@Override
		public int getWidth(final Font font) {
			return 0;
		}

		@Override
		public int getHeight(final Font font) {
			return 0;
		}
	}
}
