package dev.mikuteto.mtlrender.terrain;

import dev.mikuteto.mtlrender.pack.dh.DhDepthResources;
import dev.mikuteto.mtlrender.pack.dh.DhProgramKind;
import dev.mikuteto.mtlrender.pack.dh.DhRenderContract;
import dev.mikuteto.mtlrender.pack.dh.DhShadowDraw;
import dev.mikuteto.mtlrender.pack.dh.DhShadowFrame;
import dev.mikuteto.mtlrender.pack.dh.DhUniformState;
import dev.mikuteto.mtlrender.pack.uniform.DepthConvention;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DhShadowTerrainIntegrationTest {
	@Test
	void mainUsesOneLodEntryPerLayerAndKeepsLayerOrder() {
		DirectUberBuffer.Entry solid = entry(2);
		DirectUberBuffer.Entry cutout = entry(3);
		DirectUberBuffer.Entry translucent = entry(4);
		DhUniformState uniforms = mainUniforms(5L);
		DhDepthResources depth = DhDepthResources.of(5L, java.util.Map.of());

		DhLodPass.Prepared first = prepare(solid, ChunkSectionLayer.SOLID,
			DhRenderContract.MaterialKind.TERRAIN, DhProgramKind.TERRAIN, uniforms, depth);
		DhLodPass.Prepared second = prepare(cutout, ChunkSectionLayer.CUTOUT,
			DhRenderContract.MaterialKind.GENERIC, DhProgramKind.GENERIC, uniforms, depth);
		DhLodPass.Prepared third = prepare(translucent, ChunkSectionLayer.TRANSLUCENT,
			DhRenderContract.MaterialKind.WATER, DhProgramKind.WATER, uniforms, depth);

		assertEquals(List.of(ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT, ChunkSectionLayer.TRANSLUCENT),
			List.of(ChunkSectionLayer.values()));
		assertEquals(List.of(DhProgramKind.TERRAIN, DhProgramKind.GENERIC, DhProgramKind.WATER),
			List.of(first.contract().programKind(), second.contract().programKind(), third.contract().programKind()));
		assertSame(solid, first.entry());
		assertSame(cutout, second.entry());
		assertSame(translucent, third.entry());
	}

	@Test
	void shadowUsesSeparateVisibilityAndDoesNotDuplicateNearTerrain() {
		DirectUberBuffer.Entry lod = entry(1);
		DhDepthResources depth = DhDepthResources.of(8L, java.util.Map.of());
		DhUniformState uniforms = shadowUniforms(8L);
		DhShadowFrame frame = DhShadowFrame.enabled(new DhShadowFrame.PackIdentity("pack"), 8L,
			true, true, true, uniforms, 8L, 8L, depth);
		DhLodPass.Material material = new DhLodPass.Material(ChunkSectionLayer.SOLID,
			DhRenderContract.MaterialKind.TERRAIN, 0);

		Optional<DhLodPass.Prepared> main = DhLodPass.prepare(lod, material, 0, false,
			EnumSet.of(DhProgramKind.TERRAIN), uniforms, depth);
		Optional<DhLodPass.ShadowPrepared> shadow = DhLodPass.prepareShadow(lod, material, 0, frame, depth);
		assertTrue(main.isPresent());
		assertTrue(shadow.isPresent());
		assertSame(lod, main.orElseThrow().entry());
		assertSame(lod, shadow.orElseThrow().prepared().entry());
		assertEquals(DhProgramKind.SHADOW, shadow.orElseThrow().prepared().contract().programKind());
		assertEquals(DhShadowDraw.CullingMode.BACK, shadow.orElseThrow().culling());
		assertTrue(DhLodPass.shadowVisible(32, 0, 0, 48, 16, 16,
			List.of(new DhLodPass.Plane(1, 0, 0, -32))));
		assertFalse(DhLodPass.shadowVisible(0, 0, 0, 16, 16, 16,
			List.of(new DhLodPass.Plane(1, 0, 0, -32))));
	}

	@Test
	void shadowFlagsAndProgramFallbackAreIndependent() {
		DirectUberBuffer.Entry entry = entry(1);
		DhUniformState uniforms = shadowUniforms(2L);
		DhDepthResources depth = DhDepthResources.of(2L, java.util.Map.of());
		DhLodPass.Material terrain = new DhLodPass.Material(ChunkSectionLayer.SOLID,
			DhRenderContract.MaterialKind.TERRAIN, 0);
		DhLodPass.Material water = new DhLodPass.Material(ChunkSectionLayer.TRANSLUCENT,
			DhRenderContract.MaterialKind.WATER, 0);
		DhShadowFrame terrainOnly = DhShadowFrame.enabled(new DhShadowFrame.PackIdentity("pack"), 2L,
			true, true, false, uniforms, 2L, 2L, depth);
		DhShadowFrame translucentOnly = DhShadowFrame.enabled(new DhShadowFrame.PackIdentity("pack"), 2L,
			true, false, true, uniforms, 2L, 2L, depth);
		DhShadowFrame missing = DhShadowFrame.enabled(new DhShadowFrame.PackIdentity("pack"), 2L,
			false, true, true, uniforms, 2L, 2L, depth);

		assertTrue(DhLodPass.prepareShadow(entry, terrain, 0, terrainOnly, depth).isPresent());
		assertFalse(DhLodPass.prepareShadow(entry, water, 0, terrainOnly, depth).isPresent());
		assertFalse(DhLodPass.prepareShadow(entry, terrain, 0, translucentOnly, depth).isPresent());
		assertTrue(DhLodPass.prepareShadow(entry, water, 0, translucentOnly, depth).isPresent());
		assertFalse(DhLodPass.prepareShadow(entry, terrain, 0, missing, depth).isPresent());
		assertFalse(DhLodPass.prepareShadow(entry, terrain, 0, DhShadowFrame.disabled(), depth).isPresent());
	}

	@Test
	void staleDepthGenerationCannotProduceShadowDraw() {
		DirectUberBuffer.Entry entry = entry(1);
		DhUniformState uniforms = shadowUniforms(6L);
		DhDepthResources oldDepth = DhDepthResources.of(6L, java.util.Map.of());
		DhDepthResources resizedDepth = DhDepthResources.of(7L, java.util.Map.of());
		DhShadowFrame frame = DhShadowFrame.enabled(new DhShadowFrame.PackIdentity("pack"), 6L,
			true, true, true, uniforms, 6L, 6L, oldDepth);
		DhLodPass.Material material = new DhLodPass.Material(ChunkSectionLayer.CUTOUT,
			DhRenderContract.MaterialKind.GENERIC, 0);

		assertTrue(DhLodPass.prepareShadow(entry, material, 0, frame, oldDepth).isPresent());
		assertFalse(DhLodPass.prepareShadow(entry, material, 0, frame, resizedDepth).isPresent());
	}

	private static DirectUberBuffer.Entry entry(int quads) {
		return new DirectUberBuffer.Entry(null, 0L, new int[] {quads, 0, 0, 0, 0, 0}, null,
			TerrainVertex.BLOCK, null, 0L, -1L, 0L);
	}

	private static DhLodPass.Prepared prepare(DirectUberBuffer.Entry entry, ChunkSectionLayer layer,
		DhRenderContract.MaterialKind kind, DhProgramKind program, DhUniformState uniforms, DhDepthResources depth) {
		return DhLodPass.prepare(entry, new DhLodPass.Material(layer, kind, 0), 0, false,
			program, uniforms, depth).orElseThrow();
	}

	private static DhUniformState mainUniforms(long generation) {
		return new dev.mikuteto.mtlrender.pack.dh.DhUniforms().update(DhUniformState.CameraKind.MAIN,
			new Matrix4f(), DepthConvention.gameProjectionToPack(new Matrix4f().setPerspective(
				(float) Math.toRadians(70.0), 1.5F, 0.05F, 400.0F)), 0.1F,
			new dev.mikuteto.mtlrender.pack.dh.DhUniforms.LodExtent(0, 0, 64, 64, 0.0, 0.0), generation);
	}

	private static DhUniformState shadowUniforms(long generation) {
		return new dev.mikuteto.mtlrender.pack.dh.DhUniforms().updateShadow(
			new dev.mikuteto.mtlrender.pack.dh.DhUniforms.ShadowCamera(new Matrix4f(), new Matrix4f(),
				0.1F, 1000.0F, 0.1F, generation),
			new dev.mikuteto.mtlrender.pack.dh.DhUniforms.LodExtent(0, 0, 64, 64, 0.0, 0.0));
	}
}
