package dev.mikuteto.mtlrender.terrain;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.core.BlockPos;

final class TranslucentSort {
	private static final int BLOCK_STRIDE = 28;
	private static final int COMPACT_STRIDE = 20;
	private static final int POSITION_MASK = (1 << TerrainVertex.POSITION_BITS) - 1;
	private static final float POSITION_SCALE = 1.0F / (1 << TerrainVertex.POSITION_FRACTION_BITS);
	private static final float POSITION_BIAS = TerrainVertex.POSITION_BIAS;

	private TranslucentSort() {
	}

	static MeshData.SortState identity(MeshData mesh, ByteBufferBuilder target, VertexSorting ignored) {
		if (mesh.drawState().primitiveTopology() != PrimitiveTopology.QUADS) {
			return null;
		}
		return mesh.sortQuads(target, values -> {
			int[] order = new int[values.size()];
			for (int i = 0; i < order.length; i++) {
				order[i] = i;
			}
			return order;
		});
	}

	static int[] order(DirectUberBuffer.Entry vertices, int quads, float cameraX, float cameraY, float cameraZ, BlockPos origin) {
		float[] keys = new float[quads];
		float[] first = new float[3];
		float[] third = new float[3];
		for (int quad = 0; quad < quads; quad++) {
			position(vertices, quad * 4, first);
			position(vertices, quad * 4 + 2, third);
			float x = (first[0] + third[0]) * 0.5F + origin.getX() - cameraX;
			float y = (first[1] + third[1]) * 0.5F + origin.getY() - cameraY;
			float z = (first[2] + third[2]) * 0.5F + origin.getZ() - cameraZ;
			keys[quad] = x * x + y * y + z * z;
		}
		int[] order = new int[quads];
		int[] scratch = new int[quads];
		for (int i = 0; i < quads; i++) {
			order[i] = i;
		}
		for (int width = 1; width < quads; width *= 2) {
			for (int start = 0; start < quads; start += width * 2) {
				int middle = Math.min(start + width, quads);
				int end = Math.min(start + width * 2, quads);
				int left = start;
				int right = middle;
				int out = start;
				while (left < middle && right < end) {
					int leftIndex = order[left];
					int rightIndex = order[right];
					if (Float.compare(keys[leftIndex], keys[rightIndex]) >= 0) {
						scratch[out++] = leftIndex;
						left++;
					} else {
						scratch[out++] = rightIndex;
						right++;
					}
				}
				while (left < middle) {
					scratch[out++] = order[left++];
				}
				while (right < end) {
					scratch[out++] = order[right++];
				}
			}
			int[] swap = order;
			order = scratch;
			scratch = swap;
		}
		return order;
	}

	static void write(DirectUberBuffer.Entry indices, int indexType, int[] order) {
		MemorySegment target = MemorySegment.ofAddress(indices.address()).reinterpret(indices.allocation().getSize());
		for (int output = 0; output < order.length; output++) {
			int base = order[output] * 4;
			writeIndex(target, (long) (output * 6) * indexType, base, indexType);
			writeIndex(target, (long) (output * 6 + 1) * indexType, base + 1, indexType);
			writeIndex(target, (long) (output * 6 + 2) * indexType, base + 2, indexType);
			writeIndex(target, (long) (output * 6 + 3) * indexType, base + 2, indexType);
			writeIndex(target, (long) (output * 6 + 4) * indexType, base + 3, indexType);
			writeIndex(target, (long) (output * 6 + 5) * indexType, base, indexType);
		}
	}

	private static void writeIndex(MemorySegment target, long offset, int value, int indexType) {
		if (indexType == IndexType.SHORT.bytes) {
			target.set(ValueLayout.JAVA_SHORT_UNALIGNED, offset, (short) value);
		} else {
			target.set(ValueLayout.JAVA_INT_UNALIGNED, offset, value);
		}
	}

	private static void position(DirectUberBuffer.Entry entry, int vertex, float[] output) {
		MemorySegment data = MemorySegment.ofAddress(entry.address()).reinterpret(entry.allocation().getSize());
		long offset = (long) vertex * entry.format().size;
		if (entry.format() == TerrainVertex.BLOCK) {
			output[0] = data.get(ValueLayout.JAVA_FLOAT_UNALIGNED, offset);
			output[1] = data.get(ValueLayout.JAVA_FLOAT_UNALIGNED, offset + 4);
			output[2] = data.get(ValueLayout.JAVA_FLOAT_UNALIGNED, offset + 8);
			return;
		}
		int word0 = data.get(ValueLayout.JAVA_INT_UNALIGNED, offset);
		int word1 = data.get(ValueLayout.JAVA_INT_UNALIGNED, offset + 4);
		int word2 = data.get(ValueLayout.JAVA_INT_UNALIGNED, offset + 8);
		output[0] = (word0 & POSITION_MASK) * POSITION_SCALE - POSITION_BIAS;
		output[1] = (word1 & POSITION_MASK) * POSITION_SCALE - POSITION_BIAS;
		output[2] = (word2 & POSITION_MASK) * POSITION_SCALE - POSITION_BIAS;
	}
}
