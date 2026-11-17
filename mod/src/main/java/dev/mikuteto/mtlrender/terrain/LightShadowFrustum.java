package dev.mikuteto.mtlrender.terrain;

import dev.mikuteto.mtlrender.pack.shadow.ShadowMatrices;
import dev.mikuteto.mtlrender.pack.shadow.ShadowPassPlan;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Arrays;
import java.util.Objects;

public final class LightShadowFrustum {
	private static final float SECTION_RADIUS = (float) (8.0 * Math.sqrt(3.0));
	private final float[] clip;
	private final int cameraBlockX;
	private final int cameraBlockY;
	private final int cameraBlockZ;
	private final float cameraOffsetX;
	private final float cameraOffsetY;
	private final float cameraOffsetZ;
	private final ShadowPassPlan.CullingMode cullingMode;
	private final float shadowDistance;
	private final float casterExtent;

	private LightShadowFrustum(float[] clip, int cameraBlockX, int cameraBlockY, int cameraBlockZ,
		float cameraOffsetX, float cameraOffsetY, float cameraOffsetZ,
		ShadowPassPlan.CullingMode cullingMode, float shadowDistance, float casterExtent) {
		this.clip = clip;
		this.cameraBlockX = cameraBlockX;
		this.cameraBlockY = cameraBlockY;
		this.cameraBlockZ = cameraBlockZ;
		this.cameraOffsetX = cameraOffsetX;
		this.cameraOffsetY = cameraOffsetY;
		this.cameraOffsetZ = cameraOffsetZ;
		this.cullingMode = cullingMode;
		this.shadowDistance = shadowDistance;
		this.casterExtent = casterExtent;
	}

	public static LightShadowFrustum from(ShadowPassPlan plan) {
		Objects.requireNonNull(plan, "shadow plan");
		ShadowMatrices matrices = plan.matrices();
		float originX = matrices.snappedOriginX();
		float originY = matrices.snappedOriginY();
		float originZ = matrices.snappedOriginZ();
		int blockX = floor(originX);
		int blockY = floor(originY);
		int blockZ = floor(originZ);
		float offsetX = blockX - originX;
		float offsetY = blockY - originY;
		float offsetZ = blockZ - originZ;
		Matrix4f worldClip = new Matrix4f(matrices.projection()).mul(matrices.modelView());
		Matrix4f localClip = worldClip.translate(originX, originY, originZ);
		float extent = SECTION_RADIUS + Math.max(0.0F, plan.shadowDistanceRenderMul()) * plan.shadowDistance();
		float factor = plan.shadowDistance() / (plan.shadowDistance() + extent);
		float[] values = new float[16];
		localClip.get(values);
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 4; column++) {
				int index = column * 4 + row;
				values[index] *= factor;
			}
		}
		return new LightShadowFrustum(values, blockX, blockY, blockZ, offsetX, offsetY, offsetZ,
			plan.cullingMode(), plan.shadowDistance(), extent);
	}

	public float[] clipValues() {
		return clip.clone();
	}

	public Matrix4fc clipMatrix() {
		return new Matrix4f().set(Arrays.copyOf(clip, clip.length));
	}

	public int cameraBlockX() { return cameraBlockX; }
	public int cameraBlockY() { return cameraBlockY; }
	public int cameraBlockZ() { return cameraBlockZ; }
	public float cameraOffsetX() { return cameraOffsetX; }
	public float cameraOffsetY() { return cameraOffsetY; }
	public float cameraOffsetZ() { return cameraOffsetZ; }
	public ShadowPassPlan.CullingMode cullingMode() { return cullingMode; }
	public float shadowDistance() { return shadowDistance; }
	public float casterExtent() { return casterExtent; }

	public boolean includes(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		if (!finite(minX) || !finite(minY) || !finite(minZ) || !finite(maxX) || !finite(maxY) || !finite(maxZ)
			|| minX > maxX || minY > maxY || minZ > maxZ) {
			return true;
		}
		for (int corner = 0; corner < 8; corner++) {
			float x = (corner & 1) == 0 ? minX : maxX;
			float y = (corner & 2) == 0 ? minY : maxY;
			float z = (corner & 4) == 0 ? minZ : maxZ;
			if (inside(clip, x - cameraBlockX + cameraOffsetX, y - cameraBlockY + cameraOffsetY,
				z - cameraBlockZ + cameraOffsetZ)) {
				return true;
			}
		}
		return intersectsByPlane(minX, minY, minZ, maxX, maxY, maxZ);
	}

	private boolean intersectsByPlane(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		for (int plane = 0; plane < 6; plane++) {
			int axis = plane / 2;
			float sign = (plane & 1) == 0 ? 1.0F : -1.0F;
			float a = clip[3] + sign * clip[axis];
			float b = clip[7] + sign * clip[4 + axis];
			float c = clip[11] + sign * clip[8 + axis];
			float d = clip[15] + sign * clip[12 + axis];
			float x = a >= 0.0F ? maxX : minX;
			float y = b >= 0.0F ? maxY : minY;
			float z = c >= 0.0F ? maxZ : minZ;
			if (a * x + b * y + c * z + d < 0.0F) {
				return false;
			}
		}
		return true;
	}

	private static boolean inside(float[] matrix, float x, float y, float z) {
		float cx = matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12];
		float cy = matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13];
		float cz = matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14];
		float cw = matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15];
		return cw <= 1.0E-5F || (cx >= -cw && cx <= cw && cy >= -cw && cy <= cw && cz >= -cw && cz <= cw);
	}

	private static int floor(float value) {
		return (int) Math.floor(value);
	}

	private static boolean finite(float value) {
		return Float.isFinite(value);
	}
}
