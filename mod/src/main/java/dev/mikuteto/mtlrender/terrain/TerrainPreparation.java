package dev.mikuteto.mtlrender.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class TerrainPreparation {
	static final int LAYER_COUNT = 3;
	static final int FACE_COUNT = 7;
	static final int QUADS_PER_UNIT = 32;
	static final int UNIT_STRIDE = 48;
	static final int PACK_UNIT_STRIDE = 64;
	static final int UNIT_VERTICES_OFFSET = 0;
	static final int UNIT_INDICES_OFFSET = 8;
	static final int UNIT_ORIGIN_X_OFFSET = 16;
	static final int UNIT_ORIGIN_Y_OFFSET = 20;
	static final int UNIT_ORIGIN_Z_OFFSET = 24;
	static final int UNIT_VISIBILITY_OFFSET = 28;
	static final int UNIT_QUADS_OFFSET = 32;
	static final int UNIT_INDEX_FORMAT_OFFSET = 36;
	static final int UNIT_VERTEX_FORMAT_OFFSET = 40;
	static final int UNIT_SLOT_OFFSET = 44;
	static final int PACK_UNIT_METADATA_OFFSET = 48;
	static final int PACK_UNIT_METADATA_STRIDE_OFFSET = 56;
	static final int PACK_UNIT_QUAD_INDEX_OFFSET = 60;
	static final int RECORD_STRIDE = 80;
	static final int PACK_RECORD_STRIDE = 96;
	static final int RECORD_VERTICES_OFFSET = 0;
	static final int RECORD_INDICES_OFFSET = 8;
	static final int RECORD_ORIGIN_X_OFFSET = 16;
	static final int RECORD_ORIGIN_Y_OFFSET = 20;
	static final int RECORD_ORIGIN_Z_OFFSET = 24;
	static final int RECORD_VISIBILITY_OFFSET = 28;
	static final int RECORD_QUADS_OFFSET = 32;
	static final int RECORD_FLAGS_OFFSET = 36;
	static final int RECORD_SLOT_OFFSET = 40;
	static final int RECORD_RESERVED_OFFSET = 44;
	static final int RECORD_FACES_OFFSET = 48;
	static final int RECORD_PADDING_OFFSET = 76;
	static final int PACK_RECORD_METADATA_OFFSET = 80;
	static final int PACK_RECORD_METADATA_STRIDE_OFFSET = 88;
	static final int RECORD_UPDATE_STRIDE = 88;
	static final int PACK_RECORD_UPDATE_STRIDE = 104;
	static final int RECORD_UPDATE_SLOT_OFFSET = 0;
	static final int RECORD_UPDATE_RESERVED_OFFSET = 4;
	static final int RECORD_UPDATE_RECORD_OFFSET = 8;
	static final int RECORD_UPDATE_RECORD_SLOT_OFFSET = RECORD_UPDATE_RECORD_OFFSET + RECORD_SLOT_OFFSET;
	static final int RECORD_UPDATE_PADDING_OFFSET = RECORD_UPDATE_RECORD_OFFSET + RECORD_PADDING_OFFSET;
	static final int PACK_RECORD_UPDATE_METADATA_OFFSET = RECORD_UPDATE_RECORD_OFFSET + PACK_RECORD_METADATA_OFFSET;
	static final int PACK_RECORD_UPDATE_METADATA_STRIDE_OFFSET = RECORD_UPDATE_RECORD_OFFSET + PACK_RECORD_METADATA_STRIDE_OFFSET;
	static final int VISIBLE_ENTRY_STRIDE = 8;
	static final int VISIBLE_ENTRY_INDEX_OFFSET = 0;
	static final int VISIBLE_ENTRY_VISIBILITY_OFFSET = 4;

	private static final List<Layer> LAYERS = List.of(Layer.SOLID, Layer.CUTOUT, Layer.TRANSLUCENT);

	private TerrainPreparation() {
	}

	enum Layer {
		SOLID,
		CUTOUT,
		TRANSLUCENT;

		boolean reverseSections() {
			return this == TRANSLUCENT;
		}
	}

	enum VertexFormat {
		BLOCK(28),
		COMPACT(20);

		private final int bytes;

		VertexFormat(int bytes) {
			this.bytes = bytes;
		}

		int bytes() {
			return bytes;
		}
	}

	enum IndexFormat {
		NONE(0, 0),
		SHORT(1, 2),
		INT(2, 4);

		private final int code;
		private final int bytes;

		IndexFormat(int code, int bytes) {
			this.code = code;
			this.bytes = bytes;
		}

		int code() {
			return code;
		}

		int bytes() {
			return bytes;
		}
	}

	record Camera(int blockX, int blockY, int blockZ, float offsetX, float offsetY, float offsetZ) {
		Camera {
			if (!Float.isFinite(offsetX) || !Float.isFinite(offsetY) || !Float.isFinite(offsetZ)) {
				throw new IllegalArgumentException("camera offsets must be finite");
			}
		}
	}

	record SectionIdentity(int index, long meshGeneration, long meshSequence) {
		SectionIdentity {
			if (index < 0 || meshGeneration < 0 || meshSequence < 0) {
				throw new IllegalArgumentException("section identity values must not be negative");
			}
		}
	}

	record PreparationPolicy(
		boolean expand,
		boolean indexed,
		boolean cpuUnits,
		boolean quadStats,
		boolean faceCulling,
		boolean lod,
		boolean lodDropPlants,
		int lodMinRing,
		boolean skipSolid,
		boolean skipCutout,
		boolean skipTranslucent,
		boolean skipFar,
		boolean skipPlants,
		boolean packRecords
	) {
		PreparationPolicy(
			boolean expand,
			boolean indexed,
			boolean cpuUnits,
			boolean quadStats,
			boolean faceCulling,
			boolean lod,
			boolean lodDropPlants,
			int lodMinRing
		) {
			this(expand, indexed, cpuUnits, quadStats, faceCulling, lod, lodDropPlants, lodMinRing, false, false, false, false, false, false);
		}

		PreparationPolicy(
			boolean expand,
			boolean indexed,
			boolean cpuUnits,
			boolean quadStats,
			boolean faceCulling,
			boolean lod,
			boolean lodDropPlants,
			int lodMinRing,
			boolean skipSolid,
			boolean skipCutout,
			boolean skipTranslucent,
			boolean skipFar,
			boolean skipPlants
		) {
			this(expand, indexed, cpuUnits, quadStats, faceCulling, lod, lodDropPlants, lodMinRing,
				skipSolid, skipCutout, skipTranslucent, skipFar, skipPlants, false);
		}

		PreparationPolicy(
			boolean expand,
			boolean indexed,
			boolean cpuUnits,
			boolean quadStats,
			boolean faceCulling,
			boolean lod,
			boolean lodDropPlants,
			int lodMinRing,
			boolean skipFar
		) {
			this(expand, indexed, cpuUnits, quadStats, faceCulling, lod, lodDropPlants, lodMinRing, false, false, false, skipFar, false, false);
		}

		PreparationPolicy {
			if (lodMinRing < 0) {
				throw new IllegalArgumentException("lod ring must not be negative");
			}
		}

		boolean gpuExpansionEnabled() {
			return expand && indexed && !cpuUnits && !quadStats;
		}
	}

	record LayerInput(
		SectionIdentity section,
		Layer layer,
		int originX,
		int originY,
		int originZ,
		float visibility,
		long verticesAddress,
		long indicesAddress,
		VertexFormat vertexFormat,
		IndexFormat indexFormat,
		int quadCount,
		int[] faceCounts,
		int[] mergedCounts,
		int flags,
		boolean lod,
		long metadataAddress,
		int metadataStride,
		long metadataGeneration,
		long metadataRequirements
	) {
		LayerInput(SectionIdentity section, Layer layer, int originX, int originY, int originZ, float visibility,
			long verticesAddress, long indicesAddress, VertexFormat vertexFormat, IndexFormat indexFormat, int quadCount,
			int[] faceCounts, int[] mergedCounts, int flags, boolean lod) {
			this(section, layer, originX, originY, originZ, visibility, verticesAddress, indicesAddress, vertexFormat, indexFormat,
				quadCount, faceCounts, mergedCounts, flags, lod, 0L, 0, -1L, 0L);
		}

		LayerInput {
			Objects.requireNonNull(section, "section");
			Objects.requireNonNull(layer, "layer");
			Objects.requireNonNull(vertexFormat, "vertexFormat");
			Objects.requireNonNull(indexFormat, "indexFormat");
			if (!Float.isFinite(visibility)) {
				throw new IllegalArgumentException("visibility must be finite");
			}
			if (verticesAddress < 0 || indicesAddress < 0 || quadCount < 0 || flags < 0 || metadataAddress < 0 || metadataStride < 0) {
				throw new IllegalArgumentException("layer input addresses, counts, and flags must not be negative");
			}
			if ((flags & 3) != indexFormat.code() || (flags >> 2 & 3) != vertexFormat.ordinal()) {
				throw new IllegalArgumentException("layer input flags do not match its formats");
			}
			faceCounts = counts(faceCounts, "face counts");
			mergedCounts = mergedCounts == null ? null : counts(mergedCounts, "merged counts");
		}

		@Override
		public int[] faceCounts() {
			return Arrays.copyOf(faceCounts, faceCounts.length);
		}

		@Override
		public int[] mergedCounts() {
			return mergedCounts == null ? null : Arrays.copyOf(mergedCounts, mergedCounts.length);
		}

		int unitSlot() {
			return TerrainPreparation.unitSlot(section.index(), layer);
		}

		int recordSlot() {
			return TerrainPreparation.recordSlot(section.index(), layer);
		}

		private static int[] counts(int[] values, String name) {
			if (values.length != FACE_COUNT) {
				throw new IllegalArgumentException(name + " must contain " + FACE_COUNT + " values");
			}
			for (int value : values) {
				if (value < 0) {
					throw new IllegalArgumentException(name + " must not contain negative values");
				}
			}
			return Arrays.copyOf(values, values.length);
		}
	}

	record SectionInput(
		SectionIdentity identity,
		int originX,
		int originY,
		int originZ,
		float visibility,
		List<LayerInput> layers
	) {
		SectionInput {
			Objects.requireNonNull(identity, "identity");
			if (!Float.isFinite(visibility)) {
				throw new IllegalArgumentException("visibility must be finite");
			}
			layers = List.copyOf(layers);
			boolean[] seen = new boolean[LAYER_COUNT];
			for (LayerInput layer : layers) {
				if (seen[layer.layer().ordinal()]) {
					throw new IllegalArgumentException("section contains duplicate layers");
				}
				seen[layer.layer().ordinal()] = true;
				if (!identity.equals(layer.section())
					|| originX != layer.originX()
					|| originY != layer.originY()
					|| originZ != layer.originZ()
					|| Float.compare(visibility, layer.visibility()) != 0) {
					throw new IllegalArgumentException("layer input does not match its section");
				}
			}
		}

		Optional<LayerInput> layer(Layer layer) {
			for (LayerInput input : layers) {
				if (input.layer() == layer) {
					return Optional.of(input);
				}
			}
			return Optional.empty();
		}
	}

	record Frame(long generation, long cameraSequence, Camera camera, List<SectionInput> sections, PreparationPolicy policy) {
		Frame {
			if (generation < 0 || cameraSequence < 0) {
				throw new IllegalArgumentException("frame values must not be negative");
			}
			Objects.requireNonNull(camera, "camera");
			Objects.requireNonNull(policy, "policy");
			sections = List.copyOf(sections);
			for (int i = 0; i < sections.size(); i++) {
				SectionInput section = sections.get(i);
				for (int j = i + 1; j < sections.size(); j++) {
					if (section.identity().index() == sections.get(j).identity().index()) {
						throw new IllegalArgumentException("frame contains duplicate section indices");
					}
				}
			}
		}

		List<Layer> layers() {
			return LAYERS;
		}

		List<SectionInput> sections(Layer layer) {
			Objects.requireNonNull(layer, "layer");
			List<SectionInput> ordered = new ArrayList<>();
			for (SectionInput section : sections) {
				if (section.layer(layer).isPresent()) {
					ordered.add(section);
				}
			}
			if (layer.reverseSections()) {
				ordered = new ArrayList<>(ordered.reversed());
			}
			return List.copyOf(ordered);
		}

		Optional<SectionInput> section(int index) {
			for (SectionInput section : sections) {
				if (section.identity().index() == index) {
					return Optional.of(section);
				}
			}
			return Optional.empty();
		}

		boolean accepts(long resultGeneration, long resultCameraSequence) {
			return generation == resultGeneration && cameraSequence == resultCameraSequence;
		}

		boolean accepts(SectionIdentity identity) {
			return section(identity.index()).map(section -> section.identity().equals(identity)).orElse(false);
		}

		boolean accepts(UnitBatch batch) {
			return accepts(batch.frameGeneration(), batch.cameraSequence())
				&& batch.units().stream().allMatch(unit -> accepts(unit.section()));
		}

		boolean accepts(RecordBatch batch) {
			return accepts(batch.frameGeneration(), batch.cameraSequence())
				&& batch.updates().stream().allMatch(update -> accepts(update.section()))
				&& batch.visible().stream().allMatch(entry -> accepts(entry.section()));
		}

		boolean accepts(DiagnosticStats stats) {
			return accepts(stats.frameGeneration(), stats.cameraSequence())
				&& stats.sections().stream().allMatch(this::accepts);
		}
	}

	record Unit(
		long frameGeneration,
		long cameraSequence,
		SectionIdentity section,
		Layer layer,
		long verticesAddress,
		long indicesAddress,
		int originX,
		int originY,
		int originZ,
		float visibility,
		int quadCount,
		IndexFormat indexFormat,
		VertexFormat vertexFormat,
		int slot
	) {
		Unit {
			Objects.requireNonNull(section, "section");
			Objects.requireNonNull(layer, "layer");
			Objects.requireNonNull(indexFormat, "indexFormat");
			Objects.requireNonNull(vertexFormat, "vertexFormat");
			if (frameGeneration < 0 || cameraSequence < 0 || quadCount < 0 || slot != unitSlot(section.index(), layer)) {
				throw new IllegalArgumentException("invalid unit identity or layout values");
			}
			if (!Float.isFinite(visibility)) {
				throw new IllegalArgumentException("visibility must be finite");
			}
		}

		boolean isCurrent(Frame frame) {
			return frame.accepts(frameGeneration, cameraSequence) && frame.accepts(section);
		}
	}

	record UnitBatch(long frameGeneration, long cameraSequence, List<Unit> units, int[] first, int[] counts, long quads) {
		UnitBatch {
			if (frameGeneration < 0 || cameraSequence < 0 || quads < 0) {
				throw new IllegalArgumentException("invalid unit batch values");
			}
			units = List.copyOf(units);
			first = layerValues(first, "first");
			counts = layerValues(counts, "counts");
			for (Unit unit : units) {
				if (unit.frameGeneration() != frameGeneration || unit.cameraSequence() != cameraSequence) {
					throw new IllegalArgumentException("unit does not match the batch snapshot");
				}
			}
			if (units.stream().mapToLong(Unit::quadCount).sum() != quads) {
				throw new IllegalArgumentException("unit quad total does not match the batch");
			}
		}

		@Override
		public int[] first() {
			return Arrays.copyOf(first, first.length);
		}

		@Override
		public int[] counts() {
			return Arrays.copyOf(counts, counts.length);
		}

		int first(Layer layer) {
			return first[layer.ordinal()];
		}

		int count(Layer layer) {
			return counts[layer.ordinal()];
		}

		boolean isCurrent(Frame frame) {
			return frame.accepts(this);
		}
	}

	record RecordUpdate(
		SectionIdentity section,
		Layer layer,
		long verticesAddress,
		long indicesAddress,
		VertexFormat vertexFormat,
		IndexFormat indexFormat,
		int originX,
		int originY,
		int originZ,
		int quadCount,
		int flags,
		int recordSlot,
		int unitSlot,
		int[] faceCounts,
		long metadataAddress,
		int metadataStride,
		long metadataGeneration,
		long metadataRequirements
	) {
		RecordUpdate(SectionIdentity section, Layer layer, long verticesAddress, long indicesAddress, VertexFormat vertexFormat,
			IndexFormat indexFormat, int originX, int originY, int originZ, int quadCount, int flags, int recordSlot,
			int unitSlot, int[] faceCounts) {
			this(section, layer, verticesAddress, indicesAddress, vertexFormat, indexFormat, originX, originY, originZ, quadCount,
				flags, recordSlot, unitSlot, faceCounts, 0L, 0, -1L, 0L);
		}

		RecordUpdate {
			Objects.requireNonNull(section, "section");
			Objects.requireNonNull(layer, "layer");
			Objects.requireNonNull(vertexFormat, "vertexFormat");
			Objects.requireNonNull(indexFormat, "indexFormat");
			if (verticesAddress < 0 || indicesAddress < 0 || quadCount < 0 || flags < 0 || metadataAddress < 0 || metadataStride < 0
				|| (flags & 3) != indexFormat.code() || (flags >> 2 & 3) != vertexFormat.ordinal()
				|| recordSlot != TerrainPreparation.recordSlot(section.index(), layer)
				|| unitSlot != TerrainPreparation.unitSlot(section.index(), layer)) {
				throw new IllegalArgumentException("invalid record input");
			}
			faceCounts = faceCounts == null ? new int[FACE_COUNT] : counts(faceCounts);
		}

		@Override
		public int[] faceCounts() {
			return Arrays.copyOf(faceCounts, faceCounts.length);
		}

		private static int[] counts(int[] values) {
			if (values.length != FACE_COUNT) {
				throw new IllegalArgumentException("face counts must contain " + FACE_COUNT + " values");
			}
			for (int value : values) {
				if (value < 0) {
					throw new IllegalArgumentException("face counts must not contain negative values");
				}
			}
			return Arrays.copyOf(values, values.length);
		}
	}

	record VisibleEntry(SectionIdentity section, float visibility) {
		VisibleEntry {
			Objects.requireNonNull(section, "section");
			if (!Float.isFinite(visibility)) {
				throw new IllegalArgumentException("visibility must be finite");
			}
		}
	}

	record RecordBatch(
		long frameGeneration,
		long cameraSequence,
		int sectionCount,
		int cullMask,
		int layerMask,
		int options,
		int expandCapacity,
		List<RecordUpdate> updates,
		List<VisibleEntry> visible
	) {
		RecordBatch {
			if (frameGeneration < 0 || cameraSequence < 0 || sectionCount < 0 || expandCapacity < 0) {
				throw new IllegalArgumentException("invalid record batch values");
			}
			updates = List.copyOf(updates);
			visible = List.copyOf(visible);
		}

		boolean isCurrent(Frame frame) {
			return frame.accepts(this);
		}
	}

	record LayerStats(Layer layer, int sections, int units, long quads, long mergedQuads) {
		LayerStats {
			Objects.requireNonNull(layer, "layer");
			if (sections < 0 || units < 0 || quads < 0 || mergedQuads < 0) {
				throw new IllegalArgumentException("diagnostic values must not be negative");
			}
		}
	}

	record DiagnosticStats(
		long frameGeneration,
		long cameraSequence,
		List<SectionIdentity> sections,
		List<LayerStats> layers,
		long units,
		long quads,
		long mergedQuads,
		long[] quadStats,
		long[] mergeStats
	) {
		DiagnosticStats {
			quadStats = stats(quadStats, "quad stats");
			mergeStats = stats(mergeStats, "merge stats");
			if (frameGeneration < 0 || cameraSequence < 0 || units < 0 || quads < 0 || mergedQuads < 0) {
				throw new IllegalArgumentException("diagnostic values must not be negative");
			}
			sections = List.copyOf(sections);
			layers = List.copyOf(layers);
			if (layers.size() != LAYER_COUNT) {
				throw new IllegalArgumentException("diagnostic results must contain one entry per layer");
			}
			for (SectionIdentity section : sections) {
				Objects.requireNonNull(section, "section");
			}
		}

		boolean isCurrent(Frame frame) {
			return frame.accepts(this);
		}

		@Override
		public long[] quadStats() {
			return Arrays.copyOf(quadStats, quadStats.length);
		}

		@Override
		public long[] mergeStats() {
			return Arrays.copyOf(mergeStats, mergeStats.length);
		}

		private static long[] stats(long[] values, String name) {
			if (values.length != 4 * 3 * 3) {
				throw new IllegalArgumentException(name + " must contain 36 values");
			}
			for (long value : values) {
				if (value < 0) {
					throw new IllegalArgumentException(name + " must not contain negative values");
				}
			}
			return Arrays.copyOf(values, values.length);
		}
	}

	static int unitSlot(int sectionIndex, Layer layer) {
		return sectionIndex * 4 + layer.ordinal();
	}

	static int recordSlot(int sectionIndex, Layer layer) {
		return sectionIndex * 3 + layer.ordinal();
	}

	private static int[] layerValues(int[] values, String name) {
		if (values.length != LAYER_COUNT) {
			throw new IllegalArgumentException(name + " must contain " + LAYER_COUNT + " values");
		}
		for (int value : values) {
			if (value < 0) {
				throw new IllegalArgumentException(name + " must not contain negative values");
			}
		}
		return Arrays.copyOf(values, values.length);
	}
}
