package dev.mikuteto.mtlrender.debug;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.terrain.OcclusionMode;
import dev.mikuteto.mtlrender.terrain.TerrainRenderer;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

public final class Benchmark {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final long NANOS_PER_SECOND = 1_000_000_000L;
	private static final double NANOS_PER_MILLI = 1_000_000.0;
	private static final String MOTION = System.getProperty("mtlrender.bench.motion", "orbit");
	private static final boolean EVAL = Boolean.getBoolean("mtlrender.eval");
	private static final boolean FIXED_ENVIRONMENT = Boolean.getBoolean("mtlrender.bench.fixed");
	private static final int EVAL_REFRESH = Math.max(1, Integer.getInteger("mtlrender.eval.refresh", 60));
	private static final String EVAL_PATH_ID = System.getProperty("mtlrender.eval.path", "recorded");
	private static final CameraPath EVAL_PATH = evaluationPath();
	private static final double ORBIT_RADIUS = 48.0;
	private static final double ORBIT_SECONDS = 16.0;
	private static final double ORBIT_HEIGHT = 24.0;
	private static final long SETTLE_NANOS = 2_000_000_000L;
	private static final long SETTLED_NANOS = 3_000_000_000L;
	private static final long SETTLE_TIMEOUT_NANOS = 180_000_000_000L;
	private static final int AB_SETTLE_FRAMES = 60;
	private static final int AB_SHOT_FRAMES = 10;
	private static final int MAX_SAMPLES = 1_000_000;
	private static final String[] QUAD_KINDS = {"axis", "other", "unsorted"};

	private enum Phase {
		WAITING_FOR_WORLD,
		SETTLING,
		WARMUP,
		AB_SETTLE,
		AB_SHOT,
		MEASURING,
		DONE
	}

	private final Path output;
	private final long warmup;
	private final long duration;
	private final CameraPath evalPath = EVAL_PATH;
	private final boolean evaluation;
	private final List<String> rows = new ArrayList<>();
	private final Set<String> capturedImagePoints = new HashSet<>();
	private final LongArrayList frameTimes = new LongArrayList();
	private final LongArrayList gpuTimes = new LongArrayList();
	private final PassTotals passTimes = new PassTotals();
	private long presents = -1;
	private long screenFrames;
	private long terrainUnits;
	private long terrainQuads;
	private long terrainSections;
	private long terrainFrames;
	private long cullUnits;
	private long cullKeptA;
	private long cullKeptB;
	private long startCullFrames;
	private long startFrames;
	private long[] startQuadStats;
	private long[] startMergeStats;
	private boolean focusedAtStart;
	private Vec3 orbitCenter;
	private Vec3 evalAnchor;
	private long motionStart;
	private long evalFrame;
	private EvalEnvironment evalEnvironment;
	private long settledSince;
	private boolean settledTerrain;
	private boolean settlingTimeout;
	private long terrainLoadMillis;
	private Phase phase = Phase.WAITING_FOR_WORLD;
	private long phaseStart;
	private long measureStartMillis;
	private long renderCpuStart;
	private int counter;
	private int abSettleFrames;
	private int abShotFrames;
	private long abSumA;
	private long abFrameSumA;
	private int abFramesA;
	private long abSumB;
	private long abFrameSumB;
	private int abFramesB;
	private OcclusionMode abRequestedA;
	private OcclusionMode abSelectedA;
	private OcclusionMode abEffectiveA;
	private OcclusionMode abRequestedB;
	private OcclusionMode abSelectedB;
	private OcclusionMode abEffectiveB;
	private final boolean meshCheck = MeshCheck.enabled() && AbSwitch.NAME.isEmpty();
	private int[] meshCheckImageA;
	private int meshCheckWidth;
	private int meshCheckHeight;
	private MeshCheck.LayerCounts meshCheckLayersA;
	private MeshCheck.LayerCounts meshCheckLayersB;
	private MeshCheck.ImageComparison meshCheckComparison;
	private boolean meshCheckFailure;
	private String meshCheckFailureReason;

	private static CameraPath evaluationPath() {
		String sceneId = System.getProperty("mtlrender.eval.scene", "");
		if (!sceneId.isBlank()) {
			return BenchScenes.byId(sceneId).map(BenchScene::cameraPath).orElseGet(() -> CameraPath.recorded(EVAL_PATH_ID));
		}
		return CameraPath.recorded(EVAL_PATH_ID);
	}

	private Benchmark(Path output, long warmup, long duration, boolean evaluation) {
		this.output = output;
		this.warmup = warmup;
		this.duration = duration;
		this.evaluation = evaluation;
	}

	public static boolean enabled() {
		return Boolean.getBoolean("mtlrender.eval") || System.getProperty("mtlrender.bench.out") != null;
	}

	static Benchmark fromSystemProperties() {
		boolean evaluation = Boolean.getBoolean("mtlrender.eval");
		if (MeshCheck.enabled() && !AbSwitch.NAME.isEmpty()) {
			throw new IllegalArgumentException("mtlrender.debug.meshCheck cannot be combined with mtlrender.bench.ab");
		}
		String output = System.getProperty(evaluation ? "mtlrender.eval.out" : "mtlrender.bench.out");
		if (evaluation && output == null) output = "run/evaluation";
		if (output == null) return null;
		String prefix = evaluation ? "mtlrender.eval" : "mtlrender.bench";
		long warmup = Long.getLong(prefix + ".warmup", evaluation ? 5 : 20) * NANOS_PER_SECOND;
		long duration = Long.getLong(prefix + ".seconds", evaluation ? 10 : 30) * NANOS_PER_SECOND;
		return new Benchmark(Path.of(output), warmup, duration, evaluation);
	}

