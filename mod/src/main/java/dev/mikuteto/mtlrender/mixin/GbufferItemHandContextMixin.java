package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferItemSemantics;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferSemanticContext;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferStage;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferSubmitMetadata;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemInHandRenderer.class)
abstract class GbufferItemHandContextMixin {
	@WrapMethod(method = "renderItem")
	private void mtlrender$renderHand(LivingEntity mob, ItemStack itemStack, ItemDisplayContext type,
		PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords, Operation<Void> original) {
		if (!GbufferSemanticContext.isActive()) {
			original.call(mob, itemStack, type, poseStack, submitNodeCollector, lightCoords);
			return;
		}
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enter(
			new GbufferSubmitMetadata(0, 0, GbufferItemSemantics.itemId(itemStack), GbufferStage.Items))) {
			original.call(mob, itemStack, type, poseStack, submitNodeCollector, lightCoords);
		}
	}
}
