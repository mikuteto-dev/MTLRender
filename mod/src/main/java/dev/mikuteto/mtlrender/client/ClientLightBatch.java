package dev.mikuteto.mtlrender.client;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;

public final class ClientLightBatch implements AutoCloseable {
	public static final int DEFAULT_CAPACITY = 128;
	private static final int MAX_LIGHT_SECTIONS = 64;
	private static final ClientLightBatch CURRENT = new ClientLightBatch();

	public interface ResultConsumer {
		void accept(Result result);
	}

	public static final class Result {
		private final LightSection[] sections = new LightSection[MAX_LIGHT_SECTIONS * 2];
		private int sectionCount;
		private int chunkX;
		private int chunkZ;
		private long generation;
		private boolean scheduleRebuild;
		private boolean enableChunkLight;
		private RuntimeException failure;

		private Result() {
			for (int index = 0; index < this.sections.length; index++) {
				this.sections[index] = new LightSection();
			}
		}

		public int sectionCount() {
			return this.sectionCount;
		}

		public LightSection section(int index) {
			return this.sections[index];
		}

		public int chunkX() {
			return this.chunkX;
		}

		public int chunkZ() {
			return this.chunkZ;
		}

		public boolean scheduleRebuild() {
			return this.scheduleRebuild;
		}

		public boolean enableChunkLight() {
			return this.enableChunkLight;
		}

		private void begin(Request request) {
			this.clearSections();
			this.chunkX = request.chunkX;
			this.chunkZ = request.chunkZ;
			this.generation = request.generation;
			this.scheduleRebuild = request.scheduleRebuild;
			this.enableChunkLight = request.enableChunkLight;
			this.failure = null;
		}

		private void add(LightLayer layer, long sectionKey, DataLayer data) {
			if (this.sectionCount == this.sections.length) {
				throw new IllegalArgumentException("light batch section limit exceeded");
			}
			this.sections[this.sectionCount++].set(layer, sectionKey, data);
		}

		private void fail(RuntimeException failure) {
			this.failure = failure;
		}

		private void throwFailure() {
			if (this.failure != null) {
				throw this.failure;
			}
		}

		private void clearSections() {
			for (int index = 0; index < this.sectionCount; index++) {
				this.sections[index].clear();
			}
			this.sectionCount = 0;
		}

		private void clear() {
			this.clearSections();
			this.failure = null;
		}
	}

	public static final class LightSection {
		private LightLayer layer;
		private long sectionKey;
		private DataLayer data;

		private LightSection() {
		}

		private void set(LightLayer layer, long sectionKey, DataLayer data) {
			this.layer = layer;
			this.sectionKey = sectionKey;
			this.data = data;
		}

		private void clear() {
			this.layer = null;
			this.data = null;
		}

		public LightLayer layer() {
			return this.layer;
		}

		public long sectionKey() {
			return this.sectionKey;
		}

		public DataLayer data() {
			return this.data;
		}
	}

	private static final class Request {
		private ClientboundLightUpdatePacketData data;
		private int chunkX;
		private int chunkZ;
		private int minSection;
		private int sectionCount;
		private long generation;
		private boolean scheduleRebuild;
		private boolean enableChunkLight;

		private void set(
			int chunkX,
			int chunkZ,
			ClientboundLightUpdatePacketData data,
			int minSection,
			int sectionCount,
			long generation,
			boolean scheduleRebuild,
			boolean enableChunkLight
		) {
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
			this.data = data;
			this.minSection = minSection;
			this.sectionCount = sectionCount;
			this.generation = generation;
			this.scheduleRebuild = scheduleRebuild;
			this.enableChunkLight = enableChunkLight;
		}

		private void clear() {
			this.data = null;
		}
	}