	void addGpu(LongArrayList samples) {
		if (phase == Phase.MEASURING) {
			for (int i = 0; i < samples.size() && gpuTimes.size() < MAX_SAMPLES; i++) {
				gpuTimes.add(samples.getLong(i));
			}
		}
	}

	void addPasses(MemorySegment records, int count) {
		if (phase == Phase.MEASURING) {
			passTimes.add(records, count);
		}
	}

	public void onFrame(long now, long frameTime, FrameStats.BackendFrame backend) {
		Minecraft minecraft = Minecraft.getInstance();
		if (phase != Phase.WAITING_FOR_WORLD && phase != Phase.DONE) {
			ChunkTiming.onFrame(now);
		}
		if ((phase == Phase.WARMUP || phase == Phase.AB_SETTLE || phase == Phase.AB_SHOT || phase == Phase.MEASURING) && minecraft.gui.screen() != null) {
			minecraft.gui.setScreen(null);
			if (phase == Phase.MEASURING) {
				screenFrames++;
			}
		}
		if ((phase == Phase.WARMUP || phase == Phase.MEASURING) && minecraft.player != null) {
			if (EVAL && phase == Phase.MEASURING) {
				applyEvaluationMotion(minecraft);
				captureEvaluationPoint(minecraft);
			} else if (!EVAL && "orbit".equals(MOTION)) {
				applyMotion(minecraft, now);
			}
		}
		switch (phase) {
			case WAITING_FOR_WORLD -> {
				if (minecraft.level != null && minecraft.player != null && minecraft.gui.screen() == null) {
					ChunkTiming.reset(now);
					phase = Phase.SETTLING;
					phaseStart = now;
					settledSince = now;
					placeCamera(minecraft, now);
					GLFW.glfwFocusWindow(minecraft.getWindow().handle());
					LOGGER.info("[bench] world ready, waiting for terrain");
				}
			}
			case SETTLING -> {
				int chunks = minecraft.level.getChunkSource().getLoadedChunksCount();
				int distance = minecraft.options.getEffectiveRenderDistance();
				boolean complete = chunks >= (int) (Math.PI * distance * distance * 0.95) && minecraft.levelRenderer.hasRenderedAllSections();
				if (!complete) {
					settledSince = now;
				}
				boolean settled = now - settledSince >= SETTLED_NANOS;
				if (settled || now - phaseStart >= SETTLE_TIMEOUT_NANOS) {
					LOGGER.info("[bench] terrain {} after {} ms ({} chunks, distance {}), warming up", settled ? "settled" : "NOT settled", (now - phaseStart) / 1_000_000, chunks, distance);
					settledTerrain = settled;
					settlingTimeout = !settled;
					terrainLoadMillis = (now - phaseStart) / 1_000_000;
					phase = Phase.WARMUP;
					phaseStart = now;
					motionStart = now;
				}
			}
			case WARMUP -> {
				if (now - phaseStart >= warmup) {
					if (meshCheck) {
						phase = Phase.AB_SETTLE;
						phaseStart = now;
						abSettleFrames = 0;
						MeshCheck.setVariant(MeshCheck.Variant.RASTER);
						LOGGER.info("[mesh-check] settling raster variant A");
					} else if (!AbSwitch.benchmarkEnabled()) {
						if (evaluation) {
							captureEvaluationPoint(minecraft, 0);
						} else {
							screenshot(minecraft, "screenshot.png");
						}
						startMeasuring(minecraft, now);
					} else {
						phase = Phase.AB_SETTLE;
						phaseStart = now;
						abSettleFrames = 0;
						AbSwitch.set(false);
						LOGGER.info("[bench] settling before variant A of {}", AbSwitch.NAME);
					}
				}
			}
			case AB_SETTLE -> {
				abSettleFrames++;
				if (abSettleFrames >= AB_SETTLE_FRAMES) {
					screenshot(minecraft, "screenshot.png");
					phase = Phase.AB_SHOT;
					phaseStart = now;
					abShotFrames = 0;
					if (meshCheck) {
						MeshCheck.setVariant(MeshCheck.Variant.MESH);
						LOGGER.info("[mesh-check] capturing mesh variant B");
					} else {
						AbSwitch.set(true);
						if (AbSwitch.BLOCK_ENTITY_BAKE.equals(AbSwitch.NAME)) {
							TerrainRenderer.get().markAllDirty();
						}
						LOGGER.info("[bench] capturing variant B of {}", AbSwitch.NAME);
					}
				}
			}
			case AB_SHOT -> {
				abShotFrames++;
				if (abShotFrames >= AB_SHOT_FRAMES) {
					screenshot(minecraft, "screenshot_b.png");
					if (meshCheck) {
						MeshCheck.setVariant(normalMeshVariant());
					} else {
						AbSwitch.set(false);
						if (AbSwitch.BLOCK_ENTITY_BAKE.equals(AbSwitch.NAME)) {
							TerrainRenderer.get().markAllDirty();
						}
					}
					startMeasuring(minecraft, now);
				}
			}
			case MEASURING -> {
				boolean ab = AbSwitch.benchmarkEnabled();
				if (ab) {
					boolean variantB = (counter / 120) % 2 == 1;
					boolean changed = AbSwitch.b() != variantB;
					AbSwitch.set(variantB);
					if (changed && AbSwitch.BLOCK_ENTITY_BAKE.equals(AbSwitch.NAME)) {
						TerrainRenderer.get().markAllDirty();
					}
				}
				if (frameTimes.size() < MAX_SAMPLES) {
					frameTimes.add(frameTime);
				}
				TerrainRenderer terrain = TerrainRenderer.get();
				recordOcclusionModes(terrain);
				terrainUnits += terrain.lastUnits();
				terrainQuads += terrain.lastQuads();
				terrainSections += terrain.lastSections();
				terrainFrames++;
				if (backend != null) {
					presents = Math.max(presents, 0) + backend.presents();
				}
				if (rows.size() < MAX_SAMPLES) {
					rows.add(backend == null
						? frameTime + ",,,,,,,,,,"
						: frameTime + "," + backend.submitNanos() + "," + backend.draws() + "," + backend.passes() + "," + backend.words() + "," + backend.gpuNanos()
							+ "," + backend.waitNanos() + "," + backend.encodeNanos() + "," + backend.drawableNanos() + "," + backend.commitNanos() + "," + backend.presents());
				}
				if (backend != null && ab && counter % 120 >= 8) {
					if (((counter - 8) / 120) % 2 == 1) {
						abSumB += backend.gpuNanos();
						abFrameSumB += frameTime;
						abFramesB++;
					} else {
						abSumA += backend.gpuNanos();
						abFrameSumA += frameTime;
						abFramesA++;
					}
				}
				counter++;
				if (now - phaseStart >= duration) {
					phase = Phase.DONE;
					if (ab) {
						AbSwitch.set(false);
					}
					finish(minecraft);
				}
			}
			case DONE -> {
			}
		}
	}

