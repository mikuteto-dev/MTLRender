package dev.mikuteto.mtlrender.terrain;

import java.util.Arrays;

final class TerrainRecordPreparer {
	private static final int LOD_FLAGS = 1 << 5;

	Result prepare(TerrainPreparation.Frame frame, CacheState state) {
		int sectionCount = Math.max(frame.sections().stream().mapToInt(section -> section.identity().index()).max().orElse(-1) + 1, state.sectionCount());
		CacheState next = state.resize(sectionCount);
		RecordWriter records = new RecordWriter(frame.policy().packRecords());
		VisibleWriter visible = new VisibleWriter();
		int[] layerUnits = new int[TerrainPreparation.LAYER_COUNT];
		java.util.ArrayList<TerrainPreparation.RecordUpdate> updates = new java.util.ArrayList<>();
		java.util.ArrayList<TerrainPreparation.VisibleEntry> visibleEntries = new java.util.ArrayList<>();
		int capacity = 0;
		for (TerrainPreparation.SectionInput section : frame.sections()) {
			int index = section.identity().index();
			boolean lod = selectedLod(frame, section);
			if (lod != next.cachedLod[index]) {
				next = next.withDirty(index, true).withCachedLod(index, lod);
			}
			boolean identityChanged = nextIdentity(next, index) == null || !nextIdentity(next, index).equals(section.identity());
			if (identityChanged) {
				next = next.withDirty(index, true);
			}
			if (next.dirty(index) || next.allDirty()) {
				int sectionUnits = 0;
				for (TerrainPreparation.Layer layer : frame.layers()) {
					TerrainPreparation.LayerInput input = section.layer(layer).orElse(null);
					int units = writeRecord(records, input, section, layer, lod, frame.policy().lodDropPlants(), frame.policy().packRecords(), updates);
					next = next.withCachedLayerUnits(index, layer, input == null || units == 0 ? 0 : units - 3);
					sectionUnits += units;
				}
				next = next.withCachedUnits(index, sectionUnits).withIdentity(index, section.identity()).withDirty(index, false);
			}
			if (!skipFar(frame, section)) {
				visible.entry(index, section.visibility());
				visibleEntries.add(new TerrainPreparation.VisibleEntry(section.identity(), section.visibility()));
				for (TerrainPreparation.Layer layer : frame.layers()) {
					layerUnits[layer.ordinal()] += next.cachedLayerUnits(index, layer);
				}
				capacity += next.cachedUnits(index);
			}
		}
		TerrainPreparation.RecordBatch batch = new TerrainPreparation.RecordBatch(
				frame.generation(), frame.cameraSequence(), sectionCount, cullMask(frame.policy()), layerMask(frame.policy()),
				options(frame.policy()), capacity, updates, visibleEntries);
		return new Result(frame.generation(), state.generation(), batch, records.snapshot(), visible.snapshot(), next.withAllDirty(false).withGeneration(frame.generation()), layerUnits);
	}

	private static int writeRecord(
			RecordWriter records,
			TerrainPreparation.LayerInput input,
			TerrainPreparation.SectionInput section,
			TerrainPreparation.Layer layer,
			boolean lod,
			boolean lodDropPlants,
		boolean packRecords,
		java.util.List<TerrainPreparation.RecordUpdate> updates
	) {
		int recordSlot = section.identity().index() * 3 + layer.ordinal();
		if (input == null) {
			records.update(recordSlot, 0L, 0L, 0, 0, 0, 0, 0, section.identity().index() * 4 + layer.ordinal(), null);
			updates.add(new TerrainPreparation.RecordUpdate(section.identity(), layer, 0L, 0L, TerrainPreparation.VertexFormat.BLOCK, TerrainPreparation.IndexFormat.NONE, 0, 0, 0, 0, 0, recordSlot, section.identity().index() * 4 + layer.ordinal(), null));
			return 0;
		}
		boolean useLod = lod && input.lod() && input.indexFormat() == TerrainPreparation.IndexFormat.NONE && input.mergedCounts() != null;
		int[] faces = useLod ? input.mergedCounts() : input.faceCounts();
		int quads = useLod ? sum(faces) : input.quadCount();
		if (packRecords && (input.metadataAddress() == 0L || input.metadataStride() != PackTerrainUnits.METADATA_STRIDE
			|| input.metadataGeneration() < 0L)) {
			records.update(recordSlot, 0L, 0L, 0, 0, 0, 0, 0, section.identity().index() * 4 + layer.ordinal(), null, 0L, 0);
			updates.add(new TerrainPreparation.RecordUpdate(section.identity(), layer, 0L, 0L, TerrainPreparation.VertexFormat.BLOCK,
				TerrainPreparation.IndexFormat.NONE, 0, 0, 0, 0, 0, recordSlot, section.identity().index() * 4 + layer.ordinal(), null, 0L, 0, -1L, 0L));
			return 0;
		}
		int flags = input.flags();
		if (useLod && lodDropPlants) {
			flags |= LOD_FLAGS;
		}
		records.update(recordSlot, input.verticesAddress(), input.indicesAddress(), input.originX(), input.originY(), input.originZ(), quads, flags,
				section.identity().index() * 4 + layer.ordinal(), faces, input.metadataAddress(), input.metadataStride());
		updates.add(new TerrainPreparation.RecordUpdate(section.identity(), layer, input.verticesAddress(), input.indicesAddress(), input.vertexFormat(), input.indexFormat(), input.originX(), input.originY(), input.originZ(), quads, flags, recordSlot, section.identity().index() * 4 + layer.ordinal(), faces,
			input.metadataAddress(), input.metadataStride(), input.metadataGeneration(), input.metadataRequirements()));
		return (quads + TerrainPreparation.QUADS_PER_UNIT - 1) / TerrainPreparation.QUADS_PER_UNIT + 3;
	}

