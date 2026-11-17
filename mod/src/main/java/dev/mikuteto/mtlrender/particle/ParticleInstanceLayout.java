package dev.mikuteto.mtlrender.particle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class ParticleInstanceLayout {
	public static final int STRIDE = 64;
	public static final int POSITION_X = 0;
	public static final int POSITION_Y = 4;
	public static final int POSITION_Z = 8;
	public static final int QUATERNION_X = 12;
	public static final int QUATERNION_Y = 16;
	public static final int QUATERNION_Z = 20;
	public static final int QUATERNION_W = 24;
	public static final int SCALE = 28;
	public static final int U0 = 32;
	public static final int U1 = 36;
	public static final int V0 = 40;
	public static final int V1 = 44;
	public static final int COLOR = 48;
	public static final int LIGHT = 52;

	private ParticleInstanceLayout() {
	}

	public static void pack(ByteBuffer target, int offset, ParticleInstance instance) {
		ByteBuffer buffer = target.order(ByteOrder.nativeOrder());
		buffer.putFloat(offset + POSITION_X, instance.x());
		buffer.putFloat(offset + POSITION_Y, instance.y());
		buffer.putFloat(offset + POSITION_Z, instance.z());
		buffer.putFloat(offset + QUATERNION_X, instance.xRot());
		buffer.putFloat(offset + QUATERNION_Y, instance.yRot());
		buffer.putFloat(offset + QUATERNION_Z, instance.zRot());
		buffer.putFloat(offset + QUATERNION_W, instance.wRot());
		buffer.putFloat(offset + SCALE, instance.scale());
		buffer.putFloat(offset + U0, instance.u0());
		buffer.putFloat(offset + U1, instance.u1());
		buffer.putFloat(offset + V0, instance.v0());
		buffer.putFloat(offset + V1, instance.v1());
		buffer.putInt(offset + COLOR, instance.color());
		buffer.putInt(offset + LIGHT, instance.lightCoords());
	}

	public static ParticleInstance unpack(ByteBuffer source, int offset) {
		ByteBuffer buffer = source.order(ByteOrder.nativeOrder());
		return new ParticleInstance(
			buffer.getFloat(offset + POSITION_X),
			buffer.getFloat(offset + POSITION_Y),
			buffer.getFloat(offset + POSITION_Z),
			buffer.getFloat(offset + QUATERNION_X),
			buffer.getFloat(offset + QUATERNION_Y),
			buffer.getFloat(offset + QUATERNION_Z),
			buffer.getFloat(offset + QUATERNION_W),
			buffer.getFloat(offset + SCALE),
			buffer.getFloat(offset + U0),
			buffer.getFloat(offset + U1),
			buffer.getFloat(offset + V0),
			buffer.getFloat(offset + V1),
			buffer.getInt(offset + COLOR),
			buffer.getInt(offset + LIGHT)
		);
	}

	public record ParticleInstance(
		float x, float y, float z,
		float xRot, float yRot, float zRot, float wRot,
		float scale, float u0, float u1, float v0, float v1,
		int color, int lightCoords
	) {
	}
}
