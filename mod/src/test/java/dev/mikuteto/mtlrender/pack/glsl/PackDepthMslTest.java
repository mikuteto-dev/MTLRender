package dev.mikuteto.mtlrender.pack.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikuteto.mtlrender.pack.compiler.CompiledShader;
import dev.mikuteto.mtlrender.pack.compiler.GlslCompiler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PackDepthMslTest {
	@Test
	void convertsPackVertexClipZOnceWithoutChangingXYOrW() throws Exception {
		CompiledShader shader = compile(GlslCompiler.Stage.VERTEX,
			"layout(location = 0) in vec4 position; void main() { gl_Position = position; }\n");
		String msl = shader.msl();

		assertEquals(1, count(msl, "mtlr_position.z = (mtlr_position.w - mtlr_position.z) / 2.0;"));
		assertTrue(msl.indexOf("mtlr_position.z =") < msl.indexOf("mtlr_out.mtlr_position = mtlr_position;"));
		assertFalse(msl.contains("mtlr_position.x ="));
		assertFalse(msl.contains("mtlr_position.y ="));
		assertFalse(msl.contains("mtlr_position.w ="));
		assertFalse(msl.contains("get_height() - 1"));
		assertTrue(compileMetal("pack-depth-vertex", msl));
		assertEquals(2.5f, (4.0f - (-1.0f)) / 2.0f);
		assertEquals(1.75f, (4.0f - 0.5f) / 2.0f);
	}

	@Test
	void convertsOnlyExplicitPackFragmentDepthAndPreservesColor() throws Exception {
		CompiledShader shader = compile(GlslCompiler.Stage.FRAGMENT,
			"layout(location = 0) out vec4 color; void main() { if (1 == 1) gl_FragDepth = 0.25; else gl_FragDepth = 0.75; color = vec4(0.2, 0.3, 0.4, 1.0); }\n");
		String msl = shader.msl();

		assertEquals(1, count(msl, "mtlr_out.mtlr_frag_depth = 1.0 - mtlr_frag_depth;"));
		assertTrue(msl.contains("mtlr_out.color = color;"));
		assertFalse(msl.contains("mtlr_out.color = 1.0 - color;"));
		assertTrue(compileMetal("pack-depth-fragment", msl));
		assertEquals(1.0f, 1.0f - 0.0f);
		assertEquals(0.25f, 1.0f - 0.75f);
	}

	@Test
	void leavesFragmentProgramsWithoutPackDepthUnchanged() throws Exception {
		CompiledShader shader = compile(GlslCompiler.Stage.FRAGMENT,
			"layout(location = 0) out vec4 color; void main() { color = vec4(0.5); }\n");
		String msl = shader.msl();

		assertFalse(msl.contains("mtlr_frag_depth"));
		assertFalse(msl.contains("1.0 -"));
		assertTrue(msl.contains("mtlr_out.color = color;"));
		assertTrue(compileMetal("pack-no-depth-fragment", msl));
	}

	private static CompiledShader compile(GlslCompiler.Stage stage, String source) throws Exception {
		return new GlslCompiler().compile(stage, List.of(new GlslCompiler.Source("pack-test.glsl", source)), List.of());
	}

	private static boolean compileMetal(String name, String source) throws IOException, InterruptedException {
		Path directory = Files.createTempDirectory("mtlr-pack-depth-");
		Path input = directory.resolve(name + ".metal");
		Path output = directory.resolve(name + ".air");
		try {
			Files.writeString(input, source, StandardCharsets.UTF_8);
			Process process = new ProcessBuilder("xcrun", "-sdk", "macosx", "metal", "-std=metal3.2", "-c",
				input.toString(), "-o", output.toString()).redirectErrorStream(true).start();
			process.getInputStream().readAllBytes();
			return process.waitFor() == 0;
		} finally {
			Files.deleteIfExists(input);
			Files.deleteIfExists(output);
			Files.deleteIfExists(directory);
		}
	}

	private static int count(String source, String value) {
		int count = 0;
		int offset = 0;
		while ((offset = source.indexOf(value, offset)) >= 0) {
			count++;
			offset += value.length();
		}
		return count;
	}
}
