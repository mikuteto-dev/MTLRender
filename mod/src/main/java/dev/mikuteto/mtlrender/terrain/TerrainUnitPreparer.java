package dev.mikuteto.mtlrender.terrain;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

final class TerrainUnitPreparer {
	private static final int RING_ONE_DISTANCE_SQUARED = 128 * 128;
	private static final int RING_TWO_DISTANCE_SQUARED = 256 * 256;
	private static final int RING_THREE_DISTANCE_SQUARED = 384 * 384;
	private static final int JOB_SECTIONS = 32;
	private static final ExecutorService WORKERS = Executors.newFixedThreadPool(
		Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
		new TerrainThreadFactory()
	);

	private TerrainUnitPreparer() {
	}

	static Prepared prepare(TerrainPreparation.Frame frame) {
		List<Job> jobs = new ArrayList<>();
		for (TerrainPreparation.Layer layer : frame.layers()) {
			List<TerrainPreparation.SectionInput> sections = frame.sections(layer);
			for (int first = 0; first < sections.size(); first += JOB_SECTIONS) {
				int end = Math.min(sections.size(), first + JOB_SECTIONS);
				jobs.add(new Job(layer, sections, first, end));
			}
		}
		List<Future<Part>> futures = new ArrayList<>(jobs.size());
		for (Job job : jobs) {
			futures.add(WORKERS.submit(() -> run(frame, job)));
		}
		UnitWriter mergedWriter = new UnitWriter();
		List<TerrainPreparation.Unit> units = new ArrayList<>();
		int[] first = new int[TerrainPreparation.LAYER_COUNT];
		int[] counts = new int[TerrainPreparation.LAYER_COUNT];
		long quads = 0;
		long mergedQuads = 0;
		int[] layerSections = new int[TerrainPreparation.LAYER_COUNT];
		long[] layerQuads = new long[TerrainPreparation.LAYER_COUNT];
		long[] layerMerged = new long[TerrainPreparation.LAYER_COUNT];
		long[] quadStats = new long[4 * 3 * 3];
		long[] mergeStats = new long[4 * 3 * 3];
		int currentLayer = -1;
		for (int i = 0; i < futures.size(); i++) {
			Part part = result(futures.get(i));
			int layer = part.layer().ordinal();
			addStats(quadStats, part.quadStats());
			addStats(mergeStats, part.mergeStats());
			if (layer != currentLayer) {
				currentLayer = layer;
				first[layer] = mergedWriter.count();
			}
			for (TerrainPreparation.Unit unit : part.units()) {
				write(mergedWriter, unit);
				units.add(unit);
			}
			counts[layer] += part.units().size();
			quads += part.quads();
			mergedQuads += part.mergedQuads();
			layerSections[layer] += part.sections();
			layerQuads[layer] += part.quads();
			layerMerged[layer] += part.mergedQuads();
		}
		List<TerrainPreparation.LayerStats> stats = new ArrayList<>(TerrainPreparation.LAYER_COUNT);
		for (TerrainPreparation.Layer layer : frame.layers()) {
			int index = layer.ordinal();
			stats.add(new TerrainPreparation.LayerStats(layer, layerSections[index], counts[index], layerQuads[index], layerMerged[index]));
		}
		TerrainPreparation.DiagnosticStats diagnostics = new TerrainPreparation.DiagnosticStats(
			frame.generation(), frame.cameraSequence(), frame.sections().stream().map(TerrainPreparation.SectionInput::identity).toList(),
			stats, mergedWriter.quads(), quads, mergedQuads, quadStats, mergeStats
		);
		TerrainPreparation.UnitBatch batch = new TerrainPreparation.UnitBatch(
			frame.generation(), frame.cameraSequence(), units, first, counts, quads
		);
		return new Prepared(batch, mergedWriter.result(), diagnostics);
	}

