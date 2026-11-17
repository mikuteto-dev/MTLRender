package dev.mikuteto.mtlrender.terrain;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import dev.mikuteto.mtlrender.ffi.Mtlr;

final class RustLodMerge {
	private static final int FACE_COUNT = 7;
	private static final int BLOCK_VERTEX_BYTES = 28;
	private static final int QUAD_BYTES = BLOCK_VERTEX_BYTES * 4;
	private static volatile boolean unavailable;

	record Result(MemorySegment output, int[] faces) {
	}

	private RustLodMerge() {
	}

	static Result merge(MemorySegment block, int colorShift, int lightShift, int quads) {
		if (unavailable || block == null || !block.isNative() || quads < 0) {
			return null;
		}
		long inputBytes = block.byteSize();
		if (inputBytes == 0 || inputBytes % QUAD_BYTES != 0 || inputBytes > Integer.MAX_VALUE
			|| quads != inputBytes / QUAD_BYTES) {
			return null;
		}
		ByteBuffer output = ByteBuffer.allocateDirect((int) inputBytes).order(ByteOrder.nativeOrder());
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment faces = arena.allocate((long) FACE_COUNT * Integer.BYTES, Integer.BYTES);
			ByteBuffer input = block.asByteBuffer();
			long written = Mtlr.mtlr_lod_merge(
				MemorySegment.ofBuffer(input).address(),
				inputBytes,
				colorShift,
				lightShift,
				MemorySegment.ofBuffer(output).address(),
				inputBytes,
				faces,
				FACE_COUNT
			);
			if (written == 0 || written > inputBytes || written > Integer.MAX_VALUE) {
				return null;
			}
			output.limit((int) written);
			output.position(0);
			int[] counts = new int[FACE_COUNT];
			for (int index = 0; index < FACE_COUNT; index++) {
				counts[index] = faces.getAtIndex(ValueLayout.JAVA_INT, index);
			}
			return new Result(MemorySegment.ofBuffer(output).asSlice(0, written), counts);
		} catch (LinkageError error) {
			unavailable = true;
			return null;
		}
	}
}
