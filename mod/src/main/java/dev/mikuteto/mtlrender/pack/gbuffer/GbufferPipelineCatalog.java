package dev.mikuteto.mtlrender.pack.gbuffer;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.mikuteto.mtlrender.pack.ProgramId;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class GbufferPipelineCatalog {
	private static final Map<RenderPipeline, GbufferPipelineSpec> SPECS = createSpecs();
	private static final Set<RenderPipeline> LEVEL_PIPELINES = createLevelPipelines();

	private GbufferPipelineCatalog() {
	}

	public static Optional<GbufferPipelineSpec> find(RenderPipeline pipeline) {
		return Optional.ofNullable(SPECS.get(pipeline));
	}

	public static Map<RenderPipeline, GbufferPipelineSpec> specs() {
		return SPECS;
	}

	public static Set<RenderPipeline> levelPipelines() {
		return LEVEL_PIPELINES;
	}

	private static Set<RenderPipeline> createLevelPipelines() {
		Set<RenderPipeline> pipelines = Collections.newSetFromMap(new IdentityHashMap<>());
		pipelines.addAll(SPECS.keySet());
		return Collections.unmodifiableSet(pipelines);
	}

	private static Map<RenderPipeline, GbufferPipelineSpec> createSpecs() {
		Map<RenderPipeline, GbufferPipelineSpec> specs = new IdentityHashMap<>();
		add(specs, RenderPipelines.SKY, ProgramId.SkyBasic, GbufferStage.Sky, GbufferPipelineSpec.InputProfile.Position);
		add(specs, RenderPipelines.END_SKY, ProgramId.SkyTextured, GbufferStage.Sky, GbufferPipelineSpec.InputProfile.PositionTexColor);
		add(specs, RenderPipelines.SUNRISE_SUNSET, ProgramId.SkyBasic, GbufferStage.Sky, GbufferPipelineSpec.InputProfile.PositionColor);
		add(specs, RenderPipelines.STARS, ProgramId.SkyBasic, GbufferStage.Sky, GbufferPipelineSpec.InputProfile.Position);
		add(specs, RenderPipelines.CELESTIAL, ProgramId.SkyTextured, GbufferStage.Sky, GbufferPipelineSpec.InputProfile.PositionTex);

		add(specs, RenderPipelines.CLOUDS, ProgramId.Clouds, GbufferStage.Clouds, GbufferPipelineSpec.InputProfile.Cloud);
		add(specs, RenderPipelines.FLAT_CLOUDS, ProgramId.Clouds, GbufferStage.Clouds, GbufferPipelineSpec.InputProfile.Cloud);

		add(specs, RenderPipelines.ENTITY_SOLID, ProgramId.Entities, GbufferStage.Entities, GbufferPipelineSpec.InputProfile.Entity);
		add(specs, RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD, ProgramId.Entities, GbufferStage.Entities, GbufferPipelineSpec.InputProfile.Entity);
		add(specs, RenderPipelines.ENTITY_CUTOUT_CULL, ProgramId.Entities, GbufferStage.Entities, GbufferPipelineSpec.InputProfile.Entity);
		add(specs, RenderPipelines.ENTITY_CUTOUT, ProgramId.Entities, GbufferStage.Entities, GbufferPipelineSpec.InputProfile.Entity);
		add(specs, RenderPipelines.ENTITY_CUTOUT_Z_OFFSET, ProgramId.Entities, GbufferStage.Entities, GbufferPipelineSpec.InputProfile.Entity);
		add(specs, RenderPipelines.ENTITY_CUTOUT_DISSOLVE, ProgramId.Entities, GbufferStage.Entities, GbufferPipelineSpec.InputProfile.Entity);
		add(specs, RenderPipelines.ENTITY_TRANSLUCENT, ProgramId.EntitiesTrans, GbufferStage.Entities, GbufferPipelineSpec.InputProfile.Entity);
		add(specs, RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE, ProgramId.EntitiesGlowing, GbufferStage.Entities, GbufferPipelineSpec.InputProfile.Entity);
		add(specs, RenderPipelines.ENTITY_TRANSLUCENT_CULL, ProgramId.EntitiesTrans, GbufferStage.Entities, GbufferPipelineSpec.InputProfile.Entity);
		add(specs, RenderPipelines.ARMOR_CUTOUT_NO_CULL, ProgramId.Entities, GbufferStage.Entities, GbufferPipelineSpec.InputProfile.Entity);
		add(specs, RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL, ProgramId.Entities, GbufferStage.Entities, GbufferPipelineSpec.InputProfile.Entity);
		add(specs, RenderPipelines.ARMOR_TRANSLUCENT, ProgramId.EntitiesTrans, GbufferStage.Entities, GbufferPipelineSpec.InputProfile.Entity);
		add(specs, RenderPipelines.ENTITY_SHADOW, ProgramId.ShadowEntities, GbufferStage.Entities, GbufferPipelineSpec.InputProfile.Entity);
		add(specs, RenderPipelines.EYES, ProgramId.SpiderEyes, GbufferStage.Entities, GbufferPipelineSpec.InputProfile.Entity);
		add(specs, RenderPipelines.ENERGY_SWIRL, ProgramId.EntitiesGlowing, GbufferStage.Entities, GbufferPipelineSpec.InputProfile.Entity);
		add(specs, RenderPipelines.BREEZE_WIND, ProgramId.EntitiesTrans, GbufferStage.Entities, GbufferPipelineSpec.InputProfile.Entity);
		add(specs, RenderPipelines.BANNER_PATTERN, ProgramId.Entities, GbufferStage.Entities, GbufferPipelineSpec.InputProfile.Entity);
		add(specs, RenderPipelines.END_CRYSTAL_BEAM, ProgramId.EntitiesTrans, GbufferStage.Entities, GbufferPipelineSpec.InputProfile.Entity);

		add(specs, RenderPipelines.ITEM_CUTOUT, ProgramId.Item, GbufferStage.Items, GbufferPipelineSpec.InputProfile.Entity);
		add(specs, RenderPipelines.ITEM_TRANSLUCENT, ProgramId.Item, GbufferStage.Items, GbufferPipelineSpec.InputProfile.Entity);
		add(specs, RenderPipelines.SOLID_BLOCK, ProgramId.Block, GbufferStage.Blocks, GbufferPipelineSpec.InputProfile.Block);
		add(specs, RenderPipelines.CUTOUT_BLOCK, ProgramId.Block, GbufferStage.Blocks, GbufferPipelineSpec.InputProfile.Block);
		add(specs, RenderPipelines.TRANSLUCENT_BLOCK, ProgramId.BlockTrans, GbufferStage.Blocks, GbufferPipelineSpec.InputProfile.Block);
		add(specs, RenderPipelines.CRUMBLING, ProgramId.DamagedBlock, GbufferStage.Blocks, GbufferPipelineSpec.InputProfile.Block);
		add(specs, RenderPipelines.BEACON_BEAM_OPAQUE, ProgramId.BeaconBeam, GbufferStage.Beacon, GbufferPipelineSpec.InputProfile.Block);
		add(specs, RenderPipelines.BEACON_BEAM_TRANSLUCENT, ProgramId.BeaconBeam, GbufferStage.Beacon, GbufferPipelineSpec.InputProfile.Block);
		add(specs, RenderPipelines.LEASH, ProgramId.Entities, GbufferStage.Leash, GbufferPipelineSpec.InputProfile.PositionColorLightmap);
		add(specs, RenderPipelines.WATER_MASK, ProgramId.Water, GbufferStage.Water, GbufferPipelineSpec.InputProfile.Position);
		add(specs, RenderPipelines.GLINT, ProgramId.ArmorGlint, GbufferStage.Glint, GbufferPipelineSpec.InputProfile.PositionTex);

		add(specs, RenderPipelines.LIGHTNING, ProgramId.Lightning, GbufferStage.Lightning, GbufferPipelineSpec.InputProfile.PositionColor);
		add(specs, RenderPipelines.DRAGON_RAYS, ProgramId.Lightning, GbufferStage.Lightning, GbufferPipelineSpec.InputProfile.PositionColor);
		add(specs, RenderPipelines.OPAQUE_PARTICLE, ProgramId.Particles, GbufferStage.Particles, GbufferPipelineSpec.InputProfile.Particle);
		add(specs, RenderPipelines.TRANSLUCENT_PARTICLE, ProgramId.ParticlesTrans, GbufferStage.Particles, GbufferPipelineSpec.InputProfile.Particle);
		add(specs, RenderPipelines.WEATHER_DEPTH_WRITE, ProgramId.Weather, GbufferStage.Weather, GbufferPipelineSpec.InputProfile.Particle);
		add(specs, RenderPipelines.WEATHER_NO_DEPTH_WRITE, ProgramId.Weather, GbufferStage.Weather, GbufferPipelineSpec.InputProfile.Particle);
		add(specs, RenderPipelines.LINES, ProgramId.Line, GbufferStage.Lines, GbufferPipelineSpec.InputProfile.Line);
		add(specs, RenderPipelines.LINES_TRANSLUCENT, ProgramId.Line, GbufferStage.Lines, GbufferPipelineSpec.InputProfile.Line);
		add(specs, RenderPipelines.LINES_DEPTH_BIAS, ProgramId.Line, GbufferStage.Lines, GbufferPipelineSpec.InputProfile.Line);
		add(specs, RenderPipelines.SECONDARY_BLOCK_OUTLINE, ProgramId.Line, GbufferStage.Lines, GbufferPipelineSpec.InputProfile.Line);
		add(specs, RenderPipelines.DEBUG_POINTS, ProgramId.Basic, GbufferStage.Lines, GbufferPipelineSpec.InputProfile.PositionColorLineWidth);
		add(specs, RenderPipelines.DEBUG_FILLED_BOX, ProgramId.Basic, GbufferStage.Lines, GbufferPipelineSpec.InputProfile.PositionColor);
		add(specs, RenderPipelines.DEBUG_QUADS, ProgramId.Basic, GbufferStage.Lines, GbufferPipelineSpec.InputProfile.PositionColor);
		add(specs, RenderPipelines.DEBUG_TRIANGLE_FAN, ProgramId.Basic, GbufferStage.Lines, GbufferPipelineSpec.InputProfile.PositionColor);
		add(specs, RenderPipelines.OUTLINE_CULL, ProgramId.EntitiesGlowing, GbufferStage.Lines, GbufferPipelineSpec.InputProfile.PositionTexColor);
		add(specs, RenderPipelines.OUTLINE_NO_CULL, ProgramId.EntitiesGlowing, GbufferStage.Lines, GbufferPipelineSpec.InputProfile.PositionTexColor);
		add(specs, RenderPipelines.WORLD_BORDER, ProgramId.Textured, GbufferStage.WorldBorder, GbufferPipelineSpec.InputProfile.PositionTex);
		add(specs, RenderPipelines.TEXT, ProgramId.Textured, GbufferStage.Text, GbufferPipelineSpec.InputProfile.PositionTexLightmapColor);
		add(specs, RenderPipelines.TEXT_BACKGROUND, ProgramId.Textured, GbufferStage.Text, GbufferPipelineSpec.InputProfile.PositionColorLightmap);
		add(specs, RenderPipelines.TEXT_GRAYSCALE, ProgramId.Textured, GbufferStage.Text, GbufferPipelineSpec.InputProfile.PositionTexLightmapColor);
		add(specs, RenderPipelines.TEXT_POLYGON_OFFSET, ProgramId.Textured, GbufferStage.Text, GbufferPipelineSpec.InputProfile.PositionTexLightmapColor);
		add(specs, RenderPipelines.TEXT_GRAYSCALE_POLYGON_OFFSET, ProgramId.Textured, GbufferStage.Text, GbufferPipelineSpec.InputProfile.PositionTexLightmapColor);
		add(specs, RenderPipelines.TEXT_SEE_THROUGH, ProgramId.Textured, GbufferStage.Text, GbufferPipelineSpec.InputProfile.PositionTexColor);
		add(specs, RenderPipelines.TEXT_BACKGROUND_SEE_THROUGH, ProgramId.Textured, GbufferStage.Text, GbufferPipelineSpec.InputProfile.PositionColor);
		add(specs, RenderPipelines.TEXT_GRAYSCALE_SEE_THROUGH, ProgramId.Textured, GbufferStage.Text, GbufferPipelineSpec.InputProfile.PositionTexColor);
		add(specs, RenderPipelines.END_PORTAL, ProgramId.Textured, GbufferStage.Portal, GbufferPipelineSpec.InputProfile.Position);
		add(specs, RenderPipelines.END_GATEWAY, ProgramId.Textured, GbufferStage.Portal, GbufferPipelineSpec.InputProfile.Position);
		return Collections.unmodifiableMap(specs);
	}

	private static void add(Map<RenderPipeline, GbufferPipelineSpec> specs, RenderPipeline pipeline, ProgramId program, GbufferStage stage, GbufferPipelineSpec.InputProfile inputProfile) {
		specs.put(pipeline, new GbufferPipelineSpec(pipeline, program, stage, true, inputProfile));
	}
}
