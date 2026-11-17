package dev.mikuteto.mtlrender.terrain;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.StagingBuffer;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.blockentity.VanillaBlockEntityBakeSet;
import dev.mikuteto.mtlrender.debug.ChunkTiming;
import dev.mikuteto.mtlrender.pathtracing.VoxelBricks;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

public final class DirectUberBuffer<T> extends UberGpuBuffer<T> {
	private record Node(TlsfAllocator allocator, UberGpuBufferHeap heap, GpuBuffer buffer, long size) {
	}

	private record PendingFree(long frame, TlsfAllocator.Allocation allocation) {
	}

	public record Entry(TlsfAllocator.Allocation allocation, long address, int[] faces, int[] merged, TerrainVertex format,
		TlsfAllocator.Allocation metadataAllocation, long metadataAddress, long metadataGeneration, long metadataRequirements) {
		public boolean hasPackMetadata() {
			return metadataAllocation != null && metadataAddress != 0L;
		}

		public long packMetadataAddress() {
			return metadataAddress;
		}
	}

	private static final DirectUberBuffer<?>[] BLOCK_LAYERS = new DirectUberBuffer<?>[ChunkSectionLayer.values().length];
	private static final DirectUberBuffer<?>[] INDEX_LAYERS = new DirectUberBuffer<?>[ChunkSectionLayer.values().length];
	private static final DirectUberBuffer<?>[] LOD_LAYERS = new DirectUberBuffer<?>[ChunkSectionLayer.values().length];

	private static final int HEAP_SPLIT = 4;
	private static final int INDEX_SLOT = 3;
	private static final int LOD_SLOT = 2 * ChunkSectionLayer.values().length;

	private final String name;
	private final int usage;
	private final int heapSize;
	private final int alignSize;
	private final ChunkSectionLayer blockLayer;
	private final ChunkSectionLayer indexLayer;
	private final ReentrantLock copyLock;
	private final List<Node> nodes = new ArrayList<>();
	private final Map<T, Entry> allocations = new HashMap<>(256);
	private final Map<T, Entry> lodAllocations = new HashMap<>(64);
	private final Map<T, Long> generations = new HashMap<>(64);
	private final Map<T, Runnable> callbacks = new LinkedHashMap<>();
	private final ArrayDeque<PendingFree> pendingFrees = new ArrayDeque<>();
	private long heapRequest;
	private long capacity;
	private long used;
	private int copying;
	private volatile boolean closing;

	public DirectUberBuffer(String name, int usage, int heapSize, int alignSize, StagingBuffer stagingBuffer, ReentrantLock copyLock) {
		super(name, usage, heapSize, alignSize, stagingBuffer);
		this.name = "Direct UberBuffer " + name;
		this.usage = usage;
		long terrainBudget = MetalAccess.memoryBudget() == null ? Long.MAX_VALUE : MetalAccess.memoryBudget().terrainCap();
		long perBuffer = Math.max(alignSize, terrainBudget / (ChunkSectionLayer.values().length * 2L));
		this.heapSize = (int) Math.min(heapSize / HEAP_SPLIT, Math.min(Integer.MAX_VALUE, perBuffer));
		this.alignSize = alignSize;
		this.copyLock = copyLock;
		this.blockLayer = blockLayerOf(name, usage, alignSize);
		this.indexLayer = indexLayerOf(name, usage);
		if (blockLayer != null) {
			BLOCK_LAYERS[blockLayer.ordinal()] = this;
			if (blockLayer != ChunkSectionLayer.TRANSLUCENT) {
				LOD_LAYERS[blockLayer.ordinal()] = this;
			}
		}
		if (indexLayer != null) {
			INDEX_LAYERS[indexLayer.ordinal()] = this;
		}
	}

	public static void discard(Object key) {
		for (DirectUberBuffer<?> buffer : BLOCK_LAYERS) {
			if (buffer != null) {
				buffer.discardStale(key);
			}
		}
		for (DirectUberBuffer<?> buffer : INDEX_LAYERS) {
			if (buffer != null) {
				buffer.discardStale(key);
			}
		}
	}

