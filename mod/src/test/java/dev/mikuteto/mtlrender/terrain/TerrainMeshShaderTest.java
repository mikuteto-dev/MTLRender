package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerrainMeshShaderTest {
	@Test
	void describesMetalMeshLimitsAndStages() {
		TerrainMeshShader.Program program = TerrainMeshShader.program(false);
		assertEquals(TerrainMeshShader.OBJECT_ENTRY_POINT, program.entryPoints().object());
		assertEquals(TerrainMeshShader.MESH_ENTRY_POINT, program.entryPoints().mesh());
		assertEquals(TerrainMeshShader.FRAGMENT_ENTRY_POINT, program.entryPoints().fragment());
		assertEquals(TerrainMeshShader.MAX_QUADS, program.units().quadsPerUnit());
		assertEquals(TerrainMeshShader.MAX_VERTICES, program.meshThreadgroup().x());
		assertEquals(0, program.unitsBinding().orElseThrow());
	}

	@Test
	void containsAllPullingVariantsAndShortUnitBounds() {
		String source = TerrainMeshShader.meshSource(false);
		assertTrue(source.contains("unit.index_type == 0u"));
		assertTrue(source.contains("type == 1u"));
		assertTrue(source.contains("return words[index]"));
		assertTrue(source.contains("unit.vertex_format == 1u"));
		assertTrue(source.contains("vertex * 5u"));
		assertTrue(source.contains("vertex * 7u"));
		assertTrue(source.contains("min(payload.quads, 32u)"));
		assertTrue(source.contains("threadPosition.x < vertexCount"));
		assertTrue(source.contains("threadPosition.x < triangleCount"));
		assertTrue(source.contains("uint3(base + 2u, base + 3u, base)"));
	}

	@Test
	void objectStageBoundsFinalUnit() {
		String source = TerrainMeshShader.objectSource();
		assertTrue(source.contains("min(units[unitIndex].quads, 32u)"));
		assertTrue(source.contains("grid.set_threadgroups_per_grid(uint3(1u, 1u, 1u))"));
		assertTrue(source.contains("threadgroup_position_in_grid"));
	}
}
