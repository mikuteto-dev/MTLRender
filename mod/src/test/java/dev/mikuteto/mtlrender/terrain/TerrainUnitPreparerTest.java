package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

class TerrainUnitPreparerTest {
	@Test
	void writesDisjointFaceRunsAndDiagnostics() {
		TerrainPreparation.SectionIdentity identity = identity(1);
		int[] faces = {2, 3, 4, 0, 0, 0, 0};
		int[] merged = {1, 2, 3, 0, 0, 0, 0};
		TerrainPreparation.Frame frame = frame(
			new TerrainPreparation.Camera(100, -100, 100, 0.0F, 0.0F, 0.0F),
			new TerrainPreparation.SectionInput(identity, 16, 0, 0, 0.75F,
				List.of(layer(identity, TerrainPreparation.Layer.SOLID, TerrainPreparation.IndexFormat.NONE, 9, faces, merged, false))),
			policy(true, false, false, false, false)
		);

		TerrainUnitPreparer.Prepared prepared = TerrainUnitPreparer.prepare(frame);
		assertEquals(2, prepared.batch().units().size());
		assertEquals(2, prepared.batch().units().get(0).quadCount());
		assertEquals(4, prepared.batch().units().get(1).quadCount());
		assertEquals(6, prepared.batch().quads());
		assertEquals(4, prepared.diagnostics().mergedQuads());
		assertEquals(2, prepared.diagnostics().layers().get(0).units());
		assertEquals(0x100L, prepared.batch().units().get(0).verticesAddress());
		assertEquals(0x100L + 5L * 4L * TerrainVertex.BLOCK.size, prepared.batch().units().get(1).verticesAddress());
		assertEquals(0x100L, prepared.units().bytes().getLong(TerrainPreparation.UNIT_VERTICES_OFFSET));
	}

	@Test
	void preservesLayerOrderAndReversesTranslucentSections() {
		TerrainPreparation.SectionIdentity first = identity(1);
		TerrainPreparation.SectionIdentity second = identity(2);
		TerrainPreparation.Frame frame = frame(
			new TerrainPreparation.Camera(0, 0, 0, 0.0F, 0.0F, 0.0F),
			List.of(
				new TerrainPreparation.SectionInput(first, 16, 0, 0, 0.75F, List.of(layer(first, TerrainPreparation.Layer.TRANSLUCENT, 0x10L, 1))),
				new TerrainPreparation.SectionInput(second, 32, 0, 0, 0.75F, List.of(layer(second, TerrainPreparation.Layer.TRANSLUCENT, 0x20L, 1)))
			),
			policy(false, false, false, false, false)
		);

		TerrainUnitPreparer.Prepared prepared = TerrainUnitPreparer.prepare(frame);
		assertEquals(List.of(2, 1), prepared.batch().units().stream().map(unit -> unit.section().index()).toList());
		assertEquals(0, prepared.batch().first(TerrainPreparation.Layer.SOLID));
		assertEquals(0, prepared.batch().count(TerrainPreparation.Layer.SOLID));
		assertEquals(0, prepared.batch().first(TerrainPreparation.Layer.TRANSLUCENT));
		assertEquals(2, prepared.batch().count(TerrainPreparation.Layer.TRANSLUCENT));
	}

	@Test
	void keepsLayersWithoutFaceBucketsVisible() {
		TerrainPreparation.SectionIdentity identity = identity(3);
		TerrainPreparation.Frame frame = frame(new TerrainPreparation.Camera(0, 0, 0, 0.0F, 0.0F, 0.0F),
			new TerrainPreparation.SectionInput(identity, 48, 0, 0, 0.75F,
				List.of(layer(identity, TerrainPreparation.Layer.CUTOUT, 0x300L, 5))),
			policy(true, false, false, false, false));

		TerrainUnitPreparer.Prepared prepared = TerrainUnitPreparer.prepare(frame);
		assertEquals(1, prepared.batch().units().size());
		assertEquals(5, prepared.batch().quads());
	}

	@Test
	void preservesCustomIndicesAndFormats() {
		TerrainPreparation.SectionIdentity identity = identity(3);
		TerrainPreparation.LayerInput input = layer(identity, TerrainPreparation.Layer.CUTOUT, TerrainPreparation.IndexFormat.SHORT, 33,
			new int[TerrainPreparation.FACE_COUNT], null, false, TerrainPreparation.VertexFormat.COMPACT, 0x300L, 0x500L);
		TerrainPreparation.Frame frame = frame(new TerrainPreparation.Camera(0, 0, 0, 0.0F, 0.0F, 0.0F),
			new TerrainPreparation.SectionInput(identity, 48, 0, 0, 0.75F, List.of(input)),
			policy(false, true, false, false, false));

		TerrainUnitPreparer.Prepared prepared = TerrainUnitPreparer.prepare(frame);
		assertEquals(2, prepared.batch().units().size());
		TerrainPreparation.Unit unit = prepared.batch().units().get(1);
		assertEquals(0x300L, unit.verticesAddress());
		assertEquals(0x500L + 32L * 6L * 2L, unit.indicesAddress());
		assertEquals(TerrainPreparation.IndexFormat.SHORT, unit.indexFormat());
		assertEquals(TerrainPreparation.VertexFormat.COMPACT, unit.vertexFormat());
	}

