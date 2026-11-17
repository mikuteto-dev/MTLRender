package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class TerrainPreparationTest {
	@Test
	void preservesLayerAndSectionOrder() {
		TerrainPreparation.Frame frame = frame(4, 8, identity(1, 2, 3), identity(2, 3, 4), identity(3, 4, 5));

		assertEquals(
			List.of(TerrainPreparation.Layer.SOLID, TerrainPreparation.Layer.CUTOUT, TerrainPreparation.Layer.TRANSLUCENT),
			frame.layers()
		);
		assertEquals(List.of(1, 2, 3), indexes(frame.sections(TerrainPreparation.Layer.SOLID)));
		assertEquals(List.of(1, 2, 3), indexes(frame.sections(TerrainPreparation.Layer.CUTOUT)));
		assertEquals(List.of(3, 2, 1), indexes(frame.sections(TerrainPreparation.Layer.TRANSLUCENT)));
	}

	@Test
	void calculatesUnitAndRecordSlots() {
		TerrainPreparation.SectionIdentity identity = identity(7, 2, 3);
		TerrainPreparation.LayerInput input = layer(identity, TerrainPreparation.Layer.CUTOUT);

		assertEquals(29, input.unitSlot());
		assertEquals(22, input.recordSlot());
		assertEquals(29, TerrainPreparation.unitSlot(7, TerrainPreparation.Layer.CUTOUT));
		assertEquals(22, TerrainPreparation.recordSlot(7, TerrainPreparation.Layer.CUTOUT));

		TerrainPreparation.Unit unit = new TerrainPreparation.Unit(
			4, 8, identity, TerrainPreparation.Layer.CUTOUT, 10L, 0L,
			112, 64, -16, 0.75F, 2, TerrainPreparation.IndexFormat.NONE,
			TerrainPreparation.VertexFormat.BLOCK, 29
		);
		TerrainPreparation.UnitBatch batch = new TerrainPreparation.UnitBatch(4, 8, List.of(unit), new int[] {0, 1, 1}, new int[] {0, 1, 0}, 2);
		assertEquals(1, batch.first(TerrainPreparation.Layer.CUTOUT));
		assertEquals(1, batch.count(TerrainPreparation.Layer.CUTOUT));
	}

	@Test
	void rejectsSupersededFrameCameraAndMesh() {
		TerrainPreparation.SectionIdentity original = identity(3, 4, 5);
		TerrainPreparation.Unit unit = new TerrainPreparation.Unit(
			10, 20, original, TerrainPreparation.Layer.SOLID, 1L, 0L,
			0, 0, 0, 1.0F, 1, TerrainPreparation.IndexFormat.NONE,
			TerrainPreparation.VertexFormat.BLOCK, 12
		);
		TerrainPreparation.UnitBatch batch = new TerrainPreparation.UnitBatch(10, 20, List.of(unit), new int[] {0, 1, 1}, new int[] {1, 0, 0}, 1);

		assertTrue(batch.isCurrent(frame(10, 20, original)));
		assertFalse(batch.isCurrent(frame(11, 20, original)));
		assertFalse(batch.isCurrent(frame(10, 21, original)));
		assertFalse(batch.isCurrent(frame(10, 20, identity(3, 4, 6))));
	}

	@Test
	void keepsLayoutSizesAndDefensiveCopies() {
		assertEquals(48, TerrainPreparation.UNIT_STRIDE);
		assertEquals(64, TerrainPreparation.PACK_UNIT_STRIDE);
		assertEquals(80, TerrainPreparation.RECORD_STRIDE);
		assertEquals(96, TerrainPreparation.PACK_RECORD_STRIDE);
		assertEquals(88, TerrainPreparation.RECORD_UPDATE_STRIDE);
		assertEquals(104, TerrainPreparation.PACK_RECORD_UPDATE_STRIDE);
		assertEquals(8, TerrainPreparation.VISIBLE_ENTRY_STRIDE);
		assertEquals(0, TerrainPreparation.UNIT_VERTICES_OFFSET);
		assertEquals(8, TerrainPreparation.UNIT_INDICES_OFFSET);
		assertEquals(16, TerrainPreparation.UNIT_ORIGIN_X_OFFSET);
		assertEquals(28, TerrainPreparation.UNIT_VISIBILITY_OFFSET);
		assertEquals(32, TerrainPreparation.UNIT_QUADS_OFFSET);
		assertEquals(36, TerrainPreparation.UNIT_INDEX_FORMAT_OFFSET);
		assertEquals(40, TerrainPreparation.UNIT_VERTEX_FORMAT_OFFSET);
		assertEquals(44, TerrainPreparation.UNIT_SLOT_OFFSET);
		assertEquals(0, TerrainPreparation.RECORD_VERTICES_OFFSET);
		assertEquals(8, TerrainPreparation.RECORD_INDICES_OFFSET);
		assertEquals(16, TerrainPreparation.RECORD_ORIGIN_X_OFFSET);
		assertEquals(28, TerrainPreparation.RECORD_VISIBILITY_OFFSET);
		assertEquals(32, TerrainPreparation.RECORD_QUADS_OFFSET);
		assertEquals(36, TerrainPreparation.RECORD_FLAGS_OFFSET);
		assertEquals(40, TerrainPreparation.RECORD_SLOT_OFFSET);
		assertEquals(44, TerrainPreparation.RECORD_RESERVED_OFFSET);
		assertEquals(48, TerrainPreparation.RECORD_FACES_OFFSET);
		assertEquals(76, TerrainPreparation.RECORD_PADDING_OFFSET);
		assertEquals(80, TerrainPreparation.PACK_RECORD_METADATA_OFFSET);
		assertEquals(88, TerrainPreparation.PACK_RECORD_METADATA_STRIDE_OFFSET);
		assertEquals(0, TerrainPreparation.RECORD_UPDATE_SLOT_OFFSET);
		assertEquals(4, TerrainPreparation.RECORD_UPDATE_RESERVED_OFFSET);
		assertEquals(8, TerrainPreparation.RECORD_UPDATE_RECORD_OFFSET);
		assertEquals(48, TerrainPreparation.RECORD_UPDATE_RECORD_SLOT_OFFSET);
		assertEquals(84, TerrainPreparation.RECORD_UPDATE_PADDING_OFFSET);
		assertEquals(0, TerrainPreparation.VISIBLE_ENTRY_INDEX_OFFSET);
		assertEquals(4, TerrainPreparation.VISIBLE_ENTRY_VISIBILITY_OFFSET);
		assertEquals(TerrainPreparation.UNIT_STRIDE, TerrainShaderPatch.UNIT_STRIDE);
		assertEquals(7, TerrainPreparation.FACE_COUNT);

		int[] faces = {1, 2, 3, 4, 5, 6, 7};
		TerrainPreparation.LayerInput input = layer(identity(1, 1, 1), TerrainPreparation.Layer.SOLID, faces);
		faces[0] = 99;
		assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6, 7}, input.faceCounts());
	}

	@Test
	void makesExpansionPolicyExplicit() {
		TerrainPreparation.PreparationPolicy gpu = new TerrainPreparation.PreparationPolicy(true, true, false, false, true, false, false, 3);
		TerrainPreparation.PreparationPolicy stats = new TerrainPreparation.PreparationPolicy(true, true, false, true, true, false, false, 3);
		TerrainPreparation.PreparationPolicy cpu = new TerrainPreparation.PreparationPolicy(true, true, true, false, true, false, false, 3);

		assertTrue(gpu.gpuExpansionEnabled());
		assertFalse(stats.gpuExpansionEnabled());
		assertFalse(cpu.gpuExpansionEnabled());
	}

	private static TerrainPreparation.Frame frame(long generation, long cameraSequence, TerrainPreparation.SectionIdentity... identities) {
		return new TerrainPreparation.Frame(
			generation,
			cameraSequence,
			new TerrainPreparation.Camera(0, 0, 0, 0.25F, -0.5F, 0.75F),
			java.util.Arrays.stream(identities).map(identity -> section(identity, 1.0F)).toList(),
			new TerrainPreparation.PreparationPolicy(true, true, false, false, true, false, false, 3)
		);
	}

	private static TerrainPreparation.SectionInput section(TerrainPreparation.SectionIdentity identity, float visibility) {
		return new TerrainPreparation.SectionInput(
			identity,
			identity.index() * 16,
			0,
			0,
			visibility,
			List.of(
				layer(identity, TerrainPreparation.Layer.SOLID, visibility, new int[TerrainPreparation.FACE_COUNT]),
				layer(identity, TerrainPreparation.Layer.CUTOUT, visibility, new int[TerrainPreparation.FACE_COUNT]),
				layer(identity, TerrainPreparation.Layer.TRANSLUCENT, visibility, new int[TerrainPreparation.FACE_COUNT])
			)
		);
	}

	private static TerrainPreparation.LayerInput layer(TerrainPreparation.SectionIdentity identity, TerrainPreparation.Layer layer) {
		return layer(identity, layer, new int[TerrainPreparation.FACE_COUNT]);
	}

	private static TerrainPreparation.LayerInput layer(TerrainPreparation.SectionIdentity identity, TerrainPreparation.Layer layer, int[] faces) {
		return layer(identity, layer, 1.0F, faces);
	}

	private static TerrainPreparation.LayerInput layer(
		TerrainPreparation.SectionIdentity identity,
		TerrainPreparation.Layer layer,
		float visibility,
		int[] faces
	) {
		return new TerrainPreparation.LayerInput(
			identity,
			layer,
			identity.index() * 16,
			0,
			0,
			visibility,
			1L,
			0L,
			TerrainPreparation.VertexFormat.BLOCK,
			TerrainPreparation.IndexFormat.NONE,
			1,
			faces,
			null,
			0,
			false
		);
	}

	private static TerrainPreparation.SectionIdentity identity(int index, long meshGeneration, long meshSequence) {
		return new TerrainPreparation.SectionIdentity(index, meshGeneration, meshSequence);
	}

	private static List<Integer> indexes(List<TerrainPreparation.SectionInput> sections) {
		return sections.stream().map(section -> section.identity().index()).toList();
	}
}
