package dev.mikuteto.mtlrender.pack.gbuffer;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.buffers.GpuBufferSlice.MappedView;
import dev.mikuteto.mtlrender.pack.ShaderProperties;
import dev.mikuteto.mtlrender.pack.runtime.PackUniformState;
import dev.mikuteto.mtlrender.pack.uniform.Type;
import dev.mikuteto.mtlrender.pack.uniform.UniformWriter;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GbufferSemanticHooksIntegrationTest {
	@AfterEach
	void clear() {
		GbufferDrawBridge.clear();
		GbufferSemanticContext.clear();
	}

	@Test
	void catalogCoversEveryRequestedDrawFamily() {
		assertStage(RenderPipelines.ENTITY_TRANSLUCENT, GbufferStage.Entities);
		assertStage(RenderPipelines.TRANSLUCENT_BLOCK, GbufferStage.Blocks);
		assertStage(RenderPipelines.ITEM_CUTOUT, GbufferStage.Items);
		assertStage(RenderPipelines.OPAQUE_PARTICLE, GbufferStage.Particles);
		assertStage(RenderPipelines.WEATHER_NO_DEPTH_WRITE, GbufferStage.Weather);
		assertStage(RenderPipelines.SKY, GbufferStage.Sky);
		assertStage(RenderPipelines.CLOUDS, GbufferStage.Clouds);
		assertStage(RenderPipelines.LINES, GbufferStage.Lines);
		assertStage(RenderPipelines.OUTLINE_NO_CULL, GbufferStage.Lines);
		assertStage(RenderPipelines.LEASH, GbufferStage.Leash);
		assertStage(RenderPipelines.BEACON_BEAM_OPAQUE, GbufferStage.Beacon);
		assertStage(RenderPipelines.GLINT, GbufferStage.Glint);
		assertStage(RenderPipelines.TEXT, GbufferStage.Text);
		assertTrue(GbufferPipelineCatalog.levelPipelines().containsAll(GbufferPipelineCatalog.specs().keySet()));
	}

	@Test
	void bridgeCarriesDeterministicDrawStateIntoUniformContract() {
		GbufferDrawState state = new GbufferDrawState(12, 34, GbufferStage.Entities.ordinal(), 56,
			Optional.empty(), Optional.empty(), Optional.empty());
		GpuBufferSlice slice = new GpuBufferSlice(new FakeBuffer(), 0, 64);
		GbufferDrawBridge.Provider provider = () -> Optional.of(new GbufferDrawBridge.Draw(state, "Globals", slice));
		GbufferDrawBridge.install(provider);
		assertEquals(state, GbufferDrawBridge.current().orElseThrow().state());
		assertEquals(slice, GbufferDrawBridge.current().orElseThrow().uniformSlice());

		PackUniformState uniforms = new PackUniformState(ShaderProperties.empty());
		ByteBuffer buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
		new GbufferUniformBindings(GbufferPipelineSpec.InputProfile.Entity, List.of(
			new UniformWriter.Member("entityId", Type.INT, 0),
			new UniformWriter.Member("blockEntityId", Type.INT, 4),
			new UniformWriter.Member("renderStage", Type.INT, 8),
			new UniformWriter.Member("currentRenderedItemId", Type.INT, 12)
		)).write(buffer, GbufferDrawBridge.current().orElseThrow().state(), uniforms);
		assertEquals(12, buffer.getInt(0));
		assertEquals(34, buffer.getInt(4));
		assertEquals(GbufferStage.Entities.ordinal(), buffer.getInt(8));
		assertEquals(56, buffer.getInt(12));
	}

	@Test
	void inactiveBridgeAndAllLifecycleBoundariesClearState() {
		assertTrue(GbufferDrawBridge.current().isEmpty());
		GbufferDrawBridge.Provider provider = Optional::<GbufferDrawBridge.Draw>empty;
		GbufferDrawBridge.install(provider);
		GbufferDrawBridge.beginFrame();
		assertTrue(GbufferDrawBridge.current().isEmpty());
		GbufferDrawBridge.endFrame();
		assertEquals(0, GbufferSemanticContext.current().renderStage());
		assertEquals(0, GbufferSemanticContext.current().entityId());
		GbufferDrawBridge.remove(provider);
		assertTrue(GbufferDrawBridge.current().isEmpty());
	}

	private static void assertStage(RenderPipeline pipeline, GbufferStage expected) {
		assertEquals(expected, GbufferPipelineCatalog.find(pipeline).orElseThrow().stage());
	}

	private static final class FakeBuffer extends GpuBuffer {
		private FakeBuffer() {
			super(USAGE_UNIFORM, 64);
		}

		@Override
		public boolean isClosed() {
			return false;
		}

		@Override
		public void close() {
		}

		@Override
		public MappedView map(long offset, long length, boolean read, boolean write) {
			return null;
		}
	}
}
