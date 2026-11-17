package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.mikuteto.mtlrender.backend.PackPipelineDesc;
import dev.mikuteto.mtlrender.pack.runtime.PackRuntimeService;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

class PackTerrainRenderStateTest {
	private static final PackTerrainRequirements VANILLA = PackTerrainRequirements.fromDeclaredInputs(true,
		List.of("Position", "Color", "UV0", "UV2"), null);

	@Test
	void noneIsDeviceFreeAndDifferentFromVanillaOnlyPack() {
		PackTerrainRenderState none = PackTerrainRenderState.none();
		PackTerrainRenderState vanilla = PackTerrainRenderState.active(4L, VANILLA, 9L,
			Optional.empty(), Optional.empty(), Optional.empty());

		assertFalse(none.packActive());
		assertTrue(vanilla.packActive());
		assertFalse(none.equals(vanilla));
		assertTrue(none.pipeline(ChunkSectionLayer.SOLID).isEmpty());
	}

	@Test
	void exposesAllTerrainLayersWithoutTreatingWaterAsCutout() {
		PackTerrainPipelines.Selection solid = selection(1L);
		PackTerrainPipelines.Selection cutout = selection(2L);
		PackTerrainPipelines.Selection translucent = selection(3L);
		PackTerrainRenderState state = PackTerrainRenderState.active(4L, VANILLA, 9L,
			Optional.of(solid), Optional.of(cutout), Optional.of(translucent));

		assertSame(solid, state.pipeline(ChunkSectionLayer.SOLID).orElseThrow());
		assertSame(cutout, state.pipeline(ChunkSectionLayer.CUTOUT).orElseThrow());
		assertSame(translucent, state.pipeline(ChunkSectionLayer.TRANSLUCENT).orElseThrow());
	}

	@Test
	void rejectsEveryRelevantGenerationAndRequirementMismatch() {
		PackTerrainRenderState state = PackTerrainRenderState.active(4L, VANILLA, 9L,
			Optional.empty(), Optional.empty(), Optional.empty());
		long fingerprint = VANILLA.fingerprint();

		assertFalse(state.metadataCompatible(3L, fingerprint, 9L));
		assertFalse(state.metadataCompatible(4L, fingerprint + 1L, 9L));
		assertFalse(state.metadataCompatible(4L, fingerprint, 8L));
		assertTrue(state.metadataCompatible(4L, fingerprint, 9L));
	}

	@Test
	void noneIsCurrentOnlyWhenNoPackAndMatchingVanillaIdentity() {
		PackRuntimeService service = new PackRuntimeService(
			new PackRuntimeService.PackSource() {
				@Override
				public Optional<dev.mikuteto.mtlrender.pack.ShaderPack> active() {
					return Optional.empty();
				}

				@Override
				public long version() {
					return 7L;
				}
			},
			dev.mikuteto.mtlrender.pack.runtime.PackRuntime.inactive());
		PackTerrainRenderState none = PackTerrainRenderState.none();

		assertTrue(none.isCurrent(service, PackTerrainRequirements.none(), -1L));
		assertFalse(none.isCurrent(service, VANILLA, -1L));
		assertFalse(none.isCurrent(service, PackTerrainRequirements.none(), 0L));
	}

	private static PackTerrainPipelines.Selection selection(long pipeline) {
		PackPipelineDesc descriptor = new PackPipelineDesc("vertex", "vertexEntry", "fragment", "fragmentEntry",
			List.of(), null, List.of(), null, null, false, PackPipelineDesc.CullMode.NONE);
		return new PackTerrainPipelines.Selection(pipeline, descriptor, VANILLA, 12L,
			PackTerrainPipelines.VertexMode.PACK, Optional.empty());
	}
}
