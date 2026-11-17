package dev.mikuteto.mtlrender.terrain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import net.minecraft.client.resources.model.geometry.BakedQuad;

public final class PackTerrainJavaMesh {
	private static final float EPSILON = 1.1920929E-7F;
	private final int mask;
	private final long generation;
	private final long requirements;
	private ByteBuffer records = ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN);

	public PackTerrainJavaMesh(int mask) {
		this(mask, RustMesher.packGeneration(), RustMesher.packRequirements().fingerprint());
	}

	PackTerrainJavaMesh(int mask, long generation, long requirements) {
		this.mask = mask;
		this.generation = generation;
		this.requirements = requirements;
	}

	public long generation() {
		return generation;
	}

	public long requirements() {
		return requirements;
	}

	public int mask() {
		return mask;
	}

	int size() {
		return records.position();
	}

	public ByteBuffer data() {
		ByteBuffer result = records.duplicate();
		result.flip();
		return result;
	}

	void append(ByteBuffer source) {
		ByteBuffer input = source.duplicate();
		ensure(input.remaining());
		records.put(input);
	}

	void append(BakedQuad quad, float x, float y, float z, int stateId) {
		ensure(PackTerrainLayout.RECORD_STRIDE);
		float[] px = new float[4];
		float[] py = new float[4];
		float[] pz = new float[4];
		float[] u = new float[4];
		float[] v = new float[4];
		for (int index = 0; index < 4; index++) {
			var position = quad.position(index);
			px[index] = position.x() + x;
			py[index] = position.y() + y;
			pz[index] = position.z() + z;
			long packed = quad.packedUV(index);
			u[index] = (float) net.minecraft.client.model.geom.builders.UVPair.unpackU(packed);
			v[index] = (float) net.minecraft.client.model.geom.builders.UVPair.unpackV(packed);
		}
		appendRecord(px, py, pz, u, v, x, y, z, stateId);
	}

	void append(float[] px, float[] py, float[] pz, float[] u, float[] v, float x, float y, float z, int stateId) {
		ensure(PackTerrainLayout.RECORD_STRIDE);
		appendRecord(px, py, pz, u, v, x, y, z, stateId);
	}

	private void appendRecord(float[] px, float[] py, float[] pz, float[] u, float[] v,
		float x, float y, float z, int stateId) {
		records.putInt((mask & PackTerrainLayout.Field.BLOCK_ID.bit()) != 0 ? stateId : 0);
		records.putInt((mask & PackTerrainLayout.Field.MID_TEX_COORD.bit()) != 0 ? packMid(u) | (packMid(v) << 16) : 0);
		records.putInt((mask & PackTerrainLayout.Field.NORMAL_TANGENT.bit()) != 0 ? packNormalTangent(px, py, pz, u, v) : 0);
		records.putInt((mask & PackTerrainLayout.Field.MID_BLOCK.bit()) != 0 ? packMidBlock(px, py, pz, x, y, z) : 0);
	}

	private static int packMid(float[] values) {
		float sum = 0.0F;
		for (float value : values) sum += value;
		return Math.round(sum * 8192.0F) & 0xffff;
	}

	private static int packMidBlock(float[] px, float[] py, float[] pz, float x, float y, float z) {
		return (((int) ((x + 0.5F - average(px)) * 64.0F) & 0xff))
			| ((((int) ((y + 0.5F - average(py)) * 64.0F) & 0xff)) << 8)
			| ((((int) ((z + 0.5F - average(pz)) * 64.0F) & 0xff)) << 16);
	}

	private static int packNormalTangent(float[] px, float[] py, float[] pz, float[] u, float[] v) {
		float e1x = px[1] - px[0], e1y = py[1] - py[0], e1z = pz[1] - pz[0];
		float e2x = px[2] - px[0], e2y = py[2] - py[0], e2z = pz[2] - pz[0];
		float nx = e1y * e2z - e1z * e2y;
		float ny = e1z * e2x - e1x * e2z;
		float nz = e1x * e2y - e1y * e2x;
		float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (length <= EPSILON) return 0;
		nx /= length; ny /= length; nz /= length;
		float du1 = u[1] - u[0], dv1 = v[1] - v[0];
		float du2 = u[2] - u[0], dv2 = v[2] - v[0];
		float determinant = du1 * dv2 - du2 * dv1;
		if (Math.abs(determinant) <= EPSILON) return 0;
		float scale = 1.0F / determinant;
		float tx = (dv2 * e1x - dv1 * e2x) * scale;
		float ty = (dv2 * e1y - dv1 * e2y) * scale;
		float tz = (dv2 * e1z - dv1 * e2z) * scale;
		float denominator = Math.abs(nx) + Math.abs(ny) + Math.abs(nz);
		float pxn = nx / denominator, pyn = ny / denominator;
		float encX = nz > 0.0F ? pxn : (1.0F - Math.abs(pyn)) * (pxn >= 0.0F ? 1.0F : -1.0F);
		float encY = nz > 0.0F ? pyn : (1.0F - Math.abs(pxn)) * (pyn >= 0.0F ? 1.0F : -1.0F);
		float tangentLength = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
		if (tangentLength > EPSILON) { tx /= tangentLength; ty /= tangentLength; tz /= tangentLength; }
		float sign = nz >= 0.0F ? 1.0F : -1.0F;
		float a = -1.0F / (sign + nz), b = nx * ny * a;
		float b0x = 1.0F + sign * nx * nx * a, b0y = sign * b, b0z = -sign * nx;
		float b1x = ny * (-sign * nx) - nz * (sign * b);
		float b1y = nz * (1.0F + sign * nx * nx * a) - nx * (-sign * nx);
		float b1z = nx * (sign * b) - ny * (1.0F + sign * nx * nx * a);
		float dot = nx * tx + ny * ty + nz * tz;
		float qx = tx - nx * dot, qy = ty - ny * dot, qz = tz - nz * dot;
		float qLength = (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
		if (qLength <= EPSILON) { qx = b0x; qy = b0y; qz = b0z; }
		else { qx /= qLength; qy /= qLength; qz /= qLength; }
		float tangentX = qx * b0x + qy * b0y + qz * b0z;
		float tangentY = qx * b1x + qy * b1y + qz * b1z;
		float tangentDenominator = Math.max(Math.abs(tangentX) + Math.abs(tangentY), EPSILON);
		float diamondX = tangentX / tangentDenominator;
		float diamondSign = tangentY >= 0.0F ? 1.0F : -1.0F;
		float diamond = -diamondSign * 0.25F * diamondX + 0.5F + diamondSign * 0.25F;
		return ((Math.round(Math.max(0.0F, Math.min(256.0F, diamond * 256.0F))) & 0xff) << 24)
			| ((encode(encX) & 0xfff) << 12) | encode(encY);
	}

	private static int encode(float value) { return Math.round(Math.max(-1.0F, Math.min(1.0F, value)) * 2047.0F) & 0xfff; }
	private static float average(float[] values) { return (values[0] + values[1] + values[2] + values[3]) * 0.25F; }
	private void ensure(int bytes) {
		if (records.remaining() >= bytes) return;
		int required = records.position() + bytes;
		int capacity = records.capacity();
		while (capacity < required) capacity = Math.multiplyExact(capacity, 2);
		ByteBuffer replacement = ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN);
		records.flip();
		replacement.put(records);
		records = replacement;
	}
}
