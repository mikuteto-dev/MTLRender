package dev.mikuteto.mtlrender.mixin;

import java.util.List;
import java.util.Set;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.PanoramaRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GuiRenderState.class)
public interface GuiRenderStateAccess {
	@Accessor("strata")
	List<?> mtlrender$strata();

	@Accessor("firstStratumAfterBlur")
	int mtlrender$firstStratumAfterBlur();

	@Accessor("itemModelIdentities")
	Set<?> mtlrender$itemModelIdentities();

	@Accessor("panoramaRenderState")
	PanoramaRenderState mtlrender$panoramaRenderState();

	@Accessor("clearColorOverride")
	Vector4f mtlrender$clearColorOverride();

	@Accessor("isHudHidden")
	boolean mtlrender$isHudHidden();
}
