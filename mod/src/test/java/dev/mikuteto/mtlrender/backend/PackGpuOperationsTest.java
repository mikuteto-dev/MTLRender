package dev.mikuteto.mtlrender.backend;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.ValueLayout;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import org.junit.jupiter.api.Test;

final class PackGpuOperationsTest {
	private static final int COLOR_USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
	private static final int COPY_USAGE = COLOR_USAGE | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;

	@Test
	void vanillaEncodingDoesNotAddPackMipmapOpcode() {
		CommandStream stream = new CommandStream();
		stream.setPipeline(0x0102_0304_0506_0708L);
		stream.bindTexture(2, 3, 0x1112_1314_1516_1718L, 0x2122_2324_2526_2728L, 5);
		stream.vertexBuffer(0, 0x3132_3334_3536_3738L, 64);
		stream.draw(6, 1, 0, 0);
		stream.copyTextureToTexture(41L, 42L, 0, 4, 8, 12, 16, 320, 200);

		int[] expected = {
			3, 0x05060708, 0x01020304,
			5, 2, 3, 0x15161718, 0x11121314, 0x25262728, 0x21222324, 5,
			7, 0, 0x35363738, 0x31323334, 64, 0,
			9, 6, 1, 0, 0,
			16, 41, 0, 42, 0, 0, 4, 8, 12, 16, 320, 200
		};
		assertEquals(expected.length, stream.size(), java.util.Arrays.toString(snapshot(stream)));
		assertArrayEquals(expected, snapshot(stream));
		assertFalse(hasOpcode(stream, 42, 0, 3, 11, 17, 22));
	}

	@Test
	void mipmapThenFullscreenCopyKeepsOpcodeAndPayloadOrder() {
		CommandStream stream = new CommandStream();
		stream.generateMipmaps(0x1122_3344_5566_7788L, 1, 4);
		stream.copyTextureToTexture(0x0102_0304_0506_0708L, 0x1112_1314_1516_1718L, 0,
			0, 0, 0, 0, 1920, 1080);

		int[] expected = {
			42, 0x55667788, 0x11223344, 1, 4,
			16, 0x05060708, 0x01020304, 0x15161718, 0x11121314, 0,
			0, 0, 0, 0, 1920, 1080
		};
		assertEquals(expected.length, stream.size(), java.util.Arrays.toString(snapshot(stream)));
		assertArrayEquals(expected, snapshot(stream));
	}

	@Test
	void invalidMipmapRangesLeaveStreamUnchanged() {
		CommandStream stream = seededStream();
		int[] before = snapshot(stream);

		assertThrows(IllegalArgumentException.class,
			() -> MetalCommandEncoder.validatePackMipmap(false, false, COLOR_USAGE, GpuFormat.RGBA8_UNORM, 4, -1, 2));
		assertThrows(IllegalArgumentException.class,
			() -> MetalCommandEncoder.validatePackMipmap(false, false, COLOR_USAGE, GpuFormat.RGBA8_UNORM, 4, 3, 2));
		assertThrows(IllegalArgumentException.class,
			() -> MetalCommandEncoder.validatePackMipmap(false, false, COLOR_USAGE, GpuFormat.RGBA8_UNORM, 4, 0, 1));
		assertEquals(before.length, stream.size());
		assertArrayEquals(before, snapshot(stream));
	}

	@Test
	void closedPackHandlesLeaveStreamUnchanged() {
		CommandStream stream = seededStream();
		int[] before = snapshot(stream);

		assertThrows(IllegalStateException.class,
			() -> MetalCommandEncoder.validatePackMipmap(true, false, COLOR_USAGE, GpuFormat.RGBA8_UNORM, 4, 0, 2));
		assertThrows(IllegalStateException.class,
			() -> MetalCommandEncoder.validatePackMipmap(false, true, COLOR_USAGE, GpuFormat.RGBA8_UNORM, 4, 0, 2));
		assertThrows(IllegalStateException.class,
			() -> MetalCommandEncoder.validatePackCopy(true, false, COPY_USAGE, COPY_USAGE,
				GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM, 1, 1, 320, 200, 320, 200));
		assertThrows(IllegalStateException.class,
			() -> MetalCommandEncoder.validatePackCopy(false, true, COPY_USAGE, COPY_USAGE,
				GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM, 1, 1, 320, 200, 320, 200));
		assertEquals(before.length, stream.size());
		assertArrayEquals(before, snapshot(stream));
	}

	@Test
	void validBoundariesAcceptMipmapAndCopyInputs() {
		assertDoesNotThrow(() -> MetalCommandEncoder.validatePackMipmap(false, false, COLOR_USAGE,
			GpuFormat.RGBA8_UNORM, 4, 0, 4));
		assertDoesNotThrow(() -> MetalCommandEncoder.validatePackCopy(false, false, COPY_USAGE, COPY_USAGE,
			GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM, 1, 1, 320, 200, 320, 200));
	}

	private static CommandStream seededStream() {
		CommandStream stream = new CommandStream();
		stream.setPipeline(7L);
		stream.copyTextureToTexture(11L, 22L, 0, 0, 0, 0, 0, 320, 200);
		return stream;
	}

	private static int[] snapshot(CommandStream stream) {
		int[] result = new int[Math.toIntExact(stream.size())];
		for (int index = 0; index < result.length; index++) {
			result[index] = word(stream, index);
		}
		return result;
	}

	private static boolean hasOpcode(CommandStream stream, int value, long... offsets) {
		for (long offset : offsets) {
			if (word(stream, offset) == value) {
				return true;
			}
		}
		return false;
	}

	private static int word(CommandStream stream, long index) {
		return stream.words().getAtIndex(ValueLayout.JAVA_INT, index);
	}
}
