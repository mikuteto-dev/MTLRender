package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.mikuteto.mtlrender.backend.PackPipelineDesc;
import dev.mikuteto.mtlrender.pack.Includes;
import dev.mikuteto.mtlrender.pack.PackSource;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ProgramId;
import dev.mikuteto.mtlrender.pack.ProgramSet;
import dev.mikuteto.mtlrender.pack.ShaderProperties;
import dev.mikuteto.mtlrender.pack.ids.IdMap;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerrainRendererPackIntegrationTest {
	private static final IdMap IDS = IdMap.fromResolved(new IdMap.Resolved(new int[] { 0, 37 }, new int[0], new int[0], new int[] { 0, 0 }));
	private static final PackTerrainRequirements EXTRA = PackTerrainRequirements.fromDeclaredInputs(true,
		List.of("Position", "Color", "UV0", "UV2", "mc_Entity", "mc_midTexCoord", "at_tangent", "at_midBlock"), IDS);

	@Test
	void noPackSelectionDoesNotProbeRuntime() {
		AtomicBoolean probed = new AtomicBoolean();
		PackTerrainRenderState state = PackTerrainRenderState.select("", () -> {
			probed.set(true);
			return PackTerrainRenderState.none();
		});

		assertFalse(state.packActive());
		assertFalse(probed.get());
		assertTrue(state.pipeline(ChunkSectionLayer.SOLID).isEmpty());
	}

	@Test
	void terrainProgramsUseProgramSetFallbackForEachLayer(@TempDir Path directory) throws IOException {
		Files.createDirectories(directory.resolve("shaders"));
		Files.writeString(directory.resolve("shaders/gbuffers_terrain.vsh"), "void main(){}");
		Files.writeString(directory.resolve("shaders/gbuffers_terrain.fsh"), "void main(){}");
		try (PackSource source = PackSource.open(directory)) {
			Includes includes = Includes.build(source, List.of("/gbuffers_terrain.vsh", "/gbuffers_terrain.fsh"));
			ProgramSet programs = new ProgramSet(source, includes, ShaderProperties.empty(), Map.of(), "", java.util.Set.of());
			for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
				assertEquals("gbuffers_terrain", programs.resolve(PackTerrainPipelines.programFor(layer)).orElseThrow().name());
			}
		}
	}

	@Test
	void selectsPackPipelinesPerLayerWithoutConfusingWaterAndCutout() {
		PackTerrainPipelines.Selection solid = selection(11L, 21L);
		PackTerrainPipelines.Selection cutout = selection(12L, 22L);
		PackTerrainPipelines.Selection water = selection(13L, 23L);
		PackTerrainRenderState state = PackTerrainRenderState.active(7L, 8L, 9L, EXTRA, 10L,
			Optional.of(solid), Optional.of(cutout), Optional.of(water));

		assertSame(solid, state.pipeline(ChunkSectionLayer.SOLID).orElseThrow());
		assertSame(cutout, state.pipeline(ChunkSectionLayer.CUTOUT).orElseThrow());
		assertSame(water, state.pipeline(ChunkSectionLayer.TRANSLUCENT).orElseThrow());
		assertEquals(ProgramId.TerrainCutout, PackTerrainPipelines.programFor(ChunkSectionLayer.CUTOUT));
		assertEquals(ProgramId.Water, PackTerrainPipelines.programFor(ChunkSectionLayer.TRANSLUCENT));
	}

	@Test
	void generationRequirementsAndIdMapMismatchInvalidatesMetadata() {
		PackTerrainRenderState state = PackTerrainRenderState.active(4L, 5L, 6L, EXTRA, 7L,
			Optional.empty(), Optional.empty(), Optional.empty());

		assertTrue(state.metadataCompatible(5L, EXTRA.fingerprint(), 7L));
		assertFalse(state.metadataCompatible(4L, EXTRA.fingerprint(), 7L));
		assertFalse(state.metadataCompatible(5L, EXTRA.fingerprint() + 1L, 7L));
		assertFalse(state.metadataCompatible(5L, EXTRA.fingerprint(), 8L));
		assertTrue(EXTRA.blockIdMapAvailable());
	}

	@Test
	void realBlockIdsAndMetadataAddressesArePreserved() {
		PackTerrainJavaMesh metadata = new PackTerrainJavaMesh(EXTRA.sidecarMask(), 5L, EXTRA.fingerprint());
		metadata.append(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }));
		assertEquals(37, IDS.blockStateId(1));
		assertEquals(EXTRA.sidecarMask(), metadata.mask());
		assertEquals(16, metadata.size());
		assertEquals(0x01020304, metadata.data().getInt(0));
		assertTrue(PackTerrainUnits.validMetadata(64L, PackTerrainUnits.METADATA_STRIDE, 1));
		DirectUberBuffer.Entry missing = new DirectUberBuffer.Entry(null, 0L, null, null, TerrainVertex.BLOCK,
			null, 0L, -1L, 0L);
		assertFalse(missing.hasPackMetadata());
		assertThrows(IllegalArgumentException.class, () -> new UnitWriter(true).packUnit(1L, 0L, 1L,
			PackTerrainUnits.METADATA_STRIDE, 0, 0, 0, 0, 1.0F, 1, 0, 0, 0));
	}

	@Test
	void cpuAndGpuUnitFormatsMatchAtQuadBoundaries() {
		for (int quads : new int[] { 0, 1, 32, 33 }) {
			UnitWriter legacy = new UnitWriter();
			UnitWriter pack = new UnitWriter(true);
			if (quads > 0) {
				legacy.unit(0x1000L, 0x2000L, 4, 5, 6, 0.75F, quads, 1, TerrainVertex.BLOCK.ordinal(), 3);
				pack.packUnit(0x1000L, 0x2000L, 0x3000L, PackTerrainUnits.METADATA_STRIDE, 0,
					4, 5, 6, 0.75F, quads, 1, TerrainVertex.BLOCK.ordinal() | PackTerrainUnits.PACK_FLAG, 3);
			}
			assertEquals(quads == 0 ? 0 : 1, legacy.count());
			assertEquals(legacy.count(), pack.count());
			if (quads > 0) {
				ByteBuffer a = legacy.bytes();
				ByteBuffer b = pack.bytes();
				for (int offset : new int[] { 0, 8, 16, 20, 24, 28, 32, 36, 44 }) {
					assertEquals(a.get(offset), b.get(offset));
				}
				assertEquals(0x3000L, b.getLong(TerrainPreparation.PACK_UNIT_METADATA_OFFSET));
			}
		}
	}

	@Test
	void indexedAndNonIndexedUnitsKeepTheirIndexCodes() {
		UnitWriter writer = new UnitWriter();
		writer.unit(1L, 0L, 0, 0, 0, 1.0F, 1, 0, TerrainVertex.COMPACT.ordinal(), 0);
		writer.unit(1L, 2L, 0, 0, 0, 1.0F, 1, 1, TerrainVertex.COMPACT.ordinal(), 1);
		writer.unit(1L, 4L, 0, 0, 0, 1.0F, 1, 2, TerrainVertex.BLOCK.ordinal(), 2);
		ByteBuffer bytes = writer.bytes();
		assertEquals(0, bytes.getInt(TerrainPreparation.UNIT_INDEX_FORMAT_OFFSET));
		assertEquals(1, bytes.getInt(TerrainPreparation.UNIT_STRIDE + TerrainPreparation.UNIT_INDEX_FORMAT_OFFSET));
		assertEquals(2, bytes.getInt(2 * TerrainPreparation.UNIT_STRIDE + TerrainPreparation.UNIT_INDEX_FORMAT_OFFSET));
	}

	@Test
	void faceVisibilityIncludesBoundaryBuckets() {
		int near = FaceBuckets.visibleMask(new Vec3(0.0, 0.0, 0.0), 0, 0, 0);
		int far = FaceBuckets.visibleMask(new Vec3(16.0, 16.0, 16.0), 0, 0, 0);
		assertTrue((near & 1) != 0);
		assertTrue((near & (1 << FaceBuckets.UNASSIGNED)) != 0);
		assertTrue((far & (1 << (TerrainPreparation.FACE_COUNT - 1))) != 0);
		assertTrue((far & (1 << FaceBuckets.UNASSIGNED)) != 0);
	}

	@Test
	void recordsRetainLegacyLayoutAndPackSidecar() {
		int[] faces = { 1, 2, 3, 4, 5, 6, 7 };
		RecordWriter legacy = new RecordWriter();
		RecordWriter pack = new RecordWriter(true);
		legacy.update(1, 0x1000L, 0x2000L, 1, 2, 3, 33, 4, 5, faces);
		pack.update(1, 0x1000L, 0x2000L, 1, 2, 3, 33, 4, 5, faces, 0x3000L, PackTerrainUnits.METADATA_STRIDE);
		assertEquals(88, legacy.bytes().remaining());
		assertEquals(104, pack.bytes().remaining());
		assertEquals(0x3000L, pack.bytes().getLong(TerrainPreparation.PACK_RECORD_UPDATE_METADATA_OFFSET));
		assertArrayEquals(faces, new int[] { legacy.bytes().getInt(56), legacy.bytes().getInt(60), legacy.bytes().getInt(64),
			legacy.bytes().getInt(68), legacy.bytes().getInt(72), legacy.bytes().getInt(76), legacy.bytes().getInt(80) });
	}

	@Test
	void translucentSectionsReverseWhileLayersStayOpaqueFirst() {
		TerrainPreparation.SectionIdentity first = new TerrainPreparation.SectionIdentity(1, 1, 1);
		TerrainPreparation.SectionIdentity second = new TerrainPreparation.SectionIdentity(2, 2, 2);
		TerrainPreparation.SectionInput a = section(first, TerrainPreparation.Layer.TRANSLUCENT);
		TerrainPreparation.SectionInput b = section(second, TerrainPreparation.Layer.TRANSLUCENT);
		TerrainPreparation.Frame frame = new TerrainPreparation.Frame(1, 1,
			new TerrainPreparation.Camera(0, 0, 0, 0.0F, 0.0F, 0.0F), List.of(a, b),
			new TerrainPreparation.PreparationPolicy(false, true, false, false, false, false, false, 3));
		assertEquals(List.of(2, 1), frame.sections(TerrainPreparation.Layer.TRANSLUCENT).stream().map(s -> s.identity().index()).toList());
		assertEquals(List.of(TerrainPreparation.Layer.SOLID, TerrainPreparation.Layer.CUTOUT, TerrainPreparation.Layer.TRANSLUCENT), frame.layers());
	}

	@Test
	void cutoutUsesAlphaPrepassButTranslucentDoesNot() {
		TerrainPipelines pipelines = new TerrainPipelines(true, false, true, false, false, false);
		assertNotNull(pipelines.depthPrepass(ChunkSectionLayer.CUTOUT));
		assertEquals(null, pipelines.depthPrepass(ChunkSectionLayer.SOLID));
		assertEquals(null, pipelines.depthPrepass(ChunkSectionLayer.TRANSLUCENT));
		assertEquals(com.mojang.blaze3d.platform.CompareOp.EQUAL,
			pipelines.get(ChunkSectionLayer.CUTOUT).getDepthStencilState().depthTest());
	}

	@Test
	void meshEntriesKeepsVertexIndexAndPackMetadataSlotsSeparate() {
		FakeMeshEntries mesh = new FakeMeshEntries();
		DirectUberBuffer.Entry vertex = new DirectUberBuffer.Entry(null, 64L, null, null, TerrainVertex.BLOCK, null, 0L, -1L, 0L);
		DirectUberBuffer.Entry index = new DirectUberBuffer.Entry(null, 128L, null, null, TerrainVertex.BLOCK, null, 0L, -1L, 0L);
		PackTerrainJavaMesh metadata = new PackTerrainJavaMesh(EXTRA.sidecarMask(), 5L, EXTRA.fingerprint());
		mesh.mtlrender$setEntry(ChunkSectionLayer.SOLID.ordinal(), vertex);
		mesh.mtlrender$setEntry(3 + ChunkSectionLayer.SOLID.ordinal(), index);
		mesh.mtlrender$setPackTerrainMetadata(ChunkSectionLayer.SOLID.ordinal(), metadata);

		assertSame(vertex, mesh.mtlrender$entry(ChunkSectionLayer.SOLID.ordinal()));
		assertSame(index, mesh.mtlrender$entry(3 + ChunkSectionLayer.SOLID.ordinal()));
		assertSame(metadata, mesh.mtlrender$packTerrainMetadata(ChunkSectionLayer.SOLID.ordinal()));
		assertTrue(mesh.mtlrender$isCurrent());
	}

	@Test
	void reloadStateDropsSelectionsAndMetadata() {
		PackTerrainRenderState oldState = PackTerrainRenderState.active(1L, 2L, 3L, EXTRA, 4L,
			Optional.of(selection(1L, 2L)), Optional.empty(), Optional.empty());
		PackTerrainRenderState reloaded = PackTerrainRenderState.none();
		assertNotEqualsState(oldState, reloaded);
		assertFalse(reloaded.packActive());
		assertFalse(reloaded.metadataCompatible(2L, EXTRA.fingerprint(), 4L));
	}

	private static final class FakeMeshEntries implements MeshEntries {
		private final DirectUberBuffer.Entry[] entries = new DirectUberBuffer.Entry[9];
		private final PackTerrainJavaMesh[] metadata = new PackTerrainJavaMesh[3];
		private int section;

		@Override
		public DirectUberBuffer.Entry mtlrender$entry(int layer) {
			return entries[layer];
		}

		@Override
		public void mtlrender$setEntry(int layer, DirectUberBuffer.Entry entry) {
			entries[layer] = entry;
		}

		@Override
		public PackTerrainJavaMesh mtlrender$packTerrainMetadata(int layer) {
			return metadata[layer];
		}

		@Override
		public void mtlrender$setPackTerrainMetadata(int layer, PackTerrainJavaMesh value) {
			metadata[layer] = value;
		}

		@Override
		public dev.mikuteto.mtlrender.pathtracing.VoxelBrickSet mtlrender$voxelBricks() {
			return null;
		}

		@Override
		public void mtlrender$setVoxelBricks(dev.mikuteto.mtlrender.pathtracing.VoxelBrickSet bricks) {
		}

		@Override
		public boolean mtlrender$acceptUploadCallback(boolean sortedIndexBuffer) {
			return true;
		}

		@Override
		public boolean mtlrender$isCurrent() {
			return true;
		}

		@Override
		public int mtlrender$section() {
			return section;
		}

		@Override
		public void mtlrender$setSection(int index) {
			section = index;
		}
	}

	private static TerrainPreparation.SectionInput section(TerrainPreparation.SectionIdentity identity, TerrainPreparation.Layer layer) {
		TerrainPreparation.LayerInput input = new TerrainPreparation.LayerInput(identity, layer, 0, 0, 0, 1.0F,
			64L, 0L, TerrainPreparation.VertexFormat.BLOCK, TerrainPreparation.IndexFormat.NONE, 1,
			new int[TerrainPreparation.FACE_COUNT], null, 0, false);
		return new TerrainPreparation.SectionInput(identity, 0, 0, 0, 1.0F, List.of(input));
	}

	private static PackTerrainPipelines.Selection selection(long pipeline, long generation) {
		PackPipelineDesc descriptor = new PackPipelineDesc("v", "v", "f", "f", List.of(), null, List.of(), null, null,
			false, PackPipelineDesc.CullMode.NONE);
		return new PackTerrainPipelines.Selection(pipeline, descriptor, EXTRA, generation, PackTerrainPipelines.VertexMode.PACK, Optional.empty());
	}

	private static void assertNotEqualsState(PackTerrainRenderState first, PackTerrainRenderState second) {
		assertFalse(first.equals(second));
	}
}