	static Entry entry(ChunkSectionLayer layer, Object key) {
		DirectUberBuffer<?> buffer = BLOCK_LAYERS[layer.ordinal()];
		if (buffer == null) {
			return null;
		}
		if (key instanceof MeshEntries entries) {
			return entries.mtlrender$entry(layer.ordinal());
		}
		return buffer.allocations.get(key);
	}

	static Entry indexEntry(ChunkSectionLayer layer, Object key) {
		DirectUberBuffer<?> buffer = INDEX_LAYERS[layer.ordinal()];
		if (buffer == null) {
			return null;
		}
		if (key instanceof MeshEntries entries) {
			return entries.mtlrender$entry(INDEX_SLOT + layer.ordinal());
		}
		return buffer.allocations.get(key);
	}

	static Entry lodEntry(ChunkSectionLayer layer, Object key) {
		DirectUberBuffer<?> buffer = LOD_LAYERS[layer.ordinal()];
		if (buffer == null) {
			return null;
		}
		if (key instanceof MeshEntries entries) {
			return entries.mtlrender$entry(LOD_SLOT + layer.ordinal());
		}
		return buffer.lodAllocations.get(key);
	}

	public static long capacityBytes() {
		return sum(buffer -> buffer.capacity);
	}

	public static long usedBytes() {
		return sum(buffer -> buffer.used);
	}

	public static long pendingFreeBytes() {
		return sum(buffer -> buffer.pendingFrees.stream().mapToLong(entry -> entry.allocation().getSize()).sum());
	}

	public static long duplicatedStagingBytes() {
		return 0;
	}

	private interface Value {
		long get(DirectUberBuffer<?> buffer);
	}

	private static long sum(Value value) {
		long total = 0;
		for (DirectUberBuffer<?> buffer : BLOCK_LAYERS) {
			if (buffer != null) {
				total = Math.addExact(total, value.get(buffer));
			}
		}
		for (DirectUberBuffer<?> buffer : INDEX_LAYERS) {
			if (buffer != null) {
				total = Math.addExact(total, value.get(buffer));
			}
		}
		return total;
	}

