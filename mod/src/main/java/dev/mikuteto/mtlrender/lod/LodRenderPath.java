package dev.mikuteto.mtlrender.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.GpuDevice;

import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.config.Config;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

public final class LodRenderPath implements AutoCloseable {
	private static final int NODE_BYTES = 64;
	private static final int MESH_BYTES = 20;
	private static final int DRAW_BYTES = 20;
	private static final float AREA_THRESHOLD = 0.0008F;

	private final LodRenderManager manager;
	private final boolean enabled;
	private List<LodGpuMesh> complete = List.of();
	private long completeGeneration = -1L;
	private Traversal traversal;

	public LodRenderPath() {
		this(new LodRenderManager());
	}

	LodRenderPath(LodRenderManager manager) {
		this.manager = manager;
		this.enabled = Config.get().terrain.lodBeyondRenderDistance;
	}

	public void prepare(ClientLevel level, Vec3 camera, float[] frustum, int renderDistance) {
		traversal = null;
		if (!enabled || level == null || camera == null || frustum == null || frustum.length != 16) {
			return;
		}
		manager.update(level, ignored -> Map.of(), new LodMesh.Material[0], resourceGeneration(level));
		List<LodGpuMesh> current = new ArrayList<>(manager.meshes());
		if (!current.isEmpty() && manager.generation() >= completeGeneration) {
			complete = List.copyOf(current);
			completeGeneration = manager.generation();
		}
		traversal = buildTraversal(camera, frustum, renderDistance, complete);
	}

	public boolean active() {
		return enabled && traversal != null && traversal.meshCount() > 0;
	}

	public Traversal traversal() {
		return traversal;
	}

	public boolean beyondBoundary(LodData.LodKey key, Vec3 camera, int renderDistance) {
		if (key == null || camera == null || renderDistance < 0) {
			return false;
		}
		float boundary = renderDistance * 16.0F;
		float minX = key.originX();
		float maxX = minX + (LodData.LOD_SIZE << key.level());
		float minZ = key.originZ();
		float maxZ = minZ + (LodData.LOD_SIZE << key.level());
		float dx = distance(camera.x, minX, maxX);
		float dz = distance(camera.z, minZ, maxZ);
		return dx * dx + dz * dz > (boundary + 16.0F) * (boundary + 16.0F);
	}

	public void render() {
		if (traversal == null || traversal.output() == null) {
			return;
		}
	}

	@Override
	public void close() {
		manager.close();
	}

	private Traversal buildTraversal(Vec3 camera, float[] frustum, int renderDistance, List<LodGpuMesh> meshes) {
		List<LodGpuMesh> selected = meshes.stream()
			.filter(mesh -> beyondBoundary(mesh.key(), camera, renderDistance))
			.toList();
		if (selected.isEmpty()) {
			return null;
		}
		GpuDevice device = RenderSystem.getDevice();
		ByteBuffer nodes = ByteBuffer.allocate(selected.size() * NODE_BYTES).order(ByteOrder.nativeOrder());
		ByteBuffer drawMeshes = ByteBuffer.allocate(selected.size() * MESH_BYTES).order(ByteOrder.nativeOrder());
		for (int i = 0; i < selected.size(); i++) {
			LodGpuMesh mesh = selected.get(i);
			LodData.LodKey key = mesh.key();
			float span = LodData.LOD_SIZE << key.level();
			nodes.putFloat(key.originX()).putFloat(key.originY()).putFloat(key.originZ()).putFloat(0.0F);
			nodes.putFloat(key.originX() + span).putFloat(key.originY() + span).putFloat(key.originZ() + span).putFloat(0.0F);
			nodes.putInt(0).putInt(0).putInt(i).putInt(key.level()).putInt(0).putFloat(key.originY()).putFloat(key.originY() + span).putInt(0);
			int quads = 0;
			for (int count : mesh.faceCounts()) {
				quads += count;
			}
			drawMeshes.putInt(192).putInt(Math.max(1, quads)).putInt(0).putInt(0).putInt(i);
		}
		GpuBufferSlice nodeBuffer = device.createCommandEncoder().transientMemory().uploadGpu(nodes.flip(), 256, GpuBuffer.USAGE_UNIFORM);
		GpuBufferSlice meshBuffer = device.createCommandEncoder().transientMemory().uploadGpu(drawMeshes.flip(), 256, GpuBuffer.USAGE_UNIFORM);
		GpuBufferSlice output = device.createCommandEncoder().transientMemory().allocateGpuMapped(
			(long) selected.size() * DRAW_BYTES, 256, GpuBuffer.USAGE_INDIRECT_PARAMETERS,
			(long) selected.size() * DRAW_BYTES, 1).slice();
		MetalAccess.lodCull(nodeBuffer, meshBuffer, new float[] {(float) camera.x, (float) camera.y, (float) camera.z},
			frustum, output, 0, LodData.MAX_LEVEL, AREA_THRESHOLD);
		return new Traversal(nodeBuffer, meshBuffer, output, selected.size());
	}

	private static float distance(double value, float min, float max) {
		return (float) Math.max(min - value, Math.max(0.0, value - max));
	}

	private static long resourceGeneration(ClientLevel level) {
		return level.dimension().identifier().hashCode() & 0x7FFFFFFFL;
	}

	public record Traversal(GpuBufferSlice nodes, GpuBufferSlice meshes, GpuBufferSlice output, int meshCount) {
	}
}
