package dev.mikuteto.mtlrender.backend;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

import dev.mikuteto.mtlrender.debug.EvaluationFrame;
import dev.mikuteto.mtlrender.debug.MeshCheck;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferDrawBridge;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferPipelineOverride;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferStateOverride;
import dev.mikuteto.mtlrender.terrain.OcclusionMode;
import dev.mikuteto.mtlrender.terrain.TerrainMeshShader;
import dev.mikuteto.mtlrender.tune.Hardware;
import dev.mikuteto.mtlrender.tune.MemoryBudget;

public final class MetalAccess {
	public record MeshDispatch(
		int objectX, int objectY, int objectZ,
		int meshX, int meshY, int meshZ,
		int payloadBytes, int vertexCount, int primitiveCount,
		long resourceAddress, long resourceOffset, long resourceLength,
		long payloadOffset, long vertexOffset, long vertexBytes,
		long primitiveOffset, long primitiveBytes,
		int geometryX, int geometryY, int geometryZ
	) {
	}

	public static final class MeshFeature {
		private MeshFeature() {
		}

		public boolean dispatch(MeshDispatch request) {
			return dispatchMesh(this, request);
		}
	}

	public static final int GPU_BUSY = 0;
	public static final int CULL_UNITS = 6;
	public static final int CULL_KEPT_A = 7;
	public static final int CULL_KEPT_B = 8;
	public static final int EXPAND_UNITS = 9;
	public static final int EXPAND_QUADS = 10;
	public static final int EXPAND_MISMATCH_UNITS = 11;
	public static final int EXPAND_MISMATCH_LAYERS = 12;
	public static final int EXPAND_CHECKED = 13;
	public static final int SORT_MISMATCHES = 14;
	public static final int SORT_CHECKED = 15;
	public static final int FEATURE_TESTS = 16;
	public static final int FEATURE_KEPT = 17;
	public static final int FEATURE_HIDDEN = 18;

	public enum Access {
		READ(CommandStream.READ),
		WRITE(CommandStream.WRITE),
		READ_WRITE(CommandStream.READ_WRITE);

		private final int code;

		Access(int code) {
			this.code = code;
		}
	}

	private static volatile MetalDevice deviceOwner;
	private static volatile NativeGpu gpu;
	private static volatile MetalCommandEncoder encoder;
	private static volatile long submittedFrames;
	private static volatile long lastCompleted;
	private static volatile String presentMode = "unknown";
	private static volatile MemoryBudget budget;
	private static volatile GbufferPipelineOverride gbufferOverride;

	private MetalAccess() {
	}

	static void attach(MetalDevice owner, NativeGpu device, MetalCommandEncoder commandEncoder) {
		deviceOwner = owner;
		gpu = device;
		encoder = commandEncoder;
		budget = owner.budget();
		submittedFrames = device.submittedFrames();
		lastCompleted = device.completedFrames();
	}

	static void detach() {
		GbufferDrawBridge.clear();
		gbufferOverride = null;
		deviceOwner = null;
		gpu = null;
		encoder = null;
		submittedFrames = 0;
		lastCompleted = 0;
		presentMode = "unknown";
		budget = null;
	}

	static void setPresentMode(int mode) {
		presentMode = switch (mode) {
			case NativeGpu.PRESENT_FIFO -> "fifo";
			case NativeGpu.PRESENT_IMMEDIATE -> "immediate";
			case NativeGpu.PRESENT_OFFSCREEN -> "offscreen";
			default -> "unknown";
		};
	}

	static long lastCompletedFrames() {
		return lastCompleted;
	}

	static void observeCompleted(long frames) {
		lastCompleted = Math.max(lastCompleted, frames);
	}

	static void publishFrameState(long submitted, long completed) {
		submittedFrames = submitted;
		observeCompleted(completed);
	}

	public static boolean active() {
		return gpu != null;
	}

	public static MeshFeature enableMeshFeature() {
		return gpu != null && encoder != null ? new MeshFeature() : null;
	}

