package dev.mikuteto.mtlrender.backend;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.foreign.ValueLayout;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import org.junit.jupiter.api.Test;

final class PackOperationValidationTest {
	private static final int COLOR_USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
	private static final int COPY_USAGE = COLOR_USAGE | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;

	@Test
	void mipmapValidationRequiresARealViewRange() {
		assertDoesNotThrow(() -> MetalCommandEncoder.validatePackMipmap(false, false, COLOR_USAGE, GpuFormat.RGBA8_UNORM, 4, 0, 2));
		assertThrows(IllegalArgumentException.class,
			() -> MetalCommandEncoder.validatePackMipmap(false, false, COLOR_USAGE, GpuFormat.RGBA8_UNORM, 4, -1, 2));
		assertThrows(IllegalArgumentException.class,
			() -> MetalCommandEncoder.validatePackMipmap(false, false, COLOR_USAGE, GpuFormat.RGBA8_UNORM, 4, 3, 2));
		assertThrows(IllegalArgumentException.class,
			() -> MetalCommandEncoder.validatePackMipmap(false, false, COLOR_USAGE, GpuFormat.RGBA8_UINT, 4, 0, 2));
		assertThrows(IllegalStateException.class,
			() -> MetalCommandEncoder.validatePackMipmap(true, false, COLOR_USAGE, GpuFormat.RGBA8_UNORM, 4, 0, 2));
	}

	@Test
	void commandsKeepMipmapBeforeLaterCopy() {
		CommandStream stream = new CommandStream();
		stream.generateMipmaps(11L, 0, 3);
		stream.copyTextureToTexture(11L, 22L, 0, 0, 0, 0, 0, 320, 200);
		assertEquals(42, stream.words().getAtIndex(ValueLayout.JAVA_INT, 0));
		assertEquals(16, stream.words().getAtIndex(ValueLayout.JAVA_INT, 5));
	}

	@Test
	void copyValidationRequiresMatchingOpenColorTargets() {
		assertDoesNotThrow(() -> MetalCommandEncoder.validatePackCopy(false, false, COPY_USAGE, COPY_USAGE,
			GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM, 1, 1, 320, 200, 320, 200));
		assertThrows(IllegalArgumentException.class,
			() -> MetalCommandEncoder.validatePackCopy(false, false, COPY_USAGE, COPY_USAGE,
				GpuFormat.RGBA8_UNORM, GpuFormat.RGBA16_FLOAT, 1, 1, 320, 200, 320, 200));
		assertThrows(IllegalArgumentException.class,
			() -> MetalCommandEncoder.validatePackCopy(false, false, COPY_USAGE, COPY_USAGE,
				GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM, 1, 1, 320, 200, 319, 200));
		assertThrows(IllegalStateException.class,
			() -> MetalCommandEncoder.validatePackCopy(true, false, COPY_USAGE, COPY_USAGE,
				GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM, 1, 1, 320, 200, 320, 200));
	}
}
