package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferSemanticContext;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SimpleFeatureRenderPhase.class)
abstract class GbufferSubmitNodeMixin {
	@WrapMethod(method = "submit")
	private void mtlrender$recordSubmitNode(SubmitNode submit, Operation<Void> original) {
		if (GbufferSemanticContext.isActive()) {
			var metadata = GbufferSemanticContext.currentMetadata();
			GbufferSemanticContext.recordSubmit(submit, metadata);
			if (submit instanceof ModelFeatureRenderer.Submit<?> model) {
				GbufferSemanticContext.recordAssociation(model.pose(), metadata);
			} else if (submit instanceof BlockModelFeatureRenderer.Submit block) {
				GbufferSemanticContext.recordAssociation(block.pose(), metadata);
			} else if (submit instanceof ItemFeatureRenderer.Submit item) {
				GbufferSemanticContext.recordAssociation(item.pose(), metadata);
			} else if (submit instanceof MovingBlockFeatureRenderer.Submit moving) {
				GbufferSemanticContext.recordAssociation(moving.pose(), metadata);
			}
		}
		original.call(submit);
	}
}
