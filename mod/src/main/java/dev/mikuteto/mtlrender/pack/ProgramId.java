package dev.mikuteto.mtlrender.pack;

import java.util.Optional;

public enum ProgramId {
	Shadow("shadow", null),
	ShadowSolid("shadow_solid", Shadow),
	ShadowCutout("shadow_cutout", Shadow),
	ShadowWater("shadow_water", Shadow),
	ShadowEntities("shadow_entities", Shadow),
	ShadowLightning("shadow_lightning", ShadowEntities),
	ShadowBlock("shadow_block", Shadow),

	Basic("gbuffers_basic", null),
	Line("gbuffers_line", Basic),

	Textured("gbuffers_textured", Basic),
	TexturedLit("gbuffers_textured_lit", Textured),
	SkyBasic("gbuffers_skybasic", Basic),
	SkyTextured("gbuffers_skytextured", Textured),
	Clouds("gbuffers_clouds", Textured),

	Terrain("gbuffers_terrain", TexturedLit),
	TerrainSolid("gbuffers_terrain_solid", Terrain),
	TerrainCutout("gbuffers_terrain_cutout", Terrain),
	DamagedBlock("gbuffers_damagedblock", Terrain),

	Block("gbuffers_block", Terrain),
	BlockTrans("gbuffers_block_translucent", Block),
	BeaconBeam("gbuffers_beaconbeam", Textured),
	Item("gbuffers_item", TexturedLit),

	Entities("gbuffers_entities", TexturedLit),
	EntitiesTrans("gbuffers_entities_translucent", Entities),
	Lightning("gbuffers_lightning", Entities),
	Particles("gbuffers_particles", TexturedLit),
	ParticlesTrans("gbuffers_particles_translucent", Particles),
	EntitiesGlowing("gbuffers_entities_glowing", Entities),
	ArmorGlint("gbuffers_armor_glint", Textured),
	SpiderEyes("gbuffers_spidereyes", Textured),

	Hand("gbuffers_hand", TexturedLit),
	Weather("gbuffers_weather", TexturedLit),
	Water("gbuffers_water", Terrain),
	HandWater("gbuffers_hand_water", Hand),

	DhTerrain("dh_terrain", null),
	DhWater("dh_water", DhTerrain),
	DhGeneric("dh_generic", DhTerrain),
	DhShadow("dh_shadow", null),

	Final("final", null);

	private final String sourceName;
	private final ProgramId fallback;

	ProgramId(String sourceName, ProgramId fallback) {
		this.sourceName = sourceName;
		this.fallback = fallback;
	}

	public String sourceName() {
		return sourceName;
	}

	public Optional<ProgramId> fallback() {
		return Optional.ofNullable(fallback);
	}

	public static Optional<ProgramId> fromSourceName(String sourceName) {
		for (ProgramId id : values()) {
			if (id.sourceName.equals(sourceName)) {
				return Optional.of(id);
			}
		}
		return Optional.empty();
	}
}
