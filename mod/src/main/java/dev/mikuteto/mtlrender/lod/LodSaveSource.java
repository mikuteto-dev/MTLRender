package dev.mikuteto.mtlrender.lod;

import com.mojang.logging.LogUtils;
import dev.mikuteto.mtlrender.sys.Qos;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import org.slf4j.Logger;

public final class LodSaveSource {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int MAX_IN_FLIGHT = 64;
	private static final int UTILITY_THREADS = 2;
	private static final Semaphore IN_FLIGHT = new Semaphore(MAX_IN_FLIGHT);
	private static final AtomicBoolean INVALID_CONTEXT_LOGGED = new AtomicBoolean();
	private static final AtomicBoolean SATURATION_LOGGED = new AtomicBoolean();
	private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();
	private static final ExecutorService UTILITY_EXECUTOR = newExecutor();

	public LodSaveSource() {
	}

	public CompletableFuture<Optional<CompoundTag>> read(
		final IntegratedServer server,
		final ServerLevel level,
		final ResourceKey<Level> dimension,
		final ChunkPos pos
	) {
		return request(server, level, dimension, pos);
	}

	public static CompletableFuture<Optional<CompoundTag>> request(
		final IntegratedServer server,
		final ServerLevel level,
		final ResourceKey<Level> dimension,
		final ChunkPos pos
	) {
		if (!validContext(server, level, dimension, pos)) {
			warnOnce(INVALID_CONTEXT_LOGGED, "ignoring save read with an inactive singleplayer context");
			return CompletableFuture.completedFuture(Optional.empty());
		}
		if (!IN_FLIGHT.tryAcquire()) {
			warnOnce(SATURATION_LOGGED, "save read limit reached");
			return CompletableFuture.completedFuture(Optional.empty());
		}
		CompletableFuture<Optional<CompoundTag>> read;
		try {
			read = level.getChunkSource().chunkMap.read(pos);
		} catch (Throwable failure) {
			IN_FLIGHT.release();
			warnOnce(FAILURE_LOGGED, "save read could not be submitted", failure);
			return CompletableFuture.completedFuture(Optional.empty());
		}
		return read.handleAsync((value, failure) -> {
			try {
				if (failure != null) {
					warnOnce(FAILURE_LOGGED, "save read failed", failure);
					return Optional.empty();
				}
				return value == null ? Optional.empty() : value;
			} finally {
				IN_FLIGHT.release();
			}
		}, UTILITY_EXECUTOR);
	}

	private static boolean validContext(
		final IntegratedServer server,
		final ServerLevel level,
		final ResourceKey<Level> dimension,
		final ChunkPos pos
	) {
		if (server == null || level == null || dimension == null || pos == null || !server.isRunning()) {
			return false;
		}
		return dimension.equals(level.dimension()) && server.getLevel(dimension) == level;
	}

	private static ExecutorService newExecutor() {
		AtomicInteger sequence = new AtomicInteger();
		ThreadFactory factory = runnable -> {
			Thread thread = new Thread(() -> {
				Qos.setUtilityThread();
				runnable.run();
			}, "MTLRender-LodSave-" + sequence.getAndIncrement());
			thread.setDaemon(true);
			return thread;
		};
		return new ThreadPoolExecutor(
			UTILITY_THREADS,
			UTILITY_THREADS,
			0L,
			TimeUnit.MILLISECONDS,
			new ArrayBlockingQueue<>(MAX_IN_FLIGHT),
			factory,
			new ThreadPoolExecutor.AbortPolicy()
		);
	}

	private static void warnOnce(final AtomicBoolean logged, final String message) {
		if (logged.compareAndSet(false, true)) {
			LOGGER.warn("MTLRender {}", message);
		}
	}

	private static void warnOnce(final AtomicBoolean logged, final String message, final Throwable failure) {
		if (logged.compareAndSet(false, true)) {
			LOGGER.warn("MTLRender {}", message, failure);
		}
	}
}