	private final Object lock = new Object();
	private final Executor executor;
	private final ExecutorService ownedExecutor;
	private final ChunkLoadCoordinator coordinator;
	private final Request[] requests;
	private final Result[] results;
	private final int mask;
	private int requestHead;
	private int requestTail;
	private int requestCount;
	private int resultHead;
	private int resultTail;
	private int resultCount;
	private int resultReserved;
	private boolean workerScheduled;
	private boolean closed;

	public ClientLightBatch() {
		this(createExecutor(), DEFAULT_CAPACITY, true);
	}

	ClientLightBatch(Executor executor, int capacity) {
		this(executor, capacity, false);
	}

	private ClientLightBatch(Executor executor, int capacity, boolean ownsExecutor) {
		if (Integer.bitCount(capacity) != 1 || capacity < 1) {
			throw new IllegalArgumentException("capacity must be a positive power of two");
		}
		this.executor = Objects.requireNonNull(executor);
		this.ownedExecutor = ownsExecutor && executor instanceof ExecutorService service ? service : null;
		this.coordinator = new ChunkLoadCoordinator(Math.max(4096, capacity));
		this.requests = new Request[capacity];
		this.results = new Result[capacity];
		this.mask = capacity - 1;
		for (int index = 0; index < capacity; index++) {
			this.requests[index] = new Request();
			this.results[index] = new Result();
		}
	}

	public static ClientLightBatch current() {
		return CURRENT;
	}

	public static void resetCurrent() {
		CURRENT.reset();
	}

	public long generation() {
		synchronized (this.lock) {
			return this.coordinator.generation();
		}
	}

	public boolean submit(
		int chunkX,
		int chunkZ,
		ClientboundLightUpdatePacketData data,
		int minSection,
		int sectionCount,
		boolean scheduleRebuild,
		boolean enableChunkLight
	) {
		return this.submit(chunkX, chunkZ, data, minSection, sectionCount, scheduleRebuild, enableChunkLight, null);
	}

	public boolean submitInitial(
		int chunkX,
		int chunkZ,
		ClientboundLightUpdatePacketData data,
		int minSection,
		int sectionCount,
		Runnable beforeSubmit
	) {
		return this.submit(chunkX, chunkZ, data, minSection, sectionCount, false, true, beforeSubmit);
	}

	private boolean submit(
		int chunkX,
		int chunkZ,
		ClientboundLightUpdatePacketData data,
		int minSection,
		int sectionCount,
		boolean scheduleRebuild,
		boolean enableChunkLight,
		Runnable beforeSubmit
	) {
		Objects.requireNonNull(data);
		boolean schedule = false;
		synchronized (this.lock) {
			if (this.closed || this.requestCount == this.requests.length || sectionCount < 0 || sectionCount > MAX_LIGHT_SECTIONS) {
				return false;
			}
			long generation = this.coordinator.generation();
			if (enableChunkLight && !this.coordinator.recordChunkPacket(ChunkPos.pack(chunkX, chunkZ), generation)) {
				return false;
			}
			if (beforeSubmit != null) {
				beforeSubmit.run();
			}
			Request request = this.requests[this.requestTail];
			request.set(
				chunkX, chunkZ, data, minSection, sectionCount, generation, scheduleRebuild, enableChunkLight
			);
			this.requestTail = this.requestTail + 1 & this.mask;
			this.requestCount++;
			if (!this.workerScheduled) {
				this.workerScheduled = true;
				schedule = true;
			}
		}
		if (schedule) {
			this.executor.execute(this::runWorker);
		}
		return true;
	}

	public int pending() {
		synchronized (this.lock) {
			return this.resultCount;
		}
	}

	public int drain(int limit, ResultConsumer consumer) {
		if (limit < 0) {
			throw new IllegalArgumentException("limit must not be negative");
		}
		Objects.requireNonNull(consumer);
		int drained = 0;
		while (drained < limit) {
			Result result;
			synchronized (this.lock) {
				if (this.resultCount == 0) {
					break;
				}
				result = this.results[this.resultHead];
				this.resultHead = this.resultHead + 1 & this.mask;
				this.resultCount--;
			}
			long generation;
			synchronized (this.lock) {
				generation = this.coordinator.generation();
			}
			if (result.generation == generation) {
				result.throwFailure();
				consumer.accept(result);
			}
			result.clear();
			drained++;
			this.scheduleWorkerIfNeeded();
		}
		return drained;
	}