	private MeshCheck.Variant normalMeshVariant() {
		return "mesh".equalsIgnoreCase(System.getProperty("mtlrender.terrainPath", ""))
			? MeshCheck.Variant.MESH : MeshCheck.Variant.RASTER;
	}

	private String meshCheckLayerMismatch() {
		if (meshCheckLayersA == null || meshCheckLayersB == null) {
			return "solid:missing,cutout:missing,translucent:missing";
		}
		return String.format(Locale.ROOT, "solid:%b,cutout:%b,translucent:%b",
			meshCheckLayersA.solidUnits() != meshCheckLayersB.solidUnits(),
			meshCheckLayersA.cutoutUnits() != meshCheckLayersB.cutoutUnits(),
			meshCheckLayersA.translucentUnits() != meshCheckLayersB.translucentUnits());
	}

	private void startMeasuring(Minecraft minecraft, long now) {
		if (EVAL || FIXED_ENVIRONMENT) {
			freezeEvaluationEnvironment(minecraft);
		}
		phase = Phase.MEASURING;
		if (evaluation && !MetalAccess.evaluationStart()) {
			LOGGER.warn("[eval] native evaluation session could not be started");
		}
		counter = 0;
		StaticBlockEntityBakeStats.reset();
		if (AbSwitch.benchmarkEnabled()) {
			AbSwitch.set(false);
		}
		phaseStart = now;
		measureStartMillis = System.currentTimeMillis();
		renderCpuStart = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
		focusedAtStart = minecraft.getWindow().isFocused();
		cullUnits = MetalAccess.statTotal(MetalAccess.CULL_UNITS);
		cullKeptA = MetalAccess.statTotal(MetalAccess.CULL_KEPT_A);
		cullKeptB = MetalAccess.statTotal(MetalAccess.CULL_KEPT_B);
		startCullFrames = TerrainRenderer.get().cullFrames();
		startFrames = TerrainRenderer.get().frames();
		startQuadStats = TerrainRenderer.get().quadStats().clone();
		startMergeStats = TerrainRenderer.get().mergeStats().clone();
		LOGGER.info("[bench] measuring");
		if (EVAL) {
			LOGGER.info("[bench] evaluation started terrain_settled={} time=6000 weather=clear path={} waypoints={} refresh={}", settledTerrain, evalPath.id(), evalPath.waypoints().size(), EVAL_REFRESH);
		}
	}

