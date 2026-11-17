package dev.mikuteto.mtlrender.pack.tess;

import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ProgramSet;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TessellationProgramTest {
	@Test
	void discoversAllSupportedTopologiesAndSpacingModes() throws Exception {
		Path archive = Files.createTempFile("mtlrender-tess-programs", ".zip");
		List<String> names = List.of("composite", "composite1", "composite2", "composite_a", "composite_b");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
			for (String name : names) {
				put(zip, "shaders/" + name + ".vsh", "void main() { gl_Position = vec4(0.0); }");
				put(zip, "shaders/" + name + ".tcs", "layout(vertices = 3) out; void main() { gl_TessLevelOuter[0] = 2.0; }");
				put(zip, "shaders/" + name + ".tes", evaluationSource(name));
				put(zip, "shaders/" + name + ".fsh", "void main() { gl_FragColor = vec4(1.0); }");
			}
		}
		try {
			ShaderPack pack = ShaderPack.load(archive, Map.of());
			ProgramSet set = pack.programSet("");
			assertEquals(names.size(), set.tessellatedPrograms().size());
			for (String name : names) {
				Program program = set.program(name).orElseThrow();
				assertTrue(program.isTessellatedRenderProgram(), name);
				assertTrue(program.source(Program.Stage.TESS_CONTROL).isPresent(), name);
				assertTrue(program.source(Program.Stage.TESS_EVALUATION).isPresent(), name);
			}
		} finally {
			Files.deleteIfExists(archive);
		}
	}

	private static String evaluationSource(String name) {
		if (name.equals("composite1")) return "layout(quads, equal_spacing) in; void main() { gl_Position = vec4(0.0); }";
		if (name.equals("composite2")) return "layout(isolines, equal_spacing) in; void main() { gl_Position = vec4(0.0); }";
		if (name.equals("composite_a")) return "layout(triangles, fractional_even_spacing) in; void main() { gl_Position = vec4(0.0); }";
		if (name.equals("composite_b")) return "layout(triangles, fractional_odd_spacing) in; void main() { gl_Position = vec4(0.0); }";
		return "layout(triangles, equal_spacing) in; void main() { gl_Position = vec4(0.0); }";
	}

	private static void put(ZipOutputStream zip, String name, String source) throws Exception {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(source.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}
}
