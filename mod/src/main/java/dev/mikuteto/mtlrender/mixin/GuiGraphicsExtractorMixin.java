package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.gui.GuiRenderSnapshot;
import dev.mikuteto.mtlrender.gui.TooltipLayoutCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(GuiGraphicsExtractor.class)
abstract class GuiGraphicsExtractorMixin {
	@Shadow @Final private Minecraft minecraft;
	@Shadow public abstract int guiWidth();
	@Shadow public abstract int guiHeight();
	@Unique private MapRenderState mtlrender$mapState;

	@Inject(
		method = "tooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/Identifier;)V",
		at = @At("HEAD")
	)
	private void mtlrender$beginTooltip(
		final Font font,
		final java.util.List<ClientTooltipComponent> lines,
		final int xo,
		final int yo,
		final ClientTooltipPositioner positioner,
		final @Nullable Identifier style,
		final CallbackInfo callback
	) {
		if (Config.get().gui.tooltipCache) {
			TooltipLayoutCache.begin(this.guiWidth(), this.guiHeight(), this.minecraft.getWindow().getGuiScale(), style, GuiRenderSnapshot.resourceGeneration());
		}
	}

	@Inject(
		method = "tooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/Identifier;)V",
		at = @At("RETURN")
	)
	private void mtlrender$endTooltip(
		final Font font,
		final java.util.List<ClientTooltipComponent> lines,
		final int xo,
		final int yo,
		final ClientTooltipPositioner positioner,
		final @Nullable Identifier style,
		final CallbackInfo callback
	) {
		TooltipLayoutCache.end();
	}

	@Redirect(
		method = "tooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/Identifier;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent;getWidth(Lnet/minecraft/client/gui/Font;)I")
	)
	private int mtlrender$cachedWidth(final ClientTooltipComponent component, final Font font) {
		return TooltipLayoutCache.width(component, font, () -> component.getWidth(font));
	}

	@Redirect(
		method = "tooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/Identifier;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent;getHeight(Lnet/minecraft/client/gui/Font;)I")
	)
	private int mtlrender$cachedHeight(final ClientTooltipComponent component, final Font font) {
		return TooltipLayoutCache.height(component, font, () -> component.getHeight(font));
	}

	@Inject(method = "map", at = @At("HEAD"))
	private void mtlrender$beginMap(final MapRenderState state, final CallbackInfo callbackInfo) {
		this.mtlrender$mapState = state;
	}

	@Inject(method = "map", at = @At("RETURN"))
	private void mtlrender$endMap(final MapRenderState state, final CallbackInfo callbackInfo) {
		this.mtlrender$mapState = null;
	}

	@ModifyArgs(
		method = "map",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;innerBlit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;IIIIFFFFI)V",
			ordinal = 0
		)
	)
	private void mtlrender$useMapAtlas(final Args args) {
		MapRenderState state = this.mtlrender$mapState;
		if (!Config.get().gui.mapAtlas || state == null) {
			return;
		}
		MapRenderStateAccess access = (MapRenderStateAccess)(Object)state;
		if (!access.mtlrender$hasAtlas()) {
			return;
		}
		AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(access.mtlrender$atlasTexture());
		args.set(1, texture.getTextureView());
		args.set(2, texture.getSampler());
		args.set(7, access.mtlrender$atlasU0());
		args.set(8, access.mtlrender$atlasU1());
		args.set(9, access.mtlrender$atlasV0());
		args.set(10, access.mtlrender$atlasV1());
	}
}
