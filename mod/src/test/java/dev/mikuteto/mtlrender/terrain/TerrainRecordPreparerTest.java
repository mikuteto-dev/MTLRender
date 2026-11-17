package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import org.junit.jupiter.api.Test;

class TerrainRecordPreparerTest {
	@Test
	void writesZeroRecordsForMissingLayers() {
		TerrainPreparation.SectionIdentity id = id(2, 1, 1);
		TerrainPreparation.Frame frame = frame(1, 0, policy(false), section(id, List.of(layer(id, TerrainPreparation.Layer.SOLID, false))));
		TerrainRecordPreparer.Result result = new TerrainRecordPreparer().prepare(frame, new TerrainRecordPreparer.CacheState(0, 0));

		assertEquals(3, result.batch().updates().size());
		assertEquals(0, result.batch().updates().get(1).quadCount());
		assertEquals(0, result.batch().updates().get(2).quadCount());
	}

	@Test
	void allDirtyInitializationPublishesEveryLayer() {
		TerrainPreparation.SectionIdentity id = id(1, 1, 1);
		TerrainPreparation.Frame frame = frame(4, 2, policy(false), section(id, List.of(
			layer(id, TerrainPreparation.Layer.SOLID, false),
			layer(id, TerrainPreparation.Layer.CUTOUT, false),
			layer(id, TerrainPreparation.Layer.TRANSLUCENT, false))));
		TerrainRecordPreparer.Result result = new TerrainRecordPreparer().prepare(frame, new TerrainRecordPreparer.CacheState(0, 0));

		assertEquals(3, result.batch().updates().size());
		assertEquals(3, result.batch().updates().get(0).recordSlot());
		assertEquals(4, result.batch().updates().get(0).unitSlot());
	}

	@Test
	void meshAndIndexIdentityChangesAreNotAcknowledgedAsClean() {
		TerrainRecordPreparer preparer = new TerrainRecordPreparer();
		TerrainPreparation.SectionIdentity first = id(0, 1, 1);
		TerrainPreparation.SectionIdentity second = id(0, 2, 2);
		TerrainPreparation.Frame oldFrame = frame(1, 0, policy(false), section(first, List.of(layer(first, TerrainPreparation.Layer.SOLID, false))));
		TerrainRecordPreparer.CacheState state = preparer.prepare(oldFrame, new TerrainRecordPreparer.CacheState(0, 0)).apply(new TerrainRecordPreparer.CacheState(0, 0));
		TerrainPreparation.Frame newFrame = frame(2, 0, policy(false), section(second, List.of(layer(second, TerrainPreparation.Layer.SOLID, false))));
		TerrainRecordPreparer.Result result = preparer.prepare(newFrame, state);

		assertEquals(3, result.batch().updates().size());
		assertEquals(2, result.generation());
	}

	@Test
	void lodRingCrossingUsesMergedCountsAndPlantFlag() {
		TerrainPreparation.SectionIdentity id = id(0, 1, 1);
		TerrainPreparation.LayerInput input = layer(id, TerrainPreparation.Layer.SOLID, true);
		TerrainPreparation.Frame frame = frame(1, 0, policy(true), section(id, List.of(input)));
		TerrainRecordPreparer.Result result = new TerrainRecordPreparer().prepare(frame, new TerrainRecordPreparer.CacheState(0, 0));

		assertEquals(1, result.batch().updates().get(0).quadCount());
		assertTrue((result.batch().updates().get(0).flags() & (1 << 5)) != 0);
	}

	@Test
	void lodFallbackKeepsNormalQuadCountAndFlags() {
		TerrainPreparation.SectionIdentity id = id(0, 1, 1);
		TerrainPreparation.Frame frame = frame(1, 0, policy(true), sectionAt(id, 400,
			List.of(layerAt(id, TerrainPreparation.Layer.SOLID, true, 400))));
		TerrainPreparation.LayerInput input = frame.sections().getFirst().layers().getFirst();
		TerrainPreparation.LayerInput fallback = new TerrainPreparation.LayerInput(input.section(), input.layer(), input.originX(), input.originY(), input.originZ(),
			input.visibility(), input.verticesAddress(), input.indicesAddress(), input.vertexFormat(), input.indexFormat(), input.quadCount(),
			input.faceCounts(), null, input.flags(), false);
		TerrainPreparation.Frame fallbackFrame = frame(2, 0, policy(true), sectionAt(id, 400, List.of(fallback)));
		TerrainRecordPreparer.Result result = new TerrainRecordPreparer().prepare(fallbackFrame, new TerrainRecordPreparer.CacheState(0, 0));

		assertEquals(2, result.batch().updates().getFirst().quadCount());
		assertEquals(0, result.batch().updates().getFirst().flags() & (1 << 5));
	}