	public static boolean dispatchMesh(MeshFeature feature, MeshDispatch request) {
		if (feature == null || request == null) {
			return false;
		}
		MetalCommandEncoder commandEncoder = encoder;
		if (gpu == null || commandEncoder == null) {
			return false;
		}
		return commandEncoder.meshDispatch(request);
	}

	public static boolean meshCheckEnabled() {
		return gpu != null && MeshCheck.enabled();
	}

	public static boolean metalfxSupported(int mode) {
		NativeGpu device = gpu;
		return device != null && device.metalfxSupported(mode);
	}

	public static long submittedFrames() {
		return submittedFrames;
	}

	public static boolean addCompletion(final Runnable action) {
		MetalCommandEncoder commandEncoder = encoder;
		if (commandEncoder == null) {
			return false;
		}
		commandEncoder.addCompletion(action);
		return true;
	}

	public static long completedFrames() {
		NativeGpu device = gpu;
		if (device == null) {
			return Long.MAX_VALUE;
		}
		long frames = device.completedFrames();
		observeCompleted(frames);
		return frames;
	}

	public static long gpuAddress(GpuBuffer buffer) {
		return ((MetalBuffer) buffer).address();
	}

	public static long bufferHandle(GpuBuffer buffer) {
		return MetalBuffer.handle(buffer);
	}

	public static long statTotal(int counter) {
		NativeGpu device = gpu;
		return device == null ? 0L : device.statNanos(counter);
	}

	public static Hardware hardware() {
		NativeGpu device = gpu;
		return device == null ? null : device.hardware();
	}

	public static MemoryBudget memoryBudget() {
		return budget;
	}

	public static String deviceName() {
		NativeGpu device = gpu;
		return device == null ? "unknown" : device.name();
	}

	public static String presentMode() {
		return presentMode;
	}

	public static long memoryBytes() {
		NativeGpu device = gpu;
		return device == null ? 0L : device.memoryBytes();
	}

	public static boolean evaluationStart() {
		NativeGpu device = gpu;
		return device != null && device.evaluationStart();
	}

	public static void evaluationStop() {
		NativeGpu device = gpu;
		if (device != null) device.evaluationStop();
	}

	public static List<EvaluationFrame> evaluationRead(int capacity) {
		NativeGpu device = gpu;
		return device == null ? List.of() : device.evaluationRead(capacity);
	}

	public static String evaluationEnergySource() {
		NativeGpu device = gpu;
		return device == null ? "unavailable" : device.evaluationEnergySource();
	}

	public static boolean waitEvaluationFrames(long submittedFrame, long timeoutMs) {
		NativeGpu device = gpu;
		return device != null && device.waitFrame(submittedFrame, timeoutMs);
	}

	public static boolean captureNext(Path path) {
		NativeGpu device = gpu;
		return device != null && device.captureNext(path.toString());
	}

	public static int memoryPressure() {
		return NativeGpu.memoryPressure();
	}

	public static void dropCaches() {
		MetalCommandEncoder commandEncoder = encoder;
		if (commandEncoder != null) {
			commandEncoder.dropCaches();
		}
	}

	public static void overrideShaderSource(RenderPipeline pipeline, UnaryOperator<ShaderSource> source) {
		PipelineCache.overrideSource(pipeline, source);
	}

	public static void installGbufferPipelineOverride(GbufferPipelineOverride override) {
		gbufferOverride = Objects.requireNonNull(override, "gbuffer override");
	}

	public static void removeGbufferPipelineOverride(GbufferPipelineOverride override) {
		if (gbufferOverride == override) gbufferOverride = null;
	}

	public static void installGbufferDrawProvider(GbufferDrawBridge.Provider provider) {
		GbufferDrawBridge.install(provider);
	}

	public static void removeGbufferDrawProvider(GbufferDrawBridge.Provider provider) {
		GbufferDrawBridge.remove(provider);
	}

	static GbufferPipelineOverride gbufferPipelineOverride() {
		return gbufferOverride;
	}

	public static CompiledRenderPipeline precompileTerrainMesh(RenderPipeline pipeline, TerrainMeshShader.Program program) {
		MetalDevice owner = deviceOwner;
		return owner == null ? MetalPipeline.invalid(MetalPipeline.Kind.MESH) : owner.precompileTerrainMesh(pipeline, program);
	}

