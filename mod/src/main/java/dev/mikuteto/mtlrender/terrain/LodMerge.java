package dev.mikuteto.mtlrender.terrain;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

final class LodMerge {
	private static final int CELLS = 16;
	private static final int AXES = 3;
	private static final int VERTEX_SIZE = TerrainVertex.BLOCK.size;
	private static final int COLOR = 12;
	private static final int UV0 = 16;
	private static final int UV2 = 24;

	private static final int COLOR_SHIFT = shift("mtlrender.lodColorBits", 5);
	private static final int LIGHT_SHIFT = shift("mtlrender.lodLightBits", 6);
	private static final boolean CHECK = Boolean.getBoolean("mtlrender.debug.lodCheck");
	private static final boolean RUST = !"false".equalsIgnoreCase(System.getProperty("mtlrender.lodRust", "true"));

	record Merged(MemorySegment data, int[] faces) {
	}

	private record Key(int axis, int u, int v, int plane, int color, int light, int uvMinU, int uvMinV, int uvMaxU, int uvMaxV) {
	}

	private record Group(long[] rows, int pattern) {
	}

	private LodMerge() {
	}

	static Merged mergeForUpload(MemorySegment source, int quads) {
		if (!RUST) {
			return checked(mergeJava(source.asByteBuffer(), quads));
		}
		RustLodMerge.Result nativeResult = RustLodMerge.merge(source, COLOR_SHIFT, LIGHT_SHIFT, quads);
		if (nativeResult != null) {
			Merged result = new Merged(nativeResult.output(), nativeResult.faces());
			if (CHECK) {
				check(result.data(), result.faces(), result.data().byteSize());
			}
			return result;
		}
		return checked(mergeJava(source.asByteBuffer(), quads));
	}

	private static Merged checked(Merged result) {
		if (CHECK) {
			check(result.data(), result.faces(), result.data().byteSize());
		}
		return result;
	}

	static Merged merge(ByteBuffer source, int quads) {
		return mergeForUpload(MemorySegment.ofBuffer(source.slice().order(ByteOrder.nativeOrder())), quads);
	}

	static Merged mergeJava(ByteBuffer source, int quads) {
		ByteBuffer input = source.slice().order(ByteOrder.nativeOrder());
		byte[] buckets = new byte[quads];
		Key[] keys = new Key[quads];
		int[] patterns = new int[quads];
		int[] counts = new int[FaceBuckets.COUNT];
		for (int quad = 0; quad < quads; quad++) {
			int base = quad * FaceBuckets.QUAD_SIZE;
			int bucket = FaceBuckets.classify(input, base);
			buckets[quad] = (byte) bucket;
			counts[bucket]++;
			keys[quad] = bucket == FaceBuckets.UNASSIGNED ? null : key(input, base, axis(bucket));
			patterns[quad] = keys[quad] == null ? 0 : pattern(input, base, keys[quad]);
		}
		int[] merged = mergedCounts(buckets, keys);
		int[] faces = new int[FaceBuckets.COUNT];
		int[] offsets = new int[FaceBuckets.COUNT];
		int total = 0;
		for (int bucket = 0; bucket < FaceBuckets.COUNT; bucket++) {
			faces[bucket] = merged[bucket] + counts[bucket] - candidates(buckets, keys, bucket);
			offsets[bucket] = total;
			total += faces[bucket] * FaceBuckets.QUAD_SIZE;
		}
		ByteBuffer data = ByteBuffer.allocate(total).order(ByteOrder.nativeOrder());
		emit(input, buckets, keys, patterns, data, offsets);
		return new Merged(MemorySegment.ofBuffer(data).asSlice(0, total), faces);
	}