	private static Part run(TerrainPreparation.Frame frame, Job job) {
		UnitWriter writer = new UnitWriter();
		List<TerrainPreparation.Unit> units = new ArrayList<>();
		long quads = 0;
		long mergedQuads = 0;
		long[] quadStats = new long[4 * 3 * 3];
		long[] mergeStats = new long[4 * 3 * 3];
		int sections = 0;
		TerrainPreparation.PreparationPolicy policy = frame.policy();
		for (int index = job.first(); index < job.end(); index++) {
			TerrainPreparation.SectionInput section = job.sections().get(index);
			if (skipLayer(policy, job.layer()) || policy.skipFar() && ring(frame.camera(), section) == 3) {
				continue;
			}
			TerrainPreparation.LayerInput input = section.layer(job.layer()).orElse(null);
			if (input == null || input.quadCount() == 0) {
				continue;
			}
			int total = input.lod() ? total(input.mergedCounts()) : input.quadCount();
			if (total == 0) {
				continue;
			}
			int mask = visibleMask(frame.camera(), section);
			if (input.lod() && policy.lodDropPlants()) {
				mask &= ~(1 << FaceBuckets.UNASSIGNED);
			}
			if (policy.skipPlants()) {
				mask &= ~(1 << FaceBuckets.UNASSIGNED);
			}
			boolean indexed = input.indexFormat() != TerrainPreparation.IndexFormat.NONE;
			if (indexed) {
				addStat(quadStats, frame, section, job.layer(), 2, total);
				addStat(mergeStats, frame, section, job.layer(), 2, total);
				int before = writer.count();
				for (int quad = 0; quad < total; quad += TerrainPreparation.QUADS_PER_UNIT) {
					int count = Math.min(TerrainPreparation.QUADS_PER_UNIT, total - quad);
					long address = input.indicesAddress() + (long) quad * 6 * input.indexFormat().bytes();
					add(writer, units, frame, section, input, input.verticesAddress(), address, count, input.indexFormat());
				}
				if (writer.count() != before) {
					sections++;
					quads += total;
					mergedQuads += total;
				}
				continue;
			}
			if (!policy.faceCulling() || !hasFaceBuckets(input)) {
				addStat(quadStats, frame, section, job.layer(), 2, total);
				addStat(mergeStats, frame, section, job.layer(), 2, total);
				int before = writer.count();
				writeRun(writer, units, frame, section, input, 0, total);
				if (writer.count() != before) {
					sections++;
					quads += total;
					mergedQuads += total;
				}
				continue;
			}
			int[] faces = input.faceCounts();
			int[] merged = input.mergedCounts();
			int start = 0;
			int end = 0;
			int position = 0;
			long selected = 0;
			long selectedMerged = 0;
			for (int bucket = 0; bucket < TerrainPreparation.FACE_COUNT; bucket++) {
				int next = position + faces[bucket];
				if ((mask & 1 << bucket) != 0) {
					selected += faces[bucket];
					selectedMerged += merged == null ? faces[bucket] : merged[bucket];
					int kind = bucket == FaceBuckets.UNASSIGNED ? 1 : 0;
					addStat(quadStats, frame, section, job.layer(), kind, faces[bucket]);
					addStat(mergeStats, frame, section, job.layer(), kind, merged == null ? faces[bucket] : merged[bucket]);
					if (end != position) {
						writeRun(writer, units, frame, section, input, start, end);
						start = position;
					}
					end = next;
				}
				position = next;
			}
			int before = writer.count();
			writeRun(writer, units, frame, section, input, start, end);
			if (writer.count() != before || selected > 0) {
				sections++;
			}
			quads += selected;
			mergedQuads += selectedMerged;
		}
		return new Part(job.layer(), units, writer.quads(), mergedQuads, sections, quadStats, mergeStats);
	}

	private static void addStat(long[] stats, TerrainPreparation.Frame frame, TerrainPreparation.SectionInput section,
		TerrainPreparation.Layer layer, int kind, long value) {
		int ring = ring(frame.camera(), section);
		stats[(ring * 3 + layer.ordinal()) * 3 + kind] += value;
	}

	private static void addStats(long[] target, long[] source) {
		for (int i = 0; i < target.length; i++) {
			target[i] += source[i];
		}
	}

	private static void writeRun(UnitWriter writer, List<TerrainPreparation.Unit> units, TerrainPreparation.Frame frame,
		TerrainPreparation.SectionInput section, TerrainPreparation.LayerInput input, int start, int end) {
		for (int quad = start; quad < end; quad += TerrainPreparation.QUADS_PER_UNIT) {
			int count = Math.min(TerrainPreparation.QUADS_PER_UNIT, end - quad);
			long address = input.verticesAddress() + (long) quad * 4 * input.vertexFormat().bytes();
			add(writer, units, frame, section, input, address, 0L, count, TerrainPreparation.IndexFormat.NONE);
		}
	}

