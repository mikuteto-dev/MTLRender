package dev.mikuteto.mtlrender.entity;

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

final class EntityPipelines {
	static final String DATA = "MtlrEntityData";
	private static final String NAMESPACE = "mtlrender";
	private static final BindGroupLayout DATA_LAYOUT = BindGroupLayout.builder().withUniform(DATA, com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER).build();
	private static final Pattern VERSION = Pattern.compile("^#version\\s+\\d+[^\\n]*\\n", Pattern.MULTILINE);
	private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)");
	private static final Pattern INPUTS = Pattern.compile("\\bin\\s+(vec3|vec4|vec2|ivec2)\\s+(Position|Color|UV0|UV1|UV2|Normal)\\s*;");
	private static final Map<RenderPipeline, RenderPipeline> PIPELINES = new IdentityHashMap<>();

	private EntityPipelines() {
	}

	static RenderPipeline get(RenderPipeline vanilla) {
		return PIPELINES.computeIfAbsent(vanilla, EntityPipelines::create);
	}

	private static RenderPipeline create(RenderPipeline vanilla) {
		Identifier vertex = Identifier.fromNamespaceAndPath(NAMESPACE, "entity_batch_vertex_" + Integer.toHexString(System.identityHashCode(vanilla)));
		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, "entity_batch_" + Integer.toHexString(System.identityHashCode(vanilla))))
			.withVertexShader(vertex)
			.withFragmentShader(vanilla.getFragmentShader())
			.withPolygonMode(vanilla.getPolygonMode())
			.withCull(vanilla.isCull())
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withDepthStencilState(Optional.ofNullable(vanilla.getDepthStencilState()));
		for (BindGroupLayout layout : vanilla.getBindGroupLayouts()) {
			builder.withBindGroupLayout(layout);
		}
		builder.withBindGroupLayout(DATA_LAYOUT);
		ColorTargetState[] targets = vanilla.getColorTargetStates();
		for (int i = 0; i < targets.length; i++) {
			if (targets[i] == null) {
				builder.withUnusedColorTargetState(i);
			} else {
				builder.withColorTargetState(i, targets[i]);
			}
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
		if (!VERSION.matcher(source).find() || !MAIN.matcher(source).find()) {
			return null;
		}
		String body = VERSION.matcher(source).replaceAll("");
		body = INPUTS.matcher(body).replaceAll("$1 $2;");
		body = MAIN.matcher(body).replaceFirst("void mtlr_entity_main()");
		return """
			#version 460
			#extension GL_EXT_buffer_reference : require
			#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
			layout(buffer_reference, std430, buffer_reference_align = 4) readonly buffer MtlrWords { uint data[]; };
			struct MtlrPose { mat4 pose; mat3 normal; };
			layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer MtlrPoses { MtlrPose data[]; };
			struct MtlrAttributes {
				uint64_t vertices;
				uint64_t poses;
				uint partCount;
				uint vertexCount;
				uint light;
				uint overlay;
				uint color;
				uint reserved;
				uint reserved0;
				uint reserved1;
			};
			layout(std430) readonly buffer MtlrEntityData { MtlrAttributes mtlr_entities[]; };
			%s
			void main() {
				uint index = uint(gl_VertexIndex);
				MtlrAttributes attribute = mtlr_entities[gl_InstanceIndex];
				if (index >= attribute.vertexCount) {
					gl_Position = vec4(0.0);
					return;
				}
				MtlrWords words = MtlrWords(attribute.vertices);
				uint base = index * 12u;
				Position = uintBitsToFloat(uvec3(words.data[base], words.data[base + 1u], words.data[base + 2u]));
				Color = unpackUnorm4x8(words.data[base + 3u]);
				UV0 = uintBitsToFloat(uvec2(words.data[base + 4u], words.data[base + 5u]));
				uint light = words.data[base + 6u];
				UV1 = ivec2(int(light << 16u) >> 16, int(light) >> 16);
				uint overlay = words.data[base + 7u];
				UV2 = ivec2(int(overlay << 16u) >> 16, int(overlay) >> 16);
				Normal = uintBitsToFloat(uvec3(words.data[base + 8u], words.data[base + 9u], words.data[base + 10u]));
				uint part = words.data[base + 11u];
				MtlrPoses poses = MtlrPoses(attribute.poses);
				MtlrPose partPose = poses.data[part];
				Color = unpackUnorm4x8(attribute.color);
				light = attribute.light;
				UV2 = ivec2(int(light << 16u) >> 16, int(light) >> 16);
				overlay = attribute.overlay;
				UV1 = ivec2(int(overlay << 16u) >> 16, int(overlay) >> 16);
				Position = (partPose.pose * vec4(Position, 1.0)).xyz;
				Normal = partPose.normal * Normal;
				mtlr_entity_main();
			}
			""".formatted(body);
	}

	static boolean compiles(RenderPipeline pipeline) {
		return pipeline != null && com.mojang.blaze3d.systems.RenderSystem.getDevice().precompilePipeline(pipeline).isValid();
	}
}
