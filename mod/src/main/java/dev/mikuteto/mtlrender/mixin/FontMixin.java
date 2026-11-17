package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.debug.AbSwitch;
import dev.mikuteto.mtlrender.gui.GlyphLayoutCache;
import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Font.class)
abstract class FontMixin {
	@Inject(
		method = "prepareText(Ljava/lang/String;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void mtlrender$lookupString(
		final String text,
		final float x,
		final float y,
		final int color,
		final boolean shadow,
		final int background,
		final CallbackInfoReturnable<Font.PreparedText> callback
	) {
		if (!mtlrender$enabled()) {
			return;
		}
		Font font = (Font) (Object) this;
		GlyphLayoutCache.LayoutKey key = GlyphLayoutCache.stringKey(font, text, x, y, color, shadow, background);
		Font.PreparedText cached = GlyphLayoutCache.get(key);
		if (cached != null) {
			callback.setReturnValue(cached);
		}
	}

	@Inject(
		method = "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;",

		at = @At("HEAD"),
		cancellable = true
	)
	private void mtlrender$lookupSequence(
		final FormattedCharSequence text,
		final float x,
		final float y,
		final int color,
		final boolean shadow,
		final boolean includeEmpty,
		final int background,
		final CallbackInfoReturnable<Font.PreparedText> callback
	) {
		if (!mtlrender$enabled() || !GlyphLayoutCache.cacheable(text)) {
			return;
		}
		Font font = (Font) (Object) this;
		GlyphLayoutCache.LayoutKey key = GlyphLayoutCache.sequenceKey(font, text, x, y, color, shadow, includeEmpty, background, Font.DisplayMode.NORMAL, 0);
		Font.PreparedText cached = GlyphLayoutCache.get(key);
		if (cached != null) {
			callback.setReturnValue(cached);
		}
	}

	@Inject(
		method = "prepare8xTextOutline(Lnet/minecraft/util/FormattedCharSequence;FFI)Lnet/minecraft/client/gui/Font$PreparedText;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void mtlrender$lookupOutline(
		final FormattedCharSequence text,
		final float x,
		final float y,
		final int outlineColor,
		final CallbackInfoReturnable<Font.PreparedText> callback
	) {
		if (!mtlrender$enabled() || !GlyphLayoutCache.cacheable(text)) {
			return;
		}
		Font font = (Font) (Object) this;
		GlyphLayoutCache.LayoutKey key = GlyphLayoutCache.sequenceKey(font, text, x, y, 0, false, false, 0, Font.DisplayMode.POLYGON_OFFSET, outlineColor);
		Font.PreparedText cached = GlyphLayoutCache.get(key);
		if (cached != null) {
			callback.setReturnValue(cached);
		}
	}

	@Inject(method = "prepareText(Ljava/lang/String;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;", at = @At("RETURN"))
	private void mtlrender$storeString(final String text, final float x, final float y, final int color, final boolean shadow, final int background, final CallbackInfoReturnable<Font.PreparedText> callback) {
		if (mtlrender$enabled()) {
			GlyphLayoutCache.put(GlyphLayoutCache.stringKey((Font) (Object) this, text, x, y, color, shadow, background), callback.getReturnValue());
		}
	}

	@Inject(method = "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;", at = @At("RETURN"))
	private void mtlrender$storeSequence(final FormattedCharSequence text, final float x, final float y, final int color, final boolean shadow, final boolean includeEmpty, final int background, final CallbackInfoReturnable<Font.PreparedText> callback) {
		if (mtlrender$enabled() && GlyphLayoutCache.cacheable(text)) {
			GlyphLayoutCache.put(GlyphLayoutCache.sequenceKey((Font) (Object) this, text, x, y, color, shadow, includeEmpty, background, Font.DisplayMode.NORMAL, 0), callback.getReturnValue());
		}
	}

	@Inject(method = "prepare8xTextOutline(Lnet/minecraft/util/FormattedCharSequence;FFI)Lnet/minecraft/client/gui/Font$PreparedText;", at = @At("RETURN"))
	private void mtlrender$storeOutline(final FormattedCharSequence text, final float x, final float y, final int outlineColor, final CallbackInfoReturnable<Font.PreparedText> callback) {
		if (mtlrender$enabled() && GlyphLayoutCache.cacheable(text)) {
			GlyphLayoutCache.put(GlyphLayoutCache.sequenceKey((Font) (Object) this, text, x, y, 0, false, false, 0, Font.DisplayMode.POLYGON_OFFSET, outlineColor), callback.getReturnValue());
		}
	}

	@Unique
	private static boolean mtlrender$enabled() {
		return AbSwitch.is(AbSwitch.GLYPH_CACHE, Config.get().gui.glyphCache);
	}
}
