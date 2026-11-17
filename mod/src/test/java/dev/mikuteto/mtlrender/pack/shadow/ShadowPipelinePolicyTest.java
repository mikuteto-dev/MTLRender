package dev.mikuteto.mtlrender.pack.shadow;

import com.mojang.blaze3d.platform.CompareOp;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ProgramId;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.backend.PackPipelineDesc;
import dev.mikuteto.mtlrender.pack.compiler.Reflection;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferStateOverride;
import dev.mikuteto.mtlrender.pack.runtime.PackProgramCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowPipelinePolicyTest {
	private final ShadowPipelinePolicy policy = new ShadowPipelinePolicy();

	@Test
	void solidUsesDepthOnlyWithoutAColorAttachment(@TempDir Path directory) throws IOException {
		ShadowTargetPlan plan = plan(directory, "/* DRAWBUFFERS: 0 */\n");
		ShadowProgramSelection selection = selection(new Program("shadow_solid", Map.of(Program.Stage.VERTEX, "v")));

		ShadowPipelinePolicy.Decision decision = policy.choose(selection, plan, Optional.empty(), Optional.empty());

		assertTrue(decision.usable());
		assertEquals(ShadowPipelinePolicy.Kind.DEPTH_ONLY, decision.kind());
		assertFalse(decision.hasColorAttachment());
		PackPipelineDesc adapted = policy.adapt(new PackPipelineDesc("v", "v", "f", "f", List.of(), null,
			List.of(), com.mojang.blaze3d.GpuFormat.D32_FLOAT, com.mojang.blaze3d.platform.CompareOp.LESS_THAN,
			true, PackPipelineDesc.CullMode.NONE), decision);
		assertEquals(null, adapted.fragmentMsl());
		assertTrue(adapted.colorAttachments().isEmpty());
	}

	@Test
	void alphaTestPreservesCutoutAndWaterDirective(@TempDir Path directory) throws IOException {
		ShadowTargetPlan plan = plan(directory, "");
		GbufferStateOverride.AlphaTest alpha = new GbufferStateOverride.AlphaTest(CompareOp.GREATER_THAN, 0.35f);
		ShadowProgramSelection selection = selection(new Program("shadow_cutout", Map.of(
			Program.Stage.VERTEX, "v", Program.Stage.FRAGMENT, "f")));

		ShadowPipelinePolicy.Decision decision = policy.choose(selection, plan,
			Optional.of(reflection(List.of())), Optional.of(alpha));

		assertEquals(ShadowPipelinePolicy.Kind.ALPHA_TEST, decision.kind());
		assertEquals(alpha, decision.alphaTest().orElseThrow());
		assertTrue(decision.depthWrite());
	}

	@Test
	void reflectionAndTargetPlanSelectSparseColors(@TempDir Path directory) throws IOException {
		ShadowTargetPlan plan = plan(directory, "/* DRAWBUFFERS: 1-3 */\n");
		ShadowProgramSelection selection = selection(new Program("shadow", Map.of(
			Program.Stage.VERTEX, "v", Program.Stage.FRAGMENT, "f")));
		Reflection reflection = reflection(List.of(
			new Reflection.FragmentOutput("a", 0, 0, 1),
			new Reflection.FragmentOutput("b", 0, 1, 3)));

		ShadowPipelinePolicy.Decision decision = policy.choose(selection, plan, Optional.of(reflection), Optional.empty());

		assertEquals(ShadowPipelinePolicy.Kind.COLOR, decision.kind());
		assertEquals(List.of(new ShadowPipelinePolicy.ColorOutput(1, 1), new ShadowPipelinePolicy.ColorOutput(3, 3)), decision.colors());
	}

	@Test
	void invalidRenderComputeAndColorSourcesAreRejected(@TempDir Path directory) throws IOException {
		ShadowTargetPlan plan = plan(directory, "");
		ShadowProgramSelection incomplete = selection(new Program("bad", Map.of(Program.Stage.FRAGMENT, "f")));
		ShadowProgramSelection compute = selection(new Program("compute", Map.of(
			Program.Stage.VERTEX, "v", Program.Stage.COMPUTE, "c")));
		ShadowProgramSelection invalidColor = selection(new Program("color", Map.of(
			Program.Stage.VERTEX, "v", Program.Stage.FRAGMENT, "/* DRAWBUFFERS: x */")));

		assertEquals(ShadowPipelinePolicy.Failure.INVALID_SOURCE,
			policy.choose(incomplete, plan, Optional.empty(), Optional.empty()).failure());
		assertEquals(ShadowPipelinePolicy.Failure.INVALID_SOURCE,
			policy.choose(compute, plan, Optional.empty(), Optional.empty()).failure());
		assertEquals(ShadowPipelinePolicy.Failure.INVALID_SOURCE,
			policy.choose(invalidColor, plan, Optional.empty(), Optional.empty()).failure());
	}

	@Test
	void optionalFailureSkipsOnlyTheAffectedProgram(@TempDir Path directory) throws IOException {
		ShadowTargetPlan plan = plan(directory, "");
		ShadowProgramSelection selection = new ShadowProgramSelection(ShadowProgramKind.TERRAIN_WATER,
			Optional.empty(), "shadow_water", List.of(ProgramId.ShadowWater), true, false);

		ShadowPipelinePolicy.Decision decision = policy.choose(selection, plan, Optional.empty(), Optional.empty());

		assertEquals(ShadowPipelinePolicy.Failure.OPTIONAL_PROGRAM, decision.failure());
		assertTrue(decision.skip());
	}

	@Test
	void cacheGenerationMismatchIsNotUsable(@TempDir Path directory) throws IOException {
		ShadowTargetPlan plan = plan(directory, "");
		ShadowProgramSelection selection = selection(new Program("shadow", Map.of(Program.Stage.VERTEX, "v")));
		PackProgramCache<String> cache = new PackProgramCache<>(() -> new PackProgramCache.State(1L, true), ignored -> { });
		PackProgramCache<String>.Generation generation = cache.generation("overworld").orElseThrow();

		ShadowPipelinePolicy.Decision decision = policy.choose(selection, plan, Optional.empty(), Optional.empty(), generation,
			generation.identity().generation() + 1L);

		assertEquals(ShadowPipelinePolicy.Failure.CACHE_GENERATION, decision.failure());
		cache.close();
	}

	private static ShadowProgramSelection selection(Program program) {
		return new ShadowProgramSelection(ShadowProgramKind.TERRAIN_SOLID, Optional.of(program), program.name(),
			List.of(ProgramId.ShadowSolid), false, false);
	}

	private static Reflection reflection(List<Reflection.FragmentOutput> outputs) {
		return new Reflection(2, false, List.of(), List.of(), List.of(), outputs, null);
	}

	private static ShadowTargetPlan plan(Path directory, String shadowSource) throws IOException {
		Files.writeString(directory.resolve("shadow.vsh"), "void main() {}\n");
		if (!shadowSource.isEmpty()) Files.writeString(directory.resolve("shadow.fsh"), "void main() {}\n" + shadowSource);
		return ShadowTargetPlan.build(ShaderPack.load(directory, Map.of()), 1L);
	}
}
