package dev.mikuteto.mtlrender.debug;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import com.mojang.logging.LogUtils;
import dev.mikuteto.mtlrender.chunk.ChunkLoadParallelism;
import dev.mikuteto.mtlrender.chunk.ServerChunkPrefetch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.slf4j.Logger;

public final class BenchWorld {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String SCENE_SET = System.getProperty("mtlrender.eval.sceneSet", "");
	private static final String SELECTOR = System.getProperty("mtlrender.eval.scene", System.getProperty("mtlrender.benchWorld", ""));
	private static final Optional<BenchScene> SCENE = selectScene();
	static final boolean ENABLED = Boolean.getBoolean("mtlrender.eval") && SCENE.isPresent() || !System.getProperty("mtlrender.benchWorld", "").isBlank() && SCENE.isPresent();
	private static final int RADIUS_CHUNKS = 33;
	private static final int VIEW_CHUNKS = 32;
	private static final int TELEPORT_Y = 200;
	private static final int MOPUP_PER_STEP = 8;
	private static final int SETTLE_STEPS = 40;
	private static final int MAX_STEPS = 40000;

	private enum Phase { START, WAIT_LEVEL, SPIRAL, MOPUP, SETTLE, SAVE, DONE }

	private static volatile Phase phase = Phase.START;
	private static final AtomicBoolean stepping = new AtomicBoolean();
	private static volatile List<BlockPos> points = List.of();
	private static int index = -1;
	private static int steps;
	private static int settle;
	private static volatile boolean mopUpPending;

	private BenchWorld() {
	}

	public static void tick(Minecraft minecraft) {
		if (!ENABLED) {
			return;
		}
		switch (phase) {
			case START -> waitForStart(minecraft);
			case WAIT_LEVEL -> waitForLevel(minecraft);
			default -> step(minecraft);
		}
	}

	private static void waitForStart(Minecraft minecraft) {
		if (minecraft.gui.screen() == null) {
			return;
		}
		BenchScene scene = SCENE.orElse(null);
		if (scene == null) {
			fail(minecraft, "unknown scene '" + SELECTOR + "' for set '" + SCENE_SET + "'");
			return;
		}
		Path worldDir = worldDir(minecraft, scene);
		minecraft.options.renderDistance().set(VIEW_CHUNKS);
		LOGGER.info("[benchWorld] scene {} set {} world {} seed {} path {} radius {} view {} settle {}",
			scene.id(), scene.setKind(), scene.worldName(), scene.seed(), scene.cameraPath().id(), RADIUS_CHUNKS, VIEW_CHUNKS, SETTLE_STEPS);
		if (Files.exists(worldDir)) {
			LOGGER.info("[benchWorld] reusing existing world {}", worldDir);
			minecraft.createWorldOpenFlows().openWorld(scene.worldName(), () -> fail(minecraft, "failed to open existing world " + worldDir));
		} else {
			LevelSettings settings = new LevelSettings(
				scene.worldName(), GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT
			);
			LOGGER.info("[benchWorld] creating scene {} (seed {}, preset {}, biome {})", scene.id(), scene.seed(), scene.terrain().worldPreset().identifier(), scene.terrain().biome().identifier());
			minecraft.createWorldOpenFlows().createFreshLevel(
				 scene.worldName(), settings, new WorldOptions(scene.seed(), true, false),
				 registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(scene.terrain().worldPreset()).value().createWorldDimensions(), null
			);
		}
		phase = Phase.WAIT_LEVEL;
	}

