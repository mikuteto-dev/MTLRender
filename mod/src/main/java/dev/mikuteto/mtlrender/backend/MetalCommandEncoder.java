package dev.mikuteto.mtlrender.backend;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.debug.FrameStats;
import dev.mikuteto.mtlrender.debug.PassLabels;
import dev.mikuteto.mtlrender.terrain.OcclusionMode;
import org.joml.Vector4fc;

final class MetalCommandEncoder implements CommandEncoderBackend, AutoCloseable {
	private static final long STAGING_ALIGNMENT = 16;
	private static final long CLOSE_TIMEOUT_MS = 5000;
	private static final int MAX_PASS_TIMES = 1024;
	private static final float[] EMPTY_CLIP = new float[16];

	private final NativeGpu gpu;
	private final PipelineCache pipelines;
	private final CommandStream commands = new CommandStream();
	private final FrameQueue completions = new FrameQueue();
	private final MetalTransientMemory transientMemory;
	private final AllocationPool pool;
	private BindingTable bindings;
	private MetalRenderPass pass;
	private boolean computePass;
	private boolean closed;
	private MemorySegment surface = MemorySegment.NULL;
	private final long[] reportedStats = new long[NativeGpu.STAT_COUNT];
	private final MemorySegment passTimes = PassLabels.enabled()
		? Arena.ofAuto().allocate(ValueLayout.JAVA_LONG, (long) FrameStats.PASS_RECORD_LONGS * MAX_PASS_TIMES)
		: null;

	MetalCommandEncoder(NativeGpu gpu, PipelineCache pipelines, AllocationPool pool) {
		this.gpu = gpu;
		this.pipelines = pipelines;
		this.pool = pool;
		this.transientMemory = new MetalTransientMemory(pool);
	}

	void addCompletion(Runnable action) {
		completions.add(gpu.submittedFrames(), action);
	}

	void blitToSurface(MemorySegment target, GpuTextureView view) {
		commands.blitToSurface(MetalTextureView.handle(view));
		surface = target;
	}

	void usePackPipeline(long handle) {
		if (pass == null) {
			throw new IllegalStateException("No render pass is open");
		}
		pass.usePackPipeline(handle);
	}

	void beginCompute() {
		beginCompute(false);
	}

	void beginCompute(boolean allowConcurrent) {
		if (pass != null || computePass) {
			throw new IllegalStateException("A pass is already open");
		}
		commands.beginCompute(allowConcurrent);
		computePass = true;
	}

	void endCompute() {
		if (!computePass) {
			throw new IllegalStateException("No compute pass is open");
		}
		commands.endCompute();
		computePass = false;
	}

	void convertPackDepth(GpuTextureView source, GpuTextureView destination) {
		if (pass != null || computePass) {
			throw new IllegalStateException("Pack depth conversion must be outside an open pass");
		}
		commands.packDepthConvert(MetalTextureView.handle(source), MetalTextureView.handle(destination));
	}

	void generatePackMipmaps(GpuTextureView target, int firstMip, int mipCount) {
		if (pass != null || computePass) {
			throw new IllegalStateException("Pack mipmap generation must be outside an open pass");
		}
		MetalTextureView view = (MetalTextureView) target;
		MetalTexture texture = (MetalTexture) view.texture();
		validatePackMipmap(texture.isClosed(), view.isClosed(), texture.usage(), texture.getFormat(), view.mipLevels(), firstMip, mipCount);
		commands.generateMipmaps(MetalTextureView.handle(view), firstMip, mipCount);
	}

	void copyPackTarget(GpuTexture source, GpuTexture destination) {
		if (pass != null || computePass) {
			throw new IllegalStateException("Pack target copy must be outside an open pass");
		}
		MetalTexture from = (MetalTexture) source;
		MetalTexture to = (MetalTexture) destination;
		validatePackCopy(from.isClosed(), to.isClosed(), from.usage(), to.usage(), from.getFormat(), to.getFormat(),
			from.getDepthOrLayers(), to.getDepthOrLayers(), from.getWidth(0), from.getHeight(0), to.getWidth(0), to.getHeight(0));
		copyTextureToTexture(source, destination, 0, 0, 0, 0, 0, from.getWidth(0), from.getHeight(0));
	}

