package dev.mikuteto.mtlrender.pack.gbuffer;

import net.minecraft.client.renderer.rendertype.RenderType;

public final class GbufferFeatureSemantics {
	private GbufferFeatureSemantics() {
	}

	public static void withSubmit(Object submit, RenderType renderType, Runnable action) {
		if (!GbufferSemanticContext.isActive()) {
			action.run();
			return;
		}
		GbufferSubmitMetadata metadata = GbufferSemanticContext.metadata(submit)
			.orElse(GbufferSemanticContext.currentMetadata());
		GbufferStage stage = GbufferPipelineCatalog.find(renderType == null ? null : renderType.pipeline())
			.map(GbufferPipelineSpec::stage)
			.orElseGet(() -> GbufferSemanticContext.featureStage()
				.filter(feature -> feature == GbufferFeatureStage.Outline)
			.map(feature -> GbufferStage.Lines)
			.orElse(metadata.stage()));
		GbufferSubmitMetadata refined = new GbufferSubmitMetadata(metadata.entityId(), metadata.blockEntityId(),
			metadata.currentRenderedItemId(), stage);
		try (GbufferSemanticContext.Scope ignored = GbufferSemanticContext.enter(refined)) {
			action.run();
		}
	}
}
