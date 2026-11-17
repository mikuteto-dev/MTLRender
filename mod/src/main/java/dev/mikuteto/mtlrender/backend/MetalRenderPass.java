package dev.mikuteto.mtlrender.backend;

import java.nio.IntBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import dev.mikuteto.mtlrender.debug.PassLabels;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferAttachmentRouter;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferDrawBridge;
import org.joml.Vector4fc;
import org.lwjgl.PointerBuffer;
import org.slf4j.Logger;

final class MetalRenderPass implements RenderPassBackend {
	private static final Logger LOGGER = LogUtils.getLogger();
	private final PipelineCache pipelines;
	private final CommandStream commands;
	private final RenderPassDescriptor descriptor;
	private final RenderPass.RenderArea area;
	private final BindingTable bindings;
	private boolean begun;
	private MetalPipeline pipeline;
	private int debugGroups;
	private GpuBuffer indexBuffer;
	private IndexType indexType;
	private String drawUniformName;

	MetalRenderPass(PipelineCache pipelines, CommandStream commands, RenderPassDescriptor descriptor,
		RenderPass.RenderArea area, BindingTable bindings) {
		this.pipelines = pipelines;
		this.commands = commands;
		this.descriptor = descriptor;
		this.area = area;
		this.bindings = bindings;
	}

	@Override
	public void pushDebugGroup(Supplier<String> label) {
		debugGroups++;
	}

	@Override
	public void popDebugGroup() {
		if (debugGroups == 0) {
			throw new IllegalStateException("Can't pop more debug groups than was pushed!");
		}
		debugGroups--;
	}

	@Override
	public void setPipeline(RenderPipeline renderPipeline) {
		MetalPipeline compiled = pipelines.get(renderPipeline);
		if (!compiled.isValid()) {
			throw new IllegalStateException("Pipeline is not valid (may contain invalid shaders?): " + renderPipeline.getLocation());
		}
		begin(renderPipeline, compiled);
		if (compiled != pipeline) {
			clearDrawUniform();
			pipeline = compiled;
			commands.setPipeline(compiled.handle());
			bindings.useLayout(compiled.bindings());
		}
	}

	void beginIfUnused() {
		beginVanilla();
	}

	void usePackPipeline(long handle) {
		if (handle == 0) {
			throw new IllegalArgumentException("Pack pipeline handle is zero");
		}
		beginVanilla();
		clearDrawUniform();
		pipeline = new MetalPipeline(handle, List.of(), MetalPipeline.Kind.RASTER, false);
		bindings.useLayout(List.of());
		commands.setPipeline(handle);
	}

	@Override
	public void bindTexture(String name, GpuTextureView view, GpuSampler sampler) {
		if (view != null && sampler != null) {
			bindings.setTexture(name, MetalTextureView.handle(view), MetalSampler.handle(sampler));
		} else if (view == null && sampler == null) {
			bindings.clearTexture(name);
		} else {
			throw new IllegalArgumentException("Texture " + name + " needs both a view and a sampler");
		}
	}

	@Override
	public void setUniform(String name, GpuBuffer buffer) {
		setUniform(name, buffer.slice());
	}

	@Override
	public void setUniform(String name, GpuBufferSlice slice) {
		bindings.setUniform(name, slice);
	}

	@Override
	public void enableScissor(int x, int y, int width, int height) {
		commands.scissor(x, y, width, height);
	}

	@Override
	public void disableScissor() {
		commands.scissor(area.x(), area.y(), area.width(), area.height());
	}

	@Override
	public void setVertexBuffer(int slot, GpuBufferSlice slice) {
		if (slice != null) {
			commands.vertexBuffer(slot, MetalBuffer.handle(slice.buffer()), slice.offset());
		}
	}