	@Override
	public <U extends T> boolean addAllocation(U key, UploadCallback<U> callback, ByteBuffer data) {
		ByteBuffer input = inputView(data);
		MemorySegment source = MemorySegment.ofBuffer(input);
		TerrainVertex format = TerrainVertex.select(source, blockLayer != null && TerrainRenderer.get().compactVertices());
		long size = format.bytes(source.byteSize());
		int quads = source.byteSize() % FaceBuckets.QUAD_SIZE == 0
			? Math.toIntExact(source.byteSize() / FaceBuckets.QUAD_SIZE) : -1;
		PackTerrainJavaMesh packMetadata = blockLayer != null && quads >= 0
			? currentPackMetadata(key, quads) : null;
		long timingKey = timingKey(key);
		long uploadStart = timingKey >= 0 ? System.nanoTime() : 0;
		TlsfAllocator.Allocation allocation = allocate(size);
		if (allocation == null) {
			heapRequest = Math.max(heapRequest, size);
			return false;
		}
		MemorySegment target = MetalAccess.contents(getGpuBuffer(allocation)).asSlice(allocation.getOffsetFromHeap(), size);
		int[] faces = null;
		int[] merged = null;
		LodMerge.Merged lod = null;
		boolean released = false;
		try {
			if (copyLock.isHeldByCurrentThread() && copyLock.getHoldCount() == 1) {
				copying++;
				copyLock.unlock();
				released = true;
			}
			if (blockLayer != null && blockLayer != ChunkSectionLayer.TRANSLUCENT && source.byteSize() > 0 && source.byteSize() % FaceBuckets.QUAD_SIZE == 0) {
				faces = FaceBuckets.copySorted(source, target, format);
				merged = MergeEstimate.ENABLED ? MergeEstimate.estimate(source) : null;
				if (TerrainRenderer.get().lodBuild()) {
					lod = buildLod(source, Math.toIntExact(source.byteSize() / FaceBuckets.QUAD_SIZE));
				}
			} else {
				format.copy(source, 0, target, 0, source.byteSize());
			}
		} finally {
			if (released) {
				copyLock.lock();
				copying--;
			}
		}
		TlsfAllocator.Allocation metadataAllocation = null;
		if (packMetadata != null && packMetadata.size() > 0) {
			metadataAllocation = allocate(packMetadata.size());
			if (metadataAllocation == null) {
				heapRequest = Math.max(heapRequest, packMetadata.size());
				freeLater(allocation);
				return false;
			}
			MemorySegment metadataTarget = MetalAccess.contents(getGpuBuffer(metadataAllocation))
				.asSlice(metadataAllocation.getOffsetFromHeap(), packMetadata.size());
			MemorySegment.copy(MemorySegment.ofBuffer(packMetadata.data()), 0, metadataTarget, 0, packMetadata.size());
		}
		if (closing) {
			freeLater(allocation);
			if (metadataAllocation != null) {
				freeLater(metadataAllocation);
			}
			if (copying == 0) {
				finishClose();
			}
			return false;
		}
		long uploadEnd = timingKey >= 0 ? System.nanoTime() : 0;
		if (timingKey >= 0) {
			ChunkTiming.recordSectionDuration(ChunkTiming.Stage.GPU_UPLOAD, timingKey, uploadEnd - uploadStart);
		}
		long generation = generations.getOrDefault(key, 0L) + 1L;
		if (blockLayer != null && VoxelBricks.enabled()) {
			VoxelBricks.update(key, blockLayer, input, generation);
		}
		Entry entry = new Entry(allocation, address(allocation), faces, merged, format,
			metadataAllocation, metadataAllocation == null ? 0L : address(metadataAllocation),
			packMetadata == null ? -1L : packMetadata.generation(),
			packMetadata == null ? 0L : packMetadata.requirements());
		Entry previous = allocations.put(key, entry);
		if (previous != null) {
			freeLater(previous);
		}
		generations.put(key, generation);
		clearLod(key);
		if (blockLayer != null && key instanceof MeshEntries entries) {
			entries.mtlrender$setEntry(blockLayer.ordinal(), entry);
			markSectionDirty(entries);
		} else if (indexLayer != null && key instanceof MeshEntries entries) {
			entries.mtlrender$setEntry(INDEX_SLOT + indexLayer.ordinal(), entry);
			markSectionDirty(entries);
		}
		if (lod != null) {
			storeLod(key, lod);
		}
		if (callback != null) {
			callbacks.put(key, () -> {
				if (allocations.containsKey(key)) {
					if (timingKey >= 0) {
						ChunkTiming.recordSectionDuration(ChunkTiming.Stage.GPU_PUBLISH, timingKey, System.nanoTime() - uploadEnd);
					}
					callback.bufferHasBeenUploaded(key);
				}
			});
		} else {
			callbacks.remove(key);
		}
		return true;
	}

	private static ByteBuffer inputView(ByteBuffer source) {
		ByteBuffer view = source.slice().order(ByteOrder.nativeOrder());
		if (!view.isDirect() || !view.hasRemaining()) {
			return view;
		}
		MemorySegment segment = MemorySegment.ofBuffer(view);
		return MemorySegment.ofAddress(segment.address()).reinterpret(view.remaining()).asByteBuffer().order(ByteOrder.nativeOrder());
	}

	private long timingKey(T key) {
		if (key instanceof MeshEntries entries) {
			int section = entries.mtlrender$section();
			int layer = blockLayer != null ? blockLayer.ordinal() : indexLayer != null ? indexLayer.ordinal() : -1;
			if (section >= 0 && layer >= 0) {
				return ((long) section << 32) | (layer & 0xffffffffL);
			}
		}
		return -1;
	}