	public static void overrideGbufferState(RenderPipeline pipeline, GbufferStateOverride override,
		List<Integer> outputBuffers) {
		PipelineCache.overrideGbuffer(pipeline, override, outputBuffers);
	}

	public static long createPackMeshPipeline(String object, String mesh, String fragment,
		String objectEntry, String meshEntry, String fragmentEntry, PackPipelineDesc desc) {
		Objects.requireNonNull(object, "object MSL");
		Objects.requireNonNull(mesh, "mesh MSL");
		Objects.requireNonNull(fragment, "fragment MSL");
		Objects.requireNonNull(objectEntry, "object entry");
		Objects.requireNonNull(meshEntry, "mesh entry");
		Objects.requireNonNull(fragmentEntry, "fragment entry");
		Objects.requireNonNull(desc, "pipeline descriptor");
		NativeGpu device = gpu;
		if (device == null) throw new IllegalStateException("Metal backend is not active");
		long handle = device.createMeshPipeline(object, mesh, fragment, objectEntry, meshEntry, fragmentEntry,
			PipelineDescriptor.encodePack(desc));
		if (handle == 0) throw new IllegalStateException("Failed to create pack mesh pipeline: " + device.lastError());
		return handle;
	}

	public static long createPackPipeline(PackPipelineDesc desc) {
		NativeGpu device = gpu;
		if (device == null) {
			throw new IllegalStateException("Metal backend is not active");
		}
		long handle = device.createPackPipeline(desc);
		if (handle == 0) {
			throw new IllegalStateException("Failed to create pack pipeline: " + device.lastError());
		}
		return handle;
	}

	public static long createPackComputePipeline(String msl, String entry) {
		return createPackComputePipeline(msl, entry, 1, 1, 1);
	}

	public static long createPackComputePipeline(String msl, String entry, int threadsX, int threadsY, int threadsZ) {
		if (threadsX <= 0 || threadsY <= 0 || threadsZ <= 0) {
			throw new IllegalArgumentException("compute threadgroup dimensions must be positive");
		}
		NativeGpu device = gpu;
		if (device == null) {
			throw new IllegalStateException("Metal backend is not active");
		}
		long handle = device.createComputePipeline(msl, entry, threadsX, threadsY, threadsZ);
		if (handle == 0) {
			throw new IllegalStateException("Failed to create pack compute pipeline: " + device.lastError());
		}
		return handle;
	}

	public static GpuBuffer createStorageBuffer(long size) {
		return storageOwner().createBuffer(() -> "pack storage buffer", GpuBuffer.USAGE_COPY_DST, validSize(size));
	}

	public static GpuBuffer createStorageBuffer(ByteBuffer initialData) {
		Objects.requireNonNull(initialData, "initialData");
		if (!initialData.hasRemaining()) {
			throw new IllegalArgumentException("initialData must not be empty");
		}
		return storageOwner().createBuffer(() -> "pack storage buffer", GpuBuffer.USAGE_COPY_DST, initialData.duplicate());
	}

	public static GpuTexture createStorageImage(GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
		Objects.requireNonNull(format, "format");
		if (width <= 0 || height <= 0 || depthOrLayers <= 0 || mipLevels <= 0) {
			throw new IllegalArgumentException("invalid storage image dimensions");
		}
		return storageOwner().createStorageImage("pack storage image", format, width, height, depthOrLayers, mipLevels);
	}

	public static GpuTexture createPackTexture(GpuFormat format, int width, int height, int depth, String label) {
		Objects.requireNonNull(format, "format");
		if (width <= 0 || height <= 0 || depth <= 0) throw new IllegalArgumentException("invalid pack texture dimensions");
		return storageOwner().createTexture(label, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
			format, width, height, depth, 1);
	}

