package dev.mikuteto.mtlrender.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferPipelineCatalog;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferSemanticContext;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferStage;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import net.minecraft.client.renderer.feature.ShapeOutlineFeatureRenderer;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@Mixin({
	TextFeatureRenderer.class,
	NameTagFeatureRenderer.class,
	ShapeOutlineFeatureRenderer.class,
	CustomFeatureRenderer.class,
	FlameFeatureRenderer.class,
	ShadowFeatureRenderer.class,
	MovingBlockFeatureRenderer.class
})
abstract class GbufferSpecialFeatureMixin {
	@WrapMethod(method = "buildGroup")
	private void mtlrender$specialGroup(FeatureFrameContext context, List<? extends SubmitNode> submits,
		Operation<Void> original) {
		GbufferStage stage = stageFor(this, submits);
		try (GbufferSemanticContext.Scope semantic = GbufferSemanticContext.enterStage(stage)) {
			original.call(context, submits);
		}
	}

	private static GbufferStage stageFor(Object renderer, List<? extends SubmitNode> submits) {
		if (renderer instanceof TextFeatureRenderer || renderer instanceof NameTagFeatureRenderer) return GbufferStage.Text;
		if (renderer instanceof ShapeOutlineFeatureRenderer) return GbufferStage.Lines;
		if (renderer instanceof FlameFeatureRenderer || renderer instanceof ShadowFeatureRenderer) return GbufferStage.Entities;
		if (renderer instanceof MovingBlockFeatureRenderer) return GbufferStage.Blocks;
		if (renderer instanceof CustomFeatureRenderer && !submits.isEmpty()) {
			SubmitNode node = submits.getFirst();
			if (node instanceof CustomFeatureRenderer.Submit custom) {
				RenderType renderType = custom.renderType();
				return GbufferPipelineCatalog.find(renderType.pipeline())
					.map(spec -> spec.stage())
					.orElseGet(() -> GbufferSemanticContext.currentMetadata().stage());
			}
		}
		return GbufferSemanticContext.currentMetadata().stage();
	}
}
