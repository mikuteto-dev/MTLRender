package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteOrder;
import java.util.EnumSet;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import dev.mikuteto.mtlrender.pack.dh.DhDepthResources;
import dev.mikuteto.mtlrender.pack.uniform.DepthConvention;
import dev.mikuteto.mtlrender.pack.dh.DhProgramKind;
import dev.mikuteto.mtlrender.pack.dh.DhRenderContract;
import dev.mikuteto.mtlrender.pack.dh.DhShadowDraw;
import dev.mikuteto.mtlrender.pack.dh.DhShadowFrame;
import dev.mikuteto.mtlrender.pack.dh.DhUniformState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

final class DhLodPassTest {
	private static final DhUniformState UNIFORMS = new dev.mikuteto.mtlrender.pack.dh.DhUniforms().update(
		DhUniformState.CameraKind.MAIN, new Matrix4f(), projection(), 0.1F,
		new dev.mikuteto.mtlrender.pack.dh.DhUniforms.LodExtent(0, 0, 32, 32, 0.0, 0.0));
	private static final DhDepthResources DEPTH = DhDepthResources.of(java.util.Map.of());

	@Test
	void mapsTerrainLayersToDhPrograms() {
		assertEquals(DhProgramKind.TERRAIN, DhLodPass.programFor(ChunkSectionLayer.SOLID));
		assertEquals(DhProgramKind.GENERIC, DhLodPass.programFor(ChunkSectionLayer.CUTOUT));
		assertEquals(DhProgramKind.WATER, DhLodPass.programFor(ChunkSectionLayer.TRANSLUCENT));
	}

	@Test
	void selectsDhProgramFromMaterialLayer() {
		assertEquals(DhProgramKind.TERRAIN, DhLodPass.selectProgram(material(ChunkSectionLayer.SOLID, DhRenderContract.MaterialKind.TERRAIN), false));
		assertEquals(DhProgramKind.WATER, DhLodPass.selectProgram(material(ChunkSectionLayer.TRANSLUCENT, DhRenderContract.MaterialKind.WATER), false));
		assertEquals(DhProgramKind.GENERIC, DhLodPass.selectProgram(material(ChunkSectionLayer.CUTOUT, DhRenderContract.MaterialKind.GENERIC), false));
		assertEquals(DhProgramKind.SHADOW, DhLodPass.selectProgram(material(ChunkSectionLayer.SOLID, DhRenderContract.MaterialKind.TERRAIN), true));
	}

	@Test
	void preparesOnlyAvailableProgramAndPreservesLodEntryCounts() {
		DirectUberBuffer.Entry entry = new DirectUberBuffer.Entry(null, 0L, new int[] { 2, 1, 0, 0, 0, 0 }, null,
			TerrainVertex.BLOCK, null, 0L, -1L, 0L);
		DhLodPass.Material material = material(ChunkSectionLayer.SOLID, DhRenderContract.MaterialKind.TERRAIN);
		assertTrue(DhLodPass.prepare(entry, material, 3, false, EnumSet.of(DhProgramKind.TERRAIN), UNIFORMS, DEPTH)
			.orElseThrow().quads() == 3);
		assertFalse(DhLodPass.prepare(entry, material, 3, false, EnumSet.of(DhProgramKind.WATER), UNIFORMS, DEPTH).isPresent());
	}

	@Test
	void gatesShadowLayersAndUsesShadowUniformGeneration() {
		DhUniformState shadowUniforms = new dev.mikuteto.mtlrender.pack.dh.DhUniforms().update(
			DhUniformState.CameraKind.SHADOW, new Matrix4f(), projection(), 0.1F,
			new dev.mikuteto.mtlrender.pack.dh.DhUniforms.LodExtent(0, 0, 32, 32, 0.0, 0.0), 4L);
		DhDepthResources depth = DhDepthResources.of(4L, java.util.Map.of());
		DhShadowFrame frame = DhShadowFrame.enabled(new DhShadowFrame.PackIdentity("pack"), 4L, true, true, false,
			shadowUniforms, 4L, 4L, depth);
		DirectUberBuffer.Entry entry = new DirectUberBuffer.Entry(null, 0L, new int[] { 1, 0, 0, 0, 0, 0 }, null,
			TerrainVertex.BLOCK, null, 0L, -1L, 0L);
		assertTrue(DhLodPass.prepareShadow(entry, material(ChunkSectionLayer.SOLID, DhRenderContract.MaterialKind.TERRAIN), 0,
			frame, depth).isPresent());
		assertFalse(DhLodPass.prepareShadow(entry, material(ChunkSectionLayer.TRANSLUCENT, DhRenderContract.MaterialKind.WATER), 0,
			frame, depth).isPresent());
		assertEquals(DhShadowDraw.CullingMode.BACK, DhLodPass.shadowCulling(material(ChunkSectionLayer.SOLID, DhRenderContract.MaterialKind.TERRAIN)));
		assertEquals(DhShadowDraw.CullingMode.BOTH, DhLodPass.shadowCulling(material(ChunkSectionLayer.CUTOUT, DhRenderContract.MaterialKind.GENERIC)));
	}

	@Test
	void cullsAgainstShadowPlanesWithoutUsingMainCamera() {
		var planes = java.util.List.of(new DhLodPass.Plane(1.0F, 0.0F, 0.0F, -8.0F));
		assertTrue(DhLodPass.shadowVisible(8, 0, 0, 16, 16, 16, planes));
		assertFalse(DhLodPass.shadowVisible(-16, 0, 0, -1, 16, 16, planes));
	}

	@Test
	void packsDeterministicTerrainAttributes() {
		var first = DhLodPass.packTerrainVertex(4, 5, 6, 0.1F, 0.2F, 0.3F, 1.0F, 7, 2, 11);
		var second = DhLodPass.packTerrainVertex(4, 5, 6, 0.1F, 0.2F, 0.3F, 1.0F, 7, 2, 11);
		assertEquals(ByteOrder.LITTLE_ENDIAN, first.order());
		assertEquals(first, second);
		assertEquals(7, first.getInt(32));
		assertEquals(2, first.getInt(36));
		assertEquals(11, first.getInt(40));
	}

	private static Matrix4f projection() {
		return DepthConvention.gameProjectionToPack(new Matrix4f().setPerspective((float) Math.toRadians(70.0), 1.5F, 0.05F, 400.0F));
	}

	private static DhLodPass.Material material(ChunkSectionLayer layer, DhRenderContract.MaterialKind kind) {
		return new DhLodPass.Material(layer, kind, 4);
	}
}