	private PackTerrainJavaMesh currentPackMetadata(T key, int quads) {
		if (!RustMesher.packRequirements().needsExtraData() || !(key instanceof MeshEntries entries)) {
			return null;
		}
		PackTerrainJavaMesh metadata = entries.mtlrender$packTerrainMetadata(blockLayer.ordinal());
		if (metadata == null || metadata.generation() != RustMesher.packGeneration()
			|| metadata.requirements() != RustMesher.packRequirements().fingerprint()
			|| metadata.size() != PackTerrainLayout.bytesFor(quads)) {
			return null;
		}
		return metadata;
	}

	private void storeLod(T key, LodMerge.Merged lod) {
		long size = lod.data().byteSize();
		TlsfAllocator.Allocation allocation = allocate(size);
		if (allocation == null) {
			heapRequest = Math.max(heapRequest, size);
			Entry previous = lodAllocations.remove(key);
			if (previous != null) {
				freeLater(previous);
				if (blockLayer != null && key instanceof MeshEntries entries
					&& entries.mtlrender$entry(LOD_SLOT + blockLayer.ordinal()) == previous) {
					entries.mtlrender$setEntry(LOD_SLOT + blockLayer.ordinal(), null);
					markSectionDirty(entries);
				}
			}
			return;
		}
		MemorySegment target = MetalAccess.contents(getGpuBuffer(allocation))
			.asSlice(allocation.getOffsetFromHeap(), size);
		MemorySegment.copy(lod.data(), 0, target, 0, size);
		Entry entry = new Entry(allocation, address(allocation), lod.faces(), lod.faces(), TerrainVertex.BLOCK,
			null, 0L, -1L, 0L);
		Entry previous = lodAllocations.put(key, entry);
		if (previous != null) {
			freeLater(previous);
		}
		if (blockLayer != null && key instanceof MeshEntries entries) {
			entries.mtlrender$setEntry(LOD_SLOT + blockLayer.ordinal(), entry);
			markSectionDirty(entries);
		}
	}

	private void markSectionDirty(MeshEntries entries) {
		int section = entries.mtlrender$section();
		if (section >= 0) {
			TerrainRenderer.get().markDirty(section);
		}
	}

