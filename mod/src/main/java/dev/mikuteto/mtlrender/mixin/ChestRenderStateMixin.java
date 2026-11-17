package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.access.ChestRenderStateAccess;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ChestRenderState.class)
abstract class ChestRenderStateMixin implements ChestRenderStateAccess {
	private boolean mtlrender$dynamicOnly;

	@Override
	public boolean mtlrender$dynamicOnly() {
		return mtlrender$dynamicOnly;
	}

	@Override
	public void mtlrender$setDynamicOnly(boolean value) {
		mtlrender$dynamicOnly = value;
	}
}