	private static TerrainPreparation.SectionIdentity nextIdentity(CacheState state, int index) {
		return state.identity(index);
	}

	private static int sum(int[] values) {
		int result = 0;
		for (int value : values) {
			result += value;
		}
		return result;
	}

	private static boolean selectedLod(TerrainPreparation.Frame frame, TerrainPreparation.SectionInput section) {
		if (!frame.policy().lod() || ring(frame.camera(), section.originX(), section.originY(), section.originZ()) < frame.policy().lodMinRing()) {
			return false;
		}
		return section.layers().stream().anyMatch(TerrainPreparation.LayerInput::lod);
	}

	private static boolean skipFar(TerrainPreparation.Frame frame, TerrainPreparation.SectionInput section) {
		return frame.policy().skipFar() && ring(frame.camera(), section.originX(), section.originY(), section.originZ()) == 3;
	}

	private static int ring(TerrainPreparation.Camera camera, int x, int y, int z) {
		long dx = (long) camera.blockX() - x;
		long dy = (long) camera.blockY() - y;
		long dz = (long) camera.blockZ() - z;
		long distance = dx * dx + dy * dy + dz * dz;
		if (distance >= 384L * 384L) return 3;
		if (distance >= 256L * 256L) return 2;
		if (distance >= 128L * 128L) return 1;
		return 0;
	}

	private static int options(TerrainPreparation.PreparationPolicy policy) {
		return (policy.lodDropPlants() ? 1 : 0) | (policy.packRecords() ? PackTerrainUnits.PACK_OPTION : 0);
	}

	private static int cullMask(TerrainPreparation.PreparationPolicy policy) {
		return policy.faceCulling() ? 7 : 0;
	}

	private static int layerMask(TerrainPreparation.PreparationPolicy policy) {
		int mask = 0;
		if (!policy.skipSolid()) mask |= 1;
		if (!policy.skipCutout()) mask |= 1 << 1;
		if (!policy.skipTranslucent()) mask |= 1 << 2;
		return mask;
	}

	static final class CacheState {
		private final long generation;
		private final int sectionCount;
		private final TerrainPreparation.SectionIdentity[] identities;
		private final int[] cachedUnits;
		private final int[] cachedLayerUnits;
		private final boolean[] dirty;
		private final boolean[] cachedLod;
		private final boolean allDirty;

		CacheState(long generation, int sectionCount) {
			this(generation, sectionCount, new TerrainPreparation.SectionIdentity[sectionCount], new int[sectionCount], new int[sectionCount * TerrainPreparation.LAYER_COUNT], filled(sectionCount), new boolean[sectionCount], true);
		}

		private CacheState(long generation, int sectionCount, TerrainPreparation.SectionIdentity[] identities, int[] cachedUnits, int[] cachedLayerUnits, boolean[] dirty, boolean[] cachedLod, boolean allDirty) {
			this.generation = generation;
			this.sectionCount = sectionCount;
			this.identities = identities;
			this.cachedUnits = cachedUnits;
			this.cachedLayerUnits = cachedLayerUnits;
			this.dirty = dirty;
			this.cachedLod = cachedLod;
			this.allDirty = allDirty;
		}

