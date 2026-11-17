package dev.mikuteto.mtlrender.tune;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.mikuteto.mtlrender.diag.Diagnostics;
import dev.mikuteto.mtlrender.pack.tess.TessellationStrategy;
import dev.mikuteto.mtlrender.terrain.OcclusionMode;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public final class Calibration {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = calibrationPath();
	private static final Set<String> RUNNING = new HashSet<>();
	private static volatile Tier tier = Tier.MID;
	private static volatile TuneProfile active;
	private static volatile String activeKey = "none";
	private static volatile String activeResolution = "unknown";
	private static volatile String state = "uninitialized";

	private Calibration() {
	}

	private static Path calibrationPath() {
		try {
			Path config = FabricLoader.getInstance().getConfigDir();
			return config == null ? Path.of("config", "mtlrender-calibration.json") : config.resolve("mtlrender-calibration.json");
		} catch (RuntimeException e) {
			return Path.of("config", "mtlrender-calibration.json");
		}
	}

	private record Stored(String key, CalibrationResult profile) {
	}

	private record FileData(Map<String, Stored> entries) {
	}

	public static synchronized void start(Hardware hardware, String deviceName, int width, int height,
		BiFunction<Integer, Integer, CalibrationResult> runner) {
		if (width <= 0 || height <= 0) {
			return;
		}
		tier = Tier.of(hardware);
		String calibrationKey = key(hardware, width, height);
		if (calibrationKey.equals(activeKey) && active != null) {
			return;
		}
		active = null;
		activeKey = calibrationKey;
		activeResolution = width + "x" + height;
		Map<String, Stored> entries = read().entries();
		Stored stored = entries.get(calibrationKey);
		if (stored != null && valid(calibrationKey, stored.profile())) {
			select(calibrationKey, stored.profile());
			state = "loaded";
			return;
		}
		Stored legacy = entries.get(legacyKey(hardware));
		if (legacy != null && validLegacy(legacy.profile())) {
			CalibrationResult migrated = withResolution(legacy.profile(), width, height);
			entries.put(calibrationKey, new Stored(calibrationKey, migrated));
			write(new FileData(entries));
			select(calibrationKey, migrated);
			state = "loaded_legacy";
			return;
		}
		if (RUNNING.contains(calibrationKey)) {
			state = "pending";
			return;
		}
		if (runner == null) {
			state = "failed";
			LOGGER.warn("MTLRender calibration has no resolution-aware runner for {}", calibrationKey);
			return;
		}
		RUNNING.add(calibrationKey);
		state = "pending";
		Thread thread = new Thread(() -> run(hardware, deviceName, width, height, runner, calibrationKey), "mtlrender-calibration");
		thread.setDaemon(true);
		thread.start();
	}

	public static TuneProfile profile() {
		return active;
	}

	public static TuneProfile profile(int width, int height) {
		if (width <= 0 || height <= 0 || !activeResolution.equals(width + "x" + height)) {
			return null;
		}
		return active;
	}

	public static Tier tier() {
		return tier;
	}

	public static String description() {
		TuneProfile selected = active;
		String selectedMode = selected == null ? "tier_default" : selected.occlusionMode().configValue();
		return "state=" + state + ", key=" + activeKey + ", resolution=" + activeResolution + ", selected_mode="
			+ selectedMode + (selected == null ? "" : ", path=" + selected.drawPath() + ", " + selected);
	}

	public static String keyDescription() {
		return activeKey;
	}

	public static String resolutionDescription() {
		return activeResolution;
	}

	public static String stateDescription() {
		return state;
	}

	private static void run(Hardware hardware, String deviceName, int width, int height,
		BiFunction<Integer, Integer, CalibrationResult> runner, String calibrationKey) {
		try {
			CalibrationResult result = runner.apply(width, height);
			if (!valid(calibrationKey, result) || result.width() != width || result.height() != height) {
				markFailed(calibrationKey);
				LOGGER.warn("MTLRender calibration returned an invalid result for {}", calibrationKey);
				return;
			}
			boolean selected;
			synchronized (Calibration.class) {
				Map<String, Stored> entries = read().entries();
				entries.put(calibrationKey, new Stored(calibrationKey, result));
				write(new FileData(entries));
				selected = calibrationKey.equals(activeKey);
				if (selected) {
					select(calibrationKey, result);
					state = "loaded";
				}
			}
			if (selected) {
				LOGGER.info("MTLRender calibration selected: {}", result);
				Diagnostics.write(hardware, deviceName);
			}
		} catch (Exception e) {
			markFailed(calibrationKey);
			LOGGER.warn("MTLRender calibration failed for {}", calibrationKey, e);
		} finally {
			synchronized (Calibration.class) {
				RUNNING.remove(calibrationKey);
			}
		}
	}

	private static synchronized void markFailed(String calibrationKey) {
		if (calibrationKey.equals(activeKey)) {
			state = "failed";
		}
	}

	private static void select(String calibrationKey, CalibrationResult result) {
		active = new TuneProfile(result.drawPath(), result.occlusionMode(), result.probeInterval(), result.lodMinRing(), result.compact(), TessellationStrategy.COMPUTE);
		activeKey = calibrationKey;
		activeResolution = result.width() + "x" + result.height();
	}

	private static boolean valid(String calibrationKey, CalibrationResult result) {
		if (result == null || !calibrationKey.equals(keyForResolution(calibrationKey, result.width(), result.height()))) {
			return false;
		}
		try {
			new CalibrationResult(result.version(), result.occlusionMode(), result.drawPath(), result.indexed(), result.compact(),
				result.probeInterval(), result.lodMinRing(), result.width(), result.height());
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static String keyForResolution(String calibrationKey, int width, int height) {
		String[] parts = calibrationKey.split(":", -1);
		if (parts.length != 6) {
			return "";
		}
		return parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + parts[3] + ":" + width + ":" + height;
	}

	private static String key(Hardware hardware, int width, int height) {
		return version() + ":" + hardware.osBuild() + ":" + hardware.nativeCaps().appleFamily() + ":"
			+ hardware.gpuCores() + ":" + width + ":" + height;
	}

	private static String legacyKey(Hardware hardware) {
		return version() + ":" + hardware.osBuild() + ":" + hardware.nativeCaps().appleFamily() + ":" + hardware.gpuCores();
	}

	private static String version() {
		return FabricLoader.getInstance().getModContainer("mtlrender")
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
	}

	private static FileData read() {
		if (!Files.exists(PATH)) {
			return new FileData(new LinkedHashMap<>());
		}
		try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			JsonObject rawEntries = root.getAsJsonObject("entries");
			Map<String, Stored> entries = new LinkedHashMap<>();
			if (rawEntries == null) {
				return new FileData(entries);
			}
			for (Map.Entry<String, JsonElement> entry : rawEntries.entrySet()) {
				try {
					JsonObject raw = entry.getValue().getAsJsonObject();
					String storedKey = raw.has("key") ? raw.get("key").getAsString() : entry.getKey();
					CalibrationResult profile = decode(storedKey, raw.getAsJsonObject("profile"));
					if (entry.getKey().equals(storedKey) && (valid(storedKey, profile) || validLegacy(profile))) {
						entries.put(storedKey, new Stored(storedKey, profile));
					}
				} catch (RuntimeException e) {
					LOGGER.warn("Ignoring invalid calibration entry {}", entry.getKey(), e);
				}
			}
			return new FileData(entries);
		} catch (IOException | JsonParseException | IllegalStateException e) {
			LOGGER.warn("Could not read {}", PATH, e);
			return new FileData(new LinkedHashMap<>());
		}
	}

	private static boolean validLegacy(CalibrationResult result) {
		if (result == null || result.width() != 1 || result.height() != 1) {
			return false;
		}
		try {
			new CalibrationResult(result.version(), result.occlusionMode(), result.drawPath(), result.indexed(), result.compact(),
				result.probeInterval(), result.lodMinRing(), result.width(), result.height());
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static CalibrationResult withResolution(CalibrationResult result, int width, int height) {
		return new CalibrationResult(result.version(), result.occlusionMode(), result.drawPath(), result.indexed(), result.compact(),
			result.probeInterval(), result.lodMinRing(), width, height);
	}

	private static CalibrationResult decode(String calibrationKey, JsonObject raw) {
		if (raw == null) {
			return null;
		}
		if (raw.has("version") && raw.has("occlusionMode") && raw.has("drawPath")) {
			return GSON.fromJson(raw, CalibrationResult.class);
		}
		if (!raw.has("indexed") || !raw.has("occlusion")) {
			return null;
		}
		String[] parts = calibrationKey.split(":", -1);
		if (parts.length != 4 && parts.length != 6) {
			return null;
		}
		boolean indexed = raw.get("indexed").getAsBoolean();
		boolean occlusion = raw.get("occlusion").getAsBoolean();
		int width = raw.has("width") ? raw.get("width").getAsInt() : parts.length == 6 ? Integer.parseInt(parts[4]) : 1;
		int height = raw.has("height") ? raw.get("height").getAsInt() : parts.length == 6 ? Integer.parseInt(parts[5]) : 1;
		int interval = raw.has("cullingInterval") ? raw.get("cullingInterval").getAsInt() : 1;
		int lod = raw.has("lodMinRing") ? raw.get("lodMinRing").getAsInt() : 3;
		boolean compact = !raw.has("compactVertices") || raw.get("compactVertices").getAsBoolean();
		return new CalibrationResult(CalibrationResult.VERSION,
			occlusion ? OcclusionMode.TWO_PHASE_HIZ : OcclusionMode.OFF,
			indexed ? TerrainDrawPath.INDEXED : TerrainDrawPath.NON_INDEXED,
			indexed, compact, interval, lod, width, height);
	}

	private static void write(FileData data) {
		try {
			Path parent = PATH.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Path temporary = PATH.resolveSibling(PATH.getFileName() + ".tmp");
			JsonObject root = new JsonObject();
			if (Files.exists(PATH)) {
				try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
					JsonElement existing = JsonParser.parseReader(reader);
					if (existing.isJsonObject()) {
						root = existing.getAsJsonObject();
					}
				}
				catch (JsonParseException | IllegalStateException e) {
					root = new JsonObject();
				}
			}
			root.add("entries", GSON.toJsonTree(data.entries()));
			try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
				GSON.toJson(root, writer);
			}
			try {
				Files.move(temporary, PATH, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(temporary, PATH, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			LOGGER.warn("Could not write {}", PATH, e);
		}
	}
}
