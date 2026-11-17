package dev.mikuteto.mtlrender.backend;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.BiFunction;

import com.mojang.logging.LogUtils;
import dev.mikuteto.mtlrender.debug.EvaluationFrame;
import dev.mikuteto.mtlrender.debug.MemorySource;
import dev.mikuteto.mtlrender.debug.PassLabels;
import dev.mikuteto.mtlrender.ffi.Mtlr;
import dev.mikuteto.mtlrender.ffi.MtlrCalibration;
import dev.mikuteto.mtlrender.ffi.MtlrCaps;
import dev.mikuteto.mtlrender.ffi.MtlrEvalFrame;
import dev.mikuteto.mtlrender.ffi.MtlrFrameState;
import dev.mikuteto.mtlrender.tune.CalibrationResult;
import dev.mikuteto.mtlrender.tune.Hardware;
import dev.mikuteto.mtlrender.tune.Tier;
import org.slf4j.Logger;

final class NativeGpu implements AutoCloseable {
	static final int BUFFER = 0;
	static final int TEXTURE = 1;
	static final int SAMPLER = 2;
	static final int PIPELINE = 3;
	static final int QUERY = 4;
	static final int COMPUTE_PIPELINE = 5;

	private static final int TEXT_CAPACITY = 256;
	private static final Logger LOGGER = LogUtils.getLogger();

	record Allocation(long handle, MemorySegment contents, long address, long bytes) {
	}

	private final MemorySegment device;
	private final Arena frameStateArena;
	private final MemorySegment frameState;
	private final Hardware hardware;
	private long submittedFrames;
	private long completedFrames;
	private long allocatedBytes;
	private long textureBytes;

	private NativeGpu(MemorySegment device) {
		this.device = device;
		this.frameStateArena = Arena.ofShared();
		this.frameState = MtlrFrameState.allocate(frameStateArena);
		this.hardware = Hardware.detect(caps());
		LOGGER.info(
			"MTLRender hardware detected: {} chip, {} GPU cores, {} tier, {} bytes memory",
			hardware.chip(),
			hardware.gpuCores(),
			Tier.of(hardware),
			hardware.memoryBytes()
		);
	}

	static NativeGpu create() {
		MemorySegment device = Mtlr.mtlr_device_create(PassLabels.enabled());
		return device.equals(MemorySegment.NULL) ? null : new NativeGpu(device);
	}

