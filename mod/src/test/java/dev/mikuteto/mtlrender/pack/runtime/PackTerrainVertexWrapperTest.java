package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.Program;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackTerrainVertexWrapperTest {
	@Test
	void noPackDoesNothing() {
		assertTrue(PackTerrainVertexWrapper.wrap(Optional.empty()).isEmpty());
	}

	@Test
	void preservesEntryBodyAndCallsItWithDeclaredInputs() {
		PackTerrainVertexWrapper.Result result = PackTerrainVertexWrapper.wrap(artifact(List.of(
			new PackTranslationArtifact.VertexInput("UV0", 2, "vec2", 0, 0),
			new PackTranslationArtifact.VertexInput("Position", 0, "vec3", 0, 0))));
		assertEquals("mtlr_terrain_vertex", result.entry());
		assertTrue(result.msl().contains("void body_marker()"));
		assertTrue(result.msl().contains("mtlr_pack_vertex_main(mtlr_terrain"));
		assertEquals(List.of("Position", "UV0"), result.reads());
	}

	@Test
	void acceptsIrisAliasesAndLeavesSidecarsUnread() {
		PackTerrainVertexWrapper.Result result = PackTerrainVertexWrapper.wrap(artifact(List.of(
			new PackTranslationArtifact.VertexInput("mtlr_vertex", 0, "float4", 0, 0),
			new PackTranslationArtifact.VertexInput("mtlr_texcoord0", 1, "float4", 0, 0))));
		assertTrue(result.msl().contains("mtlr_terrain.mtlr_vertex"));
		assertFalse(result.msl().contains("mtlr_entity"));
		assertFalse(result.reads().contains("mc_Entity"));
	}

	@Test
	void rejectsUnknownNamesAndIncompatibleTypes() {
		assertThrows(IllegalArgumentException.class, () -> PackTerrainVertexWrapper.wrap(artifact(List.of(
			new PackTranslationArtifact.VertexInput("unknown", 0, "float", 0, 0)))));
		assertThrows(IllegalArgumentException.class, () -> PackTerrainVertexWrapper.wrap(artifact(List.of(
			new PackTranslationArtifact.VertexInput("UV2", 0, "float2", 0, 0)))));
	}

	@Test
	void reflectionOrderDoesNotChangeGeneratedAssignments() {
		PackTerrainVertexWrapper.Result first = PackTerrainVertexWrapper.wrap(artifact(List.of(
			new PackTranslationArtifact.VertexInput("Color", 1, "float4", 0, 0),
			new PackTranslationArtifact.VertexInput("Position", 0, "float3", 0, 0))));
		PackTerrainVertexWrapper.Result second = PackTerrainVertexWrapper.wrap(artifact(List.of(
			new PackTranslationArtifact.VertexInput("Position", 0, "float3", 0, 0),
			new PackTranslationArtifact.VertexInput("Color", 1, "float4", 0, 0))));
		assertEquals(first.msl(), second.msl());
	}

	private static PackTranslationArtifact artifact(List<PackTranslationArtifact.VertexInput> inputs) {
		Map<Program.Stage, String> ignored = new EnumMap<>(Program.Stage.class);
		ignored.put(Program.Stage.VERTEX, "vertex");
		ignored.put(Program.Stage.FRAGMENT, "fragment");
		String vertex = "struct MtlrVertexIn { float3 Position; float2 UV0; };"
			+ "struct MtlrVertexOut { float4 p; };"
			+ "vertex MtlrVertexOut main_vertex(MtlrVertexIn mtlr_in [[stage_in]], constant float4& u [[buffer(0)]]) { void body_marker(); return mtlr_pack_result; }";
		return new PackTranslationArtifact(vertex, "main_vertex", "fragment", "main_fragment",
			new PackTranslationArtifact.Reflection(List.of(), inputs, List.of(), null));
	}
}