	private static void add(UnitWriter writer, List<TerrainPreparation.Unit> units, TerrainPreparation.Frame frame,
		TerrainPreparation.SectionInput section, TerrainPreparation.LayerInput input, long vertices, long indices, int count,
		TerrainPreparation.IndexFormat indexFormat) {
		int slot = input.unitSlot();
		writer.unit(vertices, indexFormat == TerrainPreparation.IndexFormat.NONE ? 0L : indices,
			input.originX(), input.originY(), input.originZ(), input.visibility(), count, indexFormat.code(), input.vertexFormat().ordinal(), slot);
		units.add(new TerrainPreparation.Unit(frame.generation(), frame.cameraSequence(), section.identity(), input.layer(), vertices,
			indexFormat == TerrainPreparation.IndexFormat.NONE ? 0L : indices, input.originX(), input.originY(), input.originZ(),
			input.visibility(), count, indexFormat, input.vertexFormat(), slot));
	}

	private static void write(UnitWriter writer, TerrainPreparation.Unit unit) {
		writer.unit(unit.verticesAddress(), unit.indicesAddress(), unit.originX(), unit.originY(), unit.originZ(), unit.visibility(),
			unit.quadCount(), unit.indexFormat().code(), unit.vertexFormat().ordinal(), unit.slot());
	}

	private static boolean hasFaceBuckets(TerrainPreparation.LayerInput input) {
		if ((input.flags() & (1 << 4)) != 0) {
			return true;
		}
		for (int value : input.faceCounts()) {
			if (value != 0) {
				return true;
			}
		}
		return false;
	}

	private static int total(int[] values) {
		int total = 0;
		for (int value : values) {
			total += value;
		}
		return total;
	}

	private static boolean skipLayer(TerrainPreparation.PreparationPolicy policy, TerrainPreparation.Layer layer) {
		return switch (layer) {
			case SOLID -> policy.skipSolid();
			case CUTOUT -> policy.skipCutout();
			case TRANSLUCENT -> policy.skipTranslucent();
		};
	}

	private static int visibleMask(TerrainPreparation.Camera camera, TerrainPreparation.SectionInput section) {
		return FaceBuckets.visibleMask(
			camera.blockX() - camera.offsetX(), camera.blockY() - camera.offsetY(), camera.blockZ() - camera.offsetZ(),
			section.originX(), section.originY(), section.originZ()
		);
	}

	private static int ring(TerrainPreparation.Camera camera, TerrainPreparation.SectionInput section) {
		double x = section.originX() + 8.0 - (camera.blockX() - camera.offsetX());
		double z = section.originZ() + 8.0 - (camera.blockZ() - camera.offsetZ());
		double distance = x * x + z * z;
		if (distance < RING_ONE_DISTANCE_SQUARED) {
			return 0;
		}
		if (distance < RING_TWO_DISTANCE_SQUARED) {
			return 1;
		}
		if (distance < RING_THREE_DISTANCE_SQUARED) {
			return 2;
		}
		return 3;
	}

	private static Part result(Future<Part> future) {
		try {
			return future.get();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("terrain preparation interrupted", exception);
		} catch (ExecutionException exception) {
			throw new IllegalStateException("terrain preparation failed", exception.getCause());
		}
	}

	static record Prepared(TerrainPreparation.UnitBatch batch, UnitWriter.Result units, TerrainPreparation.DiagnosticStats diagnostics) {
	}

	private record Job(TerrainPreparation.Layer layer, List<TerrainPreparation.SectionInput> sections, int first, int end) {
	}

	private record Part(TerrainPreparation.Layer layer, List<TerrainPreparation.Unit> units, long quads, long mergedQuads, int sections,
		long[] quadStats, long[] mergeStats) {
	}

	private static final class TerrainThreadFactory implements ThreadFactory {
		private int sequence;

		@Override
		public Thread newThread(Runnable task) {
			Thread thread = new Thread(task, "MTLRender-Terrain-" + sequence++);
			thread.setDaemon(true);
			return thread;
		}
	}
}
