package dev.mikuteto.mtlrender.server.decode;

import dev.mikuteto.mtlrender.sys.Qos;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;

public final class ChunkDecodeExecutor implements AutoCloseable {
	private static final Object SERVERS_LOCK = new Object();
	private static final Map<MinecraftServer, ServerExecutor> SERVERS = new IdentityHashMap<>();
	private static final int MAX_THREADS = 8;
	private final ExecutorService executor;
	private final AtomicBoolean closed = new AtomicBoolean();

	public ChunkDecodeExecutor() {
		this(threadCount(Runtime.getRuntime().availableProcessors(), configuredThreads()));
	}

	ChunkDecodeExecutor(int threads) {
		if (threads < 1) {
			throw new IllegalArgumentException("threads must be positive");
		}
		AtomicInteger sequence = new AtomicInteger();
		this.executor = new ThreadPoolExecutor(
			threads,
			threads,
			0L,
			TimeUnit.MILLISECONDS,
			new java.util.concurrent.LinkedBlockingQueue<>(),
			runnable -> {
				int number = sequence.getAndIncrement();
				Thread thread = new Thread(() -> {
					Qos.setWorkerThread();
					runnable.run();
				}, "MTLRender-ChunkDecode-" + number);
				thread.setDaemon(true);
				return thread;
			}
		);
	}

	public static Executor forChunkMap(ChunkMap chunkMap, MinecraftServer server) {
		synchronized (SERVERS_LOCK) {
			ServerExecutor state = SERVERS.computeIfAbsent(server, ignored -> new ServerExecutor());
			state.owners.add(chunkMap);
			return state.executor.executor;
		}
	}

	public static void release(ChunkMap chunkMap, MinecraftServer server) {
		ServerExecutor state;
		synchronized (SERVERS_LOCK) {
			state = SERVERS.get(server);
			if (state == null) {
				return;
			}
			state.owners.remove(chunkMap);
			if (!state.owners.isEmpty()) {
				return;
			}
			SERVERS.remove(server);
		}
		state.executor.close();
	}

	static int threadCount(int availableProcessors, Integer configured) {
		int available = Math.max(1, availableProcessors);
		int defaultThreads = Math.max(1, Math.min(MAX_THREADS, available - 2));
		if (configured == null) {
			return defaultThreads;
		}
		return Math.max(1, Math.min(MAX_THREADS, Math.min(available - 1, configured)));
	}

	static Integer configuredThreads() {
		return Integer.getInteger("mtlrender.chunkDecodeThreads");
	}

	Executor executor() {
		return executor;
	}

	public int threadCount() {
		return ((ThreadPoolExecutor) executor).getCorePoolSize();
	}

	public boolean isShutdown() {
		return closed.get();
	}

	@Override
	public void close() {
		if (closed.compareAndSet(false, true)) {
			executor.shutdownNow();
		}
	}

	private static final class ServerExecutor {
		private final ChunkDecodeExecutor executor = new ChunkDecodeExecutor();
		private final Set<ChunkMap> owners = Collections.newSetFromMap(new IdentityHashMap<>());
	}
}