	public static GpuSampler createPackSampler(boolean blur, boolean clamp) {
		return storageOwner().createSampler(clamp ? com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE
			: com.mojang.blaze3d.textures.AddressMode.REPEAT,
			clamp ? com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE
				: com.mojang.blaze3d.textures.AddressMode.REPEAT,
			blur ? com.mojang.blaze3d.textures.FilterMode.LINEAR : com.mojang.blaze3d.textures.FilterMode.NEAREST,
			blur ? com.mojang.blaze3d.textures.FilterMode.LINEAR : com.mojang.blaze3d.textures.FilterMode.NEAREST,
			1, java.util.OptionalDouble.empty());
	}

	public static void writePackTexture(GpuTexture texture, ByteBuffer source, int depth, int width, int height) {
		Objects.requireNonNull(source, "source");
		if (depth < 0 || depth >= texture.getDepthOrLayers()) throw new IllegalArgumentException("invalid pack texture slice");
		activeEncoder().writeToTexture(requireTexture(texture), source, 0, depth, 0, 0, width, height);
	}

	public static GpuTextureView createStorageImageView(GpuTexture texture) {
		MetalTexture image = requireTexture(texture);
		return storageOwner().createTextureView(image);
	}

	private static long validSize(long size) {
		if (size <= 0) {
			throw new IllegalArgumentException("storage buffer size must be positive");
		}
		return size;
	}

	private static MetalDevice storageOwner() {
		MetalDevice owner = deviceOwner;
		if (owner == null || gpu == null) {
			throw new IllegalStateException("Metal backend is not active");
		}
		return owner;
	}

	private static MetalTexture requireTexture(GpuTexture texture) {
		if (!(Objects.requireNonNull(texture, "texture") instanceof MetalTexture image) || image.isClosed()) {
			throw new IllegalArgumentException("texture is not an active Metal texture");
		}
		return image;
	}

	private static GpuTextureView requireView(GpuTextureView view) {
		GpuTextureView checked = Objects.requireNonNull(view, "view");
		if (!(checked instanceof MetalTextureView) || checked.isClosed()) {
			throw new IllegalArgumentException("texture view is not an active Metal view");
		}
		return checked;
	}

	private static GpuSampler requireSampler(GpuSampler sampler) {
		if (!(Objects.requireNonNull(sampler, "sampler") instanceof MetalSampler checked) || checked.closed()) {
			throw new IllegalArgumentException("sampler is not an active Metal sampler");
		}
		return checked;
	}

	private static GpuBuffer requireBuffer(GpuBuffer buffer) {
		if (!(Objects.requireNonNull(buffer, "buffer") instanceof MetalBuffer metal) || metal.isClosed()) {
			throw new IllegalArgumentException("buffer is not an active Metal buffer");
		}
		return metal;
	}

	private static Access access(Access access) {
		return Objects.requireNonNull(access, "access");
	}

	public static void destroyPackPipeline(long handle) {
		NativeGpu device = gpu;
		if (device != null) {
			device.destroy(NativeGpu.PIPELINE, handle);
		}
	}

	public static void destroyPackComputePipeline(long handle) {
		NativeGpu device = gpu;
		if (device != null) {
			device.destroy(NativeGpu.COMPUTE_PIPELINE, handle);
		}
	}

	public static void usePackPipeline(long handle) {
		MetalCommandEncoder commandEncoder = encoder;
		if (commandEncoder == null) {
			throw new IllegalStateException("Metal backend is not active");
		}
		commandEncoder.usePackPipeline(handle);
	}

	public static void beginCompute() {
		beginCompute(false);
	}

	public static void beginCompute(boolean allowConcurrent) {
		MetalCommandEncoder commandEncoder = activeEncoder();
		commandEncoder.beginCompute(allowConcurrent);
	}

	public static void endCompute() {
		MetalCommandEncoder commandEncoder = activeEncoder();
		commandEncoder.endCompute();
	}

	public static void convertPackDepth(GpuTextureView source, GpuTextureView destination) {
		MetalCommandEncoder commandEncoder = activeEncoder();
		commandEncoder.convertPackDepth(requireView(source), requireView(destination));
	}