	@Override
	public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
		if (buffer != indexBuffer || type != indexType) {
			indexBuffer = buffer;
			indexType = type;
			commands.indexBuffer(MetalBuffer.handle(buffer), type == IndexType.INT);
		}
	}

	@Override
	public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
		prepareDraw();
		commands.drawIndexed(indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
	}

	@Override
	public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, GpuBuffer defaultIndexBuffer, IndexType defaultIndexType,
		Collection<String> dynamicUniforms, T uniformArgument) {
		for (RenderPass.Draw<T> draw : draws) {
			BiConsumer<T, RenderPass.UniformUploader> uploader = draw.uniformUploaderConsumer();
			if (uploader != null) {
				uploader.accept(uniformArgument, this::setUniform);
			}
			setIndexBuffer(
				draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer(),
				draw.indexType() == null ? defaultIndexType : draw.indexType()
			);
			setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());
			drawIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
		}
	}

	@Override
	public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
		prepareDraw();
		commands.draw(vertexCount, instanceCount, firstVertex, firstInstance);
	}

	void drawMesh(int threadCount, int objectThreads, int meshThreads) {
		if (pipeline != null) {
			prepareDraw();
			commands.drawMesh(threadCount, objectThreads, meshThreads);
		}
	}

	void resolveOit(int k) {
		commands.terrainOitResolve(k);
	}

	@Override
	public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		throw unsupported("multiDrawIndexed");
	}

	@Override
	public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer baseVertices, int drawCount) {
		throw unsupported("multiDrawIndexed");
	}

	@Override
	public void drawIndexedIndirect(GpuBufferSlice buffer, int drawCount) {
		prepareDraw();
		for (int i = 0; i < drawCount; i++) {
			commands.drawIndexedIndirect(MetalBuffer.handle(buffer.buffer()), buffer.offset() + (long) i * 20);
		}
	}

	@Override
	public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		throw unsupported("multiDraw");
	}

	@Override
	public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
		throw unsupported("multiDraw");
	}

	@Override
	public void drawIndirect(GpuBufferSlice buffer, int drawCount) {
		throw unsupported("drawIndirect");
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
	}

	private void begin(RenderPipeline renderPipeline, MetalPipeline compiled) {
		if (begun) return;
		GbufferAttachmentRouter.Route route = pipelines.gbufferRoute(renderPipeline, compiled);
		if (route.rejected()) {
			LOGGER.warn("Gbuffer route rejected for {} (reason={}); using vanilla attachments",
				renderPipeline.getLocation(), route.reason());
		}
		commands.beginPass(PassLabels.id(descriptor.label()), route.active() ? routedDescriptor(route) : descriptor, area);
		begun = true;
	}

	private void beginVanilla() {
		if (begun) return;
		commands.beginPass(PassLabels.id(descriptor.label()), descriptor, area);
		begun = true;
	}

	private RenderPassDescriptor routedDescriptor(GbufferAttachmentRouter.Route route) {
		RenderPassDescriptor routed = RenderPassDescriptor.create(descriptor.label());
		for (GbufferAttachmentRouter.Slot slot : route.slots()) {
			int location = slot.location();
			if (location < 0 || location >= GbufferAttachmentRouter.MAX_COLOR_ATTACHMENTS) return descriptor;
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> original = location < descriptor.colorAttachments().size()
				? descriptor.colorAttachments().get(location) : null;
			if (slot.view().isPresent()) {
				routed.withColorAttachment(slot.view().get(), original == null ? Optional.empty() : original.clearValue());
			} else {
				routed.withUnusedColorAttachment();
			}
		}
		RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
		if (depth != null) routed.withDepthAttachment(depth.textureView(), depth.clearValue());
		routed.withRenderArea(area);
		return routed;
	}

	private void prepareDraw() {
		if (pipeline == null) {
			throw new IllegalStateException("Pipeline is missing");
		}
		refreshDrawUniform();
		bindings.flush();
	}

	private void refreshDrawUniform() {
		if (!pipeline.packGbuffer()) {
			clearDrawUniform();
			return;
		}
		GbufferDrawBridge.current().ifPresentOrElse(draw -> {
			if (drawUniformName != null && !drawUniformName.equals(draw.uniformName())) {
				bindings.clearUniform(drawUniformName);
			}
			bindings.setUniform(draw.uniformName(), draw.uniformSlice());
			drawUniformName = draw.uniformName();
		}, this::clearDrawUniform);
	}

	private void clearDrawUniform() {
		if (drawUniformName != null) {
			bindings.clearUniform(drawUniformName);
			drawUniformName = null;
		}
	}

	private static UnsupportedOperationException unsupported(String operation) {
		return new UnsupportedOperationException(operation + " is not supported by the Metal backend");
	}
}
