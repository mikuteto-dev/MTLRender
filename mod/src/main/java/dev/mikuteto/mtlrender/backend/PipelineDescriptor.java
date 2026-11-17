package dev.mikuteto.mtlrender.backend;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.PolygonMode;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferStateOverride;

public final class PipelineDescriptor {
	private static final int OIT_FLAG = 1;
	private static final Set<RenderPipeline> OIT_PIPELINES = Collections.newSetFromMap(new IdentityHashMap<>());
	private static final int COMPARE_ALWAYS = 7;
	private static final int BLEND_ONE = 1;
	private static final int BLEND_ZERO = 0;
	private static final int BLEND_ADD = 0;

	private PipelineDescriptor() {
	}

	public static void markOit(RenderPipeline pipeline) {
		OIT_PIPELINES.add(pipeline);
	}

	static int oitDescriptorWord(boolean oit) {
		return oit ? OIT_FLAG : 0;
	}

	static int[] encode(RenderPipeline pipeline, VertexLayout layout) {
		return encode(pipeline, layout, null, java.util.List.of());
	}

	static int[] encode(RenderPipeline pipeline, VertexLayout layout, GbufferStateOverride override,
		java.util.List<Integer> outputBuffers) {
		IntArrayList words = new IntArrayList();
		words.add(topology(pipeline.getPrimitiveTopology()));
		words.add(pipeline.isCull() ? 1 : 0);
		words.add(pipeline.getPolygonMode() == PolygonMode.WIREFRAME ? 1 : 0);
		depth(words, pipeline.getDepthStencilState());
		ColorTargetState[] targets = pipeline.getColorTargetStates();
		words.add(targets.length);
		for (int index = 0; index < targets.length; index++) {
			ColorTargetState target = targets[index];
			color(words, override == null ? target : override.colorTarget(index, target, outputBuffers));
		}
		words.add(layout.bindings().size());
		for (VertexLayout.Binding binding : layout.bindings()) {
			words.add(binding.slot());
			words.add(binding.stride());
			words.add(binding.stepRate());
		}
		words.add(layout.attributes().size());
		for (VertexLayout.Attribute attribute : layout.attributes()) {
			words.add(attribute.location());
			words.add(Formats.vertexFormat(attribute.format()));
			words.add(attribute.offset());
			words.add(attribute.slot());
		}
		words.add(oitDescriptorWord(OIT_PIPELINES.contains(pipeline)));
		return words.toIntArray();
	}

	static int[] encodePack(PackPipelineDesc desc) {
		IntArrayList words = new IntArrayList();
		words.add(3);
		words.add(desc.cullMode().code());
		words.add(0);
		if (desc.depthTest() == null) {
			words.addElements(words.size(), new int[] {0, COMPARE_ALWAYS, 0, 0, 0, 0});
		} else {
			words.add(1);
			words.add(compare(PackPipelineDesc.gameCompare(desc.depthTest())));
			words.add(desc.depthWrite() ? 1 : 0);
			words.add(Formats.pixelFormat(desc.depthFormat()));
			words.add(0);
			words.add(0);
		}
		words.add(desc.colorAttachments().size());
		for (ColorTargetState target : desc.colorAttachments()) {
			color(words, target);
		}
		VertexLayout layout = desc.vertexLayout() == null
			? new VertexLayout(java.util.List.of(), java.util.List.of())
			: new VertexLayout(
				desc.vertexLayout().buffers().stream().map(buffer -> new VertexLayout.Binding(buffer.slot(), buffer.stride(), buffer.stepRate())).toList(),
				desc.vertexLayout().attributes().stream().map(attribute -> new VertexLayout.Attribute(
					"", attribute.location(), attribute.slot(), attribute.offset(), attribute.format())).toList());
		words.add(layout.bindings().size());
		for (VertexLayout.Binding binding : layout.bindings()) {
			words.add(binding.slot());
			words.add(binding.stride());
			words.add(binding.stepRate());
		}
		words.add(layout.attributes().size());
		for (VertexLayout.Attribute attribute : layout.attributes()) {
			words.add(attribute.location());
			words.add(Formats.vertexFormat(attribute.format()));
			words.add(attribute.offset());
			words.add(attribute.slot());
		}
		return words.toIntArray();
	}

