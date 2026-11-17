package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackProgramInventoryTest {
	@Test
	void includesPresentVariantsAndPreservesOptionalStages() throws IOException {
		Path packPath = syntheticPack(Map.of(
			"composite2.vsh", "v2", "composite2.fsh", "f2",
			"composite2.gsh", "g2", "composite2.tcs", "tc2", "composite2.tes", "te2",
			"composite_a.vsh", "va", "composite_a.fsh", "fa",
			"composite_b.csh", "compute-only"));
		ShaderPack pack = ShaderPack.load(packPath, Map.of());

		List<PackProgramRequest> requests = PackProgramInventory.from("synthetic", pack).requests();
		assertEquals(List.of("composite2", "composite_a"), requests.stream()
			.map(PackProgramRequest::program).toList());
		PackProgramRequest numbered = requests.get(0);
		assertEquals("g2\n", numbered.source(Program.Stage.GEOMETRY).orElseThrow());
		assertEquals("tc2\n", numbered.source(Program.Stage.TESS_CONTROL).orElseThrow());
		assertEquals("te2\n", numbered.source(Program.Stage.TESS_EVALUATION).orElseThrow());
		assertFalse(requests.stream().anyMatch(request -> request.program().equals("composite_b")));
		assertThrows(UnsupportedOperationException.class,
			() -> numbered.sources().put(Program.Stage.GEOMETRY, "changed"));
	}

	@Test
	void renderFinalSurvivesAComputeFinalWithTheSameName() throws IOException {
		Path packPath = syntheticPack(Map.of(
			"final.vsh", "vertex", "final.fsh", "fragment", "final.csh", "compute"));
		ShaderPack pack = ShaderPack.load(packPath, Map.of());

		Program finalProgram = pack.rootProgramSet().program("final").orElseThrow();
		assertTrue(finalProgram.isRenderProgram());
		assertTrue(finalProgram.isComputeProgram());
		assertTrue(PackProgramInventory.from("synthetic", pack).requests().stream()
			.anyMatch(request -> request.program().equals("final")));
	}

	private static Path syntheticPack(Map<String, String> files) throws IOException {
		Path path = Files.createTempFile("mtlrender-pack", ".zip");
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
			for (Map.Entry<String, String> file : files.entrySet()) {
				output.putNextEntry(new ZipEntry("shaders/" + file.getKey()));
				output.write(file.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
				output.closeEntry();
			}
		}
		return path;
	}
}
