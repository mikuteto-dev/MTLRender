package dev.mikuteto.mtlrender.server.io;

import dev.mikuteto.mtlrender.server.ChunkLoadPriority;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

public final class ParallelRegionReader implements AutoCloseable {
	public static final int WORKER_COUNT = 4;
	public static final int QUEUE_CAPACITY = 64;
	public static final int MAX_RETAINED_REGIONS = 64;

	interface Owner extends AutoCloseable {
		CompoundTag read(ChunkPos pos) throws Exception;

		@Override
		void close() throws Exception;
	}

	@FunctionalInterface
	interface Opener {
		Owner open(long regionKey) throws Exception;
	}

	@FunctionalInterface
	public interface PendingWrites {
		Optional<CompoundTag> get(ChunkPos pos);
	}

	private final ExecutorService executor;
	private final boolean ownsExecutor;
	private final int maxRetainedRegions;
	private final Opener opener;
	private final Map<Long, Lane> lanes = new HashMap<>();
	private final List<CompletableFuture<?>> accepted = new ArrayList<>();
	private final AtomicLong sequence = new AtomicLong();
	private final ChunkLoadPriority priority;
	private CompletableFuture<Void> closeResult;
	private boolean closed;

	public ParallelRegionReader(final RegionStorageInfo info, final Path folder, final boolean sync) {
		this(newExecutor(), true, MAX_RETAINED_REGIONS, ChunkLoadPriority.shared(), regionKey -> open(info, folder, sync, regionKey));
	}

	ParallelRegionReader(
		final ExecutorService executor,
		final int maxRetainedRegions,
		final Opener opener
	) {
		this(executor, false, maxRetainedRegions, ChunkLoadPriority.shared(), opener);
	}

	ParallelRegionReader(
		final ExecutorService executor,
		final int maxRetainedRegions,
		final ChunkLoadPriority priority,
		final Opener opener
	) {
		this(executor, false, maxRetainedRegions, priority, opener);
	}

	private ParallelRegionReader(
		final ExecutorService executor,
		final boolean ownsExecutor,
		final int maxRetainedRegions,
		final ChunkLoadPriority priority,
		final Opener opener
	) {
		if (maxRetainedRegions < 1) {
			throw new IllegalArgumentException("maxRetainedRegions must be positive");
		}
		this.executor = executor;
		this.ownsExecutor = ownsExecutor;
		this.maxRetainedRegions = maxRetainedRegions;
		this.priority = priority;
		this.opener = opener;
	}

	public CompletableFuture<Optional<CompoundTag>> loadAsync(final ChunkPos pos) {
		return loadAsync(pos, ignored -> Optional.empty());
	}

	public CompletableFuture<Optional<CompoundTag>> loadAsync(
		final ChunkPos pos,
		final PendingWrites pendingWrites
	) {
		Optional<CompoundTag> pending = pendingWrites.get(pos);
		if (pending.isPresent()) {
			return CompletableFuture.completedFuture(pending);
		}
		return submit(pos, priority.score(pos), () -> Optional.ofNullable(owner(pos).read(pos)));
	}

	public <T> CompletableFuture<T> coordinate(final ChunkPos pos, final Supplier<T> operation) {
		return submit(pos, Integer.MAX_VALUE, operation::get);
	}

	public <T> CompletableFuture<T> coordinateChecked(final ChunkPos pos, final ThrowingSupplier<T> operation) {
		return submit(pos, Integer.MAX_VALUE, operation);
	}

	private <T> CompletableFuture<T> submit(
		final ChunkPos pos,
		final int score,
		final ThrowingSupplier<T> operation
	) {
		long regionKey = regionKey(pos);
		Lane lane;
		CompletableFuture<Void> predecessor;
		CompletableFuture<T> result = new CompletableFuture<>();
		synchronized (this) {
			if (closed) {
				result.completeExceptionally(new RejectedExecutionException("parallel region reader is closed"));
				return result;
			}
			lane = lane(regionKey);
			lane.pending++;
			predecessor = lane.tail;
			lane.tail = result.handle((value, failure) -> null);
			accepted.add(result);
		}
		long order = sequence.getAndIncrement();
		predecessor.whenComplete((ignored, failure) -> {
			try {
				executor.execute(new ReadTask<>(score, order, () -> {
					try {
						result.complete(operation.get());
					} catch (Throwable taskFailure) {
						result.completeExceptionally(taskFailure);
					} finally {
						finish(lane, result);
					}
				}));
			} catch (RejectedExecutionException rejected) {
				result.completeExceptionally(rejected);
				finish(lane, result);
			}
		});
		return result;
	}

	private synchronized Lane lane(final long regionKey) {
		Lane existing = lanes.get(regionKey);
		if (existing != null) {
			return existing;
		}
		if (lanes.size() >= maxRetainedRegions) {
			Long removable = null;
			for (Map.Entry<Long, Lane> entry : lanes.entrySet()) {
				if (entry.getValue().pending == 0) {
					removable = entry.getKey();
					break;
				}
			}
			if (removable == null) {
				throw new RejectedExecutionException("parallel region reader region limit reached");
			}
			Lane old = lanes.remove(removable);
			closeOwner(old);
		}
		Lane created = new Lane(regionKey);
		lanes.put(regionKey, created);
		return created;
	}

