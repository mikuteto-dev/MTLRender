package dev.mikuteto.mtlrender.lod;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.GpuDevice;

import dev.mikuteto.mtlrender.backend.MetalAccess;

public final class LodGpuStore {
	private final long budgetBytes;
	private final int budgetEntries;
	private final LinkedHashMap<LodData.LodKey, LodGpuMesh> meshes = new LinkedHashMap<>();
	private final ArrayDeque<Retired> retired = new ArrayDeque<>();
	private long residentBytes;
	private long retiredBytes;

	public LodGpuStore(long budgetBytes, int budgetEntries) {
		if (budgetBytes <= 0 || budgetEntries <= 0) {
			throw new IllegalArgumentException("GPU LOD budgets must be positive");
		}
		this.budgetBytes = budgetBytes;
		this.budgetEntries = budgetEntries;
	}

	public synchronized Optional<LodGpuMesh> upload(LodMesh mesh, long generation) {
		RenderSystem.assertOnRenderThread();
		Objects.requireNonNull(mesh, "mesh");
		if (generation < 0) {
			throw new IllegalArgumentException("generation must be non-negative");
		}
		LodGpuMesh.validate(mesh);
		if (!MetalAccess.active()) {
			throw new IllegalStateException("Metal backend is not active");
		}
		drainRetired();
		LodGpuMesh old = meshes.get(mesh.key());
		long oldBytes = old == null ? 0 : old.byteSize();
		long size = mesh.bytes().length;
		if (size > budgetBytes || residentBytes - oldBytes + size + retiredBytes > budgetBytes) {
			return Optional.empty();
		}
		GpuDevice device = RenderSystem.getDevice();
		ByteBuffer data = ByteBuffer.wrap(mesh.bytes());
		GpuBuffer buffer = device.createBuffer(() -> "MTLRender LOD mesh", GpuBuffer.USAGE_VERTEX, data);
		LodGpuMesh uploaded = new LodGpuMesh(mesh, generation, buffer, MetalAccess.gpuAddress(buffer));
		install(uploaded);
		return Optional.of(uploaded);
	}

	public synchronized Optional<LodGpuMesh> get(LodData.LodKey key) {
		return Optional.ofNullable(meshes.get(Objects.requireNonNull(key, "key")));
	}

	public synchronized List<LodGpuMesh> entries() {
		return List.copyOf(meshes.values());
	}

	public synchronized Optional<LodGpuMesh> remove(LodData.LodKey key) {
		RenderSystem.assertOnRenderThread();
		drainRetired();
		LodGpuMesh removed = meshes.remove(Objects.requireNonNull(key, "key"));
		if (removed != null) {
			residentBytes -= removed.byteSize();
			schedule(removed);
		}
		return Optional.ofNullable(removed);
	}

	public synchronized int clearDimension(String dimension) {
		RenderSystem.assertOnRenderThread();
		Objects.requireNonNull(dimension, "dimension");
		drainRetired();
		int removed = 0;
		Iterator<Map.Entry<LodData.LodKey, LodGpuMesh>> iterator = meshes.entrySet().iterator();
		while (iterator.hasNext()) {
			LodGpuMesh mesh = iterator.next().getValue();
			if (mesh.key().dimension().equals(dimension)) {
				residentBytes -= mesh.byteSize();
				schedule(mesh);
				iterator.remove();
				removed++;
			}
		}
		return removed;
	}

	public synchronized int clearStale(long generation) {
		RenderSystem.assertOnRenderThread();
		if (generation < 0) {
			throw new IllegalArgumentException("generation must be non-negative");
		}
		drainRetired();
		int removed = 0;
		Iterator<Map.Entry<LodData.LodKey, LodGpuMesh>> iterator = meshes.entrySet().iterator();
		while (iterator.hasNext()) {
			LodGpuMesh mesh = iterator.next().getValue();
			if (mesh.generation() < generation) {
				residentBytes -= mesh.byteSize();
				schedule(mesh);
				iterator.remove();
				removed++;
			}
		}
		return removed;
	}

	public synchronized void drain() {
		RenderSystem.assertOnRenderThread();
		drainRetired();
	}

	public long budgetBytes() { return budgetBytes; }
	public int budgetEntries() { return budgetEntries; }
	public synchronized long residentBytes() { return residentBytes; }
	public synchronized long retiredBytes() { return retiredBytes; }
	public synchronized int size() { return meshes.size(); }

	private void install(LodGpuMesh mesh) {
		LodGpuMesh replaced = meshes.put(mesh.key(), mesh);
		if (replaced != null) {
			residentBytes -= replaced.byteSize();
			schedule(replaced);
		}
		residentBytes += mesh.byteSize();
		while (meshes.size() > budgetEntries) {
			Iterator<Map.Entry<LodData.LodKey, LodGpuMesh>> iterator = meshes.entrySet().iterator();
			if (!iterator.hasNext()) {
				throw new IllegalStateException("LOD GPU store accounting is inconsistent");
			}
			LodGpuMesh evicted = iterator.next().getValue();
			iterator.remove();
			residentBytes -= evicted.byteSize();
			schedule(evicted);
		}
	}

	private void schedule(LodGpuMesh mesh) {
		retired.addLast(new Retired(MetalAccess.submittedFrames(), mesh.buffer(), mesh.byteSize()));
		retiredBytes += mesh.byteSize();
	}

	private void drainRetired() {
		long completed = MetalAccess.completedFrames();
		while (!retired.isEmpty() && retired.peekFirst().frame() < completed) {
			Retired item = retired.removeFirst();
			item.buffer().close();
			retiredBytes -= item.bytes();
		}
	}

	private record Retired(long frame, GpuBuffer buffer, long bytes) {}
}
