package dev.mikuteto.mtlrender.backend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.Spv;
import dev.mikuteto.mtlrender.pack.precision.NumericUsage;
import dev.mikuteto.mtlrender.pack.precision.ReflectedOutput;
import dev.mikuteto.mtlrender.pack.precision.ReflectedOutputAdapter;

final class ShaderTranslator implements AutoCloseable {
	record Translation(String vertex, String fragment, List<ShaderBinding> bindings, List<ReflectedOutput> outputs) {
	}

	private record ShaderKey(Identifier id, ShaderType type, ShaderDefines defines) {
	}

	private final GlslCompiler glsl = new GlslCompiler();
	private final Map<ShaderKey, IntermediaryShaderModule> modules = new HashMap<>();

	Translation translate(RenderPipeline pipeline, VertexLayout layout, ShaderSource source) throws ShaderCompileException {
		IntermediaryShaderModule vertexModule = module(pipeline.getVertexShader(), ShaderType.VERTEX, pipeline.getShaderDefines(), source);
		IntermediaryShaderModule fragmentModule = module(pipeline.getFragmentShader(), ShaderType.FRAGMENT, pipeline.getShaderDefines(), source);
		try (SpirvShader vertex = SpirvShader.parse(vertexModule.spirv()); SpirvShader fragment = SpirvShader.parse(fragmentModule.spirv())) {
			List<ShaderBinding> bindings = new ArrayList<>();
			collect(bindings, vertex, pipeline, true);
			collect(bindings, fragment, pipeline, false);
			bind(bindings, vertex);
			bind(bindings, fragment);
			locateVertexInputs(vertex, layout);
			List<SpirvShader.Resource> varyings = locateOutputs(vertex);
			locateFragmentInputs(fragment, varyings);
			locateOutputs(fragment);
			return new Translation(vertex.toMsl(), fragment.toMsl(), List.copyOf(bindings), reflect(fragment.outputs()));
		}
	}

	void clear() {
		modules.values().forEach(IntermediaryShaderModule::close);
		modules.clear();
	}

	@Override
	public void close() {
		clear();
		glsl.close();
	}

	private IntermediaryShaderModule module(Identifier id, ShaderType type, ShaderDefines defines, ShaderSource source) throws ShaderCompileException {
		ShaderKey key = new ShaderKey(id, type, defines);
		IntermediaryShaderModule cached = modules.get(key);
		if (cached != null) {
			return cached;
		}
		String text = source.get(id, type);
		if (text == null) {
			throw new ShaderCompileException("Couldn't find source for " + type + " shader " + id);
		}
		IntermediaryShaderModule compiled = glsl.createIntermediary(id.toDebugFileName(), GlslPreprocessor.injectDefines(text, defines), type);
		modules.put(key, compiled);
		return compiled;
	}

