package dev.mikuteto.mtlrender.debug;

public final class AbSwitch {
	public static String NAME = System.getProperty("mtlrender.bench.ab", "");
	public static final String PARTICLE_PULLING = "particlePulling";
	public static final String PARTICLE_LIGHT = "particleLight";
	public static final String PARTICLE_SORT = "particleSort";
	public static final String GUI_FRAME = "guiFrame";
	public static final String GLYPH_CACHE = "glyphCache";
	public static final String GUI_ATLAS = "guiAtlas";
	public static final String BLOCK_ENTITY_BAKE = "blockEntityBake";
	public static final String FEATURE_HIZ = "featureHiz";
	public static final String WEATHER_GPU = "weatherGpu";
	public static final String MISC_06 = "misc06";
	public static final String ENTITY_SPECIAL = "entitySpecial";
	public static final String FAR_ANIMATION = "farAnimation";
	private static volatile boolean variantB;

	private AbSwitch() {
	}

	public static boolean b() {
		return variantB;
	}

	public static boolean is(String name) {
		return is(name, false);
	}

	public static boolean is(String name, boolean configured) {
		if (!NAME.equals(name)) {
			return configured;
		}
		return variantB;
	}

	public static boolean misc06(final boolean configured) {
		return is(MISC_06, configured);
	}

	public static boolean guiFrame(final boolean configured) {
		return is(GUI_FRAME, configured);
	}

	public static boolean guiAtlas(final boolean configured) {
		return is(GUI_ATLAS, configured);
	}

	public static boolean benchmarkEnabled() {
		return switch (NAME) {
			case "particlePulling", "particleLight", "particleSort", "guiFrame", "glyphCache", "guiAtlas",
				"blockEntityBake", "featureHiz", "weatherGpu", "misc06", "entitySpecial", "farAnimation",
				"guiBatch", "entityBatch", "oit", "metalfx", "meshShader", "meshCheck", "lod", "translucentSort",
				"occlusion-cpu", "occlusion-single", "occlusion-off", "forceCull", "cpuUnits", "halfVaryings", "cutoutPrepass", "skipSolid", "skipCutout",
				"skipTranslucent", "skipFar", "skipPlants", "skipClouds" -> true;
			default -> false;
		};
	}

	public static void set(boolean value) {
		variantB = value;
	}
}
