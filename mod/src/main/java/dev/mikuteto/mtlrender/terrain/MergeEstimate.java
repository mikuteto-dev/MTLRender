package dev.mikuteto.mtlrender.terrain;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashMap;
import java.util.Map;

final class MergeEstimate {
	static final boolean ENABLED = Boolean.getBoolean("mtlrender.mergeEstimate");
	private static final int AXES = 3;
	private static final int CELLS = 16;
	private static final int VERTEX_SIZE = TerrainVertex.BLOCK.size;
	private static final int COLOR = 12;
	private static final int UV0 = 16;
	private static final int UV2 = 24;
	private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED;
	private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED;

	private record Key(int bucket, int axis, int color, int light, int uMin, int vMin, int uMax, int vMax, int corner, int winding) {
	}

	private MergeEstimate() {
	}

	static int[] estimate(MemorySegment source) {
		int quads = Math.toIntExact(source.byteSize() / FaceBuckets.QUAD_SIZE);
		int[] counts = new int[FaceBuckets.COUNT];
		Map<Key, long[]> cells = new HashMap<>();
		for (int quad = 0; quad < quads; quad++) {
			long base = (long) quad * FaceBuckets.QUAD_SIZE;
			int bucket = FaceBuckets.classify(source, base);
			if (bucket == FaceBuckets.UNASSIGNED) {
				counts[bucket]++;
				continue;
			}
			int axis = axis(bucket);
			Key key = key(source, base, bucket, axis);
			if (key == null) {
				counts[bucket]++;
				continue;
			}
			long[] rows = cells.computeIfAbsent(key, ignored -> new long[CELLS]);
			int u = (int) minimum(source, base, (axis + 1) % AXES);
			int v = (int) minimum(source, base, (axis + 2) % AXES);
			rows[u] |= 1L << v;
		}
		for (Map.Entry<Key, long[]> entry : cells.entrySet()) {
			counts[entry.getKey().bucket()] += rectangles(entry.getValue());
		}
		return counts;
	}

	private static Key key(MemorySegment source, long base, int bucket, int axis) {
		int uAxis = (axis + 1) % AXES;
		int vAxis = (axis + 2) % AXES;
		int color = colorAt(source, base, 0);
		int light = lightAt(source, base, 0);
		float uMin = position(source, base, 0, uAxis);
		float uMax = uMin;
		float vMin = position(source, base, 0, vAxis);
		float vMax = vMin;
		for (int vertex = 1; vertex < 4; vertex++) {
			if (colorAt(source, base, vertex) != color || lightAt(source, base, vertex) != light) {
				return null;
			}
			float u = position(source, base, vertex, uAxis);
			float v = position(source, base, vertex, vAxis);
			uMin = Math.min(uMin, u);
			uMax = Math.max(uMax, u);
			vMin = Math.min(vMin, v);
			vMax = Math.max(vMax, v);
		}
		if (!square(source, base, uAxis, uMin, uMax) || !square(source, base, vAxis, vMin, vMax)) {
			return null;
		}
		if (uMin < 0.0F || uMin >= CELLS || vMin < 0.0F || vMin >= CELLS) {
			return null;
		}
		float uvMinU = uvAt(source, base, 0, true);
		float uvMaxU = uvMinU;
		float uvMinV = uvAt(source, base, 0, false);
		float uvMaxV = uvMinV;
		for (int vertex = 1; vertex < 4; vertex++) {
			float u = uvAt(source, base, vertex, true);
			float v = uvAt(source, base, vertex, false);
			uvMinU = Math.min(uvMinU, u);
			uvMaxU = Math.max(uvMaxU, u);
			uvMinV = Math.min(uvMinV, v);
			uvMaxV = Math.max(uvMaxV, v);
		}
		if (uvMinU == uvMaxU || uvMinV == uvMaxV) {
			return null;
		}
		for (int vertex = 0; vertex < 4; vertex++) {
			float u = uvAt(source, base, vertex, true);
			float v = uvAt(source, base, vertex, false);
			if ((u != uvMinU && u != uvMaxU) || (v != uvMinV && v != uvMaxV)) {
				return null;
			}
		}
		int best = 0;
		float bestU = position(source, base, 0, uAxis);
		float bestV = position(source, base, 0, vAxis);
		for (int vertex = 1; vertex < 4; vertex++) {
			float u = position(source, base, vertex, uAxis);
			float v = position(source, base, vertex, vAxis);
			if (u < bestU || u == bestU && v < bestV) {
				best = vertex;
				bestU = u;
				bestV = v;
			}
		}
		int corner = (uvAt(source, base, best, true) == uvMaxU ? 1 : 0) | (uvAt(source, base, best, false) == uvMaxV ? 2 : 0);
		double area = 0.0;
		for (int vertex = 0; vertex < 4; vertex++) {
			int next = (vertex + 1) & 3;
			area += (double) uvAt(source, base, vertex, true) * uvAt(source, base, next, false)
				- (double) uvAt(source, base, next, true) * uvAt(source, base, vertex, false);
		}
		int winding = area > 0.0 ? 1 : 0;
		return new Key(
			bucket,
			Float.floatToIntBits(position(source, base, 0, axis)),
			color,
			light,
			Float.floatToIntBits(uvMinU),
			Float.floatToIntBits(uvMinV),
			Float.floatToIntBits(uvMaxU),
			Float.floatToIntBits(uvMaxV),
			corner,
			winding
		);
	}

