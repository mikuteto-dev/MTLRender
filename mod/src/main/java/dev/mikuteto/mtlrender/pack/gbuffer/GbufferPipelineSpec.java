package dev.mikuteto.mtlrender.pack.gbuffer;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.mikuteto.mtlrender.pack.ProgramId;

public record GbufferPipelineSpec(
	RenderPipeline pipeline,
	ProgramId program,
	GbufferStage stage,
	boolean levelPipeline,
	InputProfile inputProfile
) {
	public enum InputProfile {
		Position,
		PositionColor,
		PositionTex,
		PositionTexColor,
		PositionTexLightmapColor,
		PositionColorLightmap,
		Block,
		Entity,
		Particle,
		Line,
		PositionColorLineWidth,
		Cloud
	}
}
