package dev.mikuteto.mtlrender.mixin;

import java.util.List;
import dev.mikuteto.mtlrender.gui.GuiSpatialIndex;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.renderer.state.gui.GuiRenderState$Node")
abstract class GuiRenderStateNodeMixin {
	@Shadow
	@Nullable
	public List<GuiElementRenderState> elementStates;

	@Shadow
	@Nullable
	public List<GuiItemRenderState> itemStates;

	@Shadow
	@Nullable
	public List<GuiTextRenderState> textStates;

	@Shadow
	@Nullable
	public List<PictureInPictureRenderState> picturesInPictureStates;

	@Unique
	private final GuiSpatialIndex mtlrender$elementIndex = new GuiSpatialIndex();

	@Unique
	private final GuiSpatialIndex mtlrender$itemIndex = new GuiSpatialIndex();

	@Unique
	private final GuiSpatialIndex mtlrender$textIndex = new GuiSpatialIndex();

	@Unique
	private final GuiSpatialIndex mtlrender$pictureInPictureIndex = new GuiSpatialIndex();

	@Inject(method = "addGuiElement", at = @At("HEAD"))
	private void mtlrender$prepareElementIndex(GuiElementRenderState state, CallbackInfo ci) {
		if (this.elementStates == null) {
			this.elementStates = new GuiSpatialIndex.IndexedStates<>(this.mtlrender$elementIndex);
		}
	}

	@Inject(method = "addItem", at = @At("HEAD"))
	private void mtlrender$prepareItemIndex(GuiItemRenderState state, CallbackInfo ci) {
		if (this.itemStates == null) {
			this.itemStates = new GuiSpatialIndex.IndexedStates<>(this.mtlrender$itemIndex);
		}
	}

	@Inject(method = "addText", at = @At("HEAD"))
	private void mtlrender$prepareTextIndex(GuiTextRenderState state, CallbackInfo ci) {
		if (this.textStates == null) {
			this.textStates = new GuiSpatialIndex.IndexedStates<>(this.mtlrender$textIndex);
		}
	}

	@Inject(method = "addPicturesInPictureState", at = @At("HEAD"))
	private void mtlrender$preparePictureInPictureIndex(PictureInPictureRenderState state, CallbackInfo ci) {
		if (this.picturesInPictureStates == null) {
			this.picturesInPictureStates = new GuiSpatialIndex.IndexedStates<>(this.mtlrender$pictureInPictureIndex);
		}
	}
}
