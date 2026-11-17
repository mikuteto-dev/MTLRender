package dev.mikuteto.mtlrender.backend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import dev.mikuteto.mtlrender.pack.runtime.PackFunctionConstants;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.platform.CompareOp;

public record PackPipelineDesc(
	String vertexMsl,
	String vertexEntry,
	String fragmentMsl,
	String fragmentEntry,
	List<Binding> bindings,
	VertexLayout vertexLayout,
	List<ColorTargetState> colorAttachments,
	GpuFormat depthFormat,
	CompareOp depthTest,
	boolean depthWrite,
	CullMode cullMode,
	List<FunctionConstant> functionConstants,
	Optional<LayerInstancing> layerInstancing
) {
	public PackPipelineDesc(String vertexMsl, String vertexEntry, String fragmentMsl, String fragmentEntry,
		List<Binding> bindings, VertexLayout vertexLayout, List<ColorTargetState> colorAttachments,
		GpuFormat depthFormat, CompareOp depthTest, boolean depthWrite, CullMode cullMode) {
		this(vertexMsl, vertexEntry, fragmentMsl, fragmentEntry, bindings, vertexLayout, colorAttachments,
			depthFormat, depthTest, depthWrite, cullMode, List.of(), Optional.empty());
	}

	public PackPipelineDesc(String vertexMsl, String vertexEntry, String fragmentMsl, String fragmentEntry,
		List<Binding> bindings, VertexLayout vertexLayout, List<ColorTargetState> colorAttachments,
		GpuFormat depthFormat, CompareOp depthTest, boolean depthWrite, CullMode cullMode,
		List<FunctionConstant> functionConstants) {
		this(vertexMsl, vertexEntry, fragmentMsl, fragmentEntry, bindings, vertexLayout, colorAttachments,
			depthFormat, depthTest, depthWrite, cullMode, functionConstants, Optional.empty());
	}
	public static final int STAGE_VERTEX = 1;
	public static final int STAGE_FRAGMENT = 2;

	public static CompareOp gameCompare(CompareOp packCompare) {
		Objects.requireNonNull(packCompare);
		return switch (packCompare) {
			case LESS_THAN -> CompareOp.GREATER_THAN;
			case LESS_THAN_OR_EQUAL -> CompareOp.GREATER_THAN_OR_EQUAL;
			case GREATER_THAN -> CompareOp.LESS_THAN;
			case GREATER_THAN_OR_EQUAL -> CompareOp.LESS_THAN_OR_EQUAL;
			case NEVER_PASS, EQUAL, NOT_EQUAL, ALWAYS_PASS -> packCompare;
		};
	}

	public static float gameDepthClear(float packDepth) {
		if (!Float.isFinite(packDepth) || packDepth < 0.0f || packDepth > 1.0f) {
			throw new IllegalArgumentException("pack depth clear must be finite and within 0..1");
		}
		return 1.0f - packDepth;
	}

	public PackPipelineDesc {
		if (vertexMsl == null || vertexMsl.isEmpty() || vertexEntry == null || vertexEntry.isEmpty()
			|| (fragmentMsl == null) != (fragmentEntry == null)
			|| fragmentMsl != null && (fragmentMsl.isEmpty() || fragmentEntry.isEmpty())) {
			throw new IllegalArgumentException("shader sources and entry names must not be empty");
		}
		Objects.requireNonNull(bindings);
		Objects.requireNonNull(colorAttachments);
		Objects.requireNonNull(cullMode);
		Objects.requireNonNull(functionConstants);
		Objects.requireNonNull(layerInstancing);
		bindings = List.copyOf(bindings);
		functionConstants = List.copyOf(functionConstants);
		colorAttachments = Collections.unmodifiableList(new ArrayList<>(colorAttachments));
		validateBindings(bindings);
		if (colorAttachments.size() > 8) {
			throw new IllegalArgumentException("at most 8 color attachments are supported");
		}
		for (ColorTargetState target : colorAttachments) {
			if (target != null && (target.format() == null || !target.format().hasColorAspect())) {
				throw new IllegalArgumentException("color attachments need color formats");
			}
		}
		if ((depthFormat == null) != (depthTest == null) || depthFormat != null && !depthFormat.hasDepthAspect()) {
			throw new IllegalArgumentException("invalid depth description");
		}
		if (depthFormat == null && depthWrite) {
			throw new IllegalArgumentException("depthWrite requires a depth test");
		}
		if (vertexLayout != null) {
			validateVertexLayout(vertexLayout);
		}
	}

	private static void validateBindings(List<Binding> bindings) {
		Set<String> names = new HashSet<>();
		Set<String> slots = new HashSet<>();
		for (Binding binding : bindings) {
			Objects.requireNonNull(binding);
			String name = binding.name();
			String slot = binding.kind() + ":" + binding.slot();
			if (!names.add(binding.kind() + ":" + name) || !slots.add(slot)) {
				throw new IllegalArgumentException("duplicate pack binding: " + name);
			}
			int limit = binding.kind() == Kind.BUFFER ? 32 : 16;
			if (binding.slot() >= limit) {
				throw new IllegalArgumentException("pack binding slot is out of range: " + binding.slot());
			}
		}
	}

	private static void validateVertexLayout(VertexLayout layout) {
		Set<Integer> locations = new HashSet<>();
		for (VertexBuffer buffer : layout.buffers()) {
			Objects.requireNonNull(buffer);
			if (buffer.slot() >= 16 || buffer.stride() % 4 != 0) {
				throw new IllegalArgumentException("invalid pack vertex buffer");
			}
		}
		for (VertexAttribute attribute : layout.attributes()) {
			Objects.requireNonNull(attribute);
			if (attribute.location() >= 31 || attribute.slot() >= 16 || attribute.offset() % 4 != 0 || !locations.add(attribute.location())) {
				throw new IllegalArgumentException("invalid pack vertex attribute");
			}
		}
	}

	public static List<FunctionConstant> typedConstants(PackFunctionConstants constants) {
		Objects.requireNonNull(constants, "function constants");
		java.util.ArrayList<FunctionConstant> result = new java.util.ArrayList<>();
		java.util.Map<String, String> values = new java.util.TreeMap<>();
		values.putAll(constants.optionConstants());
		values.putAll(constants.uniformConstants());
		values.forEach((name, value) -> result.add(parse(name, value)));
		return List.copyOf(result);
	}

	private static FunctionConstant parse(String name, String value) {
		if (value.equals("true") || value.equals("false")) {
			return new FunctionConstant(name, FunctionConstantType.BOOL, value.equals("true") ? 1L : 0L);
		}
		try {
			return new FunctionConstant(name, FunctionConstantType.INT, Long.parseLong(value));
		} catch (NumberFormatException ignored) {
			try {
				return new FunctionConstant(name, FunctionConstantType.FLOAT,
					Long.valueOf(Float.floatToRawIntBits(Float.parseFloat(value))));
			} catch (NumberFormatException exception) {
				throw new IllegalArgumentException("function constant is not a typed scalar: " + name, exception);
			}
		}
	}

	public record LayerInstancing(int instanceCount, int baseInstance, List<Integer> layerMapping) {
		public LayerInstancing {
			Objects.requireNonNull(layerMapping, "layer mapping");
			if (instanceCount <= 0 || instanceCount != layerMapping.size() || instanceCount > 2048
				|| baseInstance < 0) throw new IllegalArgumentException("invalid layer instancing count");
			for (int layer : layerMapping) {
				if (layer < 0 || layer >= 2048) throw new IllegalArgumentException("layer mapping is out of range");
			}
			layerMapping = List.copyOf(layerMapping);
		}
	}

	public enum FunctionConstantType {
		BOOL,
		INT,
		FLOAT
	}

	public record FunctionConstant(String name, FunctionConstantType type, long value) {
		public FunctionConstant {
			if (name == null || name.isEmpty()) throw new IllegalArgumentException("function constant name must not be empty");
			Objects.requireNonNull(type, "function constant type");
		}
	}

	public record Binding(String name, Kind kind, int slot, int stages) {
		public Binding {
			Objects.requireNonNull(name);
			Objects.requireNonNull(kind);
			if (name.isEmpty() || slot < 0 || stages <= 0 || (stages & ~(STAGE_VERTEX | STAGE_FRAGMENT)) != 0) {
				throw new IllegalArgumentException("invalid pack binding");
			}
		}
	}

	public enum Kind {
		TEXTURE,
		SAMPLER,
		BUFFER
	}

	public record VertexLayout(List<VertexBuffer> buffers, List<VertexAttribute> attributes) {
		public VertexLayout {
			Objects.requireNonNull(buffers);
			Objects.requireNonNull(attributes);
			buffers = List.copyOf(buffers);
			attributes = List.copyOf(attributes);
		}
	}

	public record VertexBuffer(int slot, int stride, int stepRate) {
		public VertexBuffer {
			if (slot < 0 || stride <= 0 || stepRate < 0) {
				throw new IllegalArgumentException("invalid pack vertex buffer");
			}
		}
	}

	public record VertexAttribute(int location, GpuFormat format, int offset, int slot) {
		public VertexAttribute {
			Objects.requireNonNull(format);
			if (location < 0 || offset < 0 || slot < 0) {
				throw new IllegalArgumentException("invalid pack vertex attribute");
			}
		}
	}

	public enum CullMode {
		NONE(0),
		BACK(1),
		FRONT(2);

		private final int code;

		CullMode(int code) {
			this.code = code;
		}

		int code() {
			return code;
		}
	}
}
