package dev.mikuteto.mtlrender.backend;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferPipelineOverride;
import dev.mikuteto.mtlrender.pack.runtime.PackRuntimeService;
import dev.mikuteto.mtlrender.terrain.TerrainMeshShader;
import dev.mikuteto.mtlrender.debug.MemoryDiagnostics;
import dev.mikuteto.mtlrender.tune.MemoryBudget;
import dev.mikuteto.mtlrender.tune.Tier;
import dev.mikuteto.mtlrender.tune.TuneProfile;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.DeviceFeatures;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.DeviceLimits;
import com.mojang.blaze3d.systems.DeviceType;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.HintsAndWorkarounds;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.lwjgl.glfw.GLFWNativeCocoa;

final class MetalDevice implements GpuDeviceBackend {
	private static final DeviceLimits LIMITS = new DeviceLimits(16, 256, 16384, Long.MAX_VALUE, 0, 8);
	private static final DeviceFeatures FEATURES = new DeviceFeatures(true, false, false, false, true, true, true);

	private final NativeGpu gpu;
	private final PipelineCache pipelines;
	private final GbufferPipelineOverride gbufferOverride;
	private final AllocationPool pool;
	private final MetalCommandEncoder encoder;
	private final DeviceInfo info;
	private final MemoryBudget budget;
	private boolean closed;

	MetalDevice(NativeGpu gpu, ShaderSource defaultShaderSource) {
		this.gpu = gpu;
		this.gbufferOverride = new GbufferPipelineOverride();
		this.pipelines = new PipelineCache(gpu, defaultShaderSource, gbufferOverride);
		Tier tier = Tier.of(gpu.hardware());
		TuneProfile profile = TuneProfile.defaults(tier);
		this.budget = MemoryBudget.derive(gpu.hardware(), tier, profile);
		this.pool = new AllocationPool(gpu, budget);
		MemoryDiagnostics.global().registerPool("allocation", pool::pooledBytes);
		MemoryDiagnostics.global().registerPool("allocation_cap", pool::cap);
		MemoryDiagnostics.global().registerPool("gpu_live", pool::liveBytes);
		MemoryDiagnostics.global().registerPool("terrain_capacity", dev.mikuteto.mtlrender.terrain.DirectUberBuffer::capacityBytes);
		MemoryDiagnostics.global().registerPool("terrain_used", dev.mikuteto.mtlrender.terrain.DirectUberBuffer::usedBytes);
		MemoryDiagnostics.global().registerPool("terrain_pending_frees", dev.mikuteto.mtlrender.terrain.DirectUberBuffer::pendingFreeBytes);
		MemoryDiagnostics.global().registerPool("terrain_staging_duplicate", dev.mikuteto.mtlrender.terrain.DirectUberBuffer::duplicatedStagingBytes);
		MemoryDiagnostics.global().registerPool("terrain_cpu_copy", dev.mikuteto.mtlrender.terrain.SectionCpuRetention::retainedBytes);
		MemoryDiagnostics.global().registerPool("terrain_voxel_retained", dev.mikuteto.mtlrender.pathtracing.VoxelBricks::retainedBytes);
		this.encoder = new MetalCommandEncoder(gpu, pipelines, pool);
		MetalAccess.attach(this, gpu, encoder);
		MetalAccess.installGbufferPipelineOverride(gbufferOverride);
		this.info = new DeviceInfo(
			gpu.name(),
			"Apple",
			"Metal 4 (MTLRender)",
			true,
			"Metal",
			(float) (1.0e9 / Math.max(1, gpu.timestampFrequency())),
			LIMITS,
			FEATURES,
			Set.of(),
			new HintsAndWorkarounds(true, false),
			DeviceType.INTEGRATED
		);
	}

	MemoryBudget budget() {
		return budget;
	}

	@Override
	public GpuSurfaceBackend createSurface(long window) {
		return new MetalSurface(gpu, gpu.createSurface(GLFWNativeCocoa.glfwGetCocoaWindow(window)));
	}

	@Override
	public CommandEncoderBackend createCommandEncoder() {
		return encoder;
	}

	@Override
	public GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int maxAnisotropy, OptionalDouble maxLod) {
		return new MetalSampler(gpu, u, v, min, mag, maxAnisotropy, maxLod);
	}

	@Override
	public GpuTexture createTexture(Supplier<String> label, int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
		return createTexture(label == null ? "" : label.get(), usage, format, width, height, depthOrLayers, mipLevels);
	}

	@Override
	public GpuTexture createTexture(String label, int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
		return new MetalTexture(gpu, usage, label == null ? "" : label, format, width, height, depthOrLayers, mipLevels, false);
	}

	GpuTexture createStorageImage(String label, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
		return new MetalTexture(gpu, GpuTexture.USAGE_TEXTURE_BINDING, label, format, width, height, depthOrLayers, mipLevels, true);
	}

	@Override
	public GpuTextureView createTextureView(GpuTexture texture) {
		return createTextureView(texture, 0, texture.getMipLevels());
	}

	@Override
	public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
		return new MetalTextureView(gpu, (MetalTexture) texture, baseMipLevel, mipLevels);
	}

	@Override
	public GpuBuffer createBuffer(Supplier<String> label, int usage, long size) {
		return new DeviceBuffer(pool, usage, size);
	}

	@Override
	public GpuBuffer createBuffer(Supplier<String> label, int usage, ByteBuffer data) {
		DeviceBuffer buffer = new DeviceBuffer(pool, usage, data.remaining());
		MemorySegment.copy(MemorySegment.ofBuffer(data), 0, buffer.contents(), 0, data.remaining());
		return buffer;
	}

	@Override
	public List<String> getLastDebugMessages() {
		return List.of();
	}

	@Override
	public boolean isDebuggingEnabled() {
		return false;
	}

	@Override
	public CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline, ShaderSource shaderSource) {
		return pipelines.precompile(pipeline, shaderSource);
	}

	CompiledRenderPipeline precompileTerrainMesh(RenderPipeline pipeline, TerrainMeshShader.Program program) {
		return pipelines.precompileMesh(pipeline, program);
	}

	@Override
	public void clearPipelineCache() {
		pipelines.clear();
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		PackRuntimeService.closeIfPresent();
		MetalAccess.removeGbufferPipelineOverride(gbufferOverride);
		MetalAccess.detach();
		encoder.close();
		pool.close();
		pipelines.close();
		gpu.close();
	}

	@Override
	public GpuQueryPool createTimestampQueryPool(int size) {
		return new MetalQueryPool(gpu, size);
	}

	@Override
	public long getTimestampNow() {
		return System.nanoTime();
	}

	@Override
	public DeviceInfo getDeviceInfo() {
		return info;
	}
}
