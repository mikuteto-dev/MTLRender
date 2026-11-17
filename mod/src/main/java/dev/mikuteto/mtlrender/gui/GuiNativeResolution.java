package dev.mikuteto.mtlrender.gui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.mikuteto.mtlrender.backend.MetalFx;
import net.minecraft.client.gui.navigation.ScreenRectangle;

public final class GuiNativeResolution {
	private GuiNativeResolution() {
	}

	public record Viewport(int width, int height) {
	}

	public record Scissor(int left, int bottom, int width, int height) {
	}

	public static RenderTarget target(final RenderTarget normal, final MetalFx.Composition composition, final boolean enabled) {
		if (enabled && composition.active() && composition.nativeTarget() != null
				&& composition.width() > 0 && composition.height() > 0) {
			return composition.nativeTarget();
		}
		return normal;
	}

	public static Viewport viewport(final MetalFx.Composition composition, final boolean enabled, final int fallbackWidth, final int fallbackHeight) {
		if (enabled && composition.active() && composition.nativeTarget() != null && composition.width() > 0 && composition.height() > 0) {
			return new Viewport(composition.width(), composition.height());
		}
		return new Viewport(fallbackWidth, fallbackHeight);
	}

	public static Scissor scissor(final ScreenRectangle rectangle, final int guiScale, final Viewport viewport) {
		double left = rectangle.left() * (double) guiScale;
		double top = rectangle.top() * (double) guiScale;
		double right = Math.min(rectangle.right() * (double) guiScale, viewport.width());
		double bottom = Math.min(rectangle.bottom() * (double) guiScale, viewport.height());
		return new Scissor(
			(int) left,
			viewport.height() - (int) bottom,
			Math.max(0, (int) (right - left)),
			Math.max(0, (int) (bottom - top))
		);
	}
}
