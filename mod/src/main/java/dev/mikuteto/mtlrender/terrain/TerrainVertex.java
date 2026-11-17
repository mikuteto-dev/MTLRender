package dev.mikuteto.mtlrender.terrain;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;

enum TerrainVertex {
	BLOCK(DefaultVertexFormat.BLOCK.getVertexSize()),
	COMPACT(20);

	static final int POSITION_BITS = 20;
	static final int POSITION_FRACTION_BITS = 15;
	static final int POSITION_BIAS = 8;

	private static final int COLOR = 12;
	private static final int UV0 = 16;
	private static final int UV2 = 24;
	private static final int UV_BITS = 30;
	private static final int OPAQUE = 0xFF;
	private static final int LIGHT_MASK = 0xFF;
	private static final double POSITION_SCALE = 1 << POSITION_FRACTION_BITS;
	private static final double POSITION_LIMIT = (1 << POSITION_BITS) - 0.5;
	private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED;
	private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED;
	private static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED;

	final int size;
	final int words;

	TerrainVertex(int size) {
		this.size = size;
		this.words = size / Integer.BYTES;
	}

	static TerrainVertex select(MemorySegment source, boolean compact) {
		return compact && fitsCompact(source) ? COMPACT : BLOCK;
	}

	long bytes(long sourceBytes) {
		return sourceBytes * size / BLOCK.size;
	}

	void copy(MemorySegment source, long sourceOffset, MemorySegment target, long targetOffset, long sourceBytes) {
		if (this == BLOCK) {
			MemorySegment.copy(source, sourceOffset, target, targetOffset, sourceBytes);
			return;
		}
		long vertices = sourceBytes / BLOCK.size;
		for (long vertex = 0; vertex < vertices; vertex++) {
			writeCompact(source, sourceOffset + vertex * BLOCK.size, target, targetOffset + vertex * COMPACT.size);
		}
	}

	private static boolean fitsCompact(MemorySegment source) {
		if (source.byteSize() % BLOCK.size != 0) {
			return false;
		}
		for (long offset = 0; offset < source.byteSize(); offset += BLOCK.size) {
			if (!positionFits(source.get(FLOAT, offset))
				|| !positionFits(source.get(FLOAT, offset + Float.BYTES))
				|| !positionFits(source.get(FLOAT, offset + 2 * Float.BYTES))
				|| source.get(INT, offset + COLOR) >>> 24 != OPAQUE
				|| source.get(INT, offset + UV0) >>> UV_BITS != 0
				|| source.get(INT, offset + UV0 + Float.BYTES) >>> UV_BITS != 0
				|| (source.get(SHORT, offset + UV2) & ~LIGHT_MASK) != 0
				|| (source.get(SHORT, offset + UV2 + Short.BYTES) & ~LIGHT_MASK) != 0) {
				return false;
			}
		}
		return true;
	}

	private static boolean positionFits(float value) {
		double scaled = (value + (double) POSITION_BIAS) * POSITION_SCALE;
		return scaled >= 0.0 && scaled < POSITION_LIMIT;
	}

	private static int position(float value) {
		return (int) Math.round((value + (double) POSITION_BIAS) * POSITION_SCALE);
	}

	private static void writeCompact(MemorySegment source, long from, MemorySegment target, long to) {
		int color = source.get(INT, from + COLOR);
		int light = source.get(INT, from + UV2);
		int lightX = light & LIGHT_MASK;
		int lightY = light >>> 16 & LIGHT_MASK;
		target.set(INT, to, position(source.get(FLOAT, from)) | (color & 0xFF) << 20 | (lightY & 0xF) << 28);
		target.set(INT, to + 4, position(source.get(FLOAT, from + 4)) | (color >>> 8 & 0xFF) << 20 | (lightY >>> 4) << 28);
		target.set(INT, to + 8, position(source.get(FLOAT, from + 8)) | (color >>> 16 & 0xFF) << 20 | (lightX & 0xF) << 28);
		target.set(INT, to + 12, source.get(INT, from + UV0) | (lightX >>> 4 & 0x3) << 30);
		target.set(INT, to + 16, source.get(INT, from + UV0 + 4) | (lightX >>> 6) << 30);
	}
}
