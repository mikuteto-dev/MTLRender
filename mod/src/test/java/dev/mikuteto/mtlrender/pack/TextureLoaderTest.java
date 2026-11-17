package dev.mikuteto.mtlrender.pack;

import dev.mikuteto.mtlrender.pack.texture.TextureSpec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TextureLoaderTest {
	@Test
	void generatesDeterministicDefaultNoise() throws IOException {
		Path pack = Files.createTempDirectory("mtlrender-texture-pack");
		Path shaders = Files.createDirectories(pack.resolve("shaders"));
		Files.writeString(shaders.resolve("noise.fsh"), "const int noiseTextureResolution = 2;\n");
		try (PackSource source = PackSource.open(pack)) {
			var textures = dev.mikuteto.mtlrender.pack.texture.TextureLoader.load(source, ShaderProperties.empty());
			TextureSpec noise = textures.getFirst();
			assertEquals(TextureSpec.Kind.NOISE, noise.kind());
			assertEquals(2, noise.width());
			assertEquals(2, noise.height());
			assertArrayEquals(new byte[] {
				0x20, (byte) 0xb4, 0x60, (byte) 0xff,
				(byte) 0xd9, 0x51, 0x38, (byte) 0xff,
				(byte) 0x93, (byte) 0xcb, 0x7a, (byte) 0xff,
				0x39, 0x70, (byte) 0xbe, (byte) 0xff
			}, noise.pixels());
		}
	}
}