	public void reset() {
		synchronized (this.lock) {
			this.coordinator.reset();
			this.requestHead = 0;
			this.requestTail = 0;
			this.requestCount = 0;
			while (this.resultReserved != 0) {
				try {
					this.lock.wait();
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			this.resultHead = 0;
			this.resultTail = 0;
			this.resultCount = 0;
			for (Result result : this.results) {
				result.clear();
			}
		}
	}

	@Override
	public void close() {
		synchronized (this.lock) {
			this.closed = true;
		}
		this.reset();
		if (this.ownedExecutor != null) {
			this.ownedExecutor.shutdownNow();
		}
	}

	private void runWorker() {
		while (true) {
			Request request;
			Result result;
			synchronized (this.lock) {
				if (this.closed || this.requestCount == 0 || this.resultCount + this.resultReserved == this.results.length) {
					this.workerScheduled = false;
					this.lock.notifyAll();
					return;
				}
				request = this.requests[this.requestHead];
				this.requestHead = this.requestHead + 1 & this.mask;
				this.requestCount--;
				result = this.results[this.resultTail];
				this.resultReserved++;
			}
			this.decode(request, result);
			synchronized (this.lock) {
				this.resultReserved--;
				request.clear();
				if (request.generation == this.coordinator.generation() && !this.closed) {
					this.resultTail = this.resultTail + 1 & this.mask;
					this.resultCount++;
				} else {
					result.clear();
				}
				this.lock.notifyAll();
			}
		}
	}

	private void decode(Request request, Result result) {
		result.begin(request);
		try {
			decodeLayer(
				result, request, LightLayer.SKY, request.data.getSkyYMask(), request.data.getEmptySkyYMask(), request.data.getSkyUpdates()
			);
			decodeLayer(
				result, request, LightLayer.BLOCK, request.data.getBlockYMask(), request.data.getEmptyBlockYMask(), request.data.getBlockUpdates()
			);
		} catch (RuntimeException failure) {
			result.fail(failure);
		}
	}

	private static void decodeLayer(
		Result result,
		Request request,
		LightLayer layer,
		BitSet dataMask,
		BitSet emptyMask,
		java.util.List<byte[]> updates
	) {
		BitSet dataBits = (BitSet)dataMask.clone();
		BitSet emptyBits = (BitSet)emptyMask.clone();
		Iterator<byte[]> iterator = updates.iterator();
		for (int sectionIndex = 0; sectionIndex < request.sectionCount; sectionIndex++) {
			boolean haveData = dataBits.get(sectionIndex);
			boolean haveEmpty = emptyBits.get(sectionIndex);
			if (haveData || haveEmpty) {
				byte[] bytes = haveData ? iterator.next() : null;
				DataLayer data = haveData ? new DataLayer(Arrays.copyOf(bytes, bytes.length)) : new DataLayer();
				long sectionKey = SectionPos.asLong(request.chunkX, request.minSection + sectionIndex, request.chunkZ);
				result.add(layer, sectionKey, data);
			}
		}
	}

	private void scheduleWorkerIfNeeded() {
		boolean schedule = false;
		synchronized (this.lock) {
			if (!this.closed && this.requestCount != 0 && !this.workerScheduled) {
				this.workerScheduled = true;
				schedule = true;
			}
		}
		if (schedule) {
			this.executor.execute(this::runWorker);
		}
	}

	private static ExecutorService createExecutor() {
		return Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "MTLRender-LightBatch");
			thread.setDaemon(true);
			return thread;
		});
	}
}
