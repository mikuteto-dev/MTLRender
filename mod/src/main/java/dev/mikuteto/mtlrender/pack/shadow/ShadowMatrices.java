package dev.mikuteto.mtlrender.pack.shadow;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Arrays;
import java.util.Objects;

public final class ShadowMatrices {
	private final float[] modelView;
	private final float[] modelViewInverse;
	private final float[] projection;
	private final float[] projectionInverse;
	private final float snappedOriginX;
	private final float snappedOriginY;
	private final float snappedOriginZ;
	private final float intervalSize;

	public ShadowMatrices(Matrix4fc modelView, Matrix4fc modelViewInverse, Matrix4fc projection,
		Matrix4fc projectionInverse, float snappedOriginX, float snappedOriginY, float snappedOriginZ,
		float intervalSize) {
		this.modelView = values(modelView);
		this.modelViewInverse = values(modelViewInverse);
		this.projection = values(projection);
		this.projectionInverse = values(projectionInverse);
		if (!Float.isFinite(snappedOriginX) || !Float.isFinite(snappedOriginY) || !Float.isFinite(snappedOriginZ)
			|| !Float.isFinite(intervalSize) || intervalSize < 0.0F) {
			throw new IllegalArgumentException("shadow origin and interval must be finite");
		}
		this.snappedOriginX = snappedOriginX;
		this.snappedOriginY = snappedOriginY;
		this.snappedOriginZ = snappedOriginZ;
		this.intervalSize = intervalSize;
	}

	public static ShadowMatrices identity() {
		Matrix4f identity = new Matrix4f();
		return new ShadowMatrices(identity, identity, identity, identity, 0.0F, 0.0F, 0.0F, 2.0F);
	}

	public Matrix4fc modelView() { return matrix(modelView); }
	public Matrix4fc modelViewInverse() { return matrix(modelViewInverse); }
	public Matrix4fc projection() { return matrix(projection); }
	public Matrix4fc projectionInverse() { return matrix(projectionInverse); }
	public float[] modelViewValues() { return modelView.clone(); }
	public float[] modelViewInverseValues() { return modelViewInverse.clone(); }
	public float[] projectionValues() { return projection.clone(); }
	public float[] projectionInverseValues() { return projectionInverse.clone(); }
	public float snappedOriginX() { return snappedOriginX; }
	public float snappedOriginY() { return snappedOriginY; }
	public float snappedOriginZ() { return snappedOriginZ; }
	public float intervalSize() { return intervalSize; }

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof ShadowMatrices that)) return false;
		return Float.compare(snappedOriginX, that.snappedOriginX) == 0
			&& Float.compare(snappedOriginY, that.snappedOriginY) == 0
			&& Float.compare(snappedOriginZ, that.snappedOriginZ) == 0
			&& Float.compare(intervalSize, that.intervalSize) == 0
			&& Arrays.equals(modelView, that.modelView)
			&& Arrays.equals(modelViewInverse, that.modelViewInverse)
			&& Arrays.equals(projection, that.projection)
			&& Arrays.equals(projectionInverse, that.projectionInverse);
	}

	@Override
	public int hashCode() {
		int result = Arrays.hashCode(modelView);
		result = 31 * result + Arrays.hashCode(modelViewInverse);
		result = 31 * result + Arrays.hashCode(projection);
		result = 31 * result + Arrays.hashCode(projectionInverse);
		result = 31 * result + Float.hashCode(snappedOriginX);
		result = 31 * result + Float.hashCode(snappedOriginY);
		result = 31 * result + Float.hashCode(snappedOriginZ);
		return 31 * result + Float.hashCode(intervalSize);
	}

	private static float[] values(Matrix4fc matrix) {
		Objects.requireNonNull(matrix, "matrix");
		float[] result = new float[16];
		matrix.get(result);
		for (float value : result) {
			if (!Float.isFinite(value)) throw new IllegalArgumentException("shadow matrices must be finite");
		}
		return result;
	}

	private static Matrix4fc matrix(float[] values) {
		return new Matrix4f().set(Arrays.copyOf(values, values.length));
	}
}