	private void placeCamera(Minecraft minecraft, long now) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			LOGGER.warn("[bench] no integrated server; camera position is not fixed");
			return;
		}
		String rotation = System.getProperty("mtlrender.bench.rotation", "45 10");
		String command = "execute positioned over world_surface run tp @a ~ ~ ~ " + rotation;
		server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
		evalAnchor = new Vec3(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ());
		if (!EVAL && "orbit".equals(MOTION)) {
			server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamemode creative @a"));
			motionStart = now;
			orbitCenter = null;
		}
	}

	private void applyEvaluationMotion(Minecraft minecraft) {
		CameraPath.Transform transform = evalPath.sample(evalFrame / (double) EVAL_REFRESH);
		evalFrame++;
		var player = minecraft.player;
		Vec3 anchor = evalAnchor == null ? new Vec3(player.getX(), player.getY(), player.getZ()) : evalAnchor;
		player.getAbilities().mayfly = true;
		player.getAbilities().flying = true;
		player.setPos(anchor.x + transform.x(), anchor.y + transform.y(), anchor.z + transform.z());
		player.xo = player.getX();
		player.yo = player.getY();
		player.zo = player.getZ();
		player.setYRot(transform.yaw());
		player.yRotO = transform.yaw();
		player.setXRot(transform.pitch());
		player.xRotO = transform.pitch();
		player.setDeltaMovement(Vec3.ZERO);
	}

	private void applyMotion(Minecraft minecraft, long now) {
		var player = minecraft.player;
		if (orbitCenter == null) {
			if (now - motionStart >= SETTLE_NANOS) {
				orbitCenter = new Vec3(player.getX(), player.getY() + ORBIT_HEIGHT, player.getZ());
			}
			return;
		}
		double t = (now - motionStart - SETTLE_NANOS) / 1e9;
		double angle = t * 2 * Math.PI / ORBIT_SECONDS;
		double x = orbitCenter.x + ORBIT_RADIUS * Math.cos(angle);
		double y = orbitCenter.y + 4.0 * Math.sin(angle * 2);
		double z = orbitCenter.z + ORBIT_RADIUS * Math.sin(angle);
		double dirX = -Math.sin(angle);
		double dirZ = Math.cos(angle);
		float yaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ));
		float pitch = (float) (10.0 + 8.0 * Math.sin(angle * 3));
		player.getAbilities().mayfly = true;
		player.getAbilities().flying = true;
		player.setPos(x, y, z);
		player.xo = x;
		player.yo = y;
		player.zo = z;
		player.setYRot(yaw);
		player.yRotO = yaw;
		player.setXRot(pitch);
		player.xRotO = pitch;
		player.setDeltaMovement(Vec3.ZERO);
	}

	public void captureEvaluationFrame(Minecraft minecraft, String name) {
		if (EVAL) {
			screenshot(minecraft, name);
		}
	}

	public long evaluationFrame() {
		return evalFrame;
	}

	public CameraPath.Transform evaluationTransform() {
		return evalPath.sample(Math.max(0L, evalFrame - 1L) / (double) EVAL_REFRESH);
	}

	private List<String> imagePointIds() {
		String sceneId = System.getProperty("mtlrender.eval.scene", "");
		return BenchScenes.byId(sceneId).map(BenchScene::expectedImagePointIds).orElse(List.of("0"));
	}

	private void captureEvaluationPoint(Minecraft minecraft) {
		captureEvaluationPoint(minecraft, evalPath.sample(Math.max(0L, evalFrame - 1L) / (double) EVAL_REFRESH).waypointId());
	}

	private void captureEvaluationPoint(Minecraft minecraft, int waypointId) {
		List<String> points = imagePointIds();
		if (waypointId < 0 || waypointId >= points.size()) {
			return;
		}
		String pointId = points.get(waypointId);
		if (capturedImagePoints.add(pointId)) {
			screenshot(minecraft, "point-" + pointId + ".png");
		}
	}

	private void screenshot(Minecraft minecraft, String name) {
		Path file = output.resolve(name);
		Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), (NativeImage image) -> {
			try (image) {
				if (meshCheck && "screenshot.png".equals(name)) {
					meshCheckImageA = image.getPixels();
					meshCheckWidth = image.getWidth();
					meshCheckHeight = image.getHeight();
					meshCheckLayersA = TerrainRenderer.get().meshCheckState().counts();
				} else if (meshCheck && "screenshot_b.png".equals(name)) {
					meshCheckLayersB = TerrainRenderer.get().meshCheckState().counts();
					meshCheckComparison = MeshCheck.compare(meshCheckWidth, meshCheckHeight, meshCheckImageA, image.getPixels());
					if (!MeshCheck.sameLayerCounts(meshCheckLayersA, meshCheckLayersB)) {
						meshCheckFailure = true;
						meshCheckFailureReason = "layer count mismatch";
					} else if (!meshCheckComparison.shapeMatch() || meshCheckComparison.outsideLimit()) {
						meshCheckFailure = true;
						meshCheckFailureReason = meshCheckComparison.shapeMatch() ? "image difference exceeds 3.0 percent" : "missing or unreadable image";
					}
				}
				Files.createDirectories(output);
				image.writeToFile(file);
			} catch (IOException e) {
				if (meshCheck) {
					meshCheckFailure = true;
					meshCheckFailureReason = "missing or unreadable image";
				}
				LOGGER.error("[bench] failed to write {}", file, e);
			}
		});
	}

	private void freezeEvaluationEnvironment(Minecraft minecraft) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}
		GameRules rules = server.getGameRules();
		WeatherData weather = server.getWeatherData();
		long clock = server.overworld().dimensionType().defaultClock().map(server.clockManager()::getTotalTicks).orElse(-1L);
		evalEnvironment = new EvalEnvironment(
			rules.get(GameRules.ADVANCE_TIME), rules.get(GameRules.ADVANCE_WEATHER), rules.get(GameRules.RANDOM_TICK_SPEED), rules.get(GameRules.SPAWN_MOBS),
			rules.get(GameRules.SPAWN_MONSTERS), rules.get(GameRules.SPAWN_PATROLS), rules.get(GameRules.SPAWN_PHANTOMS), rules.get(GameRules.SPAWN_WANDERING_TRADERS),
			rules.get(GameRules.SPAWN_WARDENS), rules.get(GameRules.RAIDS), rules.get(GameRules.PLAYER_MOVEMENT_CHECK), clock,
			weather.getClearWeatherTime(), weather.getRainTime(), weather.getThunderTime(), weather.isRaining(), weather.isThundering()
		);
		rules.set(GameRules.ADVANCE_TIME, false, server);
		rules.set(GameRules.ADVANCE_WEATHER, false, server);
		rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
		rules.set(GameRules.SPAWN_MOBS, false, server);
		rules.set(GameRules.SPAWN_MONSTERS, false, server);
		rules.set(GameRules.SPAWN_PATROLS, false, server);
		rules.set(GameRules.SPAWN_PHANTOMS, false, server);
		rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
		rules.set(GameRules.SPAWN_WARDENS, false, server);
		rules.set(GameRules.PLAYER_MOVEMENT_CHECK, false, server);
		rules.set(GameRules.RAIDS, false, server);
		server.clockManager().setTotalTicks(server.overworld().dimensionType().defaultClock().orElseThrow(), 6000L);
		server.setWeatherParameters(0, Integer.MAX_VALUE, false, false);
	}

	private void restoreEvaluationEnvironment(Minecraft minecraft) {
		if (evalEnvironment == null || minecraft.getSingleplayerServer() == null) {
			return;
		}
		IntegratedServer server = minecraft.getSingleplayerServer();
		GameRules rules = server.getGameRules();
		rules.set(GameRules.ADVANCE_TIME, evalEnvironment.advanceTime(), server);
		rules.set(GameRules.ADVANCE_WEATHER, evalEnvironment.advanceWeather(), server);
		rules.set(GameRules.RANDOM_TICK_SPEED, evalEnvironment.randomTickSpeed(), server);
		rules.set(GameRules.SPAWN_MOBS, evalEnvironment.spawnMobs(), server);
		rules.set(GameRules.SPAWN_MONSTERS, evalEnvironment.spawnMonsters(), server);
		rules.set(GameRules.SPAWN_PATROLS, evalEnvironment.spawnPatrols(), server);
		rules.set(GameRules.SPAWN_PHANTOMS, evalEnvironment.spawnPhantoms(), server);
		rules.set(GameRules.SPAWN_WANDERING_TRADERS, evalEnvironment.spawnWanderingTraders(), server);
		rules.set(GameRules.SPAWN_WARDENS, evalEnvironment.spawnWardens(), server);
		rules.set(GameRules.PLAYER_MOVEMENT_CHECK, evalEnvironment.playerMovementCheck(), server);
		rules.set(GameRules.RAIDS, evalEnvironment.raids(), server);
		if (evalEnvironment.clock() >= 0) {
			server.clockManager().setTotalTicks(server.overworld().dimensionType().defaultClock().orElseThrow(), evalEnvironment.clock());
		}
		WeatherData weather = server.getWeatherData();
		weather.setClearWeatherTime(evalEnvironment.clearWeatherTime());
		weather.setRainTime(evalEnvironment.rainTime());
		weather.setThunderTime(evalEnvironment.thunderTime());
		weather.setRaining(evalEnvironment.raining());
		weather.setThundering(evalEnvironment.thundering());
	}

	private record EvalEnvironment(boolean advanceTime, boolean advanceWeather, int randomTickSpeed, boolean spawnMobs, boolean spawnMonsters,
		boolean spawnPatrols, boolean spawnPhantoms, boolean spawnWanderingTraders, boolean spawnWardens, boolean raids, boolean playerMovementCheck,
		long clock, int clearWeatherTime, int rainTime, int thunderTime, boolean raining, boolean thundering) {
	}

	private void finish(Minecraft minecraft) {
		if (evaluation) {
			if (EVAL) {
				restoreEvaluationEnvironment(minecraft);
			}
			finishEvaluation(minecraft);
			return;
		}
		if (EVAL || FIXED_ENVIRONMENT) {
			restoreEvaluationEnvironment(minecraft);
		}
		long[] sorted = frameTimes.toLongArray();		Arrays.sort(sorted);
		long renderCpuNanos = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - renderCpuStart;
		double renderCpuMs = renderCpuNanos / 1e6 / frameTimes.size();
		String presentMode = System.getProperty("mtlrender.present", "default");
		boolean offscreen = "offscreen".equals(presentMode);
		boolean presenting = presents >= 0 && !offscreen;
		double frameFps = sorted.length * (double) NANOS_PER_SECOND / duration;
		double shownFps = presents * (double) NANOS_PER_SECOND / duration;
		double reportedFps = presenting ? shownFps : frameFps;
		String summary = String.format(
			Locale.ROOT,
			"backend=%s%nwidth=%d%nheight=%d%nframes=%d%nfps=%.2f%nfps_source=%s%nfps_comparable=%b%nrender_cpu_ms=%.3f%navg_ms=%.3f%np50_ms=%.3f%np99_ms=%.3f%nlow1_fps=%.2f%n",
			RenderSystem.getDevice().getDeviceInfo().backendName(),
			minecraft.getWindow().getWidth(),
			minecraft.getWindow().getHeight(),
			sorted.length,
			reportedFps,
			presenting ? "shown_fps" : "render_frames",
			!offscreen,
			renderCpuMs,
			StatsWindow.average(sorted) / NANOS_PER_MILLI,
			StatsWindow.percentile(sorted, 0.50) / NANOS_PER_MILLI,
			StatsWindow.percentile(sorted, 0.99) / NANOS_PER_MILLI,
			NANOS_PER_SECOND / StatsWindow.onePercentLow(sorted)
		);
		long[] gpu = gpuTimes.toLongArray();
		Arrays.sort(gpu);
		if (gpu.length > 0) {
			summary += String.format(
				Locale.ROOT,
				"gpu_ms=%.3f%ngpu_p50_ms=%.3f%ngpu_p99_ms=%.3f%ngpu_samples=%d%n",
				StatsWindow.average(gpu) / NANOS_PER_MILLI,
				StatsWindow.percentile(gpu, 0.50) / NANOS_PER_MILLI,
				StatsWindow.percentile(gpu, 0.99) / NANOS_PER_MILLI,
				gpu.length
			);
		}
		if (!AbSwitch.NAME.isEmpty()) {
			double avgA = average(abSumA, abFramesA);
			double avgB = average(abSumB, abFramesB);
			double ratio = avgA > 0.0 ? avgB / avgA : 0.0;
			summary += String.format(
				Locale.ROOT,
				"ab=%s%ncandidate=%s%nresolution=%dx%d%na_requested_mode=%s%na_selected_mode=%s%na_effective_mode=%s%nb_requested_mode=%s%nb_selected_mode=%s%nb_effective_mode=%s%nab_a_gpu_ms=%.4f%nab_b_gpu_ms=%.4f%nab_a_frame_ms=%.4f%nab_b_frame_ms=%.4f%nab_ratio=%.4f%nab_frames=%d/%d%nvisual_acceptance=inspect screenshots for terrain omissions and pop-in excluding clouds and water, then verify translucent ordering%n",
				AbSwitch.NAME,
				AbSwitch.NAME,
				minecraft.getWindow().getWidth(),
				minecraft.getWindow().getHeight(),
				modeName(abRequestedA),
				modeName(abSelectedA),
				modeName(abEffectiveA),
				modeName(abRequestedB),
				modeName(abSelectedB),
				modeName(abEffectiveB),
				avgA / NANOS_PER_MILLI,
				avgB / NANOS_PER_MILLI,
				average(abFrameSumA, abFramesA) / NANOS_PER_MILLI,
				average(abFrameSumB, abFramesB) / NANOS_PER_MILLI,
				ratio,
				abFramesA,
				abFramesB
			);
		}
		if (AbSwitch.BLOCK_ENTITY_BAKE.equals(AbSwitch.NAME)) {
			StaticBlockEntityBakeStats.Snapshot statsA = StaticBlockEntityBakeStats.snapshot(false);
			StaticBlockEntityBakeStats.Snapshot statsB = StaticBlockEntityBakeStats.snapshot(true);
			summary += blockEntityStats("a", statsA);
			summary += blockEntityStats("b", statsB);
		} else if (AbSwitch.NAME.isEmpty()) {
			summary += blockEntityStats("normal", StaticBlockEntityBakeStats.snapshot(false));
		}
		summary += String.format(Locale.ROOT, "terrain_settled=%b%nsettling_timeout=%b%nrun_valid=%b%nterrain_load_ms=%d%nmeasure_start_ms=%d%nmeasure_end_ms=%d%nscreen_frames=%d%nfocused_start=%b%nfocused_end=%b%nfullscreen=%b%npresent=%s%noffscreen=%b%ncomparison=%s%nmotion=%s%neval=%b%neval_path=%s%neval_waypoints=%d%neval_refresh=%d%n",
			settledTerrain, settlingTimeout, settledTerrain && !settlingTimeout, terrainLoadMillis, measureStartMillis, System.currentTimeMillis(), screenFrames, focusedAtStart, minecraft.getWindow().isFocused(), minecraft.getWindow().isFullscreen(), presentMode, offscreen, offscreen ? "not comparable" : "comparable", MOTION, EVAL, evalPath.id(), evalPath.waypoints().size(), EVAL_REFRESH);
		if (terrainFrames > 0) {
			summary += String.format(Locale.ROOT, "terrain_sections=%.1f%nterrain_units=%.1f%nterrain_quads=%.1f%nterrain_lanes_used=%.4f%n",
				terrainSections / (double) terrainFrames,
				terrainUnits / (double) terrainFrames,
				terrainQuads / (double) terrainFrames,
				terrainQuads / (double) (terrainUnits * 32)
			);
			long units = MetalAccess.statTotal(MetalAccess.CULL_UNITS) - cullUnits;
			long keptA = MetalAccess.statTotal(MetalAccess.CULL_KEPT_A) - cullKeptA;
			long keptB = MetalAccess.statTotal(MetalAccess.CULL_KEPT_B) - cullKeptB;
			if (units > 0) {
				summary += String.format(
					Locale.ROOT, "cull_units=%.1f%ncull_kept_a=%.1f%ncull_kept_b=%.1f%ncull_kept_ratio=%.4f%n",
					units / (double) terrainFrames,
					keptA / (double) terrainFrames,
					keptB / (double) terrainFrames,
					(keptA + keptB) / (double) units
				);
			}
			long framesDelta = TerrainRenderer.get().frames() - startFrames;
			if (framesDelta > 0) {
				summary += String.format(Locale.ROOT, "cull_frame_share=%.4f%n",
					(TerrainRenderer.get().cullFrames() - startCullFrames) / (double) framesDelta);
			}
			long[] quadsEnd = TerrainRenderer.get().quadStats();
			for (int ring = 0; ring < 4; ring++) {
				for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
					for (int kind = 0; kind < QUAD_KINDS.length; kind++) {
						int index = (ring * 3 + layer.ordinal()) * 3 + kind;
						double perFrame = (quadsEnd[index] - startQuadStats[index]) / (double) terrainFrames;
						if (perFrame != 0.0) {
							summary += String.format(Locale.ROOT, "quads.r%d.%s.%s=%.1f%n",
								ring, layer.name().toLowerCase(Locale.ROOT), QUAD_KINDS[kind], perFrame);
						}
					}
				}
			}
			long[] mergedEnd = TerrainRenderer.get().mergeStats();
			for (int ring = 0; ring < 4; ring++) {
				for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
					for (int kind = 0; kind < QUAD_KINDS.length; kind++) {
						int index = (ring * 3 + layer.ordinal()) * 3 + kind;
						double perFrame = (mergedEnd[index] - startMergeStats[index]) / (double) terrainFrames;
						if (perFrame != 0.0) {
							summary += String.format(Locale.ROOT, "merged.r%d.%s.%s=%.1f%n",
								ring, layer.name().toLowerCase(Locale.ROOT), QUAD_KINDS[kind], perFrame);
						}
					}
				}
			}
		}
		if (meshCheck) {
			if (meshCheckComparison == null || meshCheckLayersA == null || meshCheckLayersB == null) {
				meshCheckFailure = true;
				meshCheckFailureReason = "missing or unreadable image";
			}
			String status = meshCheckFailure ? "FAIL" : "PASS";
			summary += String.format(
				Locale.ROOT,
				"mesh_check=%s%nmesh_check_reason=%s%nmesh_check_a_layers=%s%nmesh_check_b_layers=%s%nmesh_check_layer_match=%b%nmesh_check_layer_mismatch=%s%nmesh_check_mismatch_pixels=%d%nmesh_check_mismatch_percent=%.6f%nmesh_check_max_channel_delta=%d%nmesh_check_limit_percent=3.0%n",
				status,
				meshCheckFailureReason == null ? "none" : meshCheckFailureReason,
				meshCheckLayersA == null ? "missing" : Arrays.toString(meshCheckLayersA.inChunkSectionLayerOrder()),
				meshCheckLayersB == null ? "missing" : Arrays.toString(meshCheckLayersB.inChunkSectionLayerOrder()),
				MeshCheck.sameLayerCounts(meshCheckLayersA, meshCheckLayersB),
				meshCheckLayerMismatch(),
				meshCheckComparison == null ? 0 : meshCheckComparison.mismatchPixels(),
				meshCheckComparison == null ? 100.0 : meshCheckComparison.mismatchPercent(),
				meshCheckComparison == null ? 0 : meshCheckComparison.maximumChannelDifference()
			);
		}
		if (Boolean.getBoolean("mtlrender.debug.expandCheck")) {
			summary += String.format(
				Locale.ROOT,
				"expand_checked_units=%d%nexpand_mismatch_units=%d%nexpand_mismatch_layers=%d%n",
				MetalAccess.statTotal(MetalAccess.EXPAND_CHECKED),
				MetalAccess.statTotal(MetalAccess.EXPAND_MISMATCH_UNITS),
				MetalAccess.statTotal(MetalAccess.EXPAND_MISMATCH_LAYERS)
			);
		}
		if (presents >= 0) {
			summary += String.format(Locale.ROOT, "shown_fps=%.2f%n", shownFps);
		}
		StringBuilder timingSummary = new StringBuilder(summary);
		ChunkTiming.appendSummary(timingSummary);
		timingSummary.append(TerrainRenderer.get().packDiagnosticsSummary());
		summary = timingSummary.toString();
		if (passTimes.frames() > 0) {
			summary += String.format(Locale.ROOT, "pass_frames=%d%npass_gpu_total_ms=%.3f%n", passTimes.frames(), passTimes.totalMs());
			List<PassTotals.Entry> passes = passTimes.top(Integer.MAX_VALUE);
			for (int i = 0; i < passes.size(); i++) {
				PassTotals.Entry pass = passes.get(i);
				summary += String.format(
					Locale.ROOT, "pass.%02d=%.3f ms  x%.2f  passes %.2f  %s%n", i + 1, pass.ms(), pass.perFrame(), pass.passesPerFrame(), pass.name()
				);
			}
		}
		try {
			Files.createDirectories(output);
			List<String> csv = new ArrayList<>(rows.size() + 1);
			csv.add("frame_ns,submit_ns,draws,passes,words,gpu_ns,wait_ns,encode_ns,drawable_ns,commit_ns,presents");
			csv.addAll(rows);
			Files.write(output.resolve("frames.csv"), csv);
			Files.writeString(output.resolve("summary.txt"), summary);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (meshCheck) {
			if (meshCheckFailure) {
				LOGGER.error("[mesh-check] FAIL: reason={} mismatch_pixels={} mismatch_percent={} maximum_channel_delta={} layers_a={} layers_b={} layer_mismatch={}",
					meshCheckFailureReason,
					meshCheckComparison == null ? 0 : meshCheckComparison.mismatchPixels(),
					meshCheckComparison == null ? 100.0 : meshCheckComparison.mismatchPercent(),
					meshCheckComparison == null ? 0 : meshCheckComparison.maximumChannelDifference(),
					meshCheckLayersA == null ? "missing" : Arrays.toString(meshCheckLayersA.inChunkSectionLayerOrder()),
					meshCheckLayersB == null ? "missing" : Arrays.toString(meshCheckLayersB.inChunkSectionLayerOrder()),
					meshCheckLayerMismatch());
			} else {
				LOGGER.info("[mesh-check] PASS: mismatch_pixels={} mismatch_percent={} maximum_channel_delta={} layers={} layer_mismatch={}",
					meshCheckComparison.mismatchPixels(), meshCheckComparison.mismatchPercent(), meshCheckComparison.maximumChannelDifference(),
					Arrays.toString(meshCheckLayersA.inChunkSectionLayerOrder()), meshCheckLayerMismatch());
			}
		}
		LOGGER.info("[bench] done: {}", summary.replace(System.lineSeparator(), " "));
		minecraft.stop();
		if (meshCheck && meshCheckFailure) {
			throw new IllegalStateException("mesh-check failed: " + meshCheckFailureReason);
		}
	}

	private void recordOcclusionModes(TerrainRenderer terrain) {
		if (!AbSwitch.NAME.equals("occlusion-single") && !AbSwitch.NAME.equals("occlusion-cpu") && !AbSwitch.NAME.equals("occlusion-off")) {
			return;
		}
		if (AbSwitch.b()) {
			if (abSelectedB == null) {
				abRequestedB = terrain.requestedOcclusionMode();
				abSelectedB = terrain.selectedOcclusionMode();
				abEffectiveB = terrain.effectiveOcclusionMode();
			}
		} else if (abSelectedA == null) {
			abRequestedA = terrain.requestedOcclusionMode();
			abSelectedA = terrain.selectedOcclusionMode();
			abEffectiveA = terrain.effectiveOcclusionMode();
		}
	}

	private static double average(long sum, int count) {
		return count == 0 ? 0.0 : sum / (double) count;
	}

	private static String modeName(OcclusionMode mode) {
		return mode == null ? "unknown" : mode.configValue();
	}

	private static String blockEntityStats(String variant, StaticBlockEntityBakeStats.Snapshot stats) {
		return String.format(Locale.ROOT,
			"block_entity_%s_extracted=%d%nblock_entity_%s_dynamic_submissions=%d%nblock_entity_%s_static_submissions_skipped=%d%nblock_entity_%s_static_portions_published=%d%nblock_entity_%s_fallbacks=%d%nblock_entity_%s_failed_bakes=%d%nblock_entity_%s_retained_dynamic=%d%nblock_entity_%s_retained_exceptions=%d%n",
			variant, stats.extracted(), variant, stats.dynamicSubmissions(), variant, stats.skippedStatic(), variant, stats.staticPublished(), variant,
			stats.fallbacks(), variant, stats.failedBakes(), variant, stats.retainedDynamic(), variant, stats.retainedException());
	}

	long retainedBytes() {
		return (long) (frameTimes.size() + gpuTimes.size()) * Long.BYTES + rows.size() * 64L;
	}

	private void finishEvaluation(Minecraft minecraft) {
		long submitted = MetalAccess.submittedFrames();
		boolean fifoComplete = submitted == 0 || MetalAccess.waitEvaluationFrames(submitted - 1, 10_000);
		List<EvaluationFrame> records = fifoComplete ? MetalAccess.evaluationRead(4096) : List.of();
		MetalAccess.evaluationStop();
		int refresh = EVAL_REFRESH;
		String sceneSet = System.getProperty("mtlrender.eval.sceneSet", "default");
		String scene = System.getProperty("mtlrender.eval.scene", "default");
		String adverse = System.getProperty("mtlrender.eval.adverse", "none");
		boolean imageGate = Boolean.getBoolean("mtlrender.eval.imageValid");
		EvaluationReport.Result result = EvaluationReport.create(
			records,
			refresh,
			settledTerrain ? terrainLoadMillis : -1,
			sceneSet,
			scene,
			adverse,
			MetalAccess.evaluationEnergySource(),
			imageGate
		);
		String summary = result.summary() + "fifo_complete=" + fifoComplete + "\n" + "comparison=" + (result.comparable() ? "comparable" : "not_comparable") + "\n";
		try {
			EvaluationReport.write(output, new EvaluationReport.Result(result.frames(), summary, result.csv(), result.comparable()));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		LOGGER.info("[eval] done: valid={} presented={} output={}", result.comparable(), result.frames().size(), output);
		minecraft.stop();
	}
}