	static void validatePackMipmap(boolean textureClosed, boolean viewClosed, int usage, com.mojang.blaze3d.GpuFormat format,
		int viewMipLevels, int firstMip, int mipCount) {
		if (textureClosed || viewClosed) throw new IllegalStateException("Pack mipmap target is closed");
		if (!format.hasColorAspect() || (usage & GpuTexture.USAGE_TEXTURE_BINDING) == 0 || !filterable(format)) {
			throw new IllegalArgumentException("Pack mipmap target must be a filterable color texture");
		}
		if (firstMip < 0 || mipCount <= 1 || firstMip > viewMipLevels - mipCount) {
			throw new IllegalArgumentException("Pack mipmap range is outside the target view");
		}
	}

	static void validatePackCopy(boolean sourceClosed, boolean destinationClosed, int sourceUsage, int destinationUsage,
		com.mojang.blaze3d.GpuFormat sourceFormat, com.mojang.blaze3d.GpuFormat destinationFormat, int sourceLayers,
		int destinationLayers, int sourceWidth, int sourceHeight, int destinationWidth, int destinationHeight) {
		if (sourceClosed || destinationClosed) throw new IllegalStateException("Pack target copy uses a closed texture");
		if (!sourceFormat.hasColorAspect() || !destinationFormat.hasColorAspect() || sourceFormat != destinationFormat) {
			throw new IllegalArgumentException("Pack target copy requires matching color formats");
		}
		if ((sourceUsage & GpuTexture.USAGE_COPY_SRC) == 0 || (destinationUsage & GpuTexture.USAGE_COPY_DST) == 0) {
			throw new IllegalArgumentException("Pack target copy requires COPY_SRC and COPY_DST usage");
		}
		if (sourceLayers > 1 || destinationLayers > 1) {
			throw new UnsupportedOperationException("Pack target copy does not support texture arrays");
		}
		if (sourceWidth != destinationWidth || sourceHeight != destinationHeight) {
			throw new IllegalArgumentException("Pack target copy requires matching mip-0 dimensions");
		}
	}

	private static boolean filterable(com.mojang.blaze3d.GpuFormat format) {
		return switch (format) {
			case R8_UNORM, R8_SNORM, RG8_UNORM, RG8_SNORM, RGB8_UNORM, RGB8_SNORM, RGBA8_UNORM, RGBA8_SNORM,
				R16_UNORM, R16_SNORM, RG16_UNORM, RG16_SNORM, RGB16_UNORM, RGB16_SNORM, RGBA16_UNORM, RGBA16_SNORM,
				R16_FLOAT, RG16_FLOAT, RGB16_FLOAT, RGBA16_FLOAT, R32_FLOAT, RG32_FLOAT, RGB32_FLOAT, RGBA32_FLOAT,
				RGB10A2_UNORM, RG11B10_FLOAT -> true;
			default -> false;
		};
	}

	private void requireCompute() {
		if (!computePass) {
			throw new IllegalStateException("No compute pass is open");
		}
	}

	void usePackComputePipeline(long handle) {
		requireCompute();
		commands.computePipeline(handle);
	}

	void bindPackComputeBuffer(int slot, GpuBuffer buffer, long offset, int access) {
		requireCompute();
		commands.computeBuffer(slot, MetalBuffer.handle(buffer), offset, access);
	}

	void bindPackComputeTexture(int textureSlot, int samplerSlot, GpuTextureView view, com.mojang.blaze3d.textures.GpuSampler sampler, int access) {
		requireCompute();
		commands.computeTexture(textureSlot, samplerSlot, MetalTextureView.handle(view), MetalSampler.handle(sampler), access);
	}

	void bindPackComputeImage(int slot, GpuTextureView view, int access) {
		requireCompute();
		commands.computeImage(slot, MetalTextureView.handle(view), access);
	}

	void setPackComputeBytes(int slot, byte[] bytes) {
		requireCompute();
		commands.computeBytes(slot, bytes);
	}

	void dispatchPackCompute(int x, int y, int z) {
		requireCompute();
		commands.computeDispatch(x, y, z);
	}

	void bindTessellationBuffer(int slot, GpuBuffer buffer, long offset, int access) {
		bindPackComputeBuffer(slot, buffer, offset, access);
	}

