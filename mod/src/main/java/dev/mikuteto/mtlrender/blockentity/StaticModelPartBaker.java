package dev.mikuteto.mtlrender.blockentity;

import com.mojang.blaze3d.vertex.PoseStack;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Vector3f;
import org.joml.Vector3fc;

public final class StaticModelPartBaker {
	public StaticBlockEntityBakePlan bake(StaticBlockEntityBakeKey key, Model<?> model,
		StaticModelMaterial material, Collection<String> selectedPaths) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(material, "material");
		Objects.requireNonNull(selectedPaths, "selectedPaths");
		if (model == null) {
			return fallback(key, StaticBlockEntityBakeFallback.Reason.UNSUPPORTED_MODEL, "");
		}
		if (!model.getClass().getPackageName().equals("net.minecraft.client.model")
			&& !model.getClass().getPackageName().startsWith("net.minecraft.client.model.")) {
			return fallback(key, StaticBlockEntityBakeFallback.Reason.UNSUPPORTED_MODEL, "");
		}
		List<String> paths = canonicalPaths(selectedPaths);
		if (paths.isEmpty()) {
			return fallback(key, StaticBlockEntityBakeFallback.Reason.MISSING_PART, "");
		}
		StaticBlockEntityBakePlan poseFailure = checkInitialPose(key, model);
		if (poseFailure != null) {
			return poseFailure;
		}
		Map<String, ModelPart> parts = new HashMap<>();
		for (String path : paths) {
			ModelPart part = find(model.root(), path);
			if (part == null) {
				return fallback(key, StaticBlockEntityBakeFallback.Reason.MISSING_PART, path);
			}
			parts.put(path, part);
		}
		TreeMap<String, List<VertexData>> geometry = new TreeMap<>();
		for (String path : paths) {
			geometry.put(path, new ArrayList<>());
		}
		List<VisitData> visits = new ArrayList<>();
		try {
			model.root().visit(new PoseStack(), (pose, path, cubeIndex, cube) -> collect(visits, pose, path, cubeIndex, cube));
		} catch (RuntimeException exception) {
			return fallback(key, StaticBlockEntityBakeFallback.Reason.INVALID_GEOMETRY, "");
		}
		visits.sort(Comparator.comparing(VisitData::path).thenComparingInt(VisitData::cubeIndex)
			.thenComparingInt(VisitData::polygonIndex).thenComparingInt(VisitData::vertexIndex));
		for (VisitData visit : visits) {
			String target = targetPath(paths, visit.path());
			ModelPart source = find(model.root(), visit.path());
			if (target == null || source == null || source.skipDraw || !visible(model.root(), visit.path())) {
				continue;
			}
			geometry.get(target).add(visit.vertex());
		}
		List<StaticBlockEntityBakePart> baked = new ArrayList<>();
		List<StaticBlockEntityBakeFallback> fallbacks = new ArrayList<>();
		for (String path : paths) {
			List<VertexData> vertices = geometry.get(path);
			ModelPart part = parts.get(path);
			if (!part.visible) {
				fallbacks.add(new StaticBlockEntityBakeFallback(StaticBlockEntityBakeFallback.Reason.HIDDEN_PART, path));
			} else if (vertices.isEmpty()) {
				fallbacks.add(new StaticBlockEntityBakeFallback(StaticBlockEntityBakeFallback.Reason.SKIPPED_PART, path));
			} else {
				baked.add(new StaticBlockEntityBakePart(partKey(path), encode(vertices), material.key()));
			}
		}
		return new StaticBlockEntityBakePlan(key, baked, fallbacks);
	}

	private StaticBlockEntityBakePlan checkInitialPose(StaticBlockEntityBakeKey key, Model<?> model) {
		for (ModelPart part : model.allParts()) {
			var pose = part.getInitialPose();
			if (!finite(part.x) || !finite(part.y) || !finite(part.z) || !finite(part.xRot)
				|| !finite(part.yRot) || !finite(part.zRot) || !finite(part.xScale) || !finite(part.yScale)
				|| !finite(part.zScale) || part.x != pose.x() || part.y != pose.y() || part.z != pose.z()
				|| part.xRot != pose.xRot() || part.yRot != pose.yRot() || part.zRot != pose.zRot()
				|| part.xScale != pose.xScale() || part.yScale != pose.yScale() || part.zScale != pose.zScale()) {
				return fallback(key, StaticBlockEntityBakeFallback.Reason.ANIMATED_POSE, "");
			}
		}
		return null;
	}

	private void collect(List<VisitData> output, PoseStack.Pose pose, String path, int cubeIndex, ModelPart.Cube cube) {
		for (int polygonIndex = 0; polygonIndex < cube.polygons.length; polygonIndex++) {
			ModelPart.Polygon polygon = cube.polygons[polygonIndex];
			Vector3f normal = pose.transformNormal(polygon.normal(), new Vector3f());
			for (int vertexIndex = 0; vertexIndex < polygon.vertices().length; vertexIndex++) {
				ModelPart.Vertex vertex = polygon.vertices()[vertexIndex];
				Vector3f position = pose.pose().transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), new Vector3f());
				VertexData data = new VertexData(position.x(), position.y(), position.z(), vertex.u(), vertex.v(),
					normal.x(), normal.y(), normal.z());
				if (!data.finite()) {
					throw new IllegalArgumentException("non-finite model geometry");
				}
				output.add(new VisitData(path, cubeIndex, polygonIndex, vertexIndex, data));
			}
		}
	}

	private static byte[] encode(List<VertexData> vertices) {
		ByteBuffer buffer = ByteBuffer.allocate(4 + vertices.size() * 32).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(vertices.size());
		for (VertexData vertex : vertices) {
			buffer.putInt(Float.floatToIntBits(vertex.x()));
			buffer.putInt(Float.floatToIntBits(vertex.y()));
			buffer.putInt(Float.floatToIntBits(vertex.z()));
			buffer.putInt(Float.floatToIntBits(vertex.u()));
			buffer.putInt(Float.floatToIntBits(vertex.v()));
			buffer.putInt(Float.floatToIntBits(vertex.nx()));
			buffer.putInt(Float.floatToIntBits(vertex.ny()));
			buffer.putInt(Float.floatToIntBits(vertex.nz()));
		}
		return buffer.array();
	}

	private static List<String> canonicalPaths(Collection<String> source) {
		return source.stream().filter(Objects::nonNull).map(String::trim).distinct().sorted().toList();
	}

	private static String targetPath(List<String> paths, String path) {
		for (String candidate : paths) {
			if (path.equals(candidate) || (candidate.isEmpty() ? true : path.startsWith(candidate + "/"))) {
				return candidate;
			}
		}
		return null;
	}

	private static ModelPart find(ModelPart root, String path) {
		if (path.isEmpty()) {
			return root;
		}
		ModelPart current = root;
		for (String name : path.substring(1).split("/")) {
			if (name.isEmpty() || !current.hasChild(name)) {
				return null;
			}
			current = current.getChild(name);
		}
		return current;
	}

	private static boolean visible(ModelPart root, String path) {
		ModelPart current = root;
		if (!current.visible) {
			return false;
		}
		if (path.isEmpty()) {
			return true;
		}
		for (String name : path.substring(1).split("/")) {
			current = current.getChild(name);
			if (!current.visible) {
				return false;
			}
		}
		return true;
	}

	private static StaticBlockEntityBakePlan fallback(StaticBlockEntityBakeKey key,
		StaticBlockEntityBakeFallback.Reason reason, String path) {
		return new StaticBlockEntityBakePlan(key, List.of(), List.of(new StaticBlockEntityBakeFallback(reason, path)));
	}

	private static String partKey(String path) {
		return path.isEmpty() ? "root" : path;
	}

	private static boolean finite(float value) {
		return Float.isFinite(value);
	}

	private record VisitData(String path, int cubeIndex, int polygonIndex, int vertexIndex, VertexData vertex) {}

	private record VertexData(float x, float y, float z, float u, float v, float nx, float ny, float nz) {
		private boolean finite() {
			return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z) && Float.isFinite(u)
				&& Float.isFinite(v) && Float.isFinite(nx) && Float.isFinite(ny) && Float.isFinite(nz);
		}
	}
}
