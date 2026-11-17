package dev.mikuteto.mtlrender.backend;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcReflectedResource;

final class SpirvShader implements AutoCloseable {
	static final String ENTRY_POINT = "main0";

	private static final int MSL_VERSION = 30200;

	record Resource(
		int id,
		String name,
		int dimension,
		int location,
		boolean locationKnown,
		int component,
		boolean componentKnown,
		int vectorWidth,
		int scalarWidth,
		int scalarBaseType,
		boolean relaxedPrecision,
		boolean depth,
		boolean builtIn,
		int arrayDimensions,
		boolean arraySizeKnown
	) {
	}

	private final long context;
	private final long compiler;
	private final long resources;

	private SpirvShader(long context, long compiler, long resources) {
		this.context = context;
		this.compiler = compiler;
		this.resources = resources;
	}

	static SpirvShader parse(ByteBuffer spirv) throws ShaderCompileException {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer out = stack.callocPointer(1);
			check(Spvc.spvc_context_create(out), 0, "create context");
			long context = out.get(0);
			try {
				check(Spvc.spvc_context_parse_spirv(context, spirv.asIntBuffer(), spirv.remaining() / Integer.BYTES, out), context, "parse SPIR-V");
				long ir = out.get(0);
				check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out), context, "create MSL compiler");
				long compiler = out.get(0);
				check(Spvc.spvc_compiler_create_shader_resources(compiler, out), context, "reflect resources");
				return new SpirvShader(context, compiler, out.get(0));
			} catch (ShaderCompileException e) {
				Spvc.spvc_context_destroy(context);
				throw e;
			}
		}
	}

	List<Resource> uniformBuffers() throws ShaderCompileException {
		return list(Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER);
	}

	List<Resource> storageBuffers() throws ShaderCompileException {
		return list(Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER);
	}

	List<Resource> sampledImages() throws ShaderCompileException {
		return list(Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE);
	}

	List<Resource> inputs() throws ShaderCompileException {
		return list(Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT);
	}

	List<Resource> outputs() throws ShaderCompileException {
		List<Resource> result = new ArrayList<>(list(Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT));
		result.addAll(list(Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT, true));
		return result;
	}

	void bind(Resource resource, int binding) {
		Spvc.spvc_compiler_set_decoration(compiler, resource.id(), Spv.SpvDecorationDescriptorSet, 0);
		Spvc.spvc_compiler_set_decoration(compiler, resource.id(), Spv.SpvDecorationBinding, binding);
	}

	void locate(Resource resource, int location) {
		Spvc.spvc_compiler_set_decoration(compiler, resource.id(), Spv.SpvDecorationLocation, location);
	}

	String toMsl() throws ShaderCompileException {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer out = stack.callocPointer(1);
			check(Spvc.spvc_compiler_create_compiler_options(compiler, out), context, "create options");
			long options = out.get(0);
			Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION);
			Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS);
			Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
			Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
			Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING, true);
			check(Spvc.spvc_compiler_install_compiler_options(compiler, options), context, "install options");
			check(Spvc.spvc_compiler_compile(compiler, out), context, "compile MSL");
			return MemoryUtil.memUTF8(out.get(0));
		}
	}

	@Override
	public void close() {
		Spvc.spvc_context_destroy(context);
	}

	private List<Resource> list(int type) throws ShaderCompileException {
		return list(type, false);
	}

	private List<Resource> list(int type, boolean builtins) throws ShaderCompileException {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer list = stack.callocPointer(1);
			PointerBuffer count = stack.callocPointer(1);
			int resultCode = builtins
				? Spvc.spvc_resources_get_builtin_resource_list_for_type(resources, type, list, count)
				: Spvc.spvc_resources_get_resource_list_for_type(resources, type, list, count);
			check(resultCode, context, "list resources");
			SpvcReflectedResource.Buffer reflected = SpvcReflectedResource.create(list.get(0), (int) count.get(0));
			List<Resource> result = new ArrayList<>(reflected.capacity());
			for (SpvcReflectedResource resource : reflected) {
				long typeHandle = Spvc.spvc_compiler_get_type_handle(compiler, resource.base_type_id());
				long variableTypeHandle = Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id());
				int dimension = type == Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE ? Spvc.spvc_type_get_image_dimension(variableTypeHandle) : -1;
				boolean locationKnown = Spvc.spvc_compiler_has_decoration(compiler, resource.id(), Spv.SpvDecorationLocation);
				int location = locationKnown ? Spvc.spvc_compiler_get_decoration(compiler, resource.id(), Spv.SpvDecorationLocation) : -1;
				boolean componentKnown = Spvc.spvc_compiler_has_decoration(compiler, resource.id(), Spv.SpvDecorationComponent);
				int component = componentKnown ? Spvc.spvc_compiler_get_decoration(compiler, resource.id(), Spv.SpvDecorationComponent) : -1;
				boolean depth = Spvc.spvc_compiler_has_decoration(compiler, resource.id(), Spv.SpvDecorationBuiltIn)
					&& Spvc.spvc_compiler_get_decoration(compiler, resource.id(), Spv.SpvDecorationBuiltIn) == Spv.SpvBuiltInFragDepth;
				boolean relaxedPrecision = Spvc.spvc_compiler_has_decoration(compiler, resource.id(), Spv.SpvDecorationRelaxedPrecision);
				int arrayDimensions = Spvc.spvc_type_get_num_array_dimensions(variableTypeHandle);
				boolean arraySizeKnown = true;
				for (int index = 0; index < arrayDimensions; index++) {
					arraySizeKnown &= Spvc.spvc_type_array_dimension_is_literal(variableTypeHandle, index);
				}
				result.add(new Resource(resource.id(), resource.nameString(), dimension, location, locationKnown, component,
					componentKnown, Spvc.spvc_type_get_vector_size(typeHandle), Spvc.spvc_type_get_bit_width(typeHandle),
					Spvc.spvc_type_get_basetype(typeHandle), relaxedPrecision, depth, builtins, arrayDimensions, arraySizeKnown));
			}
			return result;
		}
	}

	private static void check(int result, long context, String step) throws ShaderCompileException {
		if (result != Spvc.SPVC_SUCCESS) {
			String detail = context == 0 ? Integer.toString(result) : Spvc.spvc_context_get_last_error_string(context);
			throw new ShaderCompileException("SPIRV-Cross failed to " + step + ": " + detail);
		}
	}
}
