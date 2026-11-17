package dev.mikuteto.mtlrender.pack.uniform;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public final class DepthConvention {
	private DepthConvention() {
	}

	public static float gameClipZToPack(float gameClipZ, float w) {
		return w - 2.0F * gameClipZ;
	}

	public static float packClipZToGame(float packClipZ, float w) {
		return 0.5F * (w - packClipZ);
	}

	public static float gameNdcZToPack(float gameNdcZ) {
		return 1.0F - 2.0F * gameNdcZ;
	}

	public static float packNdcZToGame(float packNdcZ) {
		return 0.5F * (1.0F - packNdcZ);
	}

	public static float packWindowDepthToGame(float packDepth) {
		return 1.0F - packDepth;
	}

	public static float gameWindowDepthToPack(float gameDepth) {
		return 1.0F - gameDepth;
	}

	public static Matrix4f gameProjectionToPack(Matrix4fc gameProjection) {
		Matrix4f result = new Matrix4f(gameProjection);
		result.m02(gameProjection.m03() - 2.0F * gameProjection.m02());
		result.m12(gameProjection.m13() - 2.0F * gameProjection.m12());
		result.m22(gameProjection.m23() - 2.0F * gameProjection.m22());
		result.m32(gameProjection.m33() - 2.0F * gameProjection.m32());
		return result;
	}

	public static Matrix4f packProjectionToGame(Matrix4fc packProjection) {
		Matrix4f result = new Matrix4f(packProjection);
		result.m02(0.5F * (packProjection.m03() - packProjection.m02()));
		result.m12(0.5F * (packProjection.m13() - packProjection.m12()));
		result.m22(0.5F * (packProjection.m23() - packProjection.m22()));
		result.m32(0.5F * (packProjection.m33() - packProjection.m32()));
		return result;
	}

	public static Matrix4f gameProjectionInverseToPack(Matrix4fc gameProjectionInverse) {
		return new Matrix4f(gameProjectionInverse).mul(packClipToGameMatrix());
	}

	public static Matrix4f packProjectionInverseToGame(Matrix4fc packProjectionInverse) {
		return new Matrix4f(packProjectionInverse).mul(gameClipToPackMatrix());
	}

	private static Matrix4f gameClipToPackMatrix() {
		return new Matrix4f().m22(-2.0F).m32(1.0F);
	}

	private static Matrix4f packClipToGameMatrix() {
		return new Matrix4f().m22(-0.5F).m32(0.5F);
	}
}
