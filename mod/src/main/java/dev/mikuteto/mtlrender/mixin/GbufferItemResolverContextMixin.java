package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferItemSemantics;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemModelResolver.class)
abstract class GbufferItemResolverContextMixin {
	@WrapMethod(method = "updateForTopItem")
	private void mtlrender$rememberItem(ItemStackRenderState output, ItemStack item,
		ItemDisplayContext displayContext, Level level, ItemOwner owner, int seed, Operation<Void> original) {
		original.call(output, item, displayContext, level, owner, seed);
		if (dev.mikuteto.mtlrender.pack.gbuffer.GbufferSemanticContext.isActive()) {
			GbufferItemSemantics.remember(output, item);
		}
	}
}
