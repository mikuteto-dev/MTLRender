package dev.mikuteto.mtlrender.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import dev.mikuteto.mtlrender.pack.precision.PrecisionPolicy;
import dev.mikuteto.mtlrender.sys.GameMode;
import dev.mikuteto.mtlrender.terrain.OcclusionMode;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public final class Config {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("mtlrender.json");

	private static Config file;
	private static Config active;

	public boolean enabled = true;
	public String shaderPack = "";
	public ShaderPackPrecision shaderPackPrecision = new ShaderPackPrecision();
	public String present = "immediate";
	public boolean overlay = true;
	public boolean statsLog = false;
	public boolean debugMemory = false;
	public boolean integratedServer = true;
	public boolean assetRelease = true;
	public boolean hideRenderingWhenOccluded = true;
	public String gameMode = GameMode.DEFAULT_REQUEST;
	public MetalFx metalfx = new MetalFx();
	public Terrain terrain = new Terrain();
	public PathTracing pathTracing = new PathTracing();
	public Particles particles = new Particles();
	public Features features = new Features();
	public Gui gui = new Gui();
	public BlockEntities blockEntities = new BlockEntities();
	public Entities entities = new Entities();
	public Weather weather = new Weather();
	public Culling culling = new Culling();
	public Misc06 misc06 = new Misc06();
	public SystemConfig system = new SystemConfig();

	public static final class ShaderPackPrecision {
		public boolean reduce32To16 = false;
		public boolean reduceRgb11 = false;
	}

	public static final class PathTracing {
		public boolean enabled = false;
		public float resolutionScale = 0.5F;
		public int bounces = 1;
	}

	public static final class Particles {
		public boolean batching = true;
		public boolean pulling = true;
		public boolean light = true;
		public boolean sort = true;
	}

	public static final class Features {
		public boolean entityBatching = true;
		public boolean particleBatching = true;
		public boolean guiBatching = true;
	}

	public static final class Gui {
		public boolean batching = true;
		public boolean frameCache = true;
		public boolean frame = true;
		public boolean glyphCache = true;
		public boolean atlas = true;
		public boolean itemAtlas = true;
		public boolean mapAtlas = true;
		public boolean tooltipCache = true;
		public boolean nativeResolution = true;
		public boolean lowFrequency = false;
	}

	public static final class BlockEntities {
		public boolean bake = true;
		public boolean staticBake = true;
		public boolean resultCache = true;
		public boolean raycast = false;
		public boolean hizOcclusion = true;
	}

	public static final class Entities {
		public boolean batching = true;
		public boolean raycast = false;
		public boolean hiz = true;
		public boolean hizOcclusion = true;
		public boolean frameBackface = true;
		public boolean paintingBackface = true;
		public boolean shadowBatch = true;
		public boolean farAnimation = false;
		public boolean itemContentLod = false;
	}

	public static final class Weather {
		public boolean gpuGeneration = true;
		public boolean frustum = false;
	}

	public static final class Culling {
		public boolean entityRaycast = false;
		public boolean blockEntityRaycast = false;
		public boolean weatherFrustum = false;
	}

	public static final class Misc06 {
		public boolean lightmap = true;
		public boolean zeroFov = true;
		public boolean emptyState = true;
		public boolean soundHandles = false;
	}

	public static final class SystemConfig {
		public int unfocusedFps = 15;
		public boolean thermalTuning = false;
		public boolean lowPowerTuning = false;
		public boolean memoryPressureCacheDrop = true;
		public boolean memoryPressureReduceRenderDistance = false;
		public boolean idle = true;
		public int idleFrames = 3;
		public int idleCheckMs = 100;
	}

	public static final class MetalFx {
		public String mode = "off";
		public float scale = 0.7f;
		public boolean frameInterpolation = false;
		public boolean auto = false;
		public int targetFps = 60;
		public int hysteresis = 5;
	}

	public static final class Terrain {
		public boolean enabled = true;
		public boolean faceCulling = true;
		public String drawPath = "auto";
		public String tessellationStrategy = "auto";
		public String indexed = "auto";
		public String compactVertices = "auto";
		public String occlusion = "auto";
		public boolean halfVaryings = true;
		public boolean expand = true;
		public boolean lod = true;
		public boolean lodBeyondRenderDistance = true;
		public boolean lodGenerateMissing = false;
		public boolean rustMesher = true;
		public boolean lodDropPlants = true;
		public String cullingInterval = "auto";
		public String lodMinRing = "auto";
		public boolean translucentSort = true;
		public boolean translucentOit = false;
	}

	public PrecisionPolicy packPrecisionPolicy() {
		return PrecisionPolicy.fromConfig(shaderPackPrecision);
	}

	public static Config get() {
		if (active == null) {
			file = read();
			active = copy(file);
			applyOverrides(active);
		}
		return active;
	}

	public void save() {
		if (file == null) {
			get();
		}
		Config out = copy(this);
		restoreOverrides(out);
		write(out);
		file = out;
	}

	private static Config read() {
		if (!Files.exists(PATH)) {
			Config defaults = new Config();
			write(defaults);
			return defaults;
		}
		try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
			JsonObject object = GSON.fromJson(reader, JsonObject.class);
			normalizeLegacyFeatureSections(object);
			normalizeSections(object);
			normalizeGameMode(object);
			normalizeTerrain(object);
			normalizeGui(object);
			normalizeMetalFx(object);
			Config parsed = GSON.fromJson(object, Config.class);
			if (parsed == null) {
				return new Config();
			}
			if (parsed.gameMode == null) {
				parsed.gameMode = GameMode.DEFAULT_REQUEST;
			} else {
				parsed.gameMode = GameMode.Request.parse(parsed.gameMode).configValue();
			}
			if (parsed.metalfx == null) {
				parsed.metalfx = new MetalFx();
			}
			if (parsed.shaderPack == null) {
				parsed.shaderPack = "";
			}
			if (parsed.shaderPackPrecision == null) {
				parsed.shaderPackPrecision = new ShaderPackPrecision();
			}
			if (parsed.terrain == null) {
				parsed.terrain = new Terrain();
			}
			if (parsed.pathTracing == null) {
				parsed.pathTracing = new PathTracing();
			}
			if (parsed.particles == null) {
				parsed.particles = new Particles();
			}
			if (parsed.features == null) {
				parsed.features = new Features();
			}
			if (parsed.gui == null) {
				parsed.gui = new Gui();
			}
			if (parsed.blockEntities == null) {
				parsed.blockEntities = new BlockEntities();
			}
			if (parsed.entities == null) {
				parsed.entities = new Entities();
			}
			if (parsed.weather == null) {
				parsed.weather = new Weather();
			}
			if (parsed.culling == null) {
				parsed.culling = new Culling();
			}
			if (parsed.misc06 == null) {
				parsed.misc06 = new Misc06();
			}
			if (parsed.system == null) {
				parsed.system = new SystemConfig();
			}
			return parsed;
		} catch (IOException | JsonParseException | IllegalStateException e) {
			LOGGER.warn("Could not read {}", PATH, e);
			return new Config();
		}
	}

	private static void normalizeGui(JsonObject object) {
		if (object == null || !object.has("gui") || !object.get("gui").isJsonObject()) {
			return;
		}
		JsonObject gui = object.getAsJsonObject("gui");
		if (!gui.has("itemAtlas") || gui.get("itemAtlas").isJsonNull()) {
			gui.addProperty("itemAtlas", true);
		}
		if (!gui.has("mapAtlas") || gui.get("mapAtlas").isJsonNull()) {
			gui.addProperty("mapAtlas", true);
		}
		if (!gui.has("tooltipCache") || gui.get("tooltipCache").isJsonNull()) {
			gui.addProperty("tooltipCache", true);
		}
		if (!gui.has("nativeResolution") || gui.get("nativeResolution").isJsonNull()) {
			gui.addProperty("nativeResolution", true);
		}
		if (!gui.has("lowFrequency") || gui.get("lowFrequency").isJsonNull()) {
			gui.addProperty("lowFrequency", false);
		}
	}

	private static void normalizeMetalFx(JsonObject object) {
		if (!object.has("metalfx") || !object.get("metalfx").isJsonObject()) {
			return;
		}
		JsonObject metalfx = object.getAsJsonObject("metalfx");
		if (!metalfx.has("frameInterpolation")) {
			metalfx.addProperty("frameInterpolation", false);
		}
	}

	private static void normalizeLegacyFeatureSections(JsonObject object) {
		if (object == null) {
			return;
		}
		JsonObject features = object.has("features") && object.get("features").isJsonObject() ? object.getAsJsonObject("features") : null;
		JsonObject culling = object.has("culling") && object.get("culling").isJsonObject() ? object.getAsJsonObject("culling") : null;
		if (!object.has("particles")) {
			JsonObject particles = new JsonObject();
			if (features != null && features.has("particleBatching")) {
				particles.add("batching", features.get("particleBatching"));
			}
			object.add("particles", particles);
		}
		if (!object.has("gui")) {
			JsonObject gui = new JsonObject();
			if (features != null && features.has("guiBatching")) {
				gui.add("batching", features.get("guiBatching"));
			}
			object.add("gui", gui);
		}
		if (!object.has("entities")) {
			JsonObject entities = new JsonObject();
			if (features != null && features.has("entityBatching")) {
				entities.add("batching", features.get("entityBatching"));
			}
			if (culling != null && culling.has("entityRaycast")) {
				entities.add("raycast", culling.get("entityRaycast"));
			}
			object.add("entities", entities);
		}
		if (!object.has("blockEntities")) {
			JsonObject blockEntities = new JsonObject();
			if (culling != null && culling.has("blockEntityRaycast")) {
				blockEntities.add("raycast", culling.get("blockEntityRaycast"));
			}
			object.add("blockEntities", blockEntities);
		}
		if (!object.has("weather")) {
			JsonObject weather = new JsonObject();
			if (culling != null && culling.has("weatherFrustum")) {
				weather.add("frustum", culling.get("weatherFrustum"));
			}
			object.add("weather", weather);
		}
		JsonObject weather = object.has("weather") && object.get("weather").isJsonObject()
			? object.getAsJsonObject("weather") : null;
		if (weather != null && !weather.has("gpuGeneration") && weather.has("gpu")) {
			weather.add("gpuGeneration", weather.get("gpu"));
		}
	}

	private static void normalizeGameMode(JsonObject object) {
		if (object == null || !object.has("gameMode")) {
			return;
		}
		if (!object.get("gameMode").isJsonPrimitive() || !object.getAsJsonPrimitive("gameMode").isString()) {
			object.addProperty("gameMode", GameMode.DEFAULT_REQUEST);
			return;
		}
		object.addProperty("gameMode", GameMode.Request.parse(object.get("gameMode").getAsString()).configValue());
	}

	private static void normalizeSections(JsonObject object) {
		if (object == null) {
			return;
		}
		normalizeBooleanSection(object, "particles", "batching", "pulling", "light", "sort");
		normalizeBooleanSection(object, "features", "entityBatching", "particleBatching", "guiBatching");
		normalizeBooleanSection(object, "gui", "batching", "frameCache", "frame", "glyphCache", "atlas", "itemAtlas", "mapAtlas", "tooltipCache", "nativeResolution", "lowFrequency");
		normalizeBooleanSection(object, "blockEntities", "bake", "staticBake", "resultCache", "raycast", "hizOcclusion");
		normalizeBooleanSection(object, "entities", "batching", "raycast", "hiz", "hizOcclusion", "frameBackface", "paintingBackface", "shadowBatch", "farAnimation", "itemContentLod");
		normalizeBooleanSection(object, "weather", "gpuGeneration", "frustum");
		normalizeBooleanSection(object, "culling", "entityRaycast", "blockEntityRaycast", "weatherFrustum");
		normalizeBooleanSection(object, "misc06", "lightmap", "zeroFov", "emptyState", "soundHandles");
	}

	private static void normalizeBooleanSection(JsonObject object, String sectionName, String... fields) {
		if (!object.has(sectionName) || !object.get(sectionName).isJsonObject()) {
			if (object.has(sectionName)) {
				object.remove(sectionName);
			}
			return;
		}
		JsonObject section = object.getAsJsonObject(sectionName);
		for (String field : fields) {
			if (section.has(field) && !section.get(field).isJsonPrimitive()
				|| section.has(field) && !section.getAsJsonPrimitive(field).isBoolean()) {
				section.remove(field);
			}
		}
	}

	private static void normalizeTerrain(JsonObject object) {
		if (object == null || !object.has("terrain")) {
			return;
		}
		if (!object.get("terrain").isJsonObject()) {
			if (object.get("terrain").isJsonPrimitive() && object.getAsJsonPrimitive("terrain").isBoolean()) {
				JsonObject terrain = new JsonObject();
				terrain.addProperty("enabled", object.getAsJsonPrimitive("terrain").getAsBoolean());
				object.add("terrain", terrain);
			} else {
				object.remove("terrain");
			}
			return;
		}
		JsonObject terrain = object.getAsJsonObject("terrain");
		if (!terrain.has("drawPath") || terrain.get("drawPath").isJsonNull()) {
			terrain.addProperty("drawPath", "auto");
		}
		normalizeBoolean(terrain, "indexed");
		normalizeBoolean(terrain, "compactVertices");
		normalizeOcclusion(terrain);
		normalizeInteger(terrain, "cullingInterval");
		normalizeInteger(terrain, "lodMinRing");
	}

	private static void normalizeBoolean(JsonObject terrain, String name) {
		if (terrain.has(name) && terrain.get(name).isJsonPrimitive() && terrain.getAsJsonPrimitive(name).isBoolean()) {
			terrain.addProperty(name, terrain.get(name).getAsBoolean() ? "true" : "false");
		}
	}

	private static void normalizeOcclusion(JsonObject terrain) {
		if (!terrain.has("occlusion")) {
			return;
		}
		if (terrain.get("occlusion").isJsonPrimitive() && terrain.getAsJsonPrimitive("occlusion").isBoolean()) {
			terrain.addProperty("occlusion", terrain.getAsJsonPrimitive("occlusion").getAsBoolean() ? "two_phase" : "off");
			return;
		}
		if (!terrain.get("occlusion").isJsonPrimitive() || !terrain.getAsJsonPrimitive("occlusion").isString()) {
			terrain.addProperty("occlusion", OcclusionMode.AUTO.configValue());
			return;
		}
		terrain.addProperty("occlusion", OcclusionMode.fromString(terrain.get("occlusion").getAsString()).configValue());
	}

	private static void normalizeInteger(JsonObject terrain, String name) {
		if (!terrain.has(name)) {
			return;
		}
		if (terrain.get(name).isJsonPrimitive() && terrain.getAsJsonPrimitive(name).isNumber()) {
			terrain.addProperty(name, Integer.toString(terrain.get(name).getAsInt()));
		} else if (!terrain.get(name).isJsonPrimitive() || !terrain.getAsJsonPrimitive(name).isString()) {
			terrain.remove(name);
		}
	}

	private static void write(Config config) {
		try {
			Path parent = PATH.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(config, writer);
			}
		} catch (IOException e) {
			LOGGER.warn("Could not write {}", PATH, e);
		}
	}

	private static Config copy(Config source) {
		Config target = new Config();
		target.enabled = source.enabled;
		target.shaderPack = source.shaderPack;
		target.shaderPackPrecision.reduce32To16 = source.shaderPackPrecision.reduce32To16;
		target.shaderPackPrecision.reduceRgb11 = source.shaderPackPrecision.reduceRgb11;
		target.present = source.present;
		target.overlay = source.overlay;
		target.statsLog = source.statsLog;
		target.debugMemory = source.debugMemory;
		target.integratedServer = source.integratedServer;
		target.assetRelease = source.assetRelease;
		target.hideRenderingWhenOccluded = source.hideRenderingWhenOccluded;
		target.gameMode = GameMode.Request.parse(source.gameMode).configValue();
		target.pathTracing.enabled = source.pathTracing.enabled;
		target.pathTracing.resolutionScale = source.pathTracing.resolutionScale;
		target.pathTracing.bounces = source.pathTracing.bounces;
		target.particles.batching = source.particles.batching;
		target.particles.pulling = source.particles.pulling;
		target.particles.light = source.particles.light;
		target.particles.sort = source.particles.sort;
		target.features.entityBatching = source.features.entityBatching;
		target.features.particleBatching = source.features.particleBatching;
		target.features.guiBatching = source.features.guiBatching;
		target.gui.batching = source.gui.batching;
		target.gui.frameCache = source.gui.frameCache;
		target.gui.frame = source.gui.frame;
		target.gui.glyphCache = source.gui.glyphCache;
		target.gui.atlas = source.gui.atlas;
		target.gui.itemAtlas = source.gui.itemAtlas;
		target.gui.mapAtlas = source.gui.mapAtlas;
		target.gui.tooltipCache = source.gui.tooltipCache;
		target.gui.nativeResolution = source.gui.nativeResolution;
		target.gui.lowFrequency = source.gui.lowFrequency;
		target.blockEntities.bake = source.blockEntities.bake;
		target.blockEntities.staticBake = source.blockEntities.staticBake;
		target.blockEntities.resultCache = source.blockEntities.resultCache;
		target.blockEntities.raycast = source.blockEntities.raycast;
		target.blockEntities.hizOcclusion = source.blockEntities.hizOcclusion;
		target.entities.batching = source.entities.batching;
		target.entities.raycast = source.entities.raycast;
		target.entities.hiz = source.entities.hiz;
		target.entities.hizOcclusion = source.entities.hizOcclusion;
		target.entities.frameBackface = source.entities.frameBackface;
		target.entities.paintingBackface = source.entities.paintingBackface;
		target.entities.shadowBatch = source.entities.shadowBatch;
		target.entities.farAnimation = source.entities.farAnimation;
		target.entities.itemContentLod = source.entities.itemContentLod;
		target.weather.gpuGeneration = source.weather.gpuGeneration;
		target.weather.frustum = source.weather.frustum;
		target.culling.entityRaycast = source.culling.entityRaycast;
		target.culling.blockEntityRaycast = source.culling.blockEntityRaycast;
		target.culling.weatherFrustum = source.culling.weatherFrustum;
		target.misc06.lightmap = source.misc06.lightmap;
		target.misc06.zeroFov = source.misc06.zeroFov;
		target.misc06.emptyState = source.misc06.emptyState;
		target.misc06.soundHandles = source.misc06.soundHandles;
		target.system.unfocusedFps = source.system.unfocusedFps;
		target.system.thermalTuning = source.system.thermalTuning;
		target.system.lowPowerTuning = source.system.lowPowerTuning;
		target.system.memoryPressureCacheDrop = source.system.memoryPressureCacheDrop;
		target.system.memoryPressureReduceRenderDistance = source.system.memoryPressureReduceRenderDistance;
		target.system.idle = source.system.idle;
		target.system.idleFrames = source.system.idleFrames;
		target.system.idleCheckMs = source.system.idleCheckMs;
		target.metalfx.mode = source.metalfx.mode;
		target.metalfx.scale = source.metalfx.scale;
		target.metalfx.frameInterpolation = source.metalfx.frameInterpolation;
		target.metalfx.auto = source.metalfx.auto;
		target.metalfx.targetFps = source.metalfx.targetFps;
		target.metalfx.hysteresis = source.metalfx.hysteresis;
		target.terrain.enabled = source.terrain.enabled;
		target.terrain.faceCulling = source.terrain.faceCulling;
		target.terrain.drawPath = source.terrain.drawPath;
		target.terrain.indexed = source.terrain.indexed;
		target.terrain.compactVertices = source.terrain.compactVertices;
		target.terrain.occlusion = source.terrain.occlusion;
		target.terrain.halfVaryings = source.terrain.halfVaryings;
		target.terrain.expand = source.terrain.expand;
		target.terrain.lod = source.terrain.lod;
		target.terrain.lodBeyondRenderDistance = source.terrain.lodBeyondRenderDistance;
		target.terrain.lodGenerateMissing = source.terrain.lodGenerateMissing;
		target.terrain.rustMesher = source.terrain.rustMesher;
		target.terrain.lodDropPlants = source.terrain.lodDropPlants;
		target.terrain.cullingInterval = source.terrain.cullingInterval;
		target.terrain.lodMinRing = source.terrain.lodMinRing;
		target.terrain.translucentSort = source.terrain.translucentSort;
		target.terrain.translucentOit = source.terrain.translucentOit;
		return target;
	}

	private static void applyOverrides(Config config) {
		if (Boolean.getBoolean("mtlrender.disable")) {
			config.enabled = false;
		}
		String gameMode = System.getProperty("mtlrender.gameMode");
		if (gameMode != null) {
			config.gameMode = GameMode.Request.parse(gameMode).configValue();
		}
		String shaderPack = System.getProperty("mtlrender.shaderPack");
		if (shaderPack != null) {
			config.shaderPack = shaderPack;
		}
		String present = System.getProperty("mtlrender.present");
		if (present != null) {
			config.present = present;
		}
		String overlay = System.getProperty("mtlrender.overlay");
		if (overlay != null) {
			config.overlay = !"false".equals(overlay);
		}
		String statsLog = System.getProperty("mtlrender.statsLog");
		if (statsLog != null) {
			config.statsLog = Boolean.parseBoolean(statsLog);
		}
		String debugMemory = System.getProperty("mtlrender.debug.memory");
		if (debugMemory != null) {
			config.debugMemory = Boolean.parseBoolean(debugMemory);
		}
		String integratedServer = System.getProperty("mtlrender.memory.integratedServer");
		if (integratedServer != null) {
			config.integratedServer = Boolean.parseBoolean(integratedServer);
		}
		String assetRelease = System.getProperty("mtlrender.memory.assetRelease");
		if (assetRelease != null) {
			config.assetRelease = Boolean.parseBoolean(assetRelease);
		}
		String stopWhenHidden = System.getProperty("mtlrender.stopWhenHidden");
		if (stopWhenHidden != null) {
			config.hideRenderingWhenOccluded = Boolean.parseBoolean(stopWhenHidden);
		}
		overrideShaderPackPrecision(config);
		overrideSystem(config);
		overridePathTracing(config);
		overrideFeatures(config);
		overrideMisc06(config);
		overrideMetalFx(config);
		overrideTerrain(config);
	}

	private static void overrideShaderPackPrecision(Config config) {
		String reduce32To16 = System.getProperty(PrecisionPolicy.REDUCE_32_TO_16_PROPERTY);
		if (reduce32To16 != null) {
			config.shaderPackPrecision.reduce32To16 = Boolean.parseBoolean(reduce32To16);
		}
		String reduceRgb11 = System.getProperty(PrecisionPolicy.REDUCE_RGB11_PROPERTY);
		if (reduceRgb11 != null) {
			config.shaderPackPrecision.reduceRgb11 = Boolean.parseBoolean(reduceRgb11);
		}
	}

	private static void overrideSystem(Config config) {
		Integer unfocusedFps = Integer.getInteger("mtlrender.unfocusedFps");
		if (unfocusedFps != null) {
			config.system.unfocusedFps = unfocusedFps;
		}
		String thermalTuning = System.getProperty("mtlrender.thermalTuning");
		if (thermalTuning != null) {
			config.system.thermalTuning = Boolean.parseBoolean(thermalTuning);
		}
		String lowPowerTuning = System.getProperty("mtlrender.lowPowerTuning");
		if (lowPowerTuning != null) {
			config.system.lowPowerTuning = Boolean.parseBoolean(lowPowerTuning);
		}
		String memoryPressureCacheDrop = System.getProperty("mtlrender.memoryPressureCacheDrop");
		if (memoryPressureCacheDrop != null) {
			config.system.memoryPressureCacheDrop = Boolean.parseBoolean(memoryPressureCacheDrop);
		}
		String memoryPressureReduce = System.getProperty("mtlrender.memoryPressureReduceRenderDistance");
		if (memoryPressureReduce != null) {
			config.system.memoryPressureReduceRenderDistance = Boolean.parseBoolean(memoryPressureReduce);
		}
		String idle = System.getProperty("mtlrender.idle");
		if (idle != null) {
			config.system.idle = Boolean.parseBoolean(idle);
		}
		Integer idleFrames = Integer.getInteger("mtlrender.idleFrames");
		if (idleFrames != null) {
			config.system.idleFrames = idleFrames;
		}
		Integer idleCheckMs = Integer.getInteger("mtlrender.idleCheckMs");
		if (idleCheckMs != null) {
			config.system.idleCheckMs = idleCheckMs;
		}
	}

	private static void overridePathTracing(Config config) {
		String enabled = System.getProperty("mtlrender.pathTracing");
		if (enabled != null) {
			config.pathTracing.enabled = Boolean.parseBoolean(enabled);
		}
		String scale = System.getProperty("mtlrender.pathTracingScale");
		if (scale != null) {
			try {
				config.pathTracing.resolutionScale = Float.parseFloat(scale);
			} catch (NumberFormatException ignored) {
			}
		}
		Integer bounces = Integer.getInteger("mtlrender.pathTracingBounces");
		if (bounces != null) {
			config.pathTracing.bounces = bounces;
		}
	}

	private static void overrideFeatures(Config config) {
		override(config.features, "entityBatching", "mtlrender.entityBatching");
		override(config.features, "particleBatching", "mtlrender.particleBatching");
		override(config.features, "guiBatching", "mtlrender.guiBatching");
		override(config.culling, "entityRaycast", "mtlrender.entityRaycastCulling");
		override(config.culling, "blockEntityRaycast", "mtlrender.blockEntityRaycastCulling");
		override(config.culling, "weatherFrustum", "mtlrender.weatherFrustumCulling");
		override(config.particles, "batching", "mtlrender.particleBatching");
		override(config.particles, "pulling", "mtlrender.particlePulling");
		override(config.particles, "light", "mtlrender.particleLight");
		override(config.particles, "sort", "mtlrender.particleSort");
		override(config.gui, "batching", "mtlrender.guiBatching");
		override(config.gui, "frameCache", "mtlrender.guiFrame");
		override(config.gui, "frame", "mtlrender.guiFrame");
		override(config.gui, "glyphCache", "mtlrender.glyphCache");
		override(config.gui, "atlas", "mtlrender.guiAtlas");
		override(config.gui, "itemAtlas", "mtlrender.guiItemAtlas");
		override(config.gui, "mapAtlas", "mtlrender.guiMapAtlas");
		override(config.gui, "tooltipCache", "mtlrender.guiTooltipCache");
		override(config.gui, "nativeResolution", "mtlrender.guiNativeResolution");
		override(config.gui, "lowFrequency", "mtlrender.guiLowFrequency");
		override(config.blockEntities, "bake", "mtlrender.blockEntityBake");
		override(config.blockEntities, "raycast", "mtlrender.blockEntityRaycastCulling");
		override(config.blockEntities, "hizOcclusion", "mtlrender.featureHiz");
		override(config.entities, "batching", "mtlrender.entityBatching");
		override(config.entities, "raycast", "mtlrender.entityRaycastCulling");
		override(config.entities, "hiz", "mtlrender.featureHiz");
		override(config.entities, "hizOcclusion", "mtlrender.featureHiz");
		override(config.entities, "frameBackface", "mtlrender.frameBackface");
		override(config.entities, "paintingBackface", "mtlrender.paintingBackface");
		override(config.entities, "shadowBatch", "mtlrender.shadowBatch");
		override(config.entities, "farAnimation", "mtlrender.farEntityAnimation");
		override(config.entities, "itemContentLod", "mtlrender.itemContentLod");
		override(config.weather, "gpuGeneration", "mtlrender.weatherGpu");
		override(config.weather, "frustum", "mtlrender.weatherFrustumCulling");
	}

	private static void override(Object section, String field, String property) {
		String value = System.getProperty(property);
		if (value == null) {
			return;
		}
		try {
			section.getClass().getField(field).setBoolean(section, !"false".equals(value));
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void overrideMisc06(Config config) {
		override(config.misc06, "lightmap", "mtlrender.misc06.lightmap");
		override(config.misc06, "zeroFov", "mtlrender.misc06.zeroFov");
		override(config.misc06, "emptyState", "mtlrender.misc06.emptyState");
		override(config.misc06, "soundHandles", "mtlrender.misc06.soundHandles");
	}

	private static void overrideMetalFx(Config config) {
		String mode = System.getProperty("mtlrender.metalfx");
		if (mode != null) {
			config.metalfx.mode = mode;
		}
		String scale = System.getProperty("mtlrender.metalfx.scale");
		if (scale != null) {
			try {
				config.metalfx.scale = Float.parseFloat(scale);
			} catch (NumberFormatException ignored) {
			}
		}
		String frameInterpolation = System.getProperty("mtlrender.metalfx.frameInterpolation");
		if (frameInterpolation != null) {
			config.metalfx.frameInterpolation = Boolean.parseBoolean(frameInterpolation);
		}
		String auto = System.getProperty("mtlrender.metalfx.auto");
		if (auto != null) {
			config.metalfx.auto = Boolean.parseBoolean(auto);
		}
		String targetFps = System.getProperty("mtlrender.metalfx.targetFps");
		if (targetFps != null) {
			try {
				config.metalfx.targetFps = Integer.parseInt(targetFps);
			} catch (NumberFormatException ignored) {
			}
		}
		String hysteresis = System.getProperty("mtlrender.metalfx.hysteresis");
		if (hysteresis != null) {
			try {
				config.metalfx.hysteresis = Integer.parseInt(hysteresis);
			} catch (NumberFormatException ignored) {
			}
		}
	}

	private static void overrideTerrain(Config config) {
		String terrain = System.getProperty("mtlrender.terrain");
		if (terrain != null) {
			config.terrain.enabled = !"false".equals(terrain);
		}
		String faceCulling = System.getProperty("mtlrender.faceCulling");
		if (faceCulling != null) {
			config.terrain.faceCulling = !"false".equals(faceCulling);
		}
		String drawPath = System.getProperty("mtlrender.terrainDrawPath");
		if (drawPath != null) {
			config.terrain.drawPath = drawPath;
		}
		String indexed = System.getProperty("mtlrender.terrainIndexed");
		if (indexed != null) {
			config.terrain.indexed = Boolean.toString(!"false".equals(indexed));
		}
		String compactVertices = System.getProperty("mtlrender.compactVertices");
		if (compactVertices != null) {
			config.terrain.compactVertices = Boolean.toString(!"false".equals(compactVertices));
		}
		String occlusion = System.getProperty("mtlrender.occlusion");
		if (occlusion != null) {
			config.terrain.occlusion = OcclusionMode.fromString(occlusion).configValue();
		}
		String halfVaryings = System.getProperty("mtlrender.halfVaryings");
		if (halfVaryings != null) {
			config.terrain.halfVaryings = !"false".equals(halfVaryings);
		}
		String expand = System.getProperty("mtlrender.terrainExpand");
		if (expand != null) {
			config.terrain.expand = !"false".equals(expand);
		}
		String lod = System.getProperty("mtlrender.terrainLod");
		if (lod != null) {
			config.terrain.lod = !"false".equals(lod);
		}
		String lodBeyondRenderDistance = System.getProperty("mtlrender.lodBeyondRenderDistance");
		if (lodBeyondRenderDistance != null) {
			config.terrain.lodBeyondRenderDistance = !"false".equals(lodBeyondRenderDistance);
		}
		String lodGenerateMissing = System.getProperty("mtlrender.lodGenerateMissing");
		if (lodGenerateMissing != null) {
			config.terrain.lodGenerateMissing = "true".equals(lodGenerateMissing);
		}
		String rustMesher = System.getProperty("mtlrender.rustMesher");
		if (rustMesher != null) {
			config.terrain.rustMesher = !"false".equals(rustMesher);
		}
		String lodDropPlants = System.getProperty("mtlrender.lodDropPlants");
		if (lodDropPlants != null) {
			config.terrain.lodDropPlants = !"false".equals(lodDropPlants);
		}
		String cullingInterval = System.getProperty("mtlrender.cullingInterval");
		if (cullingInterval != null) {
			config.terrain.cullingInterval = cullingInterval;
		}
		String lodMinRing = System.getProperty("mtlrender.lodMinRing");
		if (lodMinRing != null) {
			config.terrain.lodMinRing = lodMinRing;
		}
		String translucentSort = System.getProperty("mtlrender.translucentSort");
		if (translucentSort != null) {
			config.terrain.translucentSort = !"false".equals(translucentSort);
		}
		String translucentOit = System.getProperty("mtlrender.translucentOit");
		if (translucentOit != null) {
			config.terrain.translucentOit = "true".equals(translucentOit);
		}
	}

	private static void restoreOverrides(Config config) {
		if (Boolean.getBoolean("mtlrender.disable")) {
			config.enabled = file.enabled;
		}
		if (System.getProperty("mtlrender.gameMode") != null) {
			config.gameMode = file.gameMode;
		}
		if (System.getProperty("mtlrender.shaderPack") != null) {
			config.shaderPack = file.shaderPack;
		}
		if (System.getProperty("mtlrender.present") != null) {
			config.present = file.present;
		}
		if (System.getProperty("mtlrender.overlay") != null) {
			config.overlay = file.overlay;
		}
		if (System.getProperty("mtlrender.statsLog") != null) {
			config.statsLog = file.statsLog;
		}
		if (System.getProperty("mtlrender.debug.memory") != null) {
			config.debugMemory = file.debugMemory;
		}
		if (System.getProperty("mtlrender.memory.integratedServer") != null) {
			config.integratedServer = file.integratedServer;
		}
		if (System.getProperty("mtlrender.memory.assetRelease") != null) {
			config.assetRelease = file.assetRelease;
		}
		if (System.getProperty("mtlrender.stopWhenHidden") != null) {
			config.hideRenderingWhenOccluded = file.hideRenderingWhenOccluded;
		}
		restoreShaderPackPrecisionOverrides(config);
		restoreSystemOverrides(config);
		restorePathTracingOverrides(config);
		restoreFeatureOverrides(config);
		restoreMisc06Overrides(config);
		restoreMetalFxOverrides(config);
		restoreTerrainOverrides(config);
	}

	private static void restoreShaderPackPrecisionOverrides(Config config) {
		if (System.getProperty(PrecisionPolicy.REDUCE_32_TO_16_PROPERTY) != null) {
			config.shaderPackPrecision.reduce32To16 = file.shaderPackPrecision.reduce32To16;
		}
		if (System.getProperty(PrecisionPolicy.REDUCE_RGB11_PROPERTY) != null) {
			config.shaderPackPrecision.reduceRgb11 = file.shaderPackPrecision.reduceRgb11;
		}
	}

	private static void restorePathTracingOverrides(Config config) {
		if (System.getProperty("mtlrender.pathTracing") != null) {
			config.pathTracing.enabled = file.pathTracing.enabled;
		}
		if (System.getProperty("mtlrender.pathTracingScale") != null) {
			config.pathTracing.resolutionScale = file.pathTracing.resolutionScale;
		}
		if (System.getProperty("mtlrender.pathTracingBounces") != null) {
			config.pathTracing.bounces = file.pathTracing.bounces;
		}
	}

	private static void restoreFeatureOverrides(Config config) {
		restore(config.features, file.features, "entityBatching", "mtlrender.entityBatching");
		restore(config.features, file.features, "particleBatching", "mtlrender.particleBatching");
		restore(config.features, file.features, "guiBatching", "mtlrender.guiBatching");
		restore(config.culling, file.culling, "entityRaycast", "mtlrender.entityRaycastCulling");
		restore(config.culling, file.culling, "blockEntityRaycast", "mtlrender.blockEntityRaycastCulling");
		restore(config.culling, file.culling, "weatherFrustum", "mtlrender.weatherFrustumCulling");
		restore(config.particles, file.particles, "batching", "mtlrender.particleBatching");
		restore(config.particles, file.particles, "pulling", "mtlrender.particlePulling");
		restore(config.particles, file.particles, "light", "mtlrender.particleLight");
		restore(config.particles, file.particles, "sort", "mtlrender.particleSort");
		restore(config.gui, file.gui, "batching", "mtlrender.guiBatching");
		restore(config.gui, file.gui, "frameCache", "mtlrender.guiFrame");
		restore(config.gui, file.gui, "frame", "mtlrender.guiFrame");
		restore(config.gui, file.gui, "glyphCache", "mtlrender.glyphCache");
		restore(config.gui, file.gui, "atlas", "mtlrender.guiAtlas");
		restore(config.gui, file.gui, "itemAtlas", "mtlrender.guiItemAtlas");
		restore(config.gui, file.gui, "mapAtlas", "mtlrender.guiMapAtlas");
		restore(config.gui, file.gui, "tooltipCache", "mtlrender.guiTooltipCache");
		restore(config.gui, file.gui, "nativeResolution", "mtlrender.guiNativeResolution");
		restore(config.gui, file.gui, "lowFrequency", "mtlrender.guiLowFrequency");
		restore(config.blockEntities, file.blockEntities, "bake", "mtlrender.blockEntityBake");
		restore(config.blockEntities, file.blockEntities, "raycast", "mtlrender.blockEntityRaycastCulling");
		restore(config.blockEntities, file.blockEntities, "hizOcclusion", "mtlrender.featureHiz");
		restore(config.entities, file.entities, "batching", "mtlrender.entityBatching");
		restore(config.entities, file.entities, "raycast", "mtlrender.entityRaycastCulling");
		restore(config.entities, file.entities, "hiz", "mtlrender.featureHiz");
		restore(config.entities, file.entities, "hizOcclusion", "mtlrender.featureHiz");
		restore(config.entities, file.entities, "frameBackface", "mtlrender.frameBackface");
		restore(config.entities, file.entities, "paintingBackface", "mtlrender.paintingBackface");
		restore(config.entities, file.entities, "shadowBatch", "mtlrender.shadowBatch");
		restore(config.entities, file.entities, "farAnimation", "mtlrender.farEntityAnimation");
		restore(config.entities, file.entities, "itemContentLod", "mtlrender.itemContentLod");
		restore(config.weather, file.weather, "gpuGeneration", "mtlrender.weatherGpu");
		restore(config.weather, file.weather, "frustum", "mtlrender.weatherFrustumCulling");
	}

	private static void restore(Object target, Object source, String field, String property) {
		if (System.getProperty(property) == null) {
			return;
		}
		try {
			target.getClass().getField(field).setBoolean(target, source.getClass().getField(field).getBoolean(source));
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void restoreMisc06Overrides(Config config) {
		restore(config.misc06, file.misc06, "lightmap", "mtlrender.misc06.lightmap");
		restore(config.misc06, file.misc06, "zeroFov", "mtlrender.misc06.zeroFov");
		restore(config.misc06, file.misc06, "emptyState", "mtlrender.misc06.emptyState");
		restore(config.misc06, file.misc06, "soundHandles", "mtlrender.misc06.soundHandles");
	}

	private static void restoreMetalFxOverrides(Config config) {
		if (System.getProperty("mtlrender.metalfx") != null) {
			config.metalfx.mode = file.metalfx.mode;
		}
		if (System.getProperty("mtlrender.metalfx.scale") != null) {
			config.metalfx.scale = file.metalfx.scale;
		}
		if (System.getProperty("mtlrender.metalfx.frameInterpolation") != null) {
			config.metalfx.frameInterpolation = file.metalfx.frameInterpolation;
		}
		if (System.getProperty("mtlrender.metalfx.auto") != null) {
			config.metalfx.auto = file.metalfx.auto;
		}
		if (System.getProperty("mtlrender.metalfx.targetFps") != null) {
			config.metalfx.targetFps = file.metalfx.targetFps;
		}
		if (System.getProperty("mtlrender.metalfx.hysteresis") != null) {
			config.metalfx.hysteresis = file.metalfx.hysteresis;
			}
		}

	private static void restoreSystemOverrides(Config config) {
		if (Integer.getInteger("mtlrender.unfocusedFps") != null) {
			config.system.unfocusedFps = file.system.unfocusedFps;
		}
		if (System.getProperty("mtlrender.thermalTuning") != null) {
			config.system.thermalTuning = file.system.thermalTuning;
		}
		if (System.getProperty("mtlrender.lowPowerTuning") != null) {
			config.system.lowPowerTuning = file.system.lowPowerTuning;
		}
		if (System.getProperty("mtlrender.memoryPressureCacheDrop") != null) {
			config.system.memoryPressureCacheDrop = file.system.memoryPressureCacheDrop;
		}
		if (System.getProperty("mtlrender.memoryPressureReduceRenderDistance") != null) {
			config.system.memoryPressureReduceRenderDistance = file.system.memoryPressureReduceRenderDistance;
		}
		if (System.getProperty("mtlrender.idle") != null) {
			config.system.idle = file.system.idle;
		}
		if (Integer.getInteger("mtlrender.idleFrames") != null) {
			config.system.idleFrames = file.system.idleFrames;
		}
		if (Integer.getInteger("mtlrender.idleCheckMs") != null) {
			config.system.idleCheckMs = file.system.idleCheckMs;
		}
	}

	private static void restoreTerrainOverrides(Config config) {
		if (System.getProperty("mtlrender.terrain") != null) {
			config.terrain.enabled = file.terrain.enabled;
		}
		if (System.getProperty("mtlrender.faceCulling") != null) {
			config.terrain.faceCulling = file.terrain.faceCulling;
		}
		if (System.getProperty("mtlrender.terrainDrawPath") != null) {
			config.terrain.drawPath = file.terrain.drawPath;
		}
		if (System.getProperty("mtlrender.terrainIndexed") != null) {
			config.terrain.indexed = file.terrain.indexed;
		}
		if (System.getProperty("mtlrender.compactVertices") != null) {
			config.terrain.compactVertices = file.terrain.compactVertices;
		}
		if (System.getProperty("mtlrender.occlusion") != null) {
			config.terrain.occlusion = file.terrain.occlusion;
		}
		if (System.getProperty("mtlrender.halfVaryings") != null) {
			config.terrain.halfVaryings = file.terrain.halfVaryings;
		}
		if (System.getProperty("mtlrender.terrainExpand") != null) {
			config.terrain.expand = file.terrain.expand;
		}
		if (System.getProperty("mtlrender.terrainLod") != null) {
			config.terrain.lod = file.terrain.lod;
		}
		if (System.getProperty("mtlrender.lodBeyondRenderDistance") != null) {
			config.terrain.lodBeyondRenderDistance = file.terrain.lodBeyondRenderDistance;
		}
		if (System.getProperty("mtlrender.lodGenerateMissing") != null) {
			config.terrain.lodGenerateMissing = file.terrain.lodGenerateMissing;
		}
		if (System.getProperty("mtlrender.rustMesher") != null) {
			config.terrain.rustMesher = file.terrain.rustMesher;
		}
		if (System.getProperty("mtlrender.lodDropPlants") != null) {
			config.terrain.lodDropPlants = file.terrain.lodDropPlants;
		}
		if (System.getProperty("mtlrender.cullingInterval") != null) {
			config.terrain.cullingInterval = file.terrain.cullingInterval;
		}
		if (System.getProperty("mtlrender.lodMinRing") != null) {
			config.terrain.lodMinRing = file.terrain.lodMinRing;
		}
		if (System.getProperty("mtlrender.translucentSort") != null) {
			config.terrain.translucentSort = file.terrain.translucentSort;
		}
		if (System.getProperty("mtlrender.translucentOit") != null) {
			config.terrain.translucentOit = file.terrain.translucentOit;
		}
	}
}
