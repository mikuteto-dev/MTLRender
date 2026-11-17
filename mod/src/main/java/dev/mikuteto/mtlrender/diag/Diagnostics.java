package dev.mikuteto.mtlrender.diag;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.mojang.logging.LogUtils;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.pack.PackManager;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.precision.PrecisionDecision;
import dev.mikuteto.mtlrender.pack.precision.PrecisionReport;
import dev.mikuteto.mtlrender.debug.FeatureDiagnostics;
import dev.mikuteto.mtlrender.debug.FrameStats;
import dev.mikuteto.mtlrender.debug.MemoryDiagnostics;
import dev.mikuteto.mtlrender.terrain.TerrainRenderer;
import dev.mikuteto.mtlrender.sys.GameMode;
import dev.mikuteto.mtlrender.tune.Calibration;
import dev.mikuteto.mtlrender.tune.Hardware;
import dev.mikuteto.mtlrender.tune.Tier;
import dev.mikuteto.mtlrender.tune.TuneProfile;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public final class Diagnostics {
	private static final Logger LOGGER = LogUtils.getLogger();

	private Diagnostics() {
	}

	public static void write(Hardware hardware, String deviceName) {
		write(hardware, deviceName, List.of());
	}

	public static void writeCurrent() {
		Hardware hardware = MetalAccess.hardware();
		if (hardware == null) {
			return;
		}
		List<String> stats = new ArrayList<>(FrameStats.get().debugLines());
		TerrainRenderer terrain = TerrainRenderer.get();
		long units = MetalAccess.statTotal(MetalAccess.CULL_UNITS);
		long kept = MetalAccess.statTotal(MetalAccess.CULL_KEPT_A) + MetalAccess.statTotal(MetalAccess.CULL_KEPT_B);
		stats.add("terrain_sections: " + terrain.lastSections());
		stats.add("terrain_units: " + terrain.lastUnits());
		stats.add("terrain_quads: " + terrain.lastQuads());
		stats.add("cull_kept_ratio: " + (units == 0 ? "-" : kept * 1.0 / units));
		FeatureDiagnostics.Snapshot features = FeatureDiagnostics.snapshot();
		stats.add("feature_candidates: " + features.candidates());
		stats.add("feature_cpu_rejected: " + features.cpuRejected());
		stats.add("feature_mask_rejected: " + features.maskRejected());
		stats.add("feature_forced_visible: " + features.forcedVisible());
		stats.add("feature_prepared: " + features.prepared());
		stats.add("feature_executed: " + features.executed());
		stats.add("feature_mask_requested: " + features.maskRequested());
		stats.add("feature_mask_result_ready: " + features.maskResultReady());
		stats.add("feature_fail_open: " + features.failOpen());
		stats.add("feature_native_tests: " + MetalAccess.statTotal(MetalAccess.FEATURE_TESTS));
		stats.add("feature_native_kept: " + MetalAccess.statTotal(MetalAccess.FEATURE_KEPT));
		stats.add("feature_native_hidden: " + MetalAccess.statTotal(MetalAccess.FEATURE_HIDDEN));
		write(hardware, MetalAccess.deviceName(), stats);
	}

	private static void write(Hardware hardware, String deviceName, List<String> stats) {
		Tier tier = Tier.of(hardware);
		List<String> collected = new ArrayList<>(stats);
		collected.addAll(shaderPrecisionLines(PackManager.get().active().orElse(null)));
		collected.addAll(MemoryDiagnostics.global().diagnosticLines());
		List<String> snapshot = List.copyOf(collected);
		Thread thread = new Thread(() -> write(hardware, tier, deviceName, snapshot), "mtlrender-diagnostics");
		thread.setDaemon(true);
		thread.start();
	}

	private static void write(Hardware hardware, Tier tier, String deviceName, List<String> stats) {
		try {
			Path logs = FabricLoader.getInstance().getGameDir().resolve("logs");
			Files.createDirectories(logs);
			Files.writeString(logs.resolve("mtlrender-diag.txt"), text(hardware, tier, deviceName, stats));
		} catch (IOException e) {
			LOGGER.warn("Failed to write logs/mtlrender-diag.txt", e);
		}
	}

	private static String text(Hardware hardware, Tier tier, String deviceName, List<String> stats) {
		Hardware.NativeCaps caps = hardware.nativeCaps();
		List<String> lines = new ArrayList<>();
		lines.add("mod: " + version("mtlrender"));
		lines.add("minecraft: " + version("minecraft"));
		lines.add("java: " + System.getProperty("java.version", "unknown"));
		lines.add("gc: " + ManagementFactory.getGarbageCollectorMXBeans().stream()
			.map(GarbageCollectorMXBean::getName)
			.collect(Collectors.joining(", ")));
		lines.add("chip: " + hardware.chip());
		lines.add("memory_bytes: " + hardware.memoryBytes());
		lines.add("performance_cores: " + hardware.performanceCores());
		lines.add("efficiency_cores: " + hardware.efficiencyCores());
		lines.add("os_version: " + hardware.osVersion());
		lines.add("os_build: " + hardware.osBuild());
		lines.add("gpu_cores: " + hardware.gpuCores());
		lines.add("apple_family: " + caps.appleFamily());
		lines.add("raytracing: " + caps.raytracing());
		lines.add("raytracing_from_render: " + caps.raytracingFromRender());
		lines.add("recommended_max_working_set: " + caps.recommendedMaxWorkingSet());
		lines.add("max_buffer_length: " + caps.maxBufferLength());
		lines.add("tier: " + tier);
		lines.add("device: " + deviceName);
		lines.add("present_mode: " + MetalAccess.presentMode());
		lines.add("gpu_memory_bytes: " + MetalAccess.memoryBytes());
		lines.addAll(stats);
		TuneProfile profile = TuneProfile.resolve(Config.get().terrain);
		lines.add("selected_indexed: " + profile.indexed());
		TerrainRenderer terrain = TerrainRenderer.get();
		lines.add("requested_occlusion: " + terrain.requestedOcclusionMode());
		lines.add("selected_occlusion: " + terrain.selectedOcclusionMode());
		lines.add("effective_occlusion: " + terrain.effectiveOcclusionMode());
		lines.add("occlusion_ab_name: " + (terrain.occlusionAb().isEmpty() ? "none" : terrain.occlusionAb()));
		lines.add("occlusion_ab_variant: " + terrain.occlusionAbVariant());
		lines.add("tune_occlusion: " + profile.occlusionMode());
		lines.add("selected_culling_interval: " + profile.cullingInterval());
		lines.add("selected_lod_min_ring: " + profile.lodMinRing());
		lines.add("selected_compact_vertices: " + profile.compactVertices());
		lines.add("calibration: " + Calibration.description());
		lines.add("calibration_state: " + Calibration.stateDescription());
		lines.add("calibration_key: " + Calibration.keyDescription());
		lines.add("calibration_pixel_resolution: " + Calibration.resolutionDescription());
		lines.addAll(GameMode.diagnosticLines());
		lines.add("");
		lines.add("config/mtlrender.json: " + config());
		return String.join("\n", lines) + "\n";
	}

	static List<String> shaderPrecisionLines(ShaderPack pack) {
		if (pack == null) {
			return List.of("shader_precision: no-loaded-plan");
		}
		return precisionReportLines(pack.precisionPlan().report());
	}

	static List<String> precisionReportLines(PrecisionReport report) {
		List<String> lines = new ArrayList<>();
		lines.add("shader_precision: loaded");
		for (PrecisionDecision decision : report.decisions()) {
			String state = decision.changed() ? "enabled" : "disabled";
			lines.add("shader_precision: target=" + decision.bufferId()
				+ " requested_format=" + decision.requestedFormat()
				+ " effective_format=" + decision.effectiveMetalFormat()
				+ " reduction=" + decision.reduction()
				+ " state=" + state
				+ " reason=" + decision.reason());
		}
		return List.copyOf(lines);
	}

	private static String version(String modId) {
		return FabricLoader.getInstance().getModContainer(modId)
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
	}

	private static String config() {
		Path path = FabricLoader.getInstance().getGameDir().resolve("config/mtlrender.json");
		if (!Files.exists(path)) {
			return "missing";
		}
		try {
			return Files.readString(path).strip();
		} catch (IOException e) {
			return "unreadable";
		}
	}
}
