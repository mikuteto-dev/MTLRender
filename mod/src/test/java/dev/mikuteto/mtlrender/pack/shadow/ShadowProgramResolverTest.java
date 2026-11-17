package dev.mikuteto.mtlrender.pack.shadow;

import dev.mikuteto.mtlrender.pack.ProgramId;
import dev.mikuteto.mtlrender.pack.ProgramSet;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowProgramResolverTest {
	private static final String STAGE = "void main() {}\n";

	@Test
	void resolvesEveryShadowKindThroughItsDeclaredTree(@TempDir Path directory) throws IOException {
		for (ShadowProgramKind kind : ShadowProgramKind.values()) {
			write(directory, kind.program().sourceName());
		}
		ShadowProgramResolver resolver = resolver(directory);

		for (ShadowProgramKind kind : ShadowProgramKind.values()) {
			ShadowProgramSelection selection = resolver.resolve(kind);
			assertTrue(selection.resolved());
			assertEquals(kind.program().sourceName(), selection.sourceName());
			assertEquals(List.of(kind.program()), selection.fallbackPath());
		}
	}

	@Test
	void followsAllShadowFallbackEdges(@TempDir Path directory) throws IOException {
		write(directory, "shadow");
		write(directory, "shadow_entities");
		ShadowProgramResolver resolver = resolver(directory);

		assertFallback(resolver.resolve(ShadowProgramKind.TERRAIN_SOLID), ProgramId.Shadow);
		assertFallback(resolver.resolve(ShadowProgramKind.TERRAIN_CUTOUT), ProgramId.Shadow);
		assertFallback(resolver.resolve(ShadowProgramKind.TERRAIN_WATER), ProgramId.Shadow);
		assertFallback(resolver.resolve(ShadowProgramKind.ENTITIES), ProgramId.ShadowEntities);
		assertFallback(resolver.resolve(ShadowProgramKind.LIGHTNING), ProgramId.ShadowEntities);
		assertFallback(resolver.resolve(ShadowProgramKind.BLOCK_ENTITIES), ProgramId.Shadow);
	}

	@Test
	void doesNotUseGbufferOrComputeOnlySources(@TempDir Path directory) throws IOException {
		write(directory, "gbuffers_terrain");
		Files.writeString(directory.resolve("shadow.csh"), STAGE);
		ShadowProgramResolver resolver = resolver(directory);

		ShadowProgramSelection selection = resolver.resolve(ShadowProgramKind.TERRAIN_SOLID, true);
		assertFalse(selection.resolved());
		assertTrue(selection.optional());
		assertFalse(selection.disabled());
		assertEquals("shadow_solid", selection.sourceName());
	}

	@Test
	void treatsShadowAsRenderWhenItAlsoHasCompute(@TempDir Path directory) throws IOException {
		write(directory, "shadow");
		Files.writeString(directory.resolve("shadow.csh"), STAGE);
		ShadowProgramSelection selection = resolver(directory).resolve(ShadowProgramKind.TERRAIN_SOLID);
		assertTrue(selection.resolved());
		assertEquals("shadow", selection.sourceName());
		assertTrue(selection.program().orElseThrow().isRenderProgram());
	}

	@Test
	void rejectsAnIncompleteRenderSourceInsteadOfFallingBack(@TempDir Path directory) throws IOException {
		Files.writeString(directory.resolve("shadow_solid.vsh"), STAGE);
		Files.writeString(directory.resolve("shadow.fsh"), STAGE);
		ShadowProgramSelection selection = resolver(directory).resolve(ShadowProgramKind.TERRAIN_SOLID);
		assertFalse(selection.resolved());
		assertTrue(selection.requiredFailure());
		assertEquals(List.of(ProgramId.ShadowSolid), selection.fallbackPath());
	}

	@Test
	void honorsProgramEnabledAndProfileDisabledEntries(@TempDir Path directory) throws IOException {
		write(directory, "shadow_solid");
		write(directory, "shadow");
		Files.writeString(directory.resolve("shaders.properties"),
			"program.shadow_solid.enabled=false\n");
		ShadowProgramResolver resolver = resolver(directory);
		ShadowProgramSelection selection = resolver.resolve(ShadowProgramKind.TERRAIN_SOLID);
		assertEquals("shadow", selection.sourceName());
		assertTrue(selection.resolved());
		assertFalse(selection.disabled());

		ShadowProgramSelection profile = new ShadowProgramResolver(
			resolverSet(directory),
			ShaderPack.load(directory, Map.of()).properties(),
			Set.of("shadow_solid", "shadow"))
			.resolve(ShadowProgramKind.TERRAIN_SOLID, true);
		assertFalse(profile.resolved());
		assertTrue(profile.disabled());
	}

	@Test
	void usesTheSelectedDimensionProgramSet(@TempDir Path directory) throws IOException {
		Files.createDirectories(directory.resolve("world0"));
		Files.writeString(directory.resolve("dimension.properties"),
			"dimension.world0=minecraft:overworld\n");
		write(directory.resolve("world0"), "shadow_solid");
		write(directory, "shadow");
		ShaderPack pack = ShaderPack.load(directory, Map.of());
		ShadowProgramSelection selection = new ShadowProgramResolver(pack.programSet("minecraft:overworld"),
			pack.properties()).resolve(ShadowProgramKind.TERRAIN_SOLID);
		assertEquals("shadow_solid", selection.sourceName());
	}

	private static void assertFallback(ShadowProgramSelection selection, ProgramId source) {
		assertTrue(selection.resolved());
		assertEquals(source.sourceName(), selection.sourceName());
		assertEquals(source, selection.fallbackPath().getLast());
	}

	private static ShadowProgramResolver resolver(Path directory) throws IOException {
		ShaderPack pack = ShaderPack.load(directory, Map.of());
		return new ShadowProgramResolver(pack.rootProgramSet(), pack.properties());
	}

	private static ProgramSet resolverSet(Path directory) throws IOException {
		return ShaderPack.load(directory, Map.of()).rootProgramSet();
	}

	private static void write(Path directory, String name) throws IOException {
		Files.writeString(directory.resolve(name + ".vsh"), STAGE);
		Files.writeString(directory.resolve(name + ".fsh"), STAGE);
	}
}