	private LodMerge.Merged buildLod(MemorySegment source, int quads) {
		try {
			return LodMerge.mergeForUpload(source, quads);
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private void clearLod(T key) {
		Entry previous = lodAllocations.remove(key);
		if (previous != null) {
			freeLater(previous);
			if (blockLayer != null && key instanceof MeshEntries entries
				&& entries.mtlrender$entry(LOD_SLOT + blockLayer.ordinal()) == previous) {
				entries.mtlrender$setEntry(LOD_SLOT + blockLayer.ordinal(), null);
				markSectionDirty(entries);
			}
		}
	}

	@Override
	public boolean uploadStagedAllocations(GpuDevice device, StagingBuffer.Uploader uploader) {
		boolean heapsChanged = false;
		if (heapRequest > 0) {
			addHeap(device, heapRequest);
			heapRequest = 0;
			heapsChanged = true;
		} else if (capacity > 0 && used > capacity / 4 * 3) {
			addHeap(device, 0);
			heapsChanged = true;
		}
		while (!callbacks.isEmpty()) {
			Iterator<Map.Entry<T, Runnable>> next = callbacks.entrySet().iterator();
			Runnable callback = next.next().getValue();
			next.remove();
			callback.run();
		}
		long completed = MetalAccess.completedFrames();
		while (!pendingFrees.isEmpty() && pendingFrees.peekFirst().frame() < completed) {
			release(pendingFrees.pollFirst().allocation());
		}
		return heapsChanged | releaseEmptyHeap();
	}

	@Override
	public TlsfAllocator.Allocation getAllocation(T key) {
		Entry entry = lookup(key);
		if (entry == null || entry.format() == TerrainVertex.COMPACT && !TerrainRenderer.get().compactVertices()) {
			return null;
		}
		return entry.allocation();
	}

	private void discardStale(Object key) {
		callbacks.remove(key);
		generations.remove(key);
		Entry entry = allocations.remove(key);
		if (entry != null) {
			freeLater(entry);
		}
		Entry lod = lodAllocations.remove(key);
		if (lod != null) {
			freeLater(lod);
		}
		if (key instanceof MeshEntries entries) {
			boolean changed = false;
			if (blockLayer != null) {
				if (entries.mtlrender$entry(blockLayer.ordinal()) == entry) {
					entries.mtlrender$setEntry(blockLayer.ordinal(), null);
					entries.mtlrender$setPackTerrainMetadata(blockLayer.ordinal(), null);
					changed = true;
				}
				if (entries.mtlrender$entry(LOD_SLOT + blockLayer.ordinal()) == lod) {
					entries.mtlrender$setEntry(LOD_SLOT + blockLayer.ordinal(), null);
					changed = true;
				}
			}
			if (indexLayer != null && entries.mtlrender$entry(INDEX_SLOT + indexLayer.ordinal()) == entry) {
				entries.mtlrender$setEntry(INDEX_SLOT + indexLayer.ordinal(), null);
				changed = true;
			}
			if (changed) {
				markSectionDirty(entries);
			}
		}
	}

	@Override
	public void removeAllocation(T key) {
		callbacks.remove(key);
		generations.put(key, generations.getOrDefault(key, 0L) + 1L);
		Entry entry = allocations.remove(key);
		if (entry != null) {
			freeLater(entry);
		}
		Entry lod = lodAllocations.get(key);
		clearLod(key);
		if (blockLayer != null && key instanceof MeshEntries entries) {
			boolean changed = false;
			if (entries.mtlrender$entry(blockLayer.ordinal()) == entry) {
				entries.mtlrender$setEntry(blockLayer.ordinal(), null);
				entries.mtlrender$setPackTerrainMetadata(blockLayer.ordinal(), null);
				changed = true;
			}
			if (entries.mtlrender$entry(LOD_SLOT + blockLayer.ordinal()) == lod) {
				entries.mtlrender$setEntry(LOD_SLOT + blockLayer.ordinal(), null);
				changed = true;
			}
			if (changed) {
				markSectionDirty(entries);
			}
		}
		if (indexLayer != null && key instanceof MeshEntries entries) {
			if (entries.mtlrender$entry(INDEX_SLOT + indexLayer.ordinal()) == entry) {
				entries.mtlrender$setEntry(INDEX_SLOT + indexLayer.ordinal(), null);
				markSectionDirty(entries);
			}
		}
	}

	@Override
	public void close() {
		copyLock.lock();
		try {
			closing = true;
			if (copying > 0) {
				return;
			}
			finishClose();
		} finally {
			copyLock.unlock();
		}
	}

	private void finishClose() {
		VanillaBlockEntityBakeSet.registry().clear();
		if (blockLayer != null && BLOCK_LAYERS[blockLayer.ordinal()] == this) {
			BLOCK_LAYERS[blockLayer.ordinal()] = null;
		}
		if (blockLayer != null && LOD_LAYERS[blockLayer.ordinal()] == this) {
			LOD_LAYERS[blockLayer.ordinal()] = null;
		}
		if (indexLayer != null && INDEX_LAYERS[indexLayer.ordinal()] == this) {
			INDEX_LAYERS[indexLayer.ordinal()] = null;
		}
		callbacks.clear();
		generations.clear();
		pendingFrees.clear();
		if (blockLayer != null) {
			for (T key : allocations.keySet()) {
				if (key instanceof MeshEntries entries) {
					entries.mtlrender$setEntry(blockLayer.ordinal(), null);
					entries.mtlrender$setPackTerrainMetadata(blockLayer.ordinal(), null);
					entries.mtlrender$setEntry(LOD_SLOT + blockLayer.ordinal(), null);
				}
			}
		}
		if (indexLayer != null) {
			for (T key : allocations.keySet()) {
				if (key instanceof MeshEntries entries) {
					entries.mtlrender$setEntry(INDEX_SLOT + indexLayer.ordinal(), null);
				}
			}
		}
		allocations.clear();
		lodAllocations.clear();
		capacity = 0;
		used = 0;
		for (Node node : nodes) {
			node.buffer().close();
		}
		nodes.clear();
	}

	private Entry lookup(T key) {
		if (key instanceof MeshEntries entries) {
			if (blockLayer != null) {
				return entries.mtlrender$entry(blockLayer.ordinal());
			}
			if (indexLayer != null) {
				return entries.mtlrender$entry(INDEX_SLOT + indexLayer.ordinal());
			}
		}
		return allocations.get(key);
	}

	private long address(TlsfAllocator.Allocation allocation) {
		return MetalAccess.gpuAddress(getGpuBuffer(allocation)) + allocation.getOffsetFromHeap();
	}

	private static ChunkSectionLayer blockLayerOf(String name, int usage, int alignSize) {
		if ((usage & GpuBuffer.USAGE_VERTEX) == 0 || alignSize != TerrainVertex.BLOCK.size) {
			return null;
		}
		for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
			if (layer.label().equals(name) && layer.vertexFormat().equals(DefaultVertexFormat.BLOCK)) {
				return layer;
			}
		}
		return null;
	}

	private static ChunkSectionLayer indexLayerOf(String name, int usage) {
		if ((usage & GpuBuffer.USAGE_INDEX) == 0) {
			return null;
		}
		for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
			if (layer.label().equals(name)) {
				return layer;
			}
		}
		return null;
	}