	@Test
	void recordAndUnitSlotsAreUniqueAcrossLayers() {
		TerrainPreparation.SectionIdentity id = id(0, 1, 1);
		TerrainPreparation.Frame frame = frame(1, 0, policy(false), section(id, List.of(
			layer(id, TerrainPreparation.Layer.SOLID, false),
			layer(id, TerrainPreparation.Layer.CUTOUT, false),
			layer(id, TerrainPreparation.Layer.TRANSLUCENT, false))));
		TerrainRecordPreparer.Result result = new TerrainRecordPreparer().prepare(frame, new TerrainRecordPreparer.CacheState(0, 0));

		assertEquals(3, result.batch().updates().stream().map(TerrainPreparation.RecordUpdate::recordSlot).distinct().count());
		assertEquals(3, result.batch().updates().stream().map(TerrainPreparation.RecordUpdate::unitSlot).distinct().count());
	}

	@Test
	void skipFarRemovesVisibleEntryButKeepsRecordUpdate() {
		TerrainPreparation.SectionIdentity id = id(0, 1, 1);
		TerrainPreparation.Frame frame = frame(1, 0, policy(true, true), sectionAt(id, 400, List.of(layerAt(id, TerrainPreparation.Layer.SOLID, false, 400))));
		TerrainRecordPreparer.Result result = new TerrainRecordPreparer().prepare(frame, new TerrainRecordPreparer.CacheState(0, 0));

		assertEquals(0, result.visible().length);
		assertEquals(3, result.batch().updates().size());
	}

	@Test
	void updateAndVisibleBytesUseNativeLayouts() {
		TerrainPreparation.SectionIdentity id = id(3, 1, 1);
		TerrainPreparation.Frame frame = frame(1, 0, policy(false), sectionAt(id, 48, List.of(layer(id, TerrainPreparation.Layer.SOLID, false))));
		TerrainRecordPreparer.Result result = new TerrainRecordPreparer().prepare(frame, new TerrainRecordPreparer.CacheState(0, 0));
		ByteBuffer updates = ByteBuffer.wrap(result.updates()).order(ByteOrder.nativeOrder());
		ByteBuffer visible = ByteBuffer.wrap(result.visible()).order(ByteOrder.nativeOrder());

		assertEquals(9, updates.getInt(0));
		assertEquals(12, updates.getInt(8 + TerrainPreparation.RECORD_SLOT_OFFSET));
		assertEquals(3, visible.getInt(0));
		assertEquals(1.0F, visible.getFloat(4));
	}

	@Test
	void staleResultCannotClearNewDirtyState() {
		TerrainPreparation.SectionIdentity id = id(0, 1, 1);
		TerrainPreparation.Frame frame = frame(1, 0, policy(false), section(id, List.of(layer(id, TerrainPreparation.Layer.SOLID, false))));
		TerrainRecordPreparer preparer = new TerrainRecordPreparer();
		TerrainRecordPreparer.CacheState initial = new TerrainRecordPreparer.CacheState(0, 1);
		TerrainRecordPreparer.Result result = preparer.prepare(frame, initial);
		TerrainRecordPreparer.CacheState newer = initial.markDirty(0);

		assertFalse(result.isCurrent(newer));
		assertTrue(result.apply(newer).dirty(0));
	}

	private static TerrainPreparation.PreparationPolicy policy(boolean lod) {
		return new TerrainPreparation.PreparationPolicy(true, true, false, false, true, lod, true, 0);
	}

	private static TerrainPreparation.PreparationPolicy policy(boolean lod, boolean skipFar) {
		return new TerrainPreparation.PreparationPolicy(true, true, false, false, true, lod, true, 0, skipFar);
	}

	private static TerrainPreparation.Frame frame(long generation, long cameraSequence, TerrainPreparation.PreparationPolicy policy, TerrainPreparation.SectionInput section) {
		return new TerrainPreparation.Frame(generation, cameraSequence, new TerrainPreparation.Camera(0, 0, 0, 0, 0, 0), List.of(section), policy);
	}

	private static TerrainPreparation.SectionInput section(TerrainPreparation.SectionIdentity id, List<TerrainPreparation.LayerInput> layers) {
		return sectionAt(id, id.index() * 16, layers);
	}

	private static TerrainPreparation.SectionInput sectionAt(TerrainPreparation.SectionIdentity id, int x, List<TerrainPreparation.LayerInput> layers) {
		return new TerrainPreparation.SectionInput(id, x, 0, 0, 1.0F, layers);
	}

	private static TerrainPreparation.LayerInput layer(TerrainPreparation.SectionIdentity id, TerrainPreparation.Layer layer, boolean lod) {
		return layerAt(id, layer, lod, id.index() * 16);
	}

	private static TerrainPreparation.LayerInput layerAt(TerrainPreparation.SectionIdentity id, TerrainPreparation.Layer layer, boolean lod, int x) {
		return new TerrainPreparation.LayerInput(id, layer, x, 0, 0, 1.0F, 0x1000L, 0L,
			TerrainPreparation.VertexFormat.BLOCK, TerrainPreparation.IndexFormat.NONE, 2,
			new int[] {1, 1, 0, 0, 0, 0, 0}, lod ? new int[] {1, 0, 0, 0, 0, 0, 0} : null, 0, lod);
	}

	private static TerrainPreparation.SectionIdentity id(int index, long generation, long sequence) {
		return new TerrainPreparation.SectionIdentity(index, generation, sequence);
	}
}