	public static void generatePackMipmaps(GpuTextureView target, int firstMip, int mipCount) {
		MetalCommandEncoder commandEncoder = activeEncoder();
		commandEncoder.generatePackMipmaps(requireView(target), firstMip, mipCount);
	}

	public static void copyPackTarget(GpuTexture source, GpuTexture destination) {
		MetalCommandEncoder commandEncoder = activeEncoder();
		commandEncoder.copyPackTarget(requireTexture(source), requireTexture(destination));
	}

	public static void usePackComputePipeline(long handle) {
		activeEncoder().usePackComputePipeline(handle);
	}

	public static void bindPackStorageBuffer(int slot, GpuBuffer buffer, long offset, Access access) {
		if (slot < 0 || slot >= 32 || offset < 0) {
			throw new IllegalArgumentException("invalid storage buffer binding");
		}
		GpuBuffer checked = requireBuffer(buffer);
		if (offset > checked.size()) {
			throw new IllegalArgumentException("storage buffer offset is out of bounds");
		}
		activeEncoder().bindPackComputeBuffer(slot, checked, offset, access(access).code);
	}

	public static void bindPackTexture(int textureSlot, int samplerSlot, GpuTextureView view, GpuSampler sampler, Access access) {
		if (textureSlot < 0 || textureSlot >= 16 || samplerSlot < 0 || samplerSlot >= 16) {
			throw new IllegalArgumentException("invalid compute texture binding");
		}
		GpuTextureView checkedView = requireView(view);
		GpuSampler checkedSampler = requireSampler(sampler);
		activeEncoder().bindPackComputeTexture(textureSlot, samplerSlot, checkedView, checkedSampler, access(access).code);
	}

	public static void bindPackImage(int slot, GpuTextureView view, Access access) {
		if (slot < 0 || slot >= 16) {
			throw new IllegalArgumentException("invalid compute image binding");
		}
		activeEncoder().bindPackComputeImage(slot, requireView(view), access(access).code);
	}

	public static void setPackComputeBytes(int slot, ByteBuffer bytes) {
		if (slot < 0 || slot >= 32) {
			throw new IllegalArgumentException("invalid compute byte binding");
		}
		Objects.requireNonNull(bytes, "bytes");
		ByteBuffer copy = bytes.duplicate();
		byte[] data = new byte[copy.remaining()];
		copy.get(data);
		activeEncoder().setPackComputeBytes(slot, data);
	}

	public static void dispatchPackCompute(int x, int y, int z) {
		if (x <= 0 || y <= 0 || z <= 0) {
			throw new IllegalArgumentException("compute dispatch dimensions must be positive");
		}
		activeEncoder().dispatchPackCompute(x, y, z);
	}

	public static void bindTessellationBuffer(int slot, GpuBuffer buffer, long offset, Access access) {
		if (slot < 0 || slot >= 32 || offset < 0) throw new IllegalArgumentException("invalid tessellation buffer binding");
		GpuBuffer checked = requireBuffer(buffer);
		if (offset > checked.size()) throw new IllegalArgumentException("tessellation buffer offset is out of bounds");
		activeEncoder().bindTessellationBuffer(slot, checked, offset, access(access).code);
	}

	public static void dispatchTessellationCompute(int x, int y, int z) {
		if (x <= 0 || y <= 0 || z <= 0) throw new IllegalArgumentException("tessellation dispatch dimensions must be positive");
		activeEncoder().dispatchTessellationCompute(x, y, z);
	}

	public static void dispatchPackComputeIndirect(GpuBuffer buffer, long offset) {
		if (offset < 0 || offset % Integer.BYTES != 0) {
			throw new IllegalArgumentException("invalid indirect dispatch offset");
		}
		GpuBuffer checked = requireBuffer(buffer);
		if (offset > checked.size() - 12) {
			throw new IllegalArgumentException("indirect dispatch is out of bounds");
		}
		activeEncoder().dispatchPackComputeIndirect(checked, offset);
	}

	private static MetalCommandEncoder activeEncoder() {
		MetalCommandEncoder commandEncoder = encoder;
		if (commandEncoder == null || gpu == null) {
			throw new IllegalStateException("Metal backend is not active");
		}
		return commandEncoder;
	}

