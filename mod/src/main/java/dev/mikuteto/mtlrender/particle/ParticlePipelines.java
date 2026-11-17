package dev.mikuteto.mtlrender.particle;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import net.minecraft.resources.Identifier;

final class ParticlePipelines {
	static final String DATA = "MtlrParticleData";
	private static final BindGroupLayout DATA_LAYOUT = BindGroupLayout.builder()
		.withUniform(DATA, com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER).build();
	private static final Pattern VERSION = Pattern.compile("^#version\\s+\\d+[^\\n]*\\n", Pattern.MULTILINE);
	private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)");
	private static final Pattern INPUTS = Pattern.compile("\\bin\\s+(vec3|vec4|vec2|ivec2)\\s+(Position|Color|UV0|UV1|UV2|Normal)\\s*;");
	private static final Map<RenderPipeline, RenderPipeline> PIPELINES = new IdentityHashMap<>();

	private ParticlePipelines() {
	}

	static RenderPipeline get(RenderPipeline vanilla) {
		return PIPELINES.computeIfAbsent(vanilla, ParticlePipelines::create);
	}

	private static RenderPipeline create(RenderPipeline vanilla) {
		Identifier vertex = Identifier.fromNamespaceAndPath("mtlrender", "particle_pulling_vertex_" + Integer.toHexString(System.identityHashCode(vanilla)));
		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("mtlrender", "particle_pulling_" + Integer.toHexString(System.identityHashCode(vanilla))))
			.withVertexShader(vertex)
			.withFragmentShader(vanilla.getFragmentShader())
			.withPolygonMode(vanilla.getPolygonMode())
			.withCull(vanilla.isCull())
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLE_STRIP)
			.withDepthStencilState(Optional.ofNullable(vanilla.getDepthStencilState()));
		for (BindGroupLayout layout : vanilla.getBindGroupLayouts()) {
			builder.withBindGroupLayout(layout);
		}
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
		body = MAIN.matcher(body).replaceFirst("void mtlr_particle_main()");
		return """
			#version 460
			#extension GL_EXT_buffer_reference : require
			#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
			layout(buffer_reference, std430, buffer_reference_align = 4) readonly buffer MtlrParticleRecords { uint data[]; };
			struct MtlrParticleAddress { uint64_t address; };
			layout(std430) readonly buffer MtlrParticleData { MtlrParticleAddress particle[]; } mtlr_particles;
			%s
			vec3 mtlr_rotate(vec4 q, vec3 p) {
				return p + 2.0 * cross(q.xyz, cross(q.xyz, p) + q.w * p);
			}
			void main() {
				uint corner = uint(gl_VertexIndex) & 3u;
				vec2 sign = corner == 0u ? vec2(1.0, -1.0) : corner == 1u ? vec2(1.0, 1.0) : corner == 2u ? vec2(-1.0, 1.0) : vec2(-1.0, -1.0);
				MtlrParticleRecords records = MtlrParticleRecords(mtlr_particles[gl_InstanceIndex].address);
				uint base = corner * 0u;
				uint instance = uint(gl_InstanceIndex) * 16u;
				vec3 origin = vec3(uintBitsToFloat(records.data[instance]), uintBitsToFloat(records.data[instance + 1u]), uintBitsToFloat(records.data[instance + 2u]));
				vec4 rotation = vec4(uintBitsToFloat(records.data[instance + 3u]), uintBitsToFloat(records.data[instance + 4u]), uintBitsToFloat(records.data[instance + 5u]), uintBitsToFloat(records.data[instance + 6u]));
				float scale = uintBitsToFloat(records.data[instance + 7u]);
				UV0 = vec2(uintBitsToFloat(records.data[instance + (sign.x > 0.0 ? 9u : 8u)]), uintBitsToFloat(records.data[instance + (sign.y > 0.0 ? 10u : 11u)]));
				Color = unpackUnorm4x8(records.data[instance + 12u]);
				uint light = records.data[instance + 13u];
				UV2 = ivec2(int(light << 16u) >> 16, int(light) >> 16);
				Position = origin + mtlr_rotate(rotation, vec3(sign, 0.0) * scale);
				mtlr_particle_main();
			}
			""".formatted(body);
	}

	static boolean compiles(RenderPipeline pipeline) {
		return pipeline != null && com.mojang.blaze3d.systems.RenderSystem.getDevice().precompilePipeline(pipeline).isValid();
	}
}
