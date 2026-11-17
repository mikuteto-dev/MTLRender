package dev.mikuteto.mtlrender.pack;

import java.util.LinkedHashMap;
import java.util.Map;

public final class StandardMacros {
	public static final String MC_VERSION = "260200";
	public static final String IRIS_VERSION = "10800";
	public static final String MC_GL_VERSION = "460";
	public static final String MC_GLSL_VERSION = "460";

	private static final String[] FEATURES = {
		"SEPARATE_HARDWARE_SAMPLERS",
		"HIGHER_SHADOWCOLOR",
		"CUSTOM_IMAGES",
		"PER_BUFFER_BLENDING",
		"COMPUTE_SHADERS",
		"TESSELLATION_SHADERS",
		"ENTITY_TRANSLUCENT",
		"REVERSED_CULLING",
		"BLOCK_EMISSION_ATTRIBUTE",
		"CAN_DISABLE_WEATHER",
		"SSBO",
		"FADE_VARIABLE",
		"TEXTURE_FILTERING"
	};

	private static final String[] RENDER_STAGES = {
		"NONE",
		"SKY",
		"SUNSET",
		"CUSTOM_SKY",
		"SUN",
		"MOON",
		"STARS",
		"VOID",
		"TERRAIN_SOLID",
		"TERRAIN_CUTOUT_MIPPED",
		"TERRAIN_CUTOUT",
		"ENTITIES",
		"BLOCK_ENTITIES",
		"DESTROY",
		"OUTLINE",
		"DEBUG",
		"HAND_SOLID",
		"TERRAIN_TRANSLUCENT",
		"TRIPWIRE",
		"PARTICLES",
		"CLOUDS",
		"RAIN_SNOW",
		"WORLD_BORDER",
		"HAND_TRANSLUCENT"
	};

	private StandardMacros() {
	}

	public static Map<String, String> environment() {
		Map<String, String> macros = new LinkedHashMap<>();
		macros.put("MC_VERSION", MC_VERSION);
		macros.put("MC_GL_VERSION", MC_GL_VERSION);
		macros.put("MC_GLSL_VERSION", MC_GLSL_VERSION);
		macros.put("MC_MIPMAP_LEVEL", "4");
		macros.put("IRIS_VERSION", IRIS_VERSION);
		macros.put("IS_IRIS", "");
		macros.put("DISTANT_HORIZONS", "");
		macros.put("DISTANT_HORIZONS_TEXTURES", "");
		macros.put("MC_OS_MAC", "");
		macros.put("MC_GL_VENDOR_OTHER", "");
		macros.put("MC_GL_RENDERER_APPLE", "");
		macros.put("MC_RENDER_QUALITY", "1.0");
		macros.put("MC_SHADOW_QUALITY", "1.0");
		macros.put("MC_HAND_DEPTH", "0.125");
		macros.put("MC_NORMAL_MAP", "");
		macros.put("MC_SPECULAR_MAP", "");
		macros.put("MAX_COLOR_BUFFERS", "16");
		macros.put("IRIS_REQUIRES_SEPARATE_ENTITY_DRAWS", "");
		macros.put("IRIS_HAS_TRANSLUCENCY_SORTING", "");
		macros.put("IRIS_TAG_SUPPORT", "2");
		macros.put("PPT_NONE", "0");
		macros.put("PPT_RAIN", "1");
		macros.put("PPT_SNOW", "2");
		macros.put("DH_BLOCK_UNKNOWN", "0");
		macros.put("DH_BLOCK_LEAVES", "1");
		macros.put("DH_BLOCK_STONE", "2");
		macros.put("DH_BLOCK_WOOD", "3");
		macros.put("DH_BLOCK_METAL", "4");
		macros.put("DH_BLOCK_DIRT", "5");
		macros.put("DH_BLOCK_LAVA", "6");
		macros.put("DH_BLOCK_DEEPSLATE", "7");
		macros.put("DH_BLOCK_SNOW", "8");
		macros.put("DH_BLOCK_SAND", "9");
		macros.put("DH_BLOCK_TERRACOTTA", "10");
		macros.put("DH_BLOCK_NETHER_STONE", "11");
		macros.put("DH_BLOCK_WATER", "12");
		macros.put("DH_BLOCK_GRASS", "13");
		macros.put("DH_BLOCK_AIR", "14");
		macros.put("DH_BLOCK_ILLUMINATED", "15");

		for (String feature : FEATURES) {
			macros.put("IRIS_FEATURE_" + feature, "");
		}

		for (int index = 0; index < RENDER_STAGES.length; index++) {
			macros.put("MC_RENDER_STAGE_" + RENDER_STAGES[index], Integer.toString(index));
		}

		return macros;
	}

	public static Iterable<String> features() {
		return java.util.List.of(FEATURES);
	}
}