	private static void waitForLevel(Minecraft minecraft) {
		if (minecraft.level == null || minecraft.player == null || minecraft.gui.screen() != null) {
			if (++steps > MAX_STEPS) {
				fail(minecraft, "world did not load in " + MAX_STEPS + " ticks");
			}
			return;
		}
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}
		points = buildPoints(SCENE.orElseThrow());
		index = -1;
		steps = 0;
		LOGGER.info("[benchWorld] world ready; {} teleport points, scene path {}, radius {} chunks", points.size(), SCENE.orElseThrow().cameraPath().id(), RADIUS_CHUNKS);
		phase = Phase.SPIRAL;
	}

	static List<BlockPos> buildPoints(BenchScene scene) {
		int spacing = VIEW_CHUNKS - 4;
		int originX = (int) Math.floor(scene.spawn().x()) >> 4;
		int originZ = (int) Math.floor(scene.spawn().z()) >> 4;
		List<Integer> offsets = new ArrayList<>();
		for (int a = -RADIUS_CHUNKS; a <= RADIUS_CHUNKS; a += spacing) {
			offsets.add(a);
		}
		List<BlockPos> grid = new ArrayList<>();
		for (int x : offsets) {
			for (int z : offsets) {
				grid.add(new BlockPos((originX + x) * 16 + 8, TELEPORT_Y, (originZ + z) * 16 + 8));
			}
		}
		long centerX = (long) Math.floor(scene.spawn().x());
		long centerZ = (long) Math.floor(scene.spawn().z());
		grid.sort(Comparator.comparingLong(point -> {
			long dx = point.getX() - centerX;
			long dz = point.getZ() - centerZ;
			return dx * dx + dz * dz;
		}));
		return List.copyOf(grid);
	}

	private static void step(Minecraft minecraft) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || !stepping.compareAndSet(false, true)) return;
		server.execute(() -> {
			try { runStep(server, minecraft); } finally { stepping.set(false); }
		});
	}

	private static void runStep(MinecraftServer server, Minecraft minecraft) {
		steps++;
		if (steps > MAX_STEPS) { fail(minecraft, "generation did not finish in " + MAX_STEPS + " server steps"); return; }
		ServerLevel level = server.overworld();
		if (level.players().isEmpty()) return;
		if (steps % 20 == 0) LOGGER.info("[benchWorld] step {} phase {} chunk {}/{} loaded {}", steps, phase, Math.max(index, 0), points.size(), level.getChunkSource().getLoadedChunksCount());
		ServerPlayer player = level.players().getFirst();
		switch (phase) {
			case SPIRAL -> spiralStep(level, player);
			case MOPUP -> mopUpStep(level);
			case SETTLE -> settleStep(level, player);
			case SAVE -> saveStep(server, level, minecraft);
			default -> { }
		}
	}

	private static void spiralStep(ServerLevel level, ServerPlayer player) {
		if (index < 0) { index = 0; teleport(player, points.getFirst()); return; }
		BlockPos target = points.get(index);
		if (level.getChunkSource().getChunkNow(target.getX() >> 4, target.getZ() >> 4) == null) return;
		if (index + 1 >= points.size()) { phase = Phase.MOPUP; return; }
		index++;
		teleport(player, points.get(index));
	}

	private static void mopUpStep(ServerLevel level) {
		if (mopUpPending) return;
		BenchScene scene = SCENE.orElseThrow();
		int centerX = (int) Math.floor(scene.spawn().x()) >> 4;
		int centerZ = (int) Math.floor(scene.spawn().z()) >> 4;
		List<ChunkPos> selected = new ArrayList<>(MOPUP_PER_STEP);
		for (int cx = centerX - RADIUS_CHUNKS; cx <= centerX + RADIUS_CHUNKS && selected.size() < MOPUP_PER_STEP; cx++) {
			for (int cz = centerZ - RADIUS_CHUNKS; cz <= centerZ + RADIUS_CHUNKS && selected.size() < MOPUP_PER_STEP; cz++) {
				int dx = cx - centerX;
				int dz = cz - centerZ;
				if (dx * dx + dz * dz > RADIUS_CHUNKS * RADIUS_CHUNKS || level.getChunkSource().getChunkNow(cx, cz) != null) continue;
				selected.add(new ChunkPos(cx, cz));
			}
		}
		if (selected.isEmpty()) {
			phase = Phase.SETTLE;
			LOGGER.info("[benchWorld] all chunks within radius {} generated for path {}", RADIUS_CHUNKS, scene.cameraPath().id());
			return;
		}
		mopUpPending = true;
		ServerChunkPrefetch prefetch = new ServerChunkPrefetch(level.getChunkSource(), ChunkLoadParallelism.shared());
		prefetch.prefetch(selected, ChunkStatus.FULL, true, level.getServer()::execute)
			.whenComplete((ignored, failure) -> level.getServer().execute(() -> completeMopUp(failure)));
	}

	private static void completeMopUp(Throwable failure) {
		mopUpPending = false;
		if (failure == null) return;
		ServerChunkPrefetch.ChunkPrefetchException chunkFailure = findChunkFailure(failure);
		String reason = chunkFailure == null ? String.valueOf(failure) : "position " + chunkFailure.position() + ": " + chunkFailure.getMessage();
		fail(Minecraft.getInstance(), "mop-up prefetch failed: " + reason);
	}

	private static ServerChunkPrefetch.ChunkPrefetchException findChunkFailure(Throwable failure) {
		Throwable current = failure;
		while (current != null) {
			if (current instanceof ServerChunkPrefetch.ChunkPrefetchException chunkFailure) return chunkFailure;
			current = current.getCause();
		}
		return null;
	}

	private static void settleStep(ServerLevel level, ServerPlayer player) {
		if (settle == 0) { int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) SCENE.orElseThrow().spawn().x(), (int) SCENE.orElseThrow().spawn().z()); player.teleportTo(SCENE.orElseThrow().spawn().x(), groundY + 1.0, SCENE.orElseThrow().spawn().z()); }
		if (++settle >= SETTLE_STEPS) { LOGGER.info("[benchWorld] settling result settled after {} steps", settle); phase = Phase.SAVE; }
	}

	private static void saveStep(MinecraftServer server, ServerLevel level, Minecraft minecraft) {
		boolean saved = server.saveEverything(true, false, false);
		LOGGER.info("[benchWorld] settling result {} saved: {} loaded chunks, {} region files, {} server steps", saved ? "success" : "failure", level.getChunkSource().getLoadedChunksCount(), countRegions(minecraft), steps);
		if (!saved) { fail(minecraft, "save failed"); return; }
		phase = Phase.DONE;
		minecraft.execute(minecraft::stop);
	}

	private static void teleport(ServerPlayer player, BlockPos target) { player.teleportTo(target.getX(), target.getY(), target.getZ()); }

	private static long countRegions(Minecraft minecraft) {
		try (Stream<Path> files = Files.walk(worldDir(minecraft, SCENE.orElseThrow()))) { return files.filter(path -> path.getFileName().toString().endsWith(".mca")).count(); }
		catch (IOException | UncheckedIOException e) { LOGGER.warn("[benchWorld] failed to count region files", e); return 0; }
	}

	private static Path worldDir(Minecraft minecraft, BenchScene scene) { return minecraft.getLevelSource().getBaseDir().resolve(scene.worldName()); }

	private static Optional<BenchScene> selectScene() {
		if (SELECTOR.isBlank()) return Optional.empty();
		Optional<BenchScene> scene = BenchScenes.byId(SELECTOR);
		if (scene.isEmpty()) LOGGER.error("[benchWorld] unknown scene {}", SELECTOR);
		else if (!SCENE_SET.isBlank() && !scene.orElseThrow().setKind().name().equalsIgnoreCase(SCENE_SET.replace('-', '_'))) { LOGGER.error("[benchWorld] scene {} is not in set {}", SELECTOR, SCENE_SET); return Optional.empty(); }
		return scene;
	}

	private static void fail(Minecraft minecraft, String reason) { LOGGER.error("[benchWorld] failed: {}", reason); phase = Phase.DONE; minecraft.stop(); }
}
