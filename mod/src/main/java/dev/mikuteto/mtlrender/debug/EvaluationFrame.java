package dev.mikuteto.mtlrender.debug;

import java.lang.foreign.MemorySegment;

import dev.mikuteto.mtlrender.ffi.MtlrEvalFrame;

public record EvaluationFrame(
	long submittedFrame,
	long presentTimestampNanos,
	long presentIntervalNanos,
	long presentedDrawableId,
	long presentedSequence,
	long gpuBusyNanos,
	long gpuEnergyNanojoules,
	long cpuProcessNanos,
	int cpuThreadCount,
	long physFootprintBytes,
	long metalAllocatedSizeBytes,
	int validityFlags
) {
	public static final int PRESENT_TIMESTAMP_VALID = 1;
	public static final int PRESENT_INTERVAL_VALID = 1 << 1;
	public static final int PRESENTED_SEQUENCE_VALID = 1 << 3;
	public static final int GPU_BUSY_VALID = 1 << 4;
	public static final int GPU_ENERGY_VALID = 1 << 7;
	public static final int CPU_PROCESS_VALID = 1 << 8;
	public static final int PHYS_FOOTPRINT_VALID = 1 << 10;
	public static final int METAL_ALLOCATED_SIZE_VALID = 1 << 11;

	public static EvaluationFrame read(MemorySegment struct) {
		return new EvaluationFrame(
			MtlrEvalFrame.submitted_frame(struct),
			MtlrEvalFrame.present_timestamp_ns(struct),
			MtlrEvalFrame.present_interval_ns(struct),
			MtlrEvalFrame.presented_drawable_id(struct),
			MtlrEvalFrame.presented_sequence(struct),
			MtlrEvalFrame.gpu_busy_ns(struct),
			MtlrEvalFrame.gpu_energy_nj(struct),
			MtlrEvalFrame.cpu_process_ns(struct),
			MtlrEvalFrame.cpu_thread_count(struct),
			MtlrEvalFrame.phys_footprint_bytes(struct),
			MtlrEvalFrame.metal_allocated_size_bytes(struct),
			MtlrEvalFrame.validity_flags(struct)
		);
	}
}
