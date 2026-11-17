package dev.mikuteto.mtlrender.debug;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.terrain.TerrainRenderer;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.slf4j.Logger;

public final class FrameStats {
	public record BackendFrame(
		long submitNanos, int draws, int passes, long words, long gpuNanos, long waitNanos, long encodeNanos, long drawableNanos, long commitNanos,
		long presents
	) {
	}

	public static final int PASS_RECORD_LONGS = 3;

	private static final Logger LOGGER = LogUtils.getLogger();
	private static final long NANOS_PER_MILLI = 1_000_000L;
	private static final long OVERLAY_WINDOW = 500 * NANOS_PER_MILLI;
	private static final FrameStats INSTANCE = new FrameStats();

	private final StatsWindow overlayWindow = new StatsWindow(OVERLAY_WINDOW);
	private final StatsWindow logWindow = Config.get().statsLog
		? new StatsWindow(Long.getLong("mtlrender.statsInterval", 5) * 1000 * NANOS_PER_MILLI)
		: null;
	private final Benchmark benchmark = Benchmark.fromSystemProperties();
	private final boolean overlayEnabled = Config.get().overlay && !Benchmark.enabled();
	private List<String> overlayLines = List.of();
	private long lastFrame;
	private BackendFrame pending;
	private GpuFrameTimer gpuTimer;
	private final LongArrayList gpuSamples = new LongArrayList();
	static {
		MemoryDiagnostics.global().registerPool("java.frame", () -> INSTANCE.retainedBytes());
	}

	private FrameStats() {
	}

	public static FrameStats get() {
		return INSTANCE;
	}

	public boolean enabled() {
		return overlayEnabled || logWindow != null || benchmark != null;
	}

	public boolean overlayEnabled() {
		return overlayEnabled;
	}

	public List<String> overlayLines() {
		return overlayLines;
	}

	public List<String> debugLines() {
		return overlayLines;
	}

	public void beforeSubmit() {
		if (!enabled()) {
			return;
		}
		if (gpuTimer != null) {
			gpuTimer.endFrame();
		}
	}

	public void recordBackend(BackendFrame frame) {
		if (!enabled()) {
			return;
		}
		pending = frame;
	}

	public void recordPasses(MemorySegment records, int count) {
		if (!enabled()) {
			return;
		}
		overlayWindow.addPasses(records, count);
		if (logWindow != null) {
			logWindow.addPasses(records, count);
		}
		if (benchmark != null) {
			benchmark.addPasses(records, count);
		}
	}

	public void onFrameSubmitted() {
		long now = System.nanoTime();
		if (!enabled()) {
			TerrainRenderer.get().onFrameSubmitted(now);
			return;
		}
		BackendFrame backend = pending;
		pending = null;
		TerrainRenderer.get().onFrameSubmitted(now);
		if (lastFrame == 0) {
			lastFrame = now;
			return;
		}
		long frameTime = now - lastFrame;
		lastFrame = now;
		if (gpuTimer == null) {
			gpuTimer = GpuFrameTimer.create();
		}
		gpuSamples.clear();
		gpuTimer.collect(gpuSamples);
		gpuTimer.beginFrame();
		overlayWindow.addGpu(gpuSamples);
		if (logWindow != null) {
			logWindow.addGpu(gpuSamples);
		}
		if (benchmark != null) {
			benchmark.addGpu(gpuSamples);
		}
		StatsWindow.Summary overlay = overlayWindow.add(now, frameTime, backend);
		if (overlay != null) {
			overlayLines = lines(overlay);
		}
		if (logWindow != null) {
			StatsWindow.Summary logged = logWindow.add(now, frameTime, backend);
			if (logged != null) {
				LOGGER.info("[stats] {}", String.join(" | ", lines(logged)));
			}
		}
		if (benchmark != null) {
			benchmark.onFrame(now, frameTime, backend);
		}
	}

	private long retainedBytes() {
		long bytes = overlayWindow.retainedBytes();
		if (logWindow != null) {
			bytes += logWindow.retainedBytes();
		}
		if (benchmark != null) {
			bytes += benchmark.retainedBytes();
		}
		return bytes;
	}

	private static List<String> lines(StatsWindow.Summary summary) {
		String head = summary.comparable()
			? String.format(
				Locale.ROOT,
				"%s %.0f fps  avg %.2f ms  p99 %.2f  max %.2f  1%%low %.0f",
				RenderSystem.getDevice().getDeviceInfo().backendName(),
				summary.fps(),
				summary.averageMs(),
				summary.p99Ms(),
				summary.maxMs(),
				summary.lowFps()
			)
			: String.format(Locale.ROOT, "%s offscreen not comparable  render %.0f fps", RenderSystem.getDevice().getDeviceInfo().backendName(), summary.fps());
		String gpu = Double.isNaN(summary.gpuFrameMs())
			? "gpu frame -"
			: String.format(Locale.ROOT, "gpu frame %.2f ms  p99 %.2f", summary.gpuFrameMs(), summary.gpuFrameP99Ms());
		List<String> lines = new ArrayList<>(List.of(head, gpu));
		StatsWindow.BackendSummary backend = summary.backend();
		if (backend != null) {
			lines.add(String.format(Locale.ROOT, "submit %.2f ms  gpu %.2f ms  shown %.0f fps", backend.submitMs(), backend.gpuMs(), backend.shownFps()));
			lines.add(String.format(
				Locale.ROOT, "wait %.2f  encode %.2f  drawable %.2f  commit %.2f", backend.waitMs(), backend.encodeMs(), backend.drawableMs(), backend.commitMs()
			));
			lines.add(String.format(Locale.ROOT, "draws %d  passes %d  words %d", backend.draws(), backend.passes(), backend.words()));
		}
		TerrainRenderer terrain = TerrainRenderer.get();
		lines.add(String.format(Locale.ROOT, "terrain occlusion effective=%s  ab=%s  variant=%s",
			terrain.effectiveOcclusionMode(), terrain.occlusionAb().isEmpty() ? "none" : terrain.occlusionAb(), terrain.occlusionAbVariant()));
		for (PassTotals.Entry pass : summary.topPasses()) {
			lines.add(String.format(Locale.ROOT, "%s %.2f ms", pass.name(), pass.ms()));
		}
		return lines;
	}
}