	void dispatchTessellationCompute(int x, int y, int z) {
		dispatchPackCompute(x, y, z);
	}

	void dispatchPackComputeIndirect(GpuBuffer buffer, long offset) {
		requireCompute();
		commands.computeDispatchIndirect(MetalBuffer.handle(buffer), offset);
	}

	void bindPackTexture(int textureSlot, int samplerSlot, GpuTextureView view, com.mojang.blaze3d.textures.GpuSampler sampler, int stages) {
		if (pass == null) {
			throw new IllegalStateException("No render pass is open");
		}
		commands.bindTexture(textureSlot, samplerSlot, MetalTextureView.handle(view), MetalSampler.handle(sampler), stages);
	}

	void bindPackUniform(int slot, GpuBufferSlice slice) {
		if (pass == null) {
			throw new IllegalStateException("No render pass is open");
		}
		commands.bindBuffer(slot, MetalBuffer.handle(slice.buffer()), slice.offset());
	}

	void drawMesh(int threadCount, int objectThreads, int meshThreads) {
		if (pass != null) {
			pass.drawMesh(threadCount, objectThreads, meshThreads);
		}
	}

	boolean meshDispatch(MetalAccess.MeshDispatch request) {
		if (pass != null || computePass || request == null) {
			return false;
		}
		long before = commands.size();
		commands.meshDispatch(
			request.objectX(), request.objectY(), request.objectZ(),
			request.meshX(), request.meshY(), request.meshZ(),
			request.payloadBytes(), request.vertexCount(), request.primitiveCount(),
			request.resourceAddress(), request.resourceOffset(), request.resourceLength(),
			request.payloadOffset(), request.vertexOffset(), request.vertexBytes(),
			request.primitiveOffset(), request.primitiveBytes(),
			request.geometryX(), request.geometryY(), request.geometryZ()
		);
		return commands.size() != before;
	}

	@Override
	public void submit() {
		if (pass != null || computePass) {
			throw new IllegalStateException("Cannot submit while a pass is open");
		}
		long start = System.nanoTime();
		long frame = gpu.submittedFrames();
		completions.add(frame, transientMemory.endSubmit());
		NativeGpu.FrameState state = gpu.submit(commands, surface);
		MetalAccess.publishFrameState(state.submitted(), state.completed());
		long submitNanos = System.nanoTime() - start;
		FrameStats stats = FrameStats.get();
		if (stats.enabled()) {
			stats.recordBackend(new FrameStats.BackendFrame(
			submitNanos,
			commands.draws(),
			commands.passes(),
			commands.size(),
			statDelta(NativeGpu.STAT_GPU_BUSY),
			statDelta(NativeGpu.STAT_FRAME_WAIT),
			statDelta(NativeGpu.STAT_ENCODE),
			statDelta(NativeGpu.STAT_DRAWABLE),
			statDelta(NativeGpu.STAT_COMMIT),
			statDelta(NativeGpu.STAT_PRESENTS)
			));
			if (passTimes != null) {
				stats.recordPasses(passTimes, gpu.passTimes(passTimes));
			}
		}
		commands.reset();
		surface = MemorySegment.NULL;
		completions.runCompleted(state.completed());
	}

	private long statDelta(int counter) {
		long total = gpu.statNanos(counter);
		long delta = total - reportedStats[counter];
		reportedStats[counter] = total;
		return delta;
	}

	void dropCaches() {
		pipelines.clear();
		pool.clear();
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		long submitted = gpu.submittedFrames();
		if (submitted > 0) {
			gpu.waitFrame(submitted - 1, CLOSE_TIMEOUT_MS);
		}
		completions.runAll();
		transientMemory.close();
	}

	@Override
	public TransientMemory transientMemory() {
		return transientMemory;
	}

