package dev.mikuteto.mtlrender.terrain;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.backend.PipelineDescriptor;
import dev.mikuteto.mtlrender.pack.PackManager;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.resources.Identifier;

final class TerrainPipelines {
	static final String UNITS = "MtlrUnits";

	private static final String NAMESPACE = "mtlrender";
	private static final String ALPHA_CUTOUT = "ALPHA_CUTOUT";
	private static final BindGroupLayout UNITS_LAYOUT = BindGroupLayout.builder().withUniform(UNITS, UniformType.UNIFORM_BUFFER).build();

	private final boolean indexed;
	private final boolean compactVertices;
	private final boolean cutoutPrepass;
	private final boolean prepassDiscard;
	private final boolean half;
	private final boolean translucentOit;
	private final Map<ChunkSectionLayer, RenderPipeline> pipelines = new EnumMap<>(ChunkSectionLayer.class);
	private final Map<ChunkSectionLayer, RenderPipeline> meshPipelines = new EnumMap<>(ChunkSectionLayer.class);
	private RenderPipeline cutoutDepth;
	private RenderPipeline meshCutoutDepth;

	TerrainPipelines(boolean indexed, boolean compactVertices, boolean cutoutPrepass, boolean prepassDiscard, boolean half, boolean translucentOit) {
		this.indexed = indexed;
		this.compactVertices = compactVertices;
		this.cutoutPrepass = cutoutPrepass;
		this.prepassDiscard = prepassDiscard;
		this.half = half;
		this.translucentOit = translucentOit;
	}

	boolean half() {
		return half;
	}

	RenderPipeline get(ChunkSectionLayer layer) {
		return pipelines.computeIfAbsent(layer, key -> create(key, false, false));
	}

	RenderPipeline vanilla(ChunkSectionLayer layer) {
		return layer.pipeline();
	}

	int renderState(ChunkSectionLayer layer) {
		RenderPipeline pipeline = vanilla(layer);
		int value = pipeline.isCull() ? 1 : 0;
		if (pipeline.getDepthStencilState() != null) {
			value = value * 31 + pipeline.getDepthStencilState().depthTest().ordinal();
			value = value * 31 + (pipeline.getDepthStencilState().writeDepth() ? 1 : 0);
		}
		for (ColorTargetState target : pipeline.getColorTargetStates()) {
			value = value * 31 + (target == null ? 0 : target.format().ordinal());
		}
		return value;
	}

	RenderPipeline mesh(ChunkSectionLayer layer) {
		return meshPipelines.computeIfAbsent(layer, key -> create(key, false, true));
	}

	RenderPipeline meshDepthPrepass(ChunkSectionLayer layer) {
		if (!prepassed(layer)) {
			return null;
		}
		if (meshCutoutDepth == null) {
			meshCutoutDepth = create(layer, true, true);
		}
		return meshCutoutDepth;
	}

	RenderPipeline depthPrepass(ChunkSectionLayer layer) {
		if (!prepassed(layer)) {
			return null;
		}
		if (cutoutDepth == null) {
			cutoutDepth = create(layer, true, false);
		}
		return cutoutDepth;
	}

	private boolean prepassed(ChunkSectionLayer layer) {
		return cutoutPrepass && layer == ChunkSectionLayer.CUTOUT;
	}

	private RenderPipeline create(ChunkSectionLayer layer, boolean depthOnly, boolean mesh) {
		RenderPipeline vanilla = layer.pipeline();
		boolean depthEqual = prepassed(layer) && !depthOnly;
		String name = "terrain/" + layer.name().toLowerCase(Locale.ROOT) + (half ? "_half" : "_full") + (mesh ? "_mesh" : "");
		Identifier vertex = Identifier.fromNamespaceAndPath(NAMESPACE, name + "_vertex");
		Identifier fragment = Identifier.fromNamespaceAndPath(NAMESPACE, name + (depthOnly ? "_depth_fragment" : "_fragment"));
		DepthStencilState depth = vanilla.getDepthStencilState();
		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, depthOnly ? name + "_depth" : name))
			.withVertexShader(vertex)
			.withFragmentShader(fragment)
			.withPolygonMode(vanilla.getPolygonMode())
			.withCull(vanilla.isCull())
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withDepthStencilState(Optional.ofNullable(depthEqual && depth != null
				? new DepthStencilState(CompareOp.EQUAL, false, depth.depthBiasScaleFactor(), depth.depthBiasConstant())
				: depth));
		copyDefines(builder, vanilla.getShaderDefines(), depthEqual && !prepassDiscard);
		for (BindGroupLayout layout : vanilla.getBindGroupLayouts()) {
			builder.withBindGroupLayout(layout);
		}
		builder.withBindGroupLayout(UNITS_LAYOUT);
		ColorTargetState[] targets = vanilla.getColorTargetStates();
		for (int i = 0; i < targets.length; i++) {
			ColorTargetState target = targets[i];
			if (target == null) {
				builder.withUnusedColorTargetState(i);
			} else if (depthOnly) {
				builder.withColorTargetState(i, new ColorTargetState(target.blendFunction(), target.format(), ColorTargetState.WRITE_NONE));
			} else {
				builder.withColorTargetState(i, target);
			}
		}
		RenderPipeline pipeline = builder.build();
		if (translucentOit && PackManager.get().active().isEmpty() && layer == ChunkSectionLayer.TRANSLUCENT && !depthOnly) {
			PipelineDescriptor.markOit(pipeline);
		}
		MetalAccess.overrideShaderSource(pipeline, base -> patchedSource(base, vanilla, vertex, fragment, depthOnly, mesh));
		return pipeline;
	}

	private ShaderSource patchedSource(ShaderSource base, RenderPipeline vanilla, Identifier vertex, Identifier fragment, boolean depthOnly, boolean mesh) {
		return (id, type) -> {
			if (!mesh && id.equals(vertex) && type == ShaderType.VERTEX) {
				String source = base.get(vanilla.getVertexShader(), ShaderType.VERTEX);
				return source == null ? null : TerrainShaderPatch.vertex(source, indexed, compactVertices, cutoutPrepass, half);
			}
			if (id.equals(fragment) && type == ShaderType.FRAGMENT) {
				String source = base.get(vanilla.getFragmentShader(), ShaderType.FRAGMENT);
				if (source == null) {
					return null;
				}
				return depthOnly ? TerrainShaderPatch.depthFragment(source, half) : TerrainShaderPatch.fragment(source, half);
			}
			return base.get(id, type);
		};
	}

	private static void copyDefines(RenderPipeline.Builder builder, ShaderDefines defines, boolean withoutAlphaCutout) {
		for (String flag : defines.flags()) {
			builder.withShaderDefine(flag);
		}
		for (Map.Entry<String, String> entry : defines.values().entrySet()) {
			if (withoutAlphaCutout && entry.getKey().equals(ALPHA_CUTOUT)) {
				continue;
			}
			String value = entry.getValue();
			if (value.contains(".") || value.contains("e") || value.endsWith("f")) {
				builder.withShaderDefine(entry.getKey(), Float.parseFloat(value));
			} else {
				builder.withShaderDefine(entry.getKey(), Integer.parseInt(value));
			}
		}
	}
}