	private static int[] mergedCounts(byte[] buckets, Key[] keys) {
		int[] merged = new int[FaceBuckets.COUNT];
		for (int bucket = 0; bucket < FaceBuckets.COUNT; bucket++) {
			if (bucket == FaceBuckets.UNASSIGNED) {
				continue;
			}
			for (Group group : cells(buckets, keys, bucket, null).values()) {
				merged[bucket] += rectangles(group.rows());
			}
		}
		return merged;
	}

	private static int candidates(byte[] buckets, Key[] keys, int bucket) {
		int count = 0;
		for (int quad = 0; quad < buckets.length; quad++) {
			if (buckets[quad] == bucket && keys[quad] != null) {
				count++;
			}
		}
		return count;
	}

	private static Map<Key, Group> cells(byte[] buckets, Key[] keys, int bucket, int[] patterns) {
		Map<Key, Group> cells = new HashMap<>();
		for (int quad = 0; quad < buckets.length; quad++) {
			if (buckets[quad] != bucket || keys[quad] == null) {
				continue;
			}
			Key key = keys[quad];
			Group group = cells.get(key);
			if (group == null) {
				group = new Group(new long[CELLS], patterns == null ? 0 : patterns[quad]);
				cells.put(key, group);
			}
			group.rows()[key.u()] |= 1L << key.v();
		}
		return cells;
	}

	private static void emit(ByteBuffer source, byte[] buckets, Key[] keys, int[] patterns, ByteBuffer data, int[] offsets) {
		int[] mergedAt = new int[FaceBuckets.COUNT];
		int[] passAt = new int[FaceBuckets.COUNT];
		for (int bucket = 0; bucket < FaceBuckets.COUNT; bucket++) {
			if (bucket == FaceBuckets.UNASSIGNED) {
				continue;
			}
			for (Map.Entry<Key, Group> entry : cells(buckets, keys, bucket, patterns).entrySet()) {
				mergedAt[bucket] += writeRectangles(data, offsets[bucket] + mergedAt[bucket], entry.getKey(), entry.getValue());
			}
		}
		for (int quad = 0; quad < buckets.length; quad++) {
			if (keys[quad] != null) {
				continue;
			}
			int bucket = buckets[quad];
			int position = offsets[bucket] + mergedAt[bucket] + passAt[bucket] * FaceBuckets.QUAD_SIZE;
			copyQuad(source, quad * FaceBuckets.QUAD_SIZE, data, position);
			passAt[bucket]++;
		}
	}

	private static int writeRectangles(ByteBuffer data, int base, Key key, Group group) {
		boolean[] used = new boolean[CELLS * CELLS];
		int at = base;
		for (int row = 0; row < CELLS; row++) {
			for (int column = 0; column < CELLS; column++) {
				if (used[row * CELLS + column] || (group.rows()[row] & 1L << column) == 0) {
					continue;
				}
				int right = column;
				while (right + 1 < CELLS && (group.rows()[row] & 1L << (right + 1)) != 0 && !used[row * CELLS + right + 1]) {
					right++;
				}
				int bottom = row;
				while (bottom + 1 < CELLS && available(group.rows(), used, bottom + 1, column, right)) {
					bottom++;
				}
				for (int r = row; r <= bottom; r++) {
					for (int c = column; c <= right; c++) {
						used[r * CELLS + c] = true;
					}
				}
				write(data, at, key, group.pattern(), row, bottom, column, right);
				at += FaceBuckets.QUAD_SIZE;
			}
		}
		return at - base;
	}

