package dev.mikuteto.mtlrender.mixin;

import java.util.List;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.state.gui.GuiRenderState$Node")
public interface GuiRenderStateNodeAccess {
	@Accessor("up")
	Object mtlrender$up();

	@Accessor("elementStates")
	List<GuiElementRenderState> mtlrender$elementStates();

	@Accessor("glyphStates")
	List<GuiElementRenderState> mtlrender$glyphStates();

	@Accessor("itemStates")
	List<GuiItemRenderState> mtlrender$itemStates();

	@Accessor("textStates")
	List<GuiTextRenderState> mtlrender$textStates();

	@Accessor("picturesInPictureStates")
	List<PictureInPictureRenderState> mtlrender$picturesInPictureStates();
}
