package dev.mikuteto.mtlrender.weather;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.mixin.WeatherRenderStateAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

public final class WeatherFeatureRenderer {
	private static final Identifier RAIN = Identifier.withDefaultNamespace("textures/environment/rain.png");
	private static final Identifier SNOW = Identifier.withDefaultNamespace("textures/environment/snow.png");

	private WeatherFeatureRenderer() {
	}

	public static boolean render(Vec3 cameraPos, WeatherRenderState state) {
		if (!MetalAccess.active()) return false;
		WeatherColumnStream columns = ((WeatherRenderStateAccess) (Object) state).mtlrender$columns();
		if (columns.count() == 0 || columns.count() != state.rainColumns.size() + state.snowColumns.size()) return false;
		int radius = state.radius;
		int cameraX = floor(cameraPos.x);
		int cameraZ = floor(cameraPos.z);
		if (radius <= 0 || radius > 16 || !valid(columns.buffer(false), cameraX, cameraZ) || !valid(columns.buffer(true), cameraX, cameraZ)) return false;
		Minecraft minecraft = Minecraft.getInstance();
		RenderPipeline vanilla = minecraft.gameRenderer.gameRenderState().useShaderTransparency()
			? RenderPipelines.WEATHER_DEPTH_WRITE : RenderPipelines.WEATHER_NO_DEPTH_WRITE;
		RenderPipeline pipeline = WeatherPipelines.get(vanilla);
		if (!WeatherPipelines.compiles(pipeline)) return false;
		RenderTarget target = OutputTarget.WEATHER_TARGET.getRenderTarget();
		if (target == null) return false;
		TextureManager textures = minecraft.getTextureManager();
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy());
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
			() -> "MTLRender weather", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
			pass.setPipeline(pipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transforms);
			pass.bindTexture("Sampler2", minecraft.gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			draw(pass, textures, RAIN, columns.buffer(false), columns.rainCount(), cameraPos, cameraX, cameraZ, radius, state.intensity);
			draw(pass, textures, SNOW, columns.buffer(true), columns.snowCount(), cameraPos, cameraX, cameraZ, radius, state.intensity);
		}
		return true;
	}

	private static void draw(RenderPass pass, TextureManager textures, Identifier id, ByteBuffer records, int count,
		Vec3 camera, int cameraX, int cameraZ, int radius, float intensity) {
		if (count == 0) return;
		int bytes = 48 + records.remaining();
		GpuBufferSlice data = RenderSystem.getDevice().createCommandEncoder().transientMemory()
			.allocateGpuMapped(bytes, 256, GpuBuffer.USAGE_UNIFORM, bytes, 1).slice();
		MemorySegment memory = MetalAccess.contents(data.buffer()).asSlice(data.offset(), bytes);
		long recordsAddress = MetalAccess.gpuAddress(data.buffer()) + data.offset() + 16;
		memory.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, recordsAddress);
		memory.set(ValueLayout.JAVA_LONG_UNALIGNED, 8, count);
		memory.set(ValueLayout.JAVA_FLOAT_UNALIGNED, 16, (float) camera.x);
		memory.set(ValueLayout.JAVA_FLOAT_UNALIGNED, 20, (float) camera.y);
		memory.set(ValueLayout.JAVA_FLOAT_UNALIGNED, 24, (float) camera.z);
		memory.set(ValueLayout.JAVA_INT_UNALIGNED, 28, cameraX);
		memory.set(ValueLayout.JAVA_INT_UNALIGNED, 32, cameraZ);
		memory.set(ValueLayout.JAVA_INT_UNALIGNED, 36, radius);
		memory.set(ValueLayout.JAVA_FLOAT_UNALIGNED, 40, intensity);
		MemorySegment.copy(MemorySegment.ofBuffer(records), records.position(), memory, 48, records.remaining());
		pass.setUniform(WeatherPipelines.DATA, data);
		AbstractTexture texture = textures.getTexture(id);
		pass.bindTexture("Sampler0", texture.getTextureView(), texture.getSampler());
		pass.draw(4, count, 0, 0);
	}

	private static boolean valid(ByteBuffer records, int cameraX, int cameraZ) {
		for (int offset = records.position(); offset < records.limit(); offset += WeatherColumnStream.STRIDE) {
			int x = records.getInt(offset);
			int z = records.getInt(offset + 4);
			if (x - cameraX < -16 || x - cameraX > 15 || z - cameraZ < -16 || z - cameraZ > 15) return false;
		}
		return true;
	}

	private static int floor(double value) {
		return (int) Math.floor(value);
	}
}