	@Override
	public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
		if (pass != null || computePass) {
			throw new IllegalStateException("Close the existing render pass before creating a new one");
		}
		RenderPass.RenderArea area = descriptor.renderArea != null ? descriptor.renderArea : fullArea(descriptor);
		if (bindings == null) {
			bindings = new BindingTable(commands);
		}
		bindings.reset();
		pass = new MetalRenderPass(pipelines, commands, descriptor, area, bindings);
		return pass;
	}

	@Override
	public void submitRenderPass() {
		if (pass == null) {
			throw new IllegalStateException("Cannot submit a renderpass if one hasn't been started!");
		}
		pass.beginIfUnused();
		commands.endPass();
		pass = null;
	}

	void cullTerrain(int phase, GpuBufferSlice units, int count, int firstSolid, int firstCutout, int firstTranslucent, int indicesPerUnit,
		int sections, GpuBufferSlice out, long argsOffset, GpuTextureView depthView, float[] clip, int cameraX, int cameraY, int cameraZ,
		float offsetX, float offsetY, float offsetZ, boolean fromArgs, long countArgsOffset) {
		cullTerrain(
			phase, OcclusionMode.TWO_PHASE_HIZ.code(), units, count, firstSolid, firstCutout, firstTranslucent, indicesPerUnit, sections, out, argsOffset,
			depthView, clip, EMPTY_CLIP, cameraX, cameraY, cameraZ, offsetX, offsetY, offsetZ, fromArgs, countArgsOffset
		);
	}

	void cullTerrain(int phase, int mode, GpuBufferSlice units, int count, int firstSolid, int firstCutout, int firstTranslucent, int indicesPerUnit,
		int sections, GpuBufferSlice out, long argsOffset, GpuTextureView depthView, float[] clip, float[] previousClip, int cameraX, int cameraY,
		int cameraZ, float offsetX, float offsetY, float offsetZ, boolean fromArgs, long countArgsOffset) {
		if (pass != null) {
			throw new IllegalStateException("cullTerrain inside a render pass");
		}
		commands.terrainCull(
			phase, mode, MetalBuffer.handle(units.buffer()), units.offset(), count, firstSolid, firstCutout, firstTranslucent, indicesPerUnit, sections,
			MetalBuffer.handle(out.buffer()), out.offset(), argsOffset, depthView == null ? 0 : MetalTextureView.handle(depthView), clip, previousClip,
			cameraX, cameraY, cameraZ, offsetX, offsetY, offsetZ, fromArgs, countArgsOffset
		);
	}

	void featureCull(GpuBufferSlice aabbs, int count, int strideWords, GpuBufferSlice out, GpuTextureView depthView, float[] clip,
		float[] previousClip) {
		if (pass != null) {
			throw new IllegalStateException("feature cull inside a render pass");
		}
		commands.featureCull(
			MetalBuffer.handle(aabbs.buffer()), aabbs.offset(), count, strideWords, MetalBuffer.handle(out.buffer()), out.offset(),
			depthView == null ? 0 : MetalTextureView.handle(depthView), clip, previousClip
		);
	}

	void expandTerrain(GpuBufferSlice updates, int updateCount, GpuBufferSlice visible, int visibleCount, int sections, int cullMask,
		int layerMask, int options, int indicesPerUnit, int capacity, int cameraX, int cameraY, int cameraZ, float offsetX, float offsetY,
		float offsetZ, GpuBufferSlice out, long argsOffset) {
		if (pass != null) {
			throw new IllegalStateException("expandTerrain inside a render pass");
		}
		commands.terrainExpand(
			updates == null ? 0 : MetalBuffer.handle(updates.buffer()), updates == null ? 0 : updates.offset(), updateCount,
			visible == null ? 0 : MetalBuffer.handle(visible.buffer()), visible == null ? 0 : visible.offset(), visibleCount, sections,
			cullMask, layerMask, options, indicesPerUnit, capacity, cameraX, cameraY, cameraZ, offsetX, offsetY, offsetZ,
			MetalBuffer.handle(out.buffer()), out.offset(), argsOffset
		);
	}

	void metalFx(int mode, GpuTextureView color, GpuTextureView depth, GpuTextureView output, int inputWidth, int inputHeight, int outputWidth,
		int outputHeight, float jitterX, float jitterY, float[] currentInverse, float[] previousClip, boolean reset) {
		if (pass != null) {
			throw new IllegalStateException("MetalFX inside a render pass");
		}
		commands.metalFx(
			mode, MetalTextureView.handle(color), MetalTextureView.handle(depth), MetalTextureView.handle(output), inputWidth, inputHeight,
			outputWidth, outputHeight, jitterX, jitterY, currentInverse, previousClip, reset
		);
	}

	void metalFx(long currentColor, long previousColor, long depth, long motion, long ui, long output, int inputWidth, int inputHeight,
		int outputWidth, int outputHeight, float deltaTime, float motionScaleX, float motionScaleY, float nearPlane, float farPlane,
		float fieldOfView, float aspectRatio, float[] worldToView, float[] viewToClip, float jitterX, float jitterY, boolean reset,
		boolean uiComposited, boolean depthReversed) {
		if (pass != null) {
			throw new IllegalStateException("MetalFX inside a render pass");
		}
		commands.metalFx(
			currentColor, previousColor, depth, motion, ui, output, inputWidth, inputHeight, outputWidth, outputHeight, deltaTime,
			motionScaleX, motionScaleY, nearPlane, farPlane, fieldOfView, aspectRatio, worldToView, viewToClip, jitterX, jitterY, reset,
			uiComposited, depthReversed
		);
	}

	void lodCull(GpuBufferSlice nodes, GpuBufferSlice meshes, float[] camera, float[] frustum, GpuBufferSlice output, int level, int maxLevel,
		float threshold) {
		if (pass != null) {
			throw new IllegalStateException("lodCull inside a render pass");
		}
		commands.lodCull(
			MetalBuffer.handle(nodes.buffer()), nodes.offset(), nodes.length(), MetalBuffer.handle(meshes.buffer()), meshes.offset(), meshes.length(),
			camera, frustum, MetalBuffer.handle(output.buffer()), output.offset(), output.length(), level, maxLevel, threshold
		);
	}

	void sortTerrain(GpuBufferSlice records, GpuBufferSlice expected, int count, int items, int indices, int maxPadded,
		float cameraX, float cameraY, float cameraZ) {
		if (pass != null) {
			throw new IllegalStateException("sortTerrain inside a render pass");
		}
		commands.terrainSort(
			MetalBuffer.handle(records.buffer()), records.offset(), expected == null ? 0 : MetalBuffer.handle(expected.buffer()),
			expected == null ? 0 : expected.offset(), count, items, indices, maxPadded, cameraX, cameraY, cameraZ
		);
	}

	void terrainOitResolve(int k) {
		if (pass == null) {
			throw new IllegalStateException("OIT resolve requires an open render pass");
		}
		pass.resolveOit(k);
	}

	void checkExpand(GpuBufferSlice cpu, int count, int firstSolid, int firstCutout, int firstTranslucent, GpuBufferSlice gpu, long gpuBytes,
		long argsOffset) {
		if (pass != null) {
			throw new IllegalStateException("checkExpand inside a render pass");
		}
		commands.terrainExpandCheck(
			MetalBuffer.handle(cpu.buffer()), cpu.offset(), count, firstSolid, firstCutout, firstTranslucent, MetalBuffer.handle(gpu.buffer()),
			gpu.offset(), gpuBytes, argsOffset
		);
	}

	void pathTrace(long color, long depth, long bricks, long table, int width, int height, float resolutionScale, int bounces, float cameraX,
		float cameraY, float cameraZ, float[] inverseClip, float[] previousClip, int tableOriginX, int tableOriginY, int tableOriginZ, float sunDirectionX,
		float sunDirectionY, float sunDirectionZ, float sunColorR, float sunColorG, float sunColorB, float moonDirectionX, float moonDirectionY,
		float moonDirectionZ, float moonColorR, float moonColorG, float moonColorB) {
		if (pass != null) {
			throw new IllegalStateException("path tracing inside a render pass");
		}
		commands.pathTrace(
			color, depth, bricks, table, width, height, resolutionScale, bounces, cameraX, cameraY, cameraZ, inverseClip, previousClip, tableOriginX,
			tableOriginY, tableOriginZ, sunDirectionX, sunDirectionY, sunDirectionZ, sunColorR, sunColorG, sunColorB, moonDirectionX, moonDirectionY,
			moonDirectionZ, moonColorR, moonColorG, moonColorB
		);
	}

	@Override
	public void clearColorTexture(GpuTexture texture, Vector4fc color) {
		commands.clear(MetalTexture.handle(texture), color, 0);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture color, Vector4fc clearColor, GpuTexture depth, double clearDepth) {
		commands.clear(MetalTexture.handle(color), clearColor, 0);
		commands.clear(MetalTexture.handle(depth), null, clearDepth);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture color, Vector4fc clearColor, GpuTexture depth, double clearDepth, int x, int y, int width,
		int height) {
		commands.clearRegion(MetalTexture.handle(color), clearColor, MetalTexture.handle(depth), clearDepth, x, y, width, height);
	}

	@Override
	public void clearDepthTexture(GpuTexture texture, double clearDepth) {
		commands.clear(MetalTexture.handle(texture), null, clearDepth);
	}

	@Override
	public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
		if (destination.buffer() instanceof DeviceBuffer buffer && buffer.rename(pool, destination.offset(), MemorySegment.ofBuffer(data))) {
			return;
		}
		int length = data.remaining();
		GpuBufferSlice staging = transientMemory.uploadStaging(data, STAGING_ALIGNMENT, GpuBuffer.USAGE_COPY_SRC);
		commands.copyBuffer(MetalBuffer.handle(staging.buffer()), staging.offset(), MetalBuffer.handle(destination.buffer()), destination.offset(), length);
		if (destination.buffer() instanceof DeviceBuffer buffer) {
			buffer.markGpuWritten();
		}
	}

	@Override
	public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice destination) {
		if (source.buffer() instanceof DeviceBuffer from && destination.buffer() instanceof DeviceBuffer to && from != to && from.cpuWritten()
			&& to.rename(pool, destination.offset(), from.contents().asSlice(source.offset(), source.length()))) {
			return;
		}
		commands.copyBuffer(
			MetalBuffer.handle(source.buffer()), source.offset(), MetalBuffer.handle(destination.buffer()), destination.offset(), source.length()
		);
		if (destination.buffer() instanceof DeviceBuffer buffer) {
			buffer.markGpuWritten();
		}
	}

	@Override
	public void writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer, int x, int y, int width, int height) {
		GpuBufferSlice staging = transientMemory.uploadStaging(source, STAGING_ALIGNMENT, GpuBuffer.USAGE_COPY_SRC);
		int bytesPerRow = width * destination.getFormat().blockSize();
		commands.copyBufferToTexture(
			MetalBuffer.handle(staging.buffer()), staging.offset(), bytesPerRow, MetalTexture.handle(destination), mipLevel, depthOrLayer, x, y, width, height
		);
	}

	@Override
	public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight, GpuTexture destination,
		int destX, int destY, int copyWidth, int copyHeight, int mipLevel, int depthOrLayer) {
		int texelSize = destination.getFormat().blockSize();
		long offset = source.offset() + (sourceX + (long) sourceY * sourceWidth) * texelSize;
		commands.copyBufferToTexture(
			MetalBuffer.handle(source.buffer()), offset, sourceWidth * texelSize, MetalTexture.handle(destination), mipLevel, depthOrLayer, destX, destY,
			copyWidth, copyHeight
		);
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
		copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel, int x, int y, int width,
		int height) {
		int bytesPerRow = width * source.getFormat().blockSize();
		commands.copyTextureToBuffer(MetalTexture.handle(source), mipLevel, x, y, width, height, MetalBuffer.handle(destination), offset, bytesPerRow);
		if (destination instanceof DeviceBuffer buffer) {
			buffer.markGpuWritten();
		}
		completions.add(gpu.submittedFrames(), callback);
	}

	@Override
	public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY, int sourceX, int sourceY, int width,
		int height) {
		commands.copyTextureToTexture(MetalTexture.handle(source), MetalTexture.handle(destination), mipLevel, destX, destY, sourceX, sourceY, width, height);
	}

	@Override
	public GpuFence createFence() {
		return new MetalFence(gpu, gpu.submittedFrames());
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
		MetalQueryPool queries = MetalQueryPool.cast(pool);
		commands.writeTimestamp(queries.handle(), index);
		queries.markWritten(index, gpu.submittedFrames());
	}

	private static RenderPass.RenderArea fullArea(RenderPassDescriptor descriptor) {
		GpuTextureView target = descriptor.colorAttachments().stream()
			.filter(attachment -> attachment != null)
			.map(RenderPassDescriptor.Attachment::textureView)
			.findFirst()
			.orElseGet(() -> descriptor.depthAttachment().textureView());
		return new RenderPass.RenderArea(0, 0, target.getWidth(0), target.getHeight(0));
	}
}