	private static boolean square(MemorySegment source, long base, int axis, float min, float max) {
		if (min == max || max - min != 1.0F || !integer(min) || !integer(max)) {
			return false;
		}
		for (int vertex = 0; vertex < 4; vertex++) {
			float value = position(source, base, vertex, axis);
			if (value != min && value != max) {
				return false;
			}
		}
		return true;
	}

	private static boolean integer(float value) {
		return value == Math.rint(value);
	}

	private static int rectangles(long[] rows) {
		boolean[] used = new boolean[CELLS * CELLS];
		int rectangles = 0;
		for (int row = 0; row < CELLS; row++) {
			for (int column = 0; column < CELLS; column++) {
				if (used[row * CELLS + column] || (rows[row] & (1L << column)) == 0) {
					continue;
				}
				int right = column;
				while (right + 1 < CELLS && (rows[row] & (1L << (right + 1))) != 0 && !used[row * CELLS + right + 1]) {
					right++;
				}
				int bottom = row;
				while (bottom + 1 < CELLS && available(rows, used, bottom + 1, column, right)) {
					bottom++;
				}
				for (int r = row; r <= bottom; r++) {
					for (int c = column; c <= right; c++) {
						used[r * CELLS + c] = true;
					}
				}
				rectangles++;
			}
		}
		return rectangles;
	}

	private static boolean available(long[] rows, boolean[] used, int row, int from, int to) {
		for (int column = from; column <= to; column++) {
			if ((rows[row] & (1L << column)) == 0 || used[row * CELLS + column]) {
				return false;
			}
		}
		return true;
	}

	private static int axis(int bucket) {
		return bucket < AXES ? bucket : FaceBuckets.COUNT - 1 - bucket;
	}

	private static float minimum(MemorySegment source, long base, int axis) {
		float min = position(source, base, 0, axis);
		for (int vertex = 1; vertex < 4; vertex++) {
			min = Math.min(min, position(source, base, vertex, axis));
		}
		return min;
	}

	private static float position(MemorySegment source, long base, int vertex, int axis) {
		return source.get(FLOAT, base + (long) vertex * VERTEX_SIZE + (long) axis * Float.BYTES);
	}

	private static int colorAt(MemorySegment source, long base, int vertex) {
		return source.get(INT, base + (long) vertex * VERTEX_SIZE + COLOR);
	}

	private static int lightAt(MemorySegment source, long base, int vertex) {
		return source.get(INT, base + (long) vertex * VERTEX_SIZE + UV2);
	}

	private static float uvAt(MemorySegment source, long base, int vertex, boolean u) {
		return source.get(FLOAT, base + (long) vertex * VERTEX_SIZE + UV0 + (u ? 0 : Float.BYTES));
	}
}
