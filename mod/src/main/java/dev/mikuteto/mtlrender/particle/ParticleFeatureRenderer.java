package dev.mikuteto.mtlrender.particle;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.debug.AbSwitch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;

public final class ParticleFeatureRenderer implements FeatureRenderer<QuadParticleFeatureRenderer.Submit> {
	private final QuadParticleFeatureRenderer fallback = new QuadParticleFeatureRenderer();
	private final List<Group> groups = new ArrayList<>();
	private GpuBufferSlice transforms;
	private int fallbackGroups;

	@Override
	public void prepareGroup(FeatureFrameContext context, List<QuadParticleFeatureRenderer.Submit> submits, boolean strictlyOrdered) {
		if (strictlyOrdered || !MetalAccess.active() || !enabled()) {
			int fallbackIndex = fallbackGroups++;
			fallback.prepareGroup(context, submits, strictlyOrdered);
			groups.add(Group.fallback(fallbackIndex, submits));
			return;
		}
		Map<SingleQuadParticle.Layer, List<ParticleInstanceStream>> layerStreams = new IdentityHashMap<>();
		List<SingleQuadParticle.Layer> layerOrder = new ArrayList<>();
		for (QuadParticleFeatureRenderer.Submit submit : submits) {
			for (SingleQuadParticle.Layer layer : submit.particles().layers()) {
				if (layer.translucent() == submit.translucent() && !ParticlePipelines.compiles(ParticlePipelines.get(layer.pipeline()))) {
					int fallbackIndex = fallbackGroups++;
					fallback.prepareGroup(context, submits, strictlyOrdered);
					groups.add(Group.fallback(fallbackIndex, submits));
					return;
				}
			}
			for (SingleQuadParticle.Layer layer : submit.particles().layers()) {
				if (layer.translucent() != submit.translucent()) continue;
				ParticleInstanceStream stream = ParticleInstanceStore.layers(submit.particles()).get(layer);
				if (stream != null && stream.count() > 0) {
					if (!layerStreams.containsKey(layer)) layerOrder.add(layer);
					layerStreams.computeIfAbsent(layer, ignored -> new ArrayList<>()).add(stream);
				}
			}
		}
		List<LayerStreams> layers = new ArrayList<>(layerOrder.size());
		for (SingleQuadParticle.Layer layer : layerOrder) layers.add(new LayerStreams(layer, layerStreams.get(layer)));
		groups.add(new Group(layers, submits.getFirst().translucent(), List.of(), -1));
	}

	@Override
	public void finishPrepare(FeatureFrameContext context) {
		transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy());
		fallback.finishPrepare(context);
	}

	@Override
	public void executeGroup(FeatureFrameContext context, int groupIndex, List<QuadParticleFeatureRenderer.Submit> submits, boolean strictlyOrdered) {
		Group group = groups.get(groupIndex);
		if (group.fallbackIndex() >= 0) {
			fallback.executeGroup(context, group.fallbackIndex(), group.fallback(), strictlyOrdered);
			return;
		}
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		RenderTarget particles = Minecraft.getInstance().levelRenderer.particlesTarget();
		boolean particleTarget = particles != null && group.translucent();
		var color = particleTarget ? particles.getColorTextureView() : main.getColorTextureView();
		var depth = particleTarget ? particles.getDepthTextureView() : main.getDepthTextureView();
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
			() -> "MTLRender particles", color, Optional.empty(), depth, OptionalDouble.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transforms);
			pass.bindTexture("Sampler2", context.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			for (LayerStreams layer : group.layers()) {
				drawLayer(pass, context.textureManager(), layer.layer(), layer.streams());
			}
		}
		if (group.fallbackIndex() >= 0) fallback.executeGroup(context, group.fallbackIndex(), group.fallback(), strictlyOrdered);
	}

	private static void drawLayer(RenderPass pass, TextureManager textures, SingleQuadParticle.Layer layer, List<ParticleInstanceStream> streams) {
		int count = streams.stream().mapToInt(ParticleInstanceStream::count).sum();
		if (count == 0) return;
		int bytes = 16 + count * ParticleInstanceLayout.STRIDE;
		GpuBufferSlice data = RenderSystem.getDevice().createCommandEncoder().transientMemory().allocateGpuMapped(bytes, 256, GpuBuffer.USAGE_UNIFORM, bytes, 1).slice();
		MemorySegment memory = MetalAccess.contents(data.buffer()).asSlice(data.offset(), bytes);
		long records = MetalAccess.gpuAddress(data.buffer()) + data.offset() + 16;
		memory.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, records);
		memory.set(ValueLayout.JAVA_LONG_UNALIGNED, 8, count);
		int offset = 16;
		for (ParticleInstanceStream stream : streams) {
			ByteBuffer source = stream.data();
			MemorySegment.copy(MemorySegment.ofBuffer(source), source.position(), memory, offset, source.remaining());
			offset += source.remaining();
		}
		pass.setPipeline(ParticlePipelines.get(layer.pipeline()));
		pass.setUniform(ParticlePipelines.DATA, data);
		AbstractTexture texture = textures.getTexture(layer.textureAtlasLocation());
		pass.bindTexture("Sampler0", texture.getTextureView(), texture.getSampler());
		pass.draw(4, count, 0, 0);
	}

	@Override
	public void finishExecute(FeatureFrameContext context) {
		fallback.finishExecute(context);
		groups.clear();
		fallbackGroups = 0;
		transforms = null;
	}

	@Override
	public void close() {
		fallback.close();
	}

	private static boolean enabled() {
		return "particlePulling".equals(AbSwitch.NAME) ? AbSwitch.b() : Config.get().particles.pulling;
	}

	private record LayerStreams(SingleQuadParticle.Layer layer, List<ParticleInstanceStream> streams) {
	}

	private record Group(List<LayerStreams> layers, boolean translucent, List<QuadParticleFeatureRenderer.Submit> fallback, int fallbackIndex) {
		static Group fallback(int index, List<QuadParticleFeatureRenderer.Submit> submits) {
			return new Group(List.of(), false, submits, index);
		}
	}
}