	private TlsfAllocator.Allocation allocate(long size) {
		for (Node node : nodes) {
			TlsfAllocator.Allocation allocation = node.allocator().allocate(size, alignSize);
			if (allocation != null) {
				used += allocation.getSize();
				return allocation;
			}
		}
		return null;
	}

	private void freeLater(Entry entry) {
		freeLater(entry.allocation());
		if (entry.metadataAllocation() != null) {
			freeLater(entry.metadataAllocation());
		}
	}

	private void freeLater(TlsfAllocator.Allocation allocation) {
		pendingFrees.addLast(new PendingFree(MetalAccess.submittedFrames(), allocation));
	}

	private void release(TlsfAllocator.Allocation allocation) {
		for (Node node : nodes) {
			if (node.heap() == allocation.getHeap()) {
				used -= allocation.getSize();
				node.allocator().free(allocation);
				return;
			}
		}
	}

	private boolean releaseEmptyHeap() {
		if (nodes.size() <= 1 || !pendingFrees.isEmpty()) {
			return false;
		}
		Iterator<Node> iterator = nodes.iterator();
		while (iterator.hasNext()) {
			Node node = iterator.next();
			if (node.allocator().isCompletelyFree() && used * 2 < capacity - node.size()) {
				node.buffer().close();
				capacity -= node.size();
				iterator.remove();
				return true;
			}
		}
		return false;
	}

	private void addHeap(GpuDevice device, long minimum) {
		long size = Math.max(Math.max(heapSize, minimum * 2 + alignSize), capacity / 2);
		String heapName = String.format(Locale.ROOT, "%s %d", name, nodes.size());
		UberGpuBufferHeap heap = new UberGpuBufferHeap(size, device, usage, heapName);
		TlsfAllocator allocator = new TlsfAllocator(heap);
		TlsfAllocator.Allocation probe = allocator.allocate(alignSize, alignSize);
		GpuBuffer buffer = getGpuBuffer(probe);
		allocator.free(probe);
		nodes.add(new Node(allocator, heap, buffer, size));
		capacity += size;
	}
}