	@Test
	void appliesFarAndPlantDroppingAndRunsConcurrently() throws Exception {
		TerrainPreparation.SectionIdentity identity = identity(25);
		int[] faces = {0, 0, 0, 2, 0, 0, 0};
		TerrainPreparation.SectionInput section = new TerrainPreparation.SectionInput(identity, 400, 0, 0, 0.75F,
			List.of(layer(identity, TerrainPreparation.Layer.SOLID, TerrainPreparation.IndexFormat.NONE, 2, faces, null, true)));
		TerrainPreparation.Frame frame = frame(new TerrainPreparation.Camera(0, 0, 0, 0.0F, 0.0F, 0.0F), section,
			policy(true, true, false, true, false));
		var executor = Executors.newFixedThreadPool(4);
		try {
			List<Callable<TerrainUnitPreparer.Prepared>> tasks = java.util.stream.IntStream.range(0, 8)
				.mapToObj(value -> (Callable<TerrainUnitPreparer.Prepared>) () -> TerrainUnitPreparer.prepare(frame)).toList();
			for (TerrainUnitPreparer.Prepared prepared : executor.invokeAll(tasks).stream().map(future -> {
				try {
					return future.get();
				} catch (Exception exception) {
					throw new IllegalStateException(exception);
				}
			}).toList()) {
				assertEquals(0, prepared.batch().units().size());
				assertEquals(0, prepared.batch().quads());
			}
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void resultBytesMatchUnitValues() {
		TerrainPreparation.SectionIdentity identity = identity(5);
		TerrainPreparation.Frame frame = frame(new TerrainPreparation.Camera(0, 0, 0, 0.0F, 0.0F, 0.0F),
			new TerrainPreparation.SectionInput(identity, 80, 0, 0, 0.75F,
				List.of(layer(identity, TerrainPreparation.Layer.SOLID, 0x100L, 1))),
			policy(false, false, false, false, false));
		TerrainUnitPreparer.Prepared prepared = TerrainUnitPreparer.prepare(frame);
		TerrainPreparation.Unit unit = prepared.batch().units().get(0);
		ByteBuffer bytes = prepared.units().bytes();
		assertEquals(unit.verticesAddress(), bytes.getLong(0));
		assertEquals(unit.originX(), bytes.getInt(TerrainPreparation.UNIT_ORIGIN_X_OFFSET));
		assertEquals(unit.visibility(), bytes.getFloat(TerrainPreparation.UNIT_VISIBILITY_OFFSET));
		assertEquals(unit.quadCount(), bytes.getInt(TerrainPreparation.UNIT_QUADS_OFFSET));
		assertEquals(unit.slot(), bytes.getInt(TerrainPreparation.UNIT_SLOT_OFFSET));
		assertArrayEquals(new byte[0], new byte[bytes.remaining() - TerrainPreparation.UNIT_STRIDE]);
		assertTrue(bytes.isReadOnly());
	}

	private static TerrainPreparation.Frame frame(TerrainPreparation.Camera camera, TerrainPreparation.SectionInput section,
		TerrainPreparation.PreparationPolicy policy) {
		return frame(camera, List.of(section), policy);
	}

	private static TerrainPreparation.Frame frame(TerrainPreparation.Camera camera, List<TerrainPreparation.SectionInput> sections,
		TerrainPreparation.PreparationPolicy policy) {
		return new TerrainPreparation.Frame(7, 9, camera, sections, policy);
	}

	private static TerrainPreparation.PreparationPolicy policy(boolean faceCulling, boolean skipFar, boolean skipPlants,
		boolean lodDropPlants, boolean skipSolid) {
		return new TerrainPreparation.PreparationPolicy(false, true, true, true, faceCulling, true, lodDropPlants, 3,
			skipSolid, false, false, skipFar, skipPlants);
	}

	private static TerrainPreparation.LayerInput layer(TerrainPreparation.SectionIdentity identity, TerrainPreparation.Layer layer,
		long vertices, int quads) {
		return layer(identity, layer, TerrainPreparation.IndexFormat.NONE, quads, new int[TerrainPreparation.FACE_COUNT], null, false,
			TerrainPreparation.VertexFormat.BLOCK, vertices, 0L);
	}

	private static TerrainPreparation.LayerInput layer(TerrainPreparation.SectionIdentity identity, TerrainPreparation.Layer layer,
		TerrainPreparation.IndexFormat index, int quads, int[] faces, int[] merged, boolean lod) {
		return layer(identity, layer, index, quads, faces, merged, lod, TerrainPreparation.VertexFormat.BLOCK, 0x100L, 0x200L);
	}

	private static TerrainPreparation.LayerInput layer(TerrainPreparation.SectionIdentity identity, TerrainPreparation.Layer layer,
		TerrainPreparation.IndexFormat index, int quads, int[] faces, int[] merged, boolean lod,
		TerrainPreparation.VertexFormat format, long vertices, long indices) {
		return new TerrainPreparation.LayerInput(identity, layer, identity.index() * 16, 0, 0, 0.75F, vertices, indices, format, index,
			quads, faces, merged, index.code() | format.ordinal() << 2, lod);
	}

	private static TerrainPreparation.SectionIdentity identity(int index) {
		return new TerrainPreparation.SectionIdentity(index, 1, index);
	}
}