	public static void bindPackTexture(int textureSlot, int samplerSlot, GpuTextureView view, GpuSampler sampler, int stages) {
		MetalCommandEncoder commandEncoder = encoder;
		if (commandEncoder == null) {
			throw new IllegalStateException("Metal backend is not active");
		}
		commandEncoder.bindPackTexture(textureSlot, samplerSlot, view, sampler, stages);
	}

	public static void bindPackUniform(int slot, GpuBufferSlice slice) {
		MetalCommandEncoder commandEncoder = encoder;
		if (commandEncoder == null) {
			throw new IllegalStateException("Metal backend is not active");
		}
		commandEncoder.bindPackUniform(slot, slice);
	}

	public static void drawMesh(int threadCount, int objectThreads, int meshThreads) {
		MetalCommandEncoder commandEncoder = encoder;
		if (commandEncoder == null) {
			throw new IllegalStateException("Metal backend is not active");
		}
		commandEncoder.drawMesh(threadCount, objectThreads, meshThreads);
	}

	public static MemorySegment contents(GpuBuffer buffer) {
		return ((MetalBuffer) buffer).contents();
	}

	public static void cullTerrain(int phase, GpuBufferSlice units, int count, int firstSolid, int firstCutout, int firstTranslucent,
		int indicesPerUnit, int sections, GpuBufferSlice out, long argsOffset, GpuTextureView depthView, float[] clip, int cameraX, int cameraY,
		int cameraZ, float offsetX, float offsetY, float offsetZ, boolean fromArgs, long countArgsOffset) {
		cullTerrain(
			phase, OcclusionMode.TWO_PHASE_HIZ.code(), units, count, firstSolid, firstCutout, firstTranslucent, indicesPerUnit, sections, out, argsOffset,
			depthView, clip, new float[16], cameraX, cameraY, cameraZ, offsetX, offsetY, offsetZ, fromArgs, countArgsOffset
		);
	}

	public static void cullTerrain(int phase, int mode, GpuBufferSlice units, int count, int firstSolid, int firstCutout, int firstTranslucent,
		int indicesPerUnit, int sections, GpuBufferSlice out, long argsOffset, GpuTextureView depthView, float[] clip, float[] previousClip,
		int cameraX, int cameraY, int cameraZ, float offsetX, float offsetY, float offsetZ, boolean fromArgs, long countArgsOffset) {
		MetalCommandEncoder commandEncoder = encoder;
		if (commandEncoder == null) {
			throw new IllegalStateException("Metal backend is not active");
		}
		commandEncoder.cullTerrain(
			phase, mode, units, count, firstSolid, firstCutout, firstTranslucent, indicesPerUnit, sections, out, argsOffset, depthView, clip,
			previousClip, cameraX, cameraY, cameraZ, offsetX, offsetY, offsetZ, fromArgs, countArgsOffset
		);
	}

	public static boolean featureHizSupported() {
		return encoder != null && gpu != null;
	}

	public static void featureCull(GpuBufferSlice aabbs, int count, GpuBufferSlice output, GpuTextureView depthView, float[] clip,
		float[] previousClip) {
		featureCull(aabbs, count, 6, output, depthView, clip, previousClip);
	}

	public static void featureCull(GpuBufferSlice aabbs, int count, int strideWords, GpuBufferSlice output, GpuTextureView depthView,
		float[] clip, float[] previousClip) {
		Objects.requireNonNull(aabbs, "aabbs");
		Objects.requireNonNull(output, "output");
		if (count < 0 || strideWords != 6) {
			visibleFeatureMask(output, count);
			return;
		}
		long inputBytes;
		long outputBytes;
		try {
			inputBytes = Math.multiplyExact(Math.multiplyExact((long) count, strideWords), Integer.BYTES);
			outputBytes = Math.multiplyExact((long) count, Integer.BYTES);
		} catch (ArithmeticException exception) {
			visibleFeatureMask(output, count);
			return;
		}
		if (aabbs.offset() % Integer.BYTES != 0 || output.offset() % Integer.BYTES != 0
			|| inputBytes > aabbs.length() || outputBytes > output.length() || depthView == null
			|| clip == null || previousClip == null || clip.length != 16 || previousClip.length != 16
			|| !finite(clip) || !finite(previousClip)) {
			visibleFeatureMask(output, count);
			return;
		}
		MetalCommandEncoder commandEncoder = encoder;
		if (commandEncoder == null || gpu == null) {
			visibleFeatureMask(output, count);
			return;
		}
		commandEncoder.featureCull(aabbs, count, strideWords, output, depthView, clip, previousClip);
	}