		long generation() { return generation; }
		int sectionCount() { return sectionCount; }
		TerrainPreparation.SectionIdentity identity(int index) { return identities[index]; }
		boolean dirty(int index) { return dirty[index]; }
		boolean allDirty() { return allDirty; }
		int cachedUnits(int index) { return cachedUnits[index]; }
		int cachedLayerUnits(int index, TerrainPreparation.Layer layer) { return cachedLayerUnits[index * TerrainPreparation.LAYER_COUNT + layer.ordinal()]; }
		boolean cachedLod(int index) { return cachedLod[index]; }
		CacheState withDirty(int index, boolean value) { boolean[] values = dirty.clone(); values[index] = value; return copy(identities, cachedUnits, cachedLayerUnits, values, cachedLod, allDirty); }
		CacheState markDirty(int index) { if (index < 0 || index >= sectionCount) return this; return withDirty(index, true).withGeneration(generation + 1); }
		CacheState markAllDirty() { boolean[] values = filled(sectionCount); return new CacheState(generation + 1, sectionCount, identities, cachedUnits, cachedLayerUnits, values, cachedLod, true); }
		CacheState withCachedLod(int index, boolean value) { boolean[] values = cachedLod.clone(); values[index] = value; return copy(identities, cachedUnits, cachedLayerUnits, dirty, values, allDirty); }
		CacheState withCachedLayerUnits(int index, TerrainPreparation.Layer layer, int value) { int[] values = cachedLayerUnits.clone(); values[index * TerrainPreparation.LAYER_COUNT + layer.ordinal()] = value; return copy(identities, cachedUnits, values, dirty, cachedLod, allDirty); }
		CacheState withCachedUnits(int index, int value) { int[] values = cachedUnits.clone(); values[index] = value; return copy(identities, values, cachedLayerUnits, dirty, cachedLod, allDirty); }
		CacheState withIdentity(int index, TerrainPreparation.SectionIdentity value) { TerrainPreparation.SectionIdentity[] values = identities.clone(); values[index] = value; return copy(values, cachedUnits, cachedLayerUnits, dirty, cachedLod, allDirty); }
		CacheState withAllDirty(boolean value) { return copy(identities, cachedUnits, cachedLayerUnits, dirty, cachedLod, value); }
		CacheState withGeneration(long value) { return new CacheState(value, sectionCount, identities, cachedUnits, cachedLayerUnits, dirty, cachedLod, allDirty); }
		CacheState resize(int size) { if (size <= sectionCount) return this; return new CacheState(generation, size, Arrays.copyOf(identities, size), Arrays.copyOf(cachedUnits, size), Arrays.copyOf(cachedLayerUnits, size * TerrainPreparation.LAYER_COUNT), Arrays.copyOf(dirty, size), Arrays.copyOf(cachedLod, size), true); }
		private CacheState copy(TerrainPreparation.SectionIdentity[] i, int[] u, int[] l, boolean[] d, boolean[] lod, boolean all) { return new CacheState(generation, sectionCount, i, u, l, d, lod, all); }
		private static boolean[] filled(int size) { boolean[] result = new boolean[size]; Arrays.fill(result, true); return result; }
	}

	static final class Result {
		private final long generation;
		private final long baseGeneration;
		private final TerrainPreparation.RecordBatch batch;
		private final byte[] updates;
		private final byte[] visible;
		private final CacheState next;
		private final int[] layerUnits;

		private Result(long generation, long baseGeneration, TerrainPreparation.RecordBatch batch, byte[] updates, byte[] visible, CacheState next, int[] layerUnits) {
			this.generation = generation;
			this.baseGeneration = baseGeneration;
			this.batch = batch;
			this.updates = updates;
			this.visible = visible;
			this.next = next;
			this.layerUnits = layerUnits.clone();
		}

		long generation() { return generation; }
		TerrainPreparation.RecordBatch batch() { return batch; }
		byte[] updates() { return updates.clone(); }
		byte[] visible() { return visible.clone(); }
		int[] layerUnits() { return layerUnits.clone(); }
		boolean isCurrent(CacheState state) { return state.generation() == baseGeneration; }
		CacheState apply(CacheState state) { return isCurrent(state) ? next : state; }
	}
}
