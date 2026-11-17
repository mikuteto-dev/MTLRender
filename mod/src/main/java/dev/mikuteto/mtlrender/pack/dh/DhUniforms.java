package dev.mikuteto.mtlrender.pack.dh;

import dev.mikuteto.mtlrender.pack.uniform.DepthConvention;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class DhUniforms {
	private static final float CORNER_MARGIN_BLOCKS = 512.0F;
	private static final float CORNER_SCALE = (float) Math.sqrt(2.0);
	private final Map<DhUniformState.CameraKind, DhUniformState> previous = new EnumMap<>(DhUniformState.CameraKind.class);

	public DhUniformState update(DhUniformState.CameraKind cameraKind, Matrix4fc modelView, Matrix4fc packProjection,
		float nearPlane, LodExtent extent) {
		return update(cameraKind, modelView, packProjection, nearPlane, extent, 0L);
	}

	public DhUniformState updateShadow(ShadowCamera camera, LodExtent extent) {
		Objects.requireNonNull(camera, "shadow camera");
		Objects.requireNonNull(extent, "LOD extent");
		int renderDistance = extent.renderDistanceBlocks();
		float dhFarPlane = farPlane(renderDistance);
		Matrix4f modelView = new Matrix4f(camera.modelView());
		Matrix4f projection = new Matrix4f(camera.projection());
		Matrix4f modelViewInverse = new Matrix4f(modelView).invert();
		Matrix4f projectionInverse = new Matrix4f(projection).invert();
		DhUniformState old = previous.get(DhUniformState.CameraKind.SHADOW);
		Matrix4fc previousModelView = old == null ? modelView : old.shadowModelViewMatrix();
		Matrix4fc previousProjection = old == null ? projection : old.shadowProjectionMatrix();
		DhUniformState state = new DhUniformState(DhUniformState.CameraKind.SHADOW, camera.dhNearPlane(), dhFarPlane,
			renderDistance, modelView, modelViewInverse, previousModelView, projection, projectionInverse, previousProjection,
			modelView, modelViewInverse, previousModelView, projection, projectionInverse, previousProjection,
			camera.generation(), camera.nearPlane(), camera.farPlane());
		previous.put(DhUniformState.CameraKind.SHADOW, state);
		return state;
	}

	public DhUniformState update(DhUniformState.CameraKind cameraKind, Matrix4fc modelView, Matrix4fc packProjection,
		float nearPlane, LodExtent extent, long generation) {
		Objects.requireNonNull(cameraKind, "cameraKind");
		Objects.requireNonNull(modelView, "modelView");
		Objects.requireNonNull(packProjection, "packProjection");
		Objects.requireNonNull(extent, "extent");
		if (!Float.isFinite(nearPlane) || nearPlane <= 0.0F) {
			throw new IllegalArgumentException("near plane must be finite and positive");
		}
		int renderDistance = extent.renderDistanceBlocks();
		float farPlane = farPlane(renderDistance);
		Matrix4f projection = projection(packProjection, nearPlane, farPlane);
		Matrix4f modelViewInverse = new Matrix4f(modelView).invert();
		Matrix4f projectionInverse = new Matrix4f(projection).invert();
		DhUniformState old = previous.get(cameraKind);
		Matrix4fc previousModelView = old == null ? modelView : old.dhModelViewMatrix();
		Matrix4fc previousProjection = old == null ? projection : old.dhProjectionMatrix();
		DhUniformState state = new DhUniformState(cameraKind, nearPlane, farPlane, renderDistance,
			modelView, modelViewInverse, previousModelView, projection, projectionInverse, previousProjection,
			modelView, modelViewInverse, previousModelView, projection, projectionInverse, previousProjection,
			generation, nearPlane, farPlane);
		previous.put(cameraKind, state);
		return state;
	}

	public void reset() {
		previous.clear();
	}

	private static float farPlane(int renderDistance) {
		return (renderDistance + CORNER_MARGIN_BLOCKS) * CORNER_SCALE;
	}

	public record ShadowCamera(Matrix4fc modelView, Matrix4fc projection, float nearPlane, float farPlane,
		float dhNearPlane, long generation) {
		public ShadowCamera {
			Objects.requireNonNull(modelView, "shadow model-view");
			Objects.requireNonNull(projection, "shadow projection");
			if (!Float.isFinite(nearPlane) || !Float.isFinite(farPlane) || farPlane <= nearPlane) {
				throw new IllegalArgumentException("shadow planes must be finite and ordered");
			}
			if (!Float.isFinite(dhNearPlane) || dhNearPlane <= 0.0F || generation < 0L) {
				throw new IllegalArgumentException("invalid DH shadow camera values");
			}
		}
	}

	private static Matrix4f projection(Matrix4fc packProjection, float nearPlane, float farPlane) {
		Matrix4f gameProjection = DepthConvention.packProjectionToGame(packProjection);
		return DepthConvention.gameProjectionToPack(new Matrix4f().setPerspective(
			gameProjection.perspectiveFov(), gameProjection.m11() / gameProjection.m00(), nearPlane, farPlane));
	}

	public record LodExtent(int minX, int minZ, int maxX, int maxZ, double cameraX, double cameraZ) {
		public LodExtent {
			if (maxX < minX || maxZ < minZ || !Double.isFinite(cameraX) || !Double.isFinite(cameraZ)) {
				throw new IllegalArgumentException("invalid LOD extent");
			}
		}

		public int renderDistanceBlocks() {
			double distance = 0.0;
			for (int x : new int[] {minX, maxX}) {
				for (int z : new int[] {minZ, maxZ}) {
					distance = Math.max(distance, Math.hypot(x - cameraX, z - cameraZ));
				}
			}
			if (!Double.isFinite(distance) || distance > Integer.MAX_VALUE - 15.0) {
				throw new IllegalArgumentException("LOD extent is too large");
			}
			return Math.max(0, ((int) Math.ceil(distance) + 15) / 16 * 16);
		}
	}
}