	public static MemorySegment featureCullMask(GpuBufferSlice output, int count) {
		Objects.requireNonNull(output, "output");
		if (count < 0 || (long) count * Integer.BYTES > output.length()) {
			throw new IllegalArgumentException("feature cull output is too small");
		}
		return contents(output.buffer()).asSlice(output.offset(), (long) count * Integer.BYTES);
	}

	private static boolean finite(float[] values) {
		for (float value : values) {
			if (!Float.isFinite(value)) {
				return false;
			}
		}
		return true;
	}

	private static void visibleFeatureMask(GpuBufferSlice output, int count) {
		if (count < 0 || (long) count * Integer.BYTES > output.length()) {
			return;
		}
		MemorySegment mask;
		try {
			mask = featureCullMask(output, count);
		} catch (RuntimeException exception) {
			return;
		}
		for (long offset = 0; offset < mask.byteSize(); offset += Integer.BYTES) {
			mask.set(ValueLayout.JAVA_INT_UNALIGNED, offset, 1);
		}
	}

	public static void expandTerrain(GpuBufferSlice updates, int updateCount, GpuBufferSlice visible, int visibleCount, int sections, int cullMask,
		int layerMask, int options, int indicesPerUnit, int capacity, int cameraX, int cameraY, int cameraZ, float offsetX, float offsetY,
		float offsetZ, GpuBufferSlice out, long argsOffset) {
		MetalCommandEncoder commandEncoder = encoder;
		if (commandEncoder == null) {
			throw new IllegalStateException("Metal backend is not active");
		}
		commandEncoder.expandTerrain(
			updates, updateCount, visible, visibleCount, sections, cullMask, layerMask, options, indicesPerUnit, capacity, cameraX, cameraY,
			cameraZ, offsetX, offsetY, offsetZ, out, argsOffset
		);
	}

	public static void metalFx(int mode, GpuTextureView color, GpuTextureView depth, GpuTextureView output, int inputWidth, int inputHeight,
		int outputWidth, int outputHeight, float jitterX, float jitterY, float[] currentInverse, float[] previousClip, boolean reset) {
		MetalCommandEncoder commandEncoder = encoder;
		if (commandEncoder == null) {
			throw new IllegalStateException("Metal backend is not active");
		}
		commandEncoder.metalFx(
			mode, color, depth, output, inputWidth, inputHeight, outputWidth, outputHeight, jitterX, jitterY, currentInverse, previousClip, reset
		);
	}

	public static void metalFxFrameInterpolation(GpuTextureView currentColor, GpuTextureView previousColor, GpuTextureView depth,
		GpuTextureView ui, GpuTextureView output, int inputWidth, int inputHeight, int outputWidth, int outputHeight, float deltaTime,
		float motionScaleX, float motionScaleY, float nearPlane, float farPlane, float fieldOfView, float aspectRatio,
		float[] worldToView, float[] viewToClip, float jitterX, float jitterY, boolean reset, boolean uiComposited, boolean depthReversed) {
		MetalCommandEncoder commandEncoder = encoder;
		if (commandEncoder == null) {
			throw new IllegalStateException("Metal backend is not active");
		}
		commandEncoder.metalFx(
			MetalTextureView.handle(currentColor), MetalTextureView.handle(previousColor), MetalTextureView.handle(depth), 0L,
			ui == null ? 0L : MetalTextureView.handle(ui), MetalTextureView.handle(output), inputWidth, inputHeight, outputWidth, outputHeight,
			deltaTime, motionScaleX, motionScaleY, nearPlane, farPlane, fieldOfView, aspectRatio, worldToView, viewToClip, jitterX, jitterY,
			reset, uiComposited, depthReversed
		);
	}

