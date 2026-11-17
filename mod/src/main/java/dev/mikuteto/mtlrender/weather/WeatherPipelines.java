package dev.mikuteto.mtlrender.weather;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import net.minecraft.resources.Identifier;

final class WeatherPipelines {
	static final String DATA = "MtlrWeatherData";
	private static final BindGroupLayout DATA_LAYOUT = BindGroupLayout.builder()
		.withUniform(DATA, UniformType.UNIFORM_BUFFER).build();
	private static final Pattern VERSION = Pattern.compile("^#version\\s+\\d+[^\\n]*\\n", Pattern.MULTILINE);
	private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)");
	private static final Pattern INPUTS = Pattern.compile("\\bin\\s+(vec3|vec4|vec2|ivec2)\\s+(Position|Color|UV0|UV1|UV2|Normal)\\s*;");
	private static final Map<RenderPipeline, RenderPipeline> PIPELINES = new IdentityHashMap<>();

	private WeatherPipelines() {
	}

	static RenderPipeline get(RenderPipeline vanilla) {
		return PIPELINES.computeIfAbsent(vanilla, WeatherPipelines::create);
	}

	static boolean compiles(RenderPipeline pipeline) {
		return pipeline != null && com.mojang.blaze3d.systems.RenderSystem.getDevice().precompilePipeline(pipeline).isValid();
	}

	private static RenderPipeline create(RenderPipeline vanilla) {
		Identifier vertex = Identifier.fromNamespaceAndPath("mtlrender", "weather_pulling_vertex_" + Integer.toHexString(System.identityHashCode(vanilla)));
		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("mtlrender", "weather_pulling_" + Integer.toHexString(System.identityHashCode(vanilla))))
			.withVertexShader(vertex)
			.withFragmentShader(vanilla.getFragmentShader())
			.withPolygonMode(vanilla.getPolygonMode())
			.withCull(vanilla.isCull())
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLE_STRIP)
			.withDepthStencilState(Optional.ofNullable(vanilla.getDepthStencilState()));
		for (BindGroupLayout layout : vanilla.getBindGroupLayouts()) builder.withBindGroupLayout(layout);
		builder.withBindGroupLayout(DATA_LAYOUT);
		ColorTargetState[] targets = vanilla.getColorTargetStates();
		for (int i = 0; i < targets.length; i++) {
			if (targets[i] == null) builder.withUnusedColorTargetState(i);
			else builder.withColorTargetState(i, targets[i]);
		}
		RenderPipeline pipeline = builder.build();
		MetalAccess.overrideShaderSource(pipeline, base -> (id, type) -> {
			if (id.equals(vertex) && type == ShaderType.VERTEX) {
				String source = base.get(vanilla.getVertexShader(), ShaderType.VERTEX);
				return source == null ? null : vertex(source);
			}
			return base.get(id, type);
		});
		return pipeline;
	}

	private static String vertex(String source) {
		if (!VERSION.matcher(source).find() || !MAIN.matcher(source).find()) return null;
		String body = VERSION.matcher(source).replaceAll("");
		body = INPUTS.matcher(body).replaceAll("$1 $2;");
		body = MAIN.matcher(body).replaceFirst("void mtlr_weather_main()");
		return """
			#version 460
			#extension GL_EXT_buffer_reference : require
			#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
			layout(buffer_reference, std430, buffer_reference_align = 4) readonly buffer MtlrWeatherRecords { uint data[]; };
			layout(std430) readonly buffer MtlrWeatherData { uint64_t address; uint count; } mtlr_weather;
			%s
			void main() {
				MtlrWeatherRecords records = MtlrWeatherRecords(mtlr_weather.address);
				uint corner = uint(gl_VertexIndex) & 3u;
				uint base = 12u + uint(gl_InstanceIndex) * 8u;
				float cameraX = uintBitsToFloat(records.data[0]);
				float cameraY = uintBitsToFloat(records.data[1]);
				float cameraZ = uintBitsToFloat(records.data[2]);
				int cameraBlockX = int(records.data[3]);
				int cameraBlockZ = int(records.data[4]);
				int radius = int(records.data[5]);
				float intensity = uintBitsToFloat(records.data[6]);
				int x = int(records.data[base]);
				int z = int(records.data[base + 1u]);
				int bottom = int(records.data[base + 2u]);
				int top = int(records.data[base + 3u]);
				float uOffset = uintBitsToFloat(records.data[base + 4u]);
				float vOffset = uintBitsToFloat(records.data[base + 5u]);
				uint light = records.data[base + 6u];
				float dx = float(x - cameraBlockX + 16);
				float dz = float(z - cameraBlockZ + 16);
				float length = sqrt(dx * dx + dz * dz);
				float halfX = -dz / length * 0.5;
				float halfZ = dx / length * 0.5;
				float relativeX = float(x) + 0.5 - cameraX;
				float relativeZ = float(z) + 0.5 - cameraZ;
				vec2 size = corner == 0u || corner == 3u ? vec2(-halfX, -halfZ) : vec2(halfX, halfZ);
				Position = vec3(relativeX + size.x, corner < 2u ? float(top) - cameraY : float(bottom) - cameraY, relativeZ + size.y);
				float distanceSq = relativeX * relativeX + relativeZ * relativeZ;
				float radiusSq = float(radius * radius);
				float maxAlpha = records.data[base + 7u] == 0u ? 1.0 : 0.8;
				float alpha = mix(maxAlpha, 0.5, min(distanceSq / radiusSq, 1.0)) * intensity;
				UV0 = vec2(uOffset + (corner == 0u || corner == 3u ? 0.0 : 1.0), (corner < 2u ? float(bottom) : float(top)) * 0.25 + vOffset);
				Color = vec4(1.0, 1.0, 1.0, alpha);
				UV2 = ivec2(int(light << 16u) >> 16, int(light) >> 16);
				mtlr_weather_main();
			}
			""".formatted(body);
	}
}