	private static void collect(List<ShaderBinding> bindings, SpirvShader shader, RenderPipeline pipeline, boolean vertex) throws ShaderCompileException {
		List<BindGroupLayout.UniformDescription> uniforms = BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts());
		List<SpirvShader.Resource> buffers = new ArrayList<>(shader.uniformBuffers());
		buffers.addAll(shader.storageBuffers());
		for (SpirvShader.Resource buffer : buffers) {
			if (find(uniforms, buffer.name()).isEmpty()) {
				throw new ShaderCompileException("Unable to find shader defined uniform (" + buffer.name() + ")");
			}
			add(bindings, new ShaderBinding(ShaderBinding.Kind.UNIFORM_BUFFER, buffer.name(), null, vertex, !vertex));
		}
		List<String> samplers = BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts());
		for (SpirvShader.Resource image : shader.sampledImages()) {
			Optional<BindGroupLayout.UniformDescription> texel = find(uniforms, image.name());
			if (texel.isPresent()) {
				if (image.dimension() != Spv.SpvDimBuffer) {
					throw new ShaderCompileException("UTB (" + image.name() + ") must have type of SpvDimBuffer");
				}
				add(bindings, new ShaderBinding(ShaderBinding.Kind.TEXEL_BUFFER, image.name(), texel.get().gpuFormat(), vertex, !vertex));
			} else {
				if (!samplers.contains(image.name())) {
					throw new ShaderCompileException("Unable to find shader defined uniform (" + image.name() + ")");
				}
				if (image.dimension() != Spv.SpvDim2D && image.dimension() != Spv.SpvDimCube) {
					throw new ShaderCompileException("Sampled texture (" + image.name() + ") must have type of SpvDim2D or SpvDimCube");
				}
				add(bindings, new ShaderBinding(ShaderBinding.Kind.SAMPLED_IMAGE, image.name(), null, vertex, !vertex));
			}
		}
	}

	private static Optional<BindGroupLayout.UniformDescription> find(List<BindGroupLayout.UniformDescription> uniforms, String name) {
		return uniforms.stream().filter(uniform -> uniform.name().equals(name)).findFirst();
	}

	private static void add(List<ShaderBinding> bindings, ShaderBinding binding) {
		for (int index = 0; index < bindings.size(); index++) {
			ShaderBinding existing = bindings.get(index);
			if (existing.kind() == binding.kind() && existing.name().equals(binding.name())) {
				bindings.set(index, new ShaderBinding(existing.kind(), existing.name(), existing.texelFormat(),
						existing.vertex() || binding.vertex(), existing.fragment() || binding.fragment()));
				return;
			}
		}
		bindings.add(binding);
	}

	private static void bind(List<ShaderBinding> bindings, SpirvShader shader) throws ShaderCompileException {
		for (SpirvShader.Resource buffer : shader.uniformBuffers()) {
			shader.bind(buffer, index(bindings, ShaderBinding.Kind.UNIFORM_BUFFER, buffer.name()));
		}
		for (SpirvShader.Resource buffer : shader.storageBuffers()) {
			shader.bind(buffer, index(bindings, ShaderBinding.Kind.UNIFORM_BUFFER, buffer.name()));
		}
		for (SpirvShader.Resource image : shader.sampledImages()) {
			ShaderBinding.Kind kind = image.dimension() == Spv.SpvDimBuffer ? ShaderBinding.Kind.TEXEL_BUFFER : ShaderBinding.Kind.SAMPLED_IMAGE;
			shader.bind(image, index(bindings, kind, image.name()));
		}
	}

	private static int index(List<ShaderBinding> bindings, ShaderBinding.Kind kind, String name) {
		for (int i = 0; i < bindings.size(); i++) {
			ShaderBinding binding = bindings.get(i);
			if (binding.kind() == kind && binding.name().equals(name)) {
				return i;
			}
		}
		throw new IllegalStateException("Binding " + name + " was not collected");
	}

	private static void locateVertexInputs(SpirvShader vertex, VertexLayout layout) throws ShaderCompileException {
		Map<String, Integer> locations = layout.locations();
		List<String> missing = new ArrayList<>();
		for (SpirvShader.Resource input : vertex.inputs()) {
			Integer location = locations.get(input.name());
			if (location == null) {
				missing.add(input.name());
			} else {
				vertex.locate(input, location);
			}
		}
		if (!missing.isEmpty()) {
			throw new ShaderCompileException("Shader expects input variables which are not being provided: " + missing);
		}
	}

	private static List<SpirvShader.Resource> locateOutputs(SpirvShader shader) throws ShaderCompileException {
		List<SpirvShader.Resource> outputs = shader.outputs();
		Set<Integer> locations = new HashSet<>();
		for (SpirvShader.Resource output : outputs) {
			if (output.locationKnown()) {
				locations.add(output.location());
			}
		}
		int next = 0;
		for (SpirvShader.Resource output : outputs) {
			if (!output.builtIn() && !output.locationKnown()) {
				while (locations.contains(next)) {
					next++;
				}
				shader.locate(output, next);
				locations.add(next);
				next++;
			}
		}
		return shader.outputs();
	}

	private static void locateFragmentInputs(SpirvShader fragment, List<SpirvShader.Resource> varyings) throws ShaderCompileException {
		List<String> missing = new ArrayList<>();
		for (SpirvShader.Resource input : fragment.inputs()) {
			SpirvShader.Resource varying = findVarying(varyings, input);
			if (varying == null || !varying.locationKnown()) {
				missing.add(input.name());
			} else if (!input.locationKnown()) {
				fragment.locate(input, varying.location());
			}
		}
		if (!missing.isEmpty()) {
			throw new ShaderCompileException("Fragment shader expects varyings which the vertex shader does not write: " + missing);
		}
	}

	private static SpirvShader.Resource findVarying(List<SpirvShader.Resource> varyings, SpirvShader.Resource input) {
		for (SpirvShader.Resource varying : varyings) {
			if ((input.locationKnown() && varying.locationKnown() && input.location() == varying.location())
				|| (!input.locationKnown() && varying.name().equals(input.name()))) {
				return varying;
			}
		}
		return null;
	}

	private static List<ReflectedOutput> reflect(List<SpirvShader.Resource> outputs) {
		List<ReflectedOutput> result = new ArrayList<>(outputs.size());
		for (SpirvShader.Resource output : outputs) {
			ReflectedOutput.ScalarType scalarType = switch (output.scalarBaseType()) {
				case Spvc.SPVC_BASETYPE_FP16, Spvc.SPVC_BASETYPE_FP32, Spvc.SPVC_BASETYPE_FP64 -> ReflectedOutput.ScalarType.FLOAT;
				case Spvc.SPVC_BASETYPE_INT8, Spvc.SPVC_BASETYPE_INT16, Spvc.SPVC_BASETYPE_INT32, Spvc.SPVC_BASETYPE_INT64 -> ReflectedOutput.ScalarType.SIGNED;
				case Spvc.SPVC_BASETYPE_UINT8, Spvc.SPVC_BASETYPE_UINT16, Spvc.SPVC_BASETYPE_UINT32, Spvc.SPVC_BASETYPE_UINT64 -> ReflectedOutput.ScalarType.UNSIGNED;
				case Spvc.SPVC_BASETYPE_BOOLEAN -> ReflectedOutput.ScalarType.BOOLEAN;
				default -> ReflectedOutput.ScalarType.UNKNOWN;
			};
			ReflectedOutput shape = new ReflectedOutput(output.name(), output.location(), output.locationKnown(), output.vectorWidth(),
				output.scalarWidth(), scalarType, NumericUsage.unknown(), output.relaxedPrecision(), output.depth(), output.arrayDimensions(), output.arraySizeKnown());
			result.add(new ReflectedOutput(shape.name(), shape.location(), shape.locationKnown(), shape.vectorWidth(), shape.scalarWidth(),
				shape.scalarType(), ReflectedOutputAdapter.numericUsage(shape), shape.relaxedPrecision(), shape.depth(), shape.arrayDimensions(), shape.arraySizeKnown()));
		}
		return List.copyOf(result);
	}
}