	public static void lodCull(GpuBufferSlice nodes, GpuBufferSlice meshes, float[] camera, float[] frustum, GpuBufferSlice output, int level,
		int maxLevel, float threshold) {
		if (camera == null || camera.length != 3) {
			throw new IllegalArgumentException("camera must have 3 floats");
		}
		if (frustum == null || frustum.length != 16) {
			throw new IllegalArgumentException("frustum must have 16 floats");
		}
		if (level < 0 || maxLevel < level || !Float.isFinite(threshold) || threshold < 0.0f) {
			throw new IllegalArgumentException("invalid LOD cull metadata");
		}
		GpuBufferSlice checkedNodes = Objects.requireNonNull(nodes, "nodes");
		GpuBufferSlice checkedMeshes = Objects.requireNonNull(meshes, "meshes");
		GpuBufferSlice checkedOutput = Objects.requireNonNull(output, "output");
		if (checkedNodes.length() <= 0 || checkedMeshes.length() <= 0 || checkedOutput.length() <= 0) {
			throw new IllegalArgumentException("LOD cull buffers must not be empty");
		}
		activeEncoder().lodCull(checkedNodes, checkedMeshes, camera, frustum, checkedOutput, level, maxLevel, threshold);
	}

	public static void sortTerrain(GpuBufferSlice records, GpuBufferSlice expected, int count, int items, int indices, int maxPadded,
		float cameraX, float cameraY, float cameraZ) {
		MetalCommandEncoder commandEncoder = encoder;
		if (commandEncoder == null) {
			throw new IllegalStateException("Metal backend is not active");
		}
		commandEncoder.sortTerrain(records, expected, count, items, indices, maxPadded, cameraX, cameraY, cameraZ);
	}

	public static void terrainOitResolve(int k) {
		MetalCommandEncoder commandEncoder = encoder;
		if (commandEncoder == null) {
			throw new IllegalStateException("Metal backend is not active");
		}
		commandEncoder.terrainOitResolve(k);
	}

	public static void checkExpand(GpuBufferSlice cpu, int count, int firstSolid, int firstCutout, int firstTranslucent, GpuBufferSlice gpu,
		long gpuBytes, long argsOffset) {
		MetalCommandEncoder commandEncoder = encoder;
		if (commandEncoder == null) {
			throw new IllegalStateException("Metal backend is not active");
		}
		commandEncoder.checkExpand(cpu, count, firstSolid, firstCutout, firstTranslucent, gpu, gpuBytes, argsOffset);
	}

	public static void pathTrace(GpuTextureView color, GpuTextureView depth, GpuBuffer bricks, GpuBuffer table, int width, int height,
		float resolutionScale, int bounces, float cameraX, float cameraY, float cameraZ, float[] inverseClip, float[] previousClip, int tableOriginX,
		int tableOriginY, int tableOriginZ, float sunDirectionX, float sunDirectionY, float sunDirectionZ, float sunColorR, float sunColorG,
		float sunColorB, float moonDirectionX, float moonDirectionY, float moonDirectionZ, float moonColorR, float moonColorG, float moonColorB) {
		MetalCommandEncoder commandEncoder = encoder;
		if (commandEncoder == null) {
			throw new IllegalStateException("Metal backend is not active");
		}
		commandEncoder.pathTrace(
			MetalTextureView.handle(color), MetalTextureView.handle(depth), MetalBuffer.handle(bricks), MetalBuffer.handle(table), width, height,
			resolutionScale, bounces, cameraX, cameraY, cameraZ, inverseClip, previousClip, tableOriginX, tableOriginY, tableOriginZ, sunDirectionX,
			sunDirectionY, sunDirectionZ, sunColorR, sunColorG, sunColorB, moonDirectionX, moonDirectionY, moonDirectionZ, moonColorR, moonColorG,
			moonColorB
		);
	}

	public static boolean cullingSupported() {
		return encoder != null;
	}
}