	static NativeGpu createWithArchive(String path, byte[] key) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment device = Mtlr.mtlr_device_create_with_archive(
				PassLabels.enabled(), arena.allocateFrom(path), arena.allocateFrom(ValueLayout.JAVA_BYTE, key), key.length);
			return device.equals(MemorySegment.NULL) ? null : new NativeGpu(device);
		}
	}

	int pipelineArchiveStatus() {
		return Mtlr.mtlr_pipeline_archive_status(device);
	}

	boolean flushPipelineArchive() {
		return Mtlr.mtlr_pipeline_archive_flush(device);
	}

	Hardware hardware() {
		return hardware;
	}

	private Hardware.NativeCaps caps() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = MtlrCaps.allocate(arena);
			if (!Mtlr.mtlr_device_caps(device, out)) {
				return Hardware.NativeCaps.missing();
			}
			return new Hardware.NativeCaps(
				MtlrCaps.apple_family(out),
				MtlrCaps.raytracing(out) != 0,
				MtlrCaps.raytracing_from_render(out) != 0,
				MtlrCaps.recommended_max_working_set(out),
				MtlrCaps.max_buffer_length(out)
			);
		}
	}

	String name() {
		return text(Mtlr::mtlr_device_name);
	}

	CalibrationResult calibrate(int width, int height) {
		if (width <= 0 || height <= 0) {
			return null;
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment output = MtlrCalibration.allocate(arena);
			if (!Mtlr.mtlr_calibrate_resolution(device, width, height, output)) {
				return null;
			}
			int indexed = MtlrCalibration.indexed(output);
			int compact = MtlrCalibration.compact(output);
			if (indexed > 1 || compact > 1) {
				return null;
			}
			return CalibrationResult.fromPayload(
				MtlrCalibration.version(output),
				MtlrCalibration.mode(output),
				MtlrCalibration.draw_path(output),
				indexed == 1,
				compact == 1,
				MtlrCalibration.probe_interval(output),
				MtlrCalibration.lod_min_ring(output),
				MtlrCalibration.width(output),
				MtlrCalibration.height(output)
			);
		}
	}

	String lastError() {
		return text(Mtlr::mtlr_last_error);
	}

	long submittedFrames() {
		return submittedFrames;
	}

	long completedFrames() {
		return completedFrames;
	}

	long refreshCompletedFrames() {
		completedFrames = Math.max(completedFrames, Mtlr.mtlr_completed_frames(device));
		return completedFrames;
	}

	boolean captureNext(String path) {
		try (Arena arena = Arena.ofConfined()) {
			return Mtlr.mtlr_capture_next(device, arena.allocateFrom(path));
		}
	}

	long accountedBytes() {
		return allocatedBytes + textureBytes;
	}

	void accountTexture(long bytes) {
		textureBytes = Math.addExact(textureBytes, bytes);
	}

	void releaseTexture(long bytes) {
		if (bytes < 0 || textureBytes < bytes) {
			throw new IllegalStateException("GPU texture accounting underflow");
		}
		textureBytes -= bytes;
	}

	long memoryBytes() {
		return Mtlr.mtlr_gpu_memory_bytes(device);
	}

	MemorySource.Values memorySource() {
		return new MemorySource.Values(
			OptionalLong.of(Mtlr.mtlr_native_memory_bytes(device)),
			OptionalLong.of(Mtlr.mtlr_metal_allocated_size(device)),
			OptionalLong.of(hardware.nativeCaps().recommendedMaxWorkingSet()),
			OptionalLong.of(Mtlr.mtlr_phys_footprint_bytes())
		);
	}

	boolean evaluationStart() {
		return Mtlr.mtlr_eval_start(device);
	}

	void evaluationStop() {
		Mtlr.mtlr_eval_stop(device);
	}

	List<EvaluationFrame> evaluationRead(int capacity) {
		if (capacity <= 0) {
			return List.of();
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment output = MtlrEvalFrame.allocateArray(capacity, arena);
			int count = Mtlr.mtlr_eval_read(device, output, capacity);
			List<EvaluationFrame> frames = new ArrayList<>(count);
			for (int index = 0; index < count; index++) {
				frames.add(EvaluationFrame.read(MtlrEvalFrame.asSlice(output, index)));
			}
			return List.copyOf(frames);
		}
	}

	String evaluationEnergySource() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment output = arena.allocate(32);
			Mtlr.mtlr_eval_energy_source(output, output.byteSize());
			return output.getString(0);
		}
	}

	static int memoryPressure() {
		return Mtlr.mtlr_memory_pressure();
	}

	boolean waitFrame(long frame, long timeoutMs) {
		return Mtlr.mtlr_wait_frame(device, frame, timeoutMs);
	}

	boolean metalfxSupported(int mode) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = MtlrCaps.allocate(arena);
			if (!Mtlr.mtlr_device_caps(device, out)) {
				return false;
			}
			return switch (mode) {
				case 1 -> MtlrCaps.metalfx_spatial(out) != 0;
				case 2 -> MtlrCaps.metalfx_temporal(out) != 0;
				case 3 -> MtlrCaps.metalfx_frame_interpolation(out) != 0;
				default -> false;
			};
		}
	}

	MemorySegment createSurface(long nsWindow) {
		MemorySegment surface = Mtlr.mtlr_surface_create(device, MemorySegment.ofAddress(nsWindow));
		if (surface.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("Failed to attach CAMetalLayer to the window");
		}
		return surface;
	}

	static final int PRESENT_FIFO = 0;
	static final int PRESENT_IMMEDIATE = 1;
	static final int PRESENT_OFFSCREEN = 2;

	void configureSurface(MemorySegment surface, int width, int height, int presentMode, int refresh) {
		Mtlr.mtlr_surface_configure(surface, width, height, presentMode, refresh);
	}

	boolean surfaceVisible(MemorySegment surface) {
		return Mtlr.mtlr_surface_visible(surface);
	}

	void destroySurface(MemorySegment surface) {
		Mtlr.mtlr_surface_destroy(surface);
	}

	Allocation allocate(long size) {
		return allocate(size, MemorySegment.NULL);
	}

	Allocation allocate(long size, MemorySegment initialData) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
			long initialLength = initialData.equals(MemorySegment.NULL) ? 0 : initialData.byteSize();
			long handle = Mtlr.mtlr_buffer_create(device, size, initialData, initialLength, out);
			if (handle == 0) {
				throw new IllegalStateException("Failed to allocate a Metal buffer of " + size + " bytes");
			}
			allocatedBytes = Math.addExact(allocatedBytes, size);
			return new Allocation(handle, out.get(ValueLayout.ADDRESS, 0).reinterpret(Math.max(size, 1)), Mtlr.mtlr_buffer_address(device, handle), size);
		}
	}

	long createTexture(int format, int width, int height, int layers, int mips, boolean cube, boolean renderTarget) {
		return createTexture(format, width, height, layers, mips, cube, renderTarget, false);
	}

	long createTexture(int format, int width, int height, int layers, int mips, boolean cube, boolean renderTarget, boolean shaderWrite) {
		return Mtlr.mtlr_texture_create(device, format, width, height, layers, mips, cube, renderTarget, shaderWrite);
	}

	long createView(long texture, int baseMip, int mips) {
		return Mtlr.mtlr_view_create(device, texture, baseMip, mips);
	}

	long createSampler(int[] words) {
		try (Arena arena = Arena.ofConfined()) {
			return Mtlr.mtlr_sampler_create(device, arena.allocateFrom(ValueLayout.JAVA_INT, words));
		}
	}

	long createPipeline(String vertex, String fragment, String entry, int[] descriptor) {
		return createPipeline(vertex, entry, fragment, entry, descriptor);
	}

	long createPipeline(String vertex, String vertexEntry, String fragment, String fragmentEntry, int[] descriptor) {
		try (Arena arena = Arena.ofConfined()) {
			return Mtlr.mtlr_pipeline_create(
				device,
				arena.allocateFrom(vertex),
				arena.allocateFrom(vertexEntry),
				arena.allocateFrom(fragment),
				arena.allocateFrom(fragmentEntry),
				arena.allocateFrom(ValueLayout.JAVA_INT, descriptor),
				descriptor.length
			);
		}
	}

	long createMeshPipeline(String object, String mesh, String fragment, String objectEntry, String meshEntry, String fragmentEntry, int[] descriptor) {
		try (Arena arena = Arena.ofConfined()) {
			return Mtlr.mtlr_mesh_pipeline_create(
				device,
				arena.allocateFrom(object),
				arena.allocateFrom(objectEntry),
				arena.allocateFrom(mesh),
				arena.allocateFrom(meshEntry),
				arena.allocateFrom(fragment),
				arena.allocateFrom(fragmentEntry),
				arena.allocateFrom(ValueLayout.JAVA_INT, descriptor),
				descriptor.length
			);
		}
	}

	long createPackPipeline(PackPipelineDesc desc) {
		int[] descriptor = PipelineDescriptor.encodePack(desc);
		try (Arena arena = Arena.ofConfined()) {
			return Mtlr.mtlr_pack_pipeline_create(
				device,
				arena.allocateFrom(desc.vertexMsl()),
				arena.allocateFrom(desc.vertexEntry()),
				desc.fragmentMsl() == null ? MemorySegment.NULL : arena.allocateFrom(desc.fragmentMsl()),
				desc.fragmentEntry() == null ? MemorySegment.NULL : arena.allocateFrom(desc.fragmentEntry()),
				arena.allocateFrom(ValueLayout.JAVA_INT, descriptor),
				descriptor.length
			);
		}
	}

	long createComputePipeline(String msl, String entry, int threadsX, int threadsY, int threadsZ) {
		try (Arena arena = Arena.ofConfined()) {
			return Mtlr.mtlr_compute_pipeline_create(
				device, arena.allocateFrom(msl), arena.allocateFrom(entry), threadsX, threadsY, threadsZ
			);
		}
	}

	long createQueryPool(int size) {
		return Mtlr.mtlr_query_pool_create(device, size);
	}

	boolean resolveQueries(long pool, int first, int count, MemorySegment out) {
		return Mtlr.mtlr_query_resolve(device, pool, first, count, out);
	}

	long timestampFrequency() {
		return Mtlr.mtlr_timestamp_frequency(device);
	}

	int passTimes(MemorySegment records) {
		return Mtlr.mtlr_pass_times(device, records, Math.toIntExact(records.byteSize() / Long.BYTES));
	}

	void destroyAllocation(Allocation allocation) {
		if (allocation.handle() != 0) {
			Mtlr.mtlr_destroy(device, BUFFER, allocation.handle());
			allocatedBytes -= allocation.bytes();
		}
	}

	void destroy(int kind, long handle) {
		if (handle != 0) {
			Mtlr.mtlr_destroy(device, kind, handle);
		}
	}

	static final int STAT_GPU_BUSY = 0;
	static final int STAT_FRAME_WAIT = 1;
	static final int STAT_ENCODE = 2;
	static final int STAT_DRAWABLE = 3;
	static final int STAT_COMMIT = 4;
	static final int STAT_PRESENTS = 5;
	static final int STAT_CULL_UNITS = 6;
	static final int STAT_CULL_KEPT_A = 7;
	static final int STAT_CULL_KEPT_B = 8;
	static final int STAT_COUNT = 19;

	long statNanos(int counter) {
		return Mtlr.mtlr_stat_nanos(counter);
	}

	record FrameState(long submitted, long completed) {
	}

	FrameState submit(CommandStream commands, MemorySegment surface) {
		Mtlr.mtlr_submit(device, commands.words(), commands.size(), surface, frameState);
		submittedFrames = MtlrFrameState.submitted(frameState);
		completedFrames = MtlrFrameState.completed(frameState);
		return new FrameState(submittedFrames, completedFrames);
	}

	@Override
	public void close() {
		Mtlr.mtlr_device_destroy(device);
		frameStateArena.close();
	}

	private String text(BiFunction<MemorySegment, MemorySegment, Long> read) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(TEXT_CAPACITY);
			read.apply(device, out);
			return out.getString(0);
		}
	}
}
