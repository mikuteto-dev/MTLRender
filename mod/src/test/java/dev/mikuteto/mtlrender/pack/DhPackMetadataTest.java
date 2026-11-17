package dev.mikuteto.mtlrender.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikuteto.mtlrender.pack.dh.DhProgramKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DhPackMetadataTest {
	private static final String STAGE = "void main() {}\n";

	@Test
	void advertisesIrisDhMacros() {
		Map<String, String> macros = StandardMacros.environment();

		assertEquals("", macros.get("DISTANT_HORIZONS"));
		assertEquals("", macros.get("DISTANT_HORIZONS_TEXTURES"));
		assertEquals("0", macros.get("DH_BLOCK_UNKNOWN"));
		assertEquals("15", macros.get("DH_BLOCK_ILLUMINATED"));
	}

	@Test
	void resolvesDhProgramsWithoutOrdinaryFallback(@TempDir Path directory) throws IOException {
		write(directory, "dh_terrain");
		write(directory, "dh_shadow");
		write(directory, "gbuffers_terrain");

		ProgramSet programs = ShaderPack.load(directory, Map.of()).rootProgramSet();

		assertEquals("dh_terrain", programs.resolve(DhProgramKind.TERRAIN).orElseThrow().name());
		assertEquals("dh_terrain", programs.resolve(DhProgramKind.WATER).orElseThrow().name());
		assertEquals("dh_terrain", programs.resolve(DhProgramKind.GENERIC).orElseThrow().name());
		assertEquals("dh_shadow", programs.resolve(DhProgramKind.SHADOW).orElseThrow().name());
		assertTrue(programs.hasDhProgram());
	}

	@Test
	void resolvesEachDhProgramBeforeItsFallback(@TempDir Path directory) throws IOException {
		write(directory, "dh_terrain");
		write(directory, "dh_water");
		write(directory, "dh_generic");
		write(directory, "dh_shadow");

		ProgramSet programs = ShaderPack.load(directory, Map.of()).rootProgramSet();

		for (DhProgramKind kind : DhProgramKind.values()) {
			assertEquals(kind.sourceName(), programs.resolve(kind).orElseThrow().name());
		}
	}

	@Test
	void missingDhProgramsDoNotResolveToOrdinaryPrograms(@TempDir Path directory) throws IOException {
		write(directory, "gbuffers_terrain");

		ProgramSet programs = ShaderPack.load(directory, Map.of()).rootProgramSet();

		assertTrue(programs.resolve(ProgramId.TerrainSolid).isPresent());
		for (DhProgramKind kind : DhProgramKind.values()) {
			assertTrue(programs.resolve(kind).isEmpty());
		}
		assertFalse(programs.hasDhProgram());
	}

	private static void write(Path directory, String name) throws IOException {
		Files.writeString(directory.resolve(name + ".vsh"), STAGE);
		Files.writeString(directory.resolve(name + ".fsh"), STAGE);
	}
}