	private static void depth(IntArrayList words, DepthStencilState depth) {
		if (depth == null) {
			words.addElements(words.size(), new int[] {0, COMPARE_ALWAYS, 0, 0, 0, 0});
			return;
		}
		boolean bias = depth.depthBiasConstant() != 0 && depth.depthBiasScaleFactor() != 0;
		words.add(1);
		words.add(compare(depth.depthTest()));
		words.add(depth.writeDepth() ? 1 : 0);
		words.add(Formats.pixelFormat(GpuFormat.D32_FLOAT));
		words.add(Float.floatToRawIntBits(bias ? depth.depthBiasConstant() : 0));
		words.add(Float.floatToRawIntBits(bias ? depth.depthBiasScaleFactor() : 0));
	}

	private static void color(IntArrayList words, ColorTargetState target) {
		if (target == null) {
			words.addElements(words.size(), new int[] {0, 0, BLEND_ONE, BLEND_ZERO, BLEND_ADD, BLEND_ONE, BLEND_ZERO, BLEND_ADD, 0});
			return;
		}
		words.add(Formats.pixelFormat(target.format()));
		BlendFunction blend = target.blendFunction().orElse(null);
		words.add(blend == null ? 0 : 1);
		if (blend == null) {
			words.addElements(words.size(), new int[] {BLEND_ONE, BLEND_ZERO, BLEND_ADD, BLEND_ONE, BLEND_ZERO, BLEND_ADD});
		} else {
			words.add(factor(blend.color().sourceFactor()));
			words.add(factor(blend.color().destFactor()));
			words.add(operation(blend.color().op()));
			words.add(factor(blend.alpha().sourceFactor()));
			words.add(factor(blend.alpha().destFactor()));
			words.add(operation(blend.alpha().op()));
		}
		words.add(writeMask(target));
	}

	private static int writeMask(ColorTargetState target) {
		return (target.writeRed() ? 8 : 0) | (target.writeGreen() ? 4 : 0) | (target.writeBlue() ? 2 : 0) | (target.writeAlpha() ? 1 : 0);
	}

	private static int topology(PrimitiveTopology topology) {
		return switch (topology) {
			case POINTS -> 0;
			case DEBUG_LINES -> 1;
			case DEBUG_LINE_STRIP -> 2;
			case TRIANGLE_STRIP -> 4;
			case TRIANGLE_FAN -> 5;
			case LINES, TRIANGLES, QUADS -> 3;
		};
	}

	private static int compare(CompareOp op) {
		return switch (op) {
			case NEVER_PASS -> 0;
			case LESS_THAN -> 1;
			case EQUAL -> 2;
			case LESS_THAN_OR_EQUAL -> 3;
			case GREATER_THAN -> 4;
			case NOT_EQUAL -> 5;
			case GREATER_THAN_OR_EQUAL -> 6;
			case ALWAYS_PASS -> COMPARE_ALWAYS;
		};
	}

	private static int factor(BlendFactor factor) {
		return switch (factor) {
			case ZERO -> BLEND_ZERO;
			case ONE -> BLEND_ONE;
			case SRC_COLOR -> 2;
			case ONE_MINUS_SRC_COLOR -> 3;
			case SRC_ALPHA -> 4;
			case ONE_MINUS_SRC_ALPHA -> 5;
			case DST_COLOR -> 6;
			case ONE_MINUS_DST_COLOR -> 7;
			case DST_ALPHA -> 8;
			case ONE_MINUS_DST_ALPHA -> 9;
			case SRC_ALPHA_SATURATE -> 10;
			case CONSTANT_COLOR -> 11;
			case ONE_MINUS_CONSTANT_COLOR -> 12;
			case CONSTANT_ALPHA -> 13;
			case ONE_MINUS_CONSTANT_ALPHA -> 14;
		};
	}

	private static int operation(BlendOp op) {
		return switch (op) {
			case ADD -> BLEND_ADD;
			case SUBTRACT -> 1;
			case REVERSE_SUBTRACT -> 2;
			case MIN -> 3;
			case MAX -> 4;
		};
	}
}