	private synchronized Owner owner(final ChunkPos pos) throws Exception {
		Lane lane = lanes.get(regionKey(pos));
		if (lane.owner == null) {
			lane.owner = opener.open(lane.regionKey);
		}
		return lane.owner;
	}

	private synchronized void finish(final Lane lane, final CompletableFuture<?> result) {
		lane.pending--;
		accepted.remove(result);
	}

	private synchronized void closeOwner(final Lane lane) {
		if (lane.owner == null) {
			return;
		}
		try {
			lane.owner.close();
		} catch (Exception ignored) {
		}
		lane.owner = null;
	}

	@Override
	public void close() throws IOException {
		CompletableFuture<Void> result;
		List<CompletableFuture<?>> work;
		boolean firstClose;
		synchronized (this) {
			if (closeResult != null) {
				result = closeResult;
				work = List.of();
				firstClose = false;
			} else {
				closed = true;
				result = new CompletableFuture<>();
				closeResult = result;
				work = List.copyOf(accepted);
				firstClose = true;
			}
		}
		if (firstClose) {
			CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).handle((value, failure) -> {
				IOException closeFailure = closeOwners();
				if (closeFailure != null) {
					result.completeExceptionally(closeFailure);
				} else {
					result.complete(null);
				}
				if (ownsExecutor) {
					executor.shutdown();
				}
				return null;
			}).join();
		}
		try {
			result.join();
		} catch (java.util.concurrent.CompletionException failure) {
			if (failure.getCause() instanceof IOException io) {
				throw io;
			}
			throw failure;
		}
	}

	private IOException closeOwners() {
		List<Owner> owners;
		synchronized (this) {
			owners = lanes.values().stream().map(lane -> lane.owner).filter(owner -> owner != null).toList();
			lanes.values().forEach(lane -> lane.owner = null);
		}
		IOException failure = null;
		for (Owner owner : owners) {
			try {
				owner.close();
			} catch (Exception exception) {
				if (failure == null) {
					failure = exception instanceof IOException io ? io : new IOException(exception);
				} else {
					failure.addSuppressed(exception);
				}
			}
		}
		return failure;
	}

	private static long regionKey(final ChunkPos pos) {
		return ChunkPos.pack(pos.getRegionX(), pos.getRegionZ());
	}

	private static Owner open(final RegionStorageInfo info, final Path folder, final boolean sync, final long regionKey) throws IOException {
		int regionX = (int)(regionKey >> 32);
		int regionZ = (int)regionKey;
		Files.createDirectories(folder);
		RegionFile file = new RegionFile(info, folder.resolve("r." + regionX + "." + regionZ + ".mca"), folder, sync);
		return new Owner() {
			@Override
			public CompoundTag read(final ChunkPos pos) throws IOException {
				try (DataInputStream input = file.getChunkDataInputStream(pos)) {
					return input == null ? null : NbtIo.read(input);
				}
			}

			@Override
			public void close() throws IOException {
				file.close();
			}
		};
	}

	private static ExecutorService newExecutor() {
		AtomicInteger number = new AtomicInteger();
		ThreadFactory factory = runnable -> {
			Thread thread = new Thread(runnable, "mtlrender-region-" + number.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
		return new ThreadPoolExecutor(
			WORKER_COUNT,
			WORKER_COUNT,
			0L,
			TimeUnit.MILLISECONDS,
			new BoundedPriorityQueue(QUEUE_CAPACITY),
			factory,
			new ThreadPoolExecutor.AbortPolicy()
		);
	}

	private static final class ReadTask<T> implements Runnable {
		private final int score;
		private final long order;
		private final Runnable action;

		private ReadTask(final int score, final long order, final Runnable action) {
			this.score = score;
			this.order = order;
			this.action = action;
		}

		@Override
		public void run() {
			action.run();
		}
	}

	private static final class BoundedPriorityQueue extends PriorityBlockingQueue<Runnable> {
		private final int capacity;

		private BoundedPriorityQueue(final int capacity) {
			super(capacity, Comparator.comparingInt(ParallelRegionReader::scoreOf)
				.thenComparingLong(ParallelRegionReader::orderOf));
			this.capacity = capacity;
		}

		@Override
		public synchronized boolean offer(final Runnable task) {
			if (size() >= capacity) {
				return false;
			}
			return super.offer(task);
		}
	}

	private static int scoreOf(final Runnable task) {
		return task instanceof ReadTask<?> readTask ? readTask.score : Integer.MAX_VALUE;
	}

	private static long orderOf(final Runnable task) {
		return task instanceof ReadTask<?> readTask ? readTask.order : Long.MAX_VALUE;
	}

	@FunctionalInterface
	public interface ThrowingSupplier<T> {
		T get() throws Exception;
	}

	private static final class Lane {
		private final long regionKey;
		private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
		private Owner owner;
		private int pending;

		private Lane(final long regionKey) {
			this.regionKey = regionKey;
		}
	}
}