	private static int rectangles(long[] rows) {
		boolean[] used = new boolean[CELLS * CELLS];
		int count = 0;
		for (int row = 0; row < CELLS; row++) {
			for (int column = 0; column < CELLS; column++) {
				if (used[row * CELLS + column] || (rows[row] & 1L << column) == 0) {
					continue;
				}
				int right = column;
				while (right + 1 < CELLS && (rows[row] & 1L << (right + 1)) != 0 && !used[row * CELLS + right + 1]) {
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
				count++;
			}
		}
		return count;
	}

	private static boolean available(long[] rows, boolean[] used, int row, int from, int to) {
		for (int column = from; column <= to; column++) {
			if ((rows[row] & 1L << column) == 0 || used[row * CELLS + column]) {
				return false;
			}
		}
		return true;
	}

	private static void write(ByteBuffer data, int base, Key key, int pattern, int u0, int u1, int v0, int v1) {
		int axis = key.axis();
		int uAxis = (axis + 1) % AXES;
		int vAxis = (axis + 2) % AXES;
		float uMin = u0;
		float uMax = u1 + 1;
		float vMin = v0;
		float vMax = v1 + 1;
		for (int vertex = 0; vertex < 4; vertex++) {
			int offset = base + vertex * VERTEX_SIZE;
			data.putFloat(offset + axis * Float.BYTES, Float.intBitsToFloat(key.plane()));
			data.putFloat(offset + uAxis * Float.BYTES, (pattern >>> (vertex * 4) & 1) != 0 ? uMax : uMin);
			data.putFloat(offset + vAxis * Float.BYTES, (pattern >>> (vertex * 4 + 1) & 1) != 0 ? vMax : vMin);
			data.putInt(offset + COLOR, key.color());
			data.putFloat(offset + UV0, (pattern >>> (vertex * 4 + 2) & 1) != 0 ? Float.intBitsToFloat(key.uvMaxU()) : Float.intBitsToFloat(key.uvMinU()));
			data.putFloat(offset + UV0 + Float.BYTES, (pattern >>> (vertex * 4 + 3) & 1) != 0 ? Float.intBitsToFloat(key.uvMaxV()) : Float.intBitsToFloat(key.uvMinV()));
			data.putInt(offset + UV2, key.light());
		}
	}

	private static Key key(ByteBuffer source, int base, int axis) {
		int uAxis = (axis + 1) % AXES;
		int vAxis = (axis + 2) % AXES;
		float uMin = position(source, base, 0, uAxis);
		float uMax = uMin;
		float vMin = position(source, base, 0, vAxis);
		float vMax = vMin;
		for (int vertex = 1; vertex < 4; vertex++) {
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
		float uvMinU = Float.MAX_VALUE;
		float uvMaxU = -Float.MAX_VALUE;
		float uvMinV = Float.MAX_VALUE;
		float uvMaxV = -Float.MAX_VALUE;
		for (int vertex = 0; vertex < 4; vertex++) {
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
			float u = position(source, base, vertex, uAxis);
			float v = position(source, base, vertex, vAxis);
			float uu = uvAt(source, base, vertex, true);
			float vv = uvAt(source, base, vertex, false);
			if ((u != uMin && u != uMax) || (v != vMin && v != vMax) || (uu != uvMinU && uu != uvMaxU) || (vv != uvMinV && vv != uvMaxV)) {
				return null;
			}
		}
		return new Key(
			axis,
			(int) uMin,
			(int) vMin,
			Float.floatToIntBits(position(source, base, 0, axis)),
			flattenColor(source, base),
			flattenLight(source, base),
			Float.floatToIntBits(uvMinU),
			Float.floatToIntBits(uvMinV),
			Float.floatToIntBits(uvMaxU),
			Float.floatToIntBits(uvMaxV)
		);
	}

	private static int pattern(ByteBuffer source, int base, Key key) {
		int axis = key.axis();
		int uAxis = (axis + 1) % AXES;
		int vAxis = (axis + 2) % AXES;
		float uMin = key.u();
		float vMin = key.v();
		float uMax = uMin + 1.0F;
		float vMax = vMin + 1.0F;
		float uvMinU = Float.intBitsToFloat(key.uvMinU());
		float uvMaxU = Float.intBitsToFloat(key.uvMaxU());
		float uvMinV = Float.intBitsToFloat(key.uvMinV());
		float uvMaxV = Float.intBitsToFloat(key.uvMaxV());
		int pattern = 0;
		for (int vertex = 0; vertex < 4; vertex++) {
			float u = position(source, base, vertex, uAxis);
			float v = position(source, base, vertex, vAxis);
			float uu = uvAt(source, base, vertex, true);
			float vv = uvAt(source, base, vertex, false);
			int at = vertex * 4;
			pattern |= (u == uMax ? 1 : 0) << at | (v == vMax ? 1 : 0) << (at + 1)
				| (uu == uvMaxU ? 1 : 0) << (at + 2) | (vv == uvMaxV ? 1 : 0) << (at + 3);
		}
		return pattern;
	}

	private static boolean square(ByteBuffer source, int base, int axis, float min, float max) {
		if (min == max || max - min != 1.0F || min != Math.rint(min) || max != Math.rint(max)) {
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

	private static int flattenColor(ByteBuffer source, int base) {
		int r = 0;
		int g = 0;
		int b = 0;
		int a = 0;
		for (int vertex = 0; vertex < 4; vertex++) {
			int color = source.getInt(base + vertex * VERTEX_SIZE + COLOR);
			r += color & 0xFF;
			g += color >>> 8 & 0xFF;
			b += color >>> 16 & 0xFF;
			a += color >>> 24 & 0xFF;
		}
		return quantize((r + 2) / 4, COLOR_SHIFT) | quantize((g + 2) / 4, COLOR_SHIFT) << 8
			| quantize((b + 2) / 4, COLOR_SHIFT) << 16 | quantize((a + 2) / 4, COLOR_SHIFT) << 24;
	}

	private static int flattenLight(ByteBuffer source, int base) {
		int x = 0;
		int y = 0;
		int reserved = 0;
		for (int vertex = 0; vertex < 4; vertex++) {
			int light = source.getInt(base + vertex * VERTEX_SIZE + UV2);
			x += light & 0xFF;
			y += light >>> 16 & 0xFF;
			reserved |= light & 0xFF00FF00;
		}
		return reserved | quantize((x + 2) / 4, LIGHT_SHIFT) | quantize((y + 2) / 4, LIGHT_SHIFT) << 16;
	}

	private static int quantize(int value, int shift) {
		if (shift == 0) {
			return value;
		}
		int half = 1 << (shift - 1);
		return Math.min(255, (value + half >> shift) << shift);
	}

	private static int shift(String property, int bits) {
		return Math.max(0, 8 - Math.max(1, Math.min(8, Integer.getInteger(property, bits))));
	}

	private static int axis(int bucket) {
		return bucket < AXES ? bucket : FaceBuckets.COUNT - 1 - bucket;
	}

	private static float position(ByteBuffer source, int base, int vertex, int axis) {
		return source.getFloat(base + vertex * VERTEX_SIZE + axis * Float.BYTES);
	}

	private static float uvAt(ByteBuffer source, int base, int vertex, boolean u) {
		return source.getFloat(base + vertex * VERTEX_SIZE + UV0 + (u ? 0 : Float.BYTES));
	}

	private static void copyQuad(ByteBuffer source, int from, ByteBuffer target, int to) {
		ByteBuffer input = source.duplicate();
		input.position(from);
		input.limit(from + FaceBuckets.QUAD_SIZE);
		ByteBuffer output = target.duplicate();
		output.position(to);
		output.put(input);
	}

	private static void check(MemorySegment data, int[] faces, long total) {
		long offset = 0;
		for (int bucket = 0; bucket < FaceBuckets.COUNT; bucket++) {
			for (int i = 0; i < faces[bucket]; i++) {
				if (FaceBuckets.classify(data, offset) != bucket) {
					throw new IllegalStateException("lod bucket mismatch at " + offset);
				}
				offset += FaceBuckets.QUAD_SIZE;
			}
		}
		if (offset != total) {
			throw new IllegalStateException("lod size mismatch " + offset + " != " + total);
		}
	}
}
