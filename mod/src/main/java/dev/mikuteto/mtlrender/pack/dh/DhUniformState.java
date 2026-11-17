package dev.mikuteto.mtlrender.pack.dh;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Arrays;
import java.util.Objects;

public final class DhUniformState {
	public enum CameraKind {
		MAIN,
		SHADOW
	}

	private final CameraKind cameraKind;
	private final float dhNearPlane;
	private final float dhFarPlane;
	private final int dhRenderDistance;
	private final float[] dhModelView;
	private final float[] dhModelViewInverse;
	private final float[] dhPreviousModelView;
	private final float[] dhProjection;
	private final float[] dhProjectionInverse;
	private final float[] dhPreviousProjection;
	private final float shadowNearPlane;
	private final float shadowFarPlane;
	private final float[] shadowModelView;
	private final float[] shadowModelViewInverse;
	private final float[] shadowPreviousModelView;
	private final float[] shadowProjection;
	private final float[] shadowProjectionInverse;
	private final float[] shadowPreviousProjection;
	private final long generation;

	DhUniformState(CameraKind cameraKind, float dhNearPlane, float dhFarPlane, int dhRenderDistance,
		Matrix4fc modelView, Matrix4fc modelViewInverse, Matrix4fc previousModelView,
		Matrix4fc projection, Matrix4fc projectionInverse, Matrix4fc previousProjection) {
		this(cameraKind, dhNearPlane, dhFarPlane, dhRenderDistance, modelView, modelViewInverse, previousModelView,
			projection, projectionInverse, previousProjection, modelView, modelViewInverse, previousModelView,
			projection, projectionInverse, previousProjection, 0L, dhNearPlane, dhFarPlane);
	}

	DhUniformState(CameraKind cameraKind, float dhNearPlane, float dhFarPlane, int dhRenderDistance,
		Matrix4fc modelView, Matrix4fc modelViewInverse, Matrix4fc previousModelView,
		Matrix4fc projection, Matrix4fc projectionInverse, Matrix4fc previousProjection,
		Matrix4fc shadowModelView, Matrix4fc shadowModelViewInverse, Matrix4fc shadowPreviousModelView,
		Matrix4fc shadowProjection, Matrix4fc shadowProjectionInverse, Matrix4fc shadowPreviousProjection,
		long generation, float shadowNearPlane, float shadowFarPlane) {
		this.cameraKind = Objects.requireNonNull(cameraKind, "cameraKind");
		if (!Float.isFinite(dhNearPlane) || !Float.isFinite(dhFarPlane) || dhNearPlane <= 0.0F || dhFarPlane <= dhNearPlane) {
			throw new IllegalArgumentException("DH planes must be finite and ordered");
		}
		if (!Float.isFinite(shadowNearPlane) || !Float.isFinite(shadowFarPlane) || shadowFarPlane <= shadowNearPlane) {
			throw new IllegalArgumentException("shadow planes must be finite and ordered");
		}
		if (dhRenderDistance < 0 || generation < 0L) {
			throw new IllegalArgumentException("DH distance and generation must be non-negative");
		}
		this.dhNearPlane = dhNearPlane;
		this.dhFarPlane = dhFarPlane;
		this.dhRenderDistance = dhRenderDistance;
		this.dhModelView = values(modelView);
		this.dhModelViewInverse = values(modelViewInverse);
		this.dhPreviousModelView = values(previousModelView);
		this.dhProjection = values(projection);
		this.dhProjectionInverse = values(projectionInverse);
		this.dhPreviousProjection = values(previousProjection);
		this.shadowNearPlane = shadowNearPlane;
		this.shadowFarPlane = shadowFarPlane;
		this.shadowModelView = values(shadowModelView);
		this.shadowModelViewInverse = values(shadowModelViewInverse);
		this.shadowPreviousModelView = values(shadowPreviousModelView);
		this.shadowProjection = values(shadowProjection);
		this.shadowProjectionInverse = values(shadowProjectionInverse);
		this.shadowPreviousProjection = values(shadowPreviousProjection);
		this.generation = generation;
	}

	public CameraKind cameraKind() { return cameraKind; }
	public float dhNearPlane() { return dhNearPlane; }
	public float dhFarPlane() { return dhFarPlane; }
	public int dhRenderDistance() { return dhRenderDistance; }
	public float[] dhModelView() { return dhModelView.clone(); }
	public float[] dhModelViewInverse() { return dhModelViewInverse.clone(); }
	public float[] dhPreviousModelView() { return dhPreviousModelView.clone(); }
	public float[] dhProjection() { return dhProjection.clone(); }
	public float[] dhProjectionInverse() { return dhProjectionInverse.clone(); }
	public float[] dhPreviousProjection() { return dhPreviousProjection.clone(); }
	public float shadowNearPlane() { return shadowNearPlane; }
	public float shadowFarPlane() { return shadowFarPlane; }
	public float[] shadowModelView() { return shadowModelView.clone(); }
	public float[] shadowModelViewInverse() { return shadowModelViewInverse.clone(); }
	public float[] shadowPreviousModelView() { return shadowPreviousModelView.clone(); }
	public float[] shadowProjection() { return shadowProjection.clone(); }
	public float[] shadowProjectionInverse() { return shadowProjectionInverse.clone(); }
	public float[] shadowPreviousProjection() { return shadowPreviousProjection.clone(); }
	public long generation() { return generation; }

	public Matrix4f dhModelViewMatrix() { return matrix(dhModelView); }
	public Matrix4f dhModelViewInverseMatrix() { return matrix(dhModelViewInverse); }
	public Matrix4f dhPreviousModelViewMatrix() { return matrix(dhPreviousModelView); }
	public Matrix4f dhProjectionMatrix() { return matrix(dhProjection); }
	public Matrix4f dhProjectionInverseMatrix() { return matrix(dhProjectionInverse); }
	public Matrix4f dhPreviousProjectionMatrix() { return matrix(dhPreviousProjection); }
	public Matrix4f shadowModelViewMatrix() { return matrix(shadowModelView); }
	public Matrix4f shadowModelViewInverseMatrix() { return matrix(shadowModelViewInverse); }
	public Matrix4f shadowPreviousModelViewMatrix() { return matrix(shadowPreviousModelView); }
	public Matrix4f shadowProjectionMatrix() { return matrix(shadowProjection); }
	public Matrix4f shadowProjectionInverseMatrix() { return matrix(shadowProjectionInverse); }
	public Matrix4f shadowPreviousProjectionMatrix() { return matrix(shadowPreviousProjection); }

	private static float[] values(Matrix4fc matrix) {
		Objects.requireNonNull(matrix, "matrix");
		float[] values = new float[16];
		matrix.get(values);
		for (float value : values) {
			if (!Float.isFinite(value)) {
				throw new IllegalArgumentException("DH matrices must be finite");
			}
		}
		return values;
	}

	private static Matrix4f matrix(float[] values) {
		return new Matrix4f().set(Arrays.copyOf(values, values.length));
	}
}
