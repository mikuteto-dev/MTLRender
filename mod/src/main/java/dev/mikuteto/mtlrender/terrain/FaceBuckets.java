package dev.mikuteto.mtlrender.terrain;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

import net.minecraft.world.phys.Vec3;

final class FaceBuckets {
	static final int COUNT = TerrainPreparation.FACE_COUNT;
	static final int QUAD_SIZE = TerrainVertex.BLOCK.size * 4;

	private static final int VERTEX_SIZE = TerrainVertex.BLOCK.size;
	private static final int AXES = 3;
	static final int UNASSIGNED = AXES;
	private static final float SECTION_SIZE = 16.0F;
	private static final double CAMERA_MARGIN = 1.0;

	private FaceBuckets() {
	}

	static int[] copySorted(MemorySegment source, MemorySegment target, TerrainVertex format) {
		int quads = Math.toIntExact(source.byteSize() / QUAD_SIZE);
		long targetQuadSize = format.bytes(QUAD_SIZE);
		byte[] buckets = new byte[quads];
		int[] counts = new int[COUNT];
		for (int quad = 0; quad < quads; quad++) {
			int bucket = classify(source, (long) quad * QUAD_SIZE);
			buckets[quad] = (byte) bucket;
			counts[bucket]++;
		}
		long[] offsets = new long[COUNT];
		for (int bucket = 1; bucket < COUNT; bucket++) {
			offsets[bucket] = offsets[bucket - 1] + counts[bucket - 1] * targetQuadSize;
		}
		for (int quad = 0; quad < quads; quad++) {
			int bucket = buckets[quad];
			format.copy(source, (long) quad * QUAD_SIZE, target, offsets[bucket], QUAD_SIZE);
			offsets[bucket] += targetQuadSize;
		}
		return counts;
	}

	static int visibleMask(Vec3 camera, int originX, int originY, int originZ) {
		return visibleMask(camera.x, camera.y, camera.z, originX, originY, originZ);
	}

	static int visibleMask(double cameraX, double cameraY, double cameraZ, int originX, int originY, int originZ) {
		return 1 << UNASSIGNED
			| axisMask(cameraX, originX, 0)
			| axisMask(cameraY, originY, 1)
			| axisMask(cameraZ, originZ, 2);
	}

	private static int axisMask(double camera, int origin, int axis) {
		int mask = 0;
		if (camera > origin - CAMERA_MARGIN) {
			mask |= 1 << positive(axis);
		}
		if (camera < origin + SECTION_SIZE + CAMERA_MARGIN) {
			mask |= 1 << negative(axis);
		}
		return mask;
	}

	private static int positive(int axis) {
		return axis;
	}

	private static int negative(int axis) {
		return COUNT - 1 - axis;
	}

	static int classify(MemorySegment data, long quad) {
		for (int axis = 0; axis < AXES; axis++) {
			float plane = coordinate(data, quad, 0, axis);
			if (plane >= 0.0F && plane <= SECTION_SIZE
				&& coordinate(data, quad, 1, axis) == plane
				&& coordinate(data, quad, 2, axis) == plane
				&& coordinate(data, quad, 3, axis) == plane) {
				return orientation(data, quad, axis);
			}
		}
		return UNASSIGNED;
	}

	static int classify(ByteBuffer data, int quad) {
		for (int axis = 0; axis < AXES; axis++) {
			float plane = coordinate(data, quad, 0, axis);
			if (plane >= 0.0F && plane <= SECTION_SIZE
				&& coordinate(data, quad, 1, axis) == plane
				&& coordinate(data, quad, 2, axis) == plane
				&& coordinate(data, quad, 3, axis) == plane) {
				return orientation(data, quad, axis);
			}
		}
		return UNASSIGNED;
	}

	private static int orientation(MemorySegment data, long quad, int axis) {
		int u = (axis + 1) % AXES;
		int v = (axis + 2) % AXES;
		double first = normal(data, quad, 0, 1, 2, u, v);
		double second = normal(data, quad, 2, 3, 0, u, v);
		if (first > 0.0 && second > 0.0) {
			return positive(axis);
		}
		if (first < 0.0 && second < 0.0) {
			return negative(axis);
		}
		return UNASSIGNED;
	}

	private static double normal(MemorySegment data, long quad, int a, int b, int c, int u, int v) {
		double au = coordinate(data, quad, a, u);
		double av = coordinate(data, quad, a, v);
		return (coordinate(data, quad, b, u) - au) * (coordinate(data, quad, c, v) - av)
			- (coordinate(data, quad, b, v) - av) * (coordinate(data, quad, c, u) - au);
	}

	private static int orientation(ByteBuffer data, int quad, int axis) {
		int u = (axis + 1) % AXES;
		int v = (axis + 2) % AXES;
		double first = normal(data, quad, 0, 1, 2, u, v);
		double second = normal(data, quad, 2, 3, 0, u, v);
		if (first > 0.0 && second > 0.0) {
			return positive(axis);
		}
		if (first < 0.0 && second < 0.0) {
			return negative(axis);
		}
		return UNASSIGNED;
	}

	private static double normal(ByteBuffer data, int quad, int a, int b, int c, int u, int v) {
		double au = coordinate(data, quad, a, u);
		double av = coordinate(data, quad, a, v);
		return (coordinate(data, quad, b, u) - au) * (coordinate(data, quad, c, v) - av)
			- (coordinate(data, quad, b, v) - av) * (coordinate(data, quad, c, u) - au);
	}

	private static float coordinate(MemorySegment data, long quad, int vertex, int axis) {
		return data.get(ValueLayout.JAVA_FLOAT_UNALIGNED, quad + (long) vertex * VERTEX_SIZE + (long) axis * Float.BYTES);
	}

	private static float coordinate(ByteBuffer data, int quad, int vertex, int axis) {
		return data.getFloat(quad + vertex * VERTEX_SIZE + axis * Float.BYTES);
	}
}
