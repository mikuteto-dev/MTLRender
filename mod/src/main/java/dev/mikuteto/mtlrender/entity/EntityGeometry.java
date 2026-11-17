package dev.mikuteto.mtlrender.entity;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;

public final class EntityGeometry {
	private static final int STRIDE = 48;
	private static final Map<Model<?>, EntityGeometry> CACHE = new IdentityHashMap<>();

	private final GpuBuffer buffer;
	private final int vertexCount;
	private boolean closed;
	private final Map<String, Integer> parts;
	private final Bounds bounds;

	private EntityGeometry(GpuBuffer buffer, int vertexCount, Map<String, Integer> parts, Bounds bounds) {
		this.buffer = buffer;
		this.vertexCount = vertexCount;
		this.parts = parts;
		this.bounds = bounds;
	}

	static EntityGeometry get(Model<?> model) {
		return CACHE.computeIfAbsent(model, EntityGeometry::create);
	}

	private static EntityGeometry create(Model<?> model) {
		model.resetPose();
		Map<String, Integer> parts = new LinkedHashMap<>();
		ByteBuffer[] storage = {ByteBuffer.allocateDirect(STRIDE * 256).order(ByteOrder.nativeOrder())};
		int[] vertices = {0};
		float[] bounds = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
		model.root().visit(new PoseStack(), (pose, path, cubeIndex, cube) -> {
			int part = parts.computeIfAbsent(path, key -> parts.size());
			for (ModelPart.Polygon polygon : cube.polygons) {
				if (polygon == null) {
					continue;
				}
				ModelPart.Vertex[] polygonVertices = polygon.vertices();
				int[] corners = {0, 1, 2, 2, 3, 0};
				for (int corner : corners) {
					ModelPart.Vertex vertex = polygonVertices[corner];
					if (storage[0].remaining() < STRIDE) {
						ByteBuffer larger = ByteBuffer.allocateDirect(storage[0].capacity() * 2).order(ByteOrder.nativeOrder());
						storage[0].flip();
						larger.put(storage[0]);
						storage[0] = larger;
					}
					float x = vertex.worldX();
					float y = vertex.worldY();
					float z = vertex.worldZ();
					bounds[0] = Math.min(bounds[0], x);
					bounds[1] = Math.min(bounds[1], y);
					bounds[2] = Math.min(bounds[2], z);
					bounds[3] = Math.max(bounds[3], x);
					bounds[4] = Math.max(bounds[4], y);
					bounds[5] = Math.max(bounds[5], z);
					storage[0].putFloat(x);
					storage[0].putFloat(y);
					storage[0].putFloat(z);
					storage[0].putInt(-1);
					storage[0].putFloat(vertex.u());
					storage[0].putFloat(vertex.v());
					storage[0].putInt(0);
					storage[0].putInt(0);
					storage[0].putFloat(polygon.normal().x());
					storage[0].putFloat(polygon.normal().y());
					storage[0].putFloat(polygon.normal().z());
					storage[0].putInt(part);
					vertices[0]++;
				}
			}
		});
		storage[0].flip();
		GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "MTLRender entity model", GpuBuffer.USAGE_VERTEX, storage[0]);
		Bounds modelBounds = vertices[0] == 0
			? new Bounds(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F)
			: new Bounds(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
		return new EntityGeometry(buffer, vertices[0], Collections.unmodifiableMap(new LinkedHashMap<>(parts)), modelBounds);
	}

	GpuBuffer buffer() {
		return buffer;
	}

	int vertexCount() {
		return vertexCount;
	}

	int partCount() {
		return parts.size();
	}

	Map<String, Integer> parts() {
		return parts;
	}

	Bounds bounds() {
		return bounds;
	}

	record Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
	}

	public static void closeAll() {
		List<EntityGeometry> retired = new ArrayList<>(CACHE.values());
		CACHE.clear();
		Runnable close = () -> {
			for (EntityGeometry geometry : retired) {
				geometry.close();
			}
		};
		if (!MetalAccess.addCompletion(close)) {
			close.run();
		}
	}

	private synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		buffer.close();
	}
}
