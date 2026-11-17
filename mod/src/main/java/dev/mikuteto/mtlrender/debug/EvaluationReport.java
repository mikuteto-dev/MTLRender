package dev.mikuteto.mtlrender.debug;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class EvaluationReport {
	private static final int PRESENT_TIMESTAMP = EvaluationFrame.PRESENT_TIMESTAMP_VALID;
	private static final int PRESENT_INTERVAL = EvaluationFrame.PRESENT_INTERVAL_VALID;
	private static final int PRESENT_SEQUENCE = EvaluationFrame.PRESENTED_SEQUENCE_VALID;
	private static final int GPU_BUSY = EvaluationFrame.GPU_BUSY_VALID;
	private static final int GPU_ENERGY = EvaluationFrame.GPU_ENERGY_VALID;
	private static final int CPU_TIME = EvaluationFrame.CPU_PROCESS_VALID;
	private static final int PHYS_FOOTPRINT = EvaluationFrame.PHYS_FOOTPRINT_VALID;
	private static final int METAL_ALLOCATED = EvaluationFrame.METAL_ALLOCATED_SIZE_VALID;

	public record Result(List<EvaluationFrame> frames, String summary, String csv, boolean comparable) {
		public Result {
			frames = List.copyOf(frames);
		}
	}

	private EvaluationReport() {
	}

	public static Result create(List<EvaluationFrame> records, int refreshHz, long terrainLoadMs, String sceneSet, String scene,
		String adverseCondition, String energySource, boolean imageGateValid) {
		List<EvaluationFrame> ordered = records.stream().sorted(Comparator.comparingLong(EvaluationFrame::submittedFrame)).toList();
		List<EvaluationFrame> frames = ordered.stream()
			.filter(frame -> (frame.validityFlags() & PRESENT_TIMESTAMP) != 0 && (frame.validityFlags() & PRESENT_INTERVAL) != 0 && (frame.validityFlags() & PRESENT_SEQUENCE) != 0)
			.sorted(Comparator.comparingLong(EvaluationFrame::presentTimestampNanos))
			.toList();
		long[] intervals = frames.stream().mapToLong(EvaluationFrame::presentIntervalNanos).filter(value -> value > 0).sorted().toArray();
		long hitchThreshold = refreshHz > 0 ? Math.round(1_000_000_000.0 / refreshHz * 1.5) : 0;
		long hitches = hitchThreshold == 0 ? 0 : frames.stream().filter(frame -> frame.presentIntervalNanos() > hitchThreshold).count();
		boolean presentValid = !ordered.isEmpty() && frames.size() == ordered.size();
		boolean energyValid = allValid(frames, GPU_ENERGY);
		boolean gpuValid = allValid(frames, GPU_BUSY);
		boolean cpuValid = allValid(frames, CPU_TIME);
		boolean physValid = allValid(frames, PHYS_FOOTPRINT);
		boolean metalValid = allValid(frames, METAL_ALLOCATED);
		boolean valid = presentValid && energyValid && gpuValid && cpuValid && physValid && metalValid && imageGateValid && terrainLoadMs >= 0;
		StringBuilder summary = new StringBuilder();
		line(summary, "presented_frames", frames.size());
		line(summary, "present_p50_ms", millis(percentile(intervals, 0.50)));
		line(summary, "present_p99_ms", millis(percentile(intervals, 0.99)));
		line(summary, "hitch_count", hitches);
		line(summary, "gpu_energy_nj_per_presented_frame", average(frames, GPU_ENERGY, EvaluationFrame::gpuEnergyNanojoules));
		line(summary, "gpu_busy_ms_per_presented_frame", millis(average(frames, GPU_BUSY, EvaluationFrame::gpuBusyNanos)));
		line(summary, "cpu_process_ms_per_presented_frame", millis(average(frames, CPU_TIME, EvaluationFrame::cpuProcessNanos)));
		line(summary, "phys_footprint_bytes", latest(frames, PHYS_FOOTPRINT, EvaluationFrame::physFootprintBytes));
		line(summary, "metal_allocated_bytes", latest(frames, METAL_ALLOCATED, EvaluationFrame::metalAllocatedSizeBytes));
		line(summary, "terrain_load_ms", terrainLoadMs);
		line(summary, "scene_set", sceneSet);
		line(summary, "scene", scene);
		line(summary, "refresh", refreshHz);
		line(summary, "adverse_condition", adverseCondition);
		line(summary, "energy_source", energySource);
		line(summary, "present_valid", presentValid);
		line(summary, "energy_valid", energyValid);
		line(summary, "gpu_busy_valid", gpuValid);
		line(summary, "cpu_valid", cpuValid);
		line(summary, "memory_valid", physValid && metalValid);
		line(summary, "image_gate_valid", imageGateValid);
		line(summary, "image_gate_status", imageGateValid ? "valid" : "invalid");
		line(summary, "valid", valid);
		StringBuilder csv = new StringBuilder("presented_sequence,submitted_frame,present_timestamp_ns,present_interval_ns,presented_drawable_id,gpu_busy_ns,gpu_energy_nj,cpu_process_ns,cpu_thread_count,phys_footprint_bytes,metal_allocated_size_bytes,validity_flags\n");
		int sequence = 0;
		for (EvaluationFrame frame : ordered) {
			int presentedSequence = (frame.validityFlags() & PRESENT_TIMESTAMP) != 0 && (frame.validityFlags() & PRESENT_INTERVAL) != 0 && (frame.validityFlags() & PRESENT_SEQUENCE) != 0 ? ++sequence : 0;
			csv.append(presentedSequence == 0 ? "" : Integer.toString(presentedSequence)).append(',').append(frame.submittedFrame()).append(',').append(frame.presentTimestampNanos()).append(',')
				.append(frame.presentIntervalNanos()).append(',').append(frame.presentedDrawableId()).append(',').append(frame.gpuBusyNanos()).append(',').append(frame.gpuEnergyNanojoules()).append(',')
				.append(frame.cpuProcessNanos()).append(',').append(frame.cpuThreadCount()).append(',').append(frame.physFootprintBytes()).append(',').append(frame.metalAllocatedSizeBytes()).append(',')
				.append(frame.validityFlags()).append('\n');
		}
		return new Result(frames, summary.toString(), csv.toString(), valid);
	}

	public static void write(Path output, Result result) throws IOException {
		Files.createDirectories(output);
		Files.writeString(output.resolve("summary.txt"), result.summary());
		Files.writeString(output.resolve("evaluation.csv"), result.csv());
	}

	private static boolean allValid(List<EvaluationFrame> frames, int flag) {
		return !frames.isEmpty() && frames.stream().allMatch(frame -> (frame.validityFlags() & flag) != 0);
	}

	private static long latest(List<EvaluationFrame> frames, int flag, java.util.function.ToLongFunction<EvaluationFrame> value) {
		for (int index = frames.size() - 1; index >= 0; index--) {
			EvaluationFrame frame = frames.get(index);
			if ((frame.validityFlags() & flag) != 0) return value.applyAsLong(frame);
		}
		return 0;
	}

	private static double average(List<EvaluationFrame> frames, int flag, java.util.function.ToLongFunction<EvaluationFrame> value) {
		long count = 0;
		double total = 0;
		for (EvaluationFrame frame : frames) {
			if ((frame.validityFlags() & flag) != 0) {
				total += value.applyAsLong(frame);
				count++;
			}
		}
		return count == 0 ? 0 : total / count;
	}

	private static long percentile(long[] sorted, double fraction) {
		if (sorted.length == 0) return 0;
		return sorted[(int) Math.min(sorted.length - 1, Math.round(fraction * (sorted.length - 1)))];
	}

	private static double millis(double nanos) {
		return nanos / 1_000_000.0;
	}

	private static void line(StringBuilder output, String key, Object value) {
		if (value instanceof Double number) output.append(key).append('=').append(String.format(Locale.ROOT, "%.6f", number)).append('\n');
		else output.append(key).append('=').append(value).append('\n');
	}
}
