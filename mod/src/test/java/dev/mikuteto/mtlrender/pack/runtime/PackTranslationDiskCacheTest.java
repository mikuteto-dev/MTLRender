package dev.mikuteto.mtlrender.pack.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackTranslationDiskCacheTest {
	@TempDir
	Path temp;

	@Test
	void directoryAndZipDigestIgnoreEntryOrderAndMetadata() throws Exception {
		Path directory = temp.resolve("pack");
		Files.createDirectories(directory.resolve("shaders/include"));
		Files.writeString(directory.resolve("shaders/main.vsh"), "vertex");
		Files.writeString(directory.resolve("shaders/include/common.glsl"), "common");
		Files.writeString(directory.resolve("shaders/shaders.properties"), "foo=bar");
		Path first = zip(temp.resolve("first.zip"), List.of("shaders/main.vsh", "shaders/include/common.glsl", "shaders/shaders.properties"));
		Path second = zip(temp.resolve("second.zip"), List.of("shaders/shaders.properties", "shaders/include/common.glsl", "shaders/main.vsh"));
		assertArrayEquals(PackTranslationDiskCache.packDigest(directory), PackTranslationDiskCache.packDigest(first));
		assertArrayEquals(PackTranslationDiskCache.packDigest(first), PackTranslationDiskCache.packDigest(second));
	}

	@Test
	void hitPreservesArtifactAndMissesCorruptEntry() throws Exception {
		PackTranslationDiskCache cache = new PackTranslationDiskCache(temp.resolve("cache"));
		PackCacheKey key = key(1, 1, 1, "mod");
		PackTranslationArtifact artifact = artifact("vertex msl", "fragment msl");
		assertTrue(cache.read(key).isEmpty());
		cache.write(key, artifact);
		assertEquals(artifact, cache.read(key).orElseThrow());
		Files.write(cache.path(key), new byte[] {1, 2, 3});
		assertTrue(cache.read(key).isEmpty());
	}

	@Test
	void replacementIsAtomicFromReaders() throws Exception {
		PackTranslationDiskCache cache = new PackTranslationDiskCache(temp.resolve("cache"));
		PackCacheKey key = key(1, 1, 1, "mod");
		PackTranslationArtifact oldArtifact = artifact("old vertex", "old fragment");
		PackTranslationArtifact newArtifact = artifact("new vertex", "new fragment");
		cache.write(key, oldArtifact);
		Thread writer = Thread.startVirtualThread(() -> {
			try {
				for (int i = 0; i < 50; i++) cache.write(key, newArtifact);
			} catch (IOException exception) {
				throw new RuntimeException(exception);
			}
		});
		while (writer.isAlive()) {
			PackTranslationArtifact value = cache.read(key).orElse(null);
			assertNotNull(value);
			assertTrue(value.equals(oldArtifact) || value.equals(newArtifact));
		}
		writer.join();
		assertEquals(newArtifact, cache.read(key).orElseThrow());
	}

	@Test
	void pathCannotEscapeRoot() {
		PackTranslationDiskCache cache = new PackTranslationDiskCache(temp.resolve("cache"));
		assertTrue(cache.path(key(1, 1, 1, "mod")).startsWith(cache.root()));
		assertFalse(cache.path(key(1, 1, 1, "mod")).getFileName().toString().contains("/"));
	}

	private static PackCacheKey key(int schema, int translator, int msl, String mod) {
		return new PackCacheKey(schema, translator, msl, new byte[] {1, 2, 3}, Map.of("quality", "high"), Map.of("MC_VERSION", "26.2"), mod, "macOS-27", "Apple");
	}

	private static PackTranslationArtifact artifact(String vertex, String fragment) {
		PackTranslationArtifact.Reflection reflection = new PackTranslationArtifact.Reflection(
			List.of(new PackTranslationArtifact.ResourceBinding("tex", PackTranslationArtifact.Kind.TEXTURE, 2, 3)),
			List.of(new PackTranslationArtifact.VertexInput("Position", 0, "float3", 0, 4)),
			List.of(new PackTranslationArtifact.FragmentOutput("color", 0, 0)),
			new PackTranslationArtifact.DepthState("depth32", "greater", true));
		return new PackTranslationArtifact(vertex, "vertexMain", fragment, "fragmentMain", reflection);
	}

	private static Path zip(Path path, List<String> names) throws IOException {
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
			for (String name : names) {
				output.putNextEntry(new ZipEntry(name));
				String content = name.endsWith("main.vsh") ? "vertex" : name.endsWith("common.glsl") ? "common" : "foo=bar";
				output.write(content.getBytes());
				output.closeEntry();
			}
		}
		return path;
	}
}
