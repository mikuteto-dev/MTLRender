package dev.mikuteto.mtlrender.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncludesTest {
	@TempDir
	Path directory;

	@Test
	void expandsIncludesInPlace() throws IOException {
		Path shaders = Files.createDirectories(directory.resolve("shaders"));
		Files.writeString(shaders.resolve("a.glsl"), "#include \"b.glsl\"\nA");
		Files.writeString(shaders.resolve("b.glsl"), "B");

		try (PackSource source = PackSource.open(directory)) {
			Includes includes = Includes.build(source, List.of("/a.glsl"));
			ExpandedSource expanded = includes.expand("/a.glsl");

			assertEquals("B\nA\n", expanded.text());
			assertEquals(List.of("B", "A"), expanded.lines());
			assertEquals("/b.glsl", expanded.lineMap().get(0).file());
			assertEquals(1, expanded.lineMap().get(0).line());
			assertEquals("/a.glsl", expanded.lineMap().get(1).file());
			assertEquals(2, expanded.lineMap().get(1).line());
		}
	}

	@Test
	void rejectsMissingInclude() throws IOException {
		Path shaders = Files.createDirectories(directory.resolve("shaders"));
		Files.writeString(shaders.resolve("a.glsl"), "#include \"missing.glsl\"");

		try (PackSource source = PackSource.open(directory)) {
			assertThrows(IncludeException.class, () -> Includes.build(source, List.of("/a.glsl")));
		}
	}

	@Test
	void rejectsIncludeCycle() throws IOException {
		Path shaders = Files.createDirectories(directory.resolve("shaders"));
		Files.writeString(shaders.resolve("a.glsl"), "#include \"b.glsl\"");
		Files.writeString(shaders.resolve("b.glsl"), "#include \"a.glsl\"");

		try (PackSource source = PackSource.open(directory)) {
			assertThrows(IncludeException.class, () -> Includes.build(source, List.of("/a.glsl")));
		}
	}

	@Test
	void resolvesRelativeIncludes() throws IOException {
		Path shaders = Files.createDirectories(directory.resolve("shaders"));
		Path lib = Files.createDirectories(shaders.resolve("lib"));
		Files.writeString(shaders.resolve("a.glsl"), "#include \"lib/b.glsl\"");
		Files.writeString(lib.resolve("b.glsl"), "#include \"c.glsl\"");
		Files.writeString(lib.resolve("c.glsl"), "C");

		try (PackSource source = PackSource.open(directory)) {
			Includes includes = Includes.build(source, List.of("/a.glsl"));
			assertEquals("C\n", includes.expand("/a.glsl").text());
		}
	}
}
