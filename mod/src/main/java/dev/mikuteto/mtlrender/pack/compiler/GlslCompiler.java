package dev.mikuteto.mtlrender.pack.compiler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.mikuteto.mtlrender.Native;
import dev.mikuteto.mtlrender.ffi.Mtlr;
import dev.mikuteto.mtlrender.ffi.MtlrGlslDefine;
import dev.mikuteto.mtlrender.ffi.MtlrGlslOptions;
import dev.mikuteto.mtlrender.ffi.MtlrGlslSource;
import dev.mikuteto.mtlrender.pack.glsl.DepthUsage;
import dev.mikuteto.mtlrender.pack.glsl.DepthUsageAnalyzer;

public final class GlslCompiler {
	public enum Stage { VERTEX, FRAGMENT, COMPUTE, GEOMETRY, TESSELLATION_CONTROL, TESSELLATION_EVALUATION }
	public record Source(String path, String text) {}
	public record Define(String name, String value) {}

	public CompiledShader compile(Stage stage, List<Source> sources, List<Define> defines) throws GlslCompileException {
		try {
			Native.load();
		} catch (IOException exception) {
			throw new GlslCompileException("native GLSL compiler is unavailable", exception);
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment sourceArray = MtlrGlslSource.allocateArray(sources.size(), arena);
			for (int i = 0; i < sources.size(); i++) {
				Source source = sources.get(i);
				MemorySegment item = MtlrGlslSource.asSlice(sourceArray, i);
				MtlrGlslSource.path(item, arena.allocateFrom(source.path()));
				byte[] text = source.text().getBytes(StandardCharsets.UTF_8);
				MemorySegment textMemory = arena.allocateFrom(ValueLayout.JAVA_BYTE, text);
				MtlrGlslSource.text(item, textMemory);
				MtlrGlslSource.text_len(item, text.length);
			}
			MemorySegment defineArray = MtlrGlslDefine.allocateArray(defines.size(), arena);
			for (int i = 0; i < defines.size(); i++) {
				Define define = defines.get(i);
				MemorySegment item = MtlrGlslDefine.asSlice(defineArray, i);
				MtlrGlslDefine.name(item, arena.allocateFrom(define.name()));
				if (define.value() == null) {
					MtlrGlslDefine.value(item, MemorySegment.NULL);
					MtlrGlslDefine.value_len(item, 0);
				} else {
					byte[] value = define.value().getBytes(StandardCharsets.UTF_8);
					MtlrGlslDefine.value(item, arena.allocateFrom(ValueLayout.JAVA_BYTE, value));
					MtlrGlslDefine.value_len(item, value.length);
				}
			}
			MemorySegment options = MtlrGlslOptions.allocate(arena);
			MtlrGlslOptions.msl_major(options, 3);
			MtlrGlslOptions.msl_minor(options, 2);
			MtlrGlslOptions.max_include_depth(options, 64);
			MtlrGlslOptions.max_source_bytes(options, 16L * 1024 * 1024);
			MtlrGlslOptions.max_macro_expansion_depth(options, 128);
			MtlrGlslOptions.max_macro_expanded_bytes(options, 64L * 1024 * 1024);
			MtlrGlslOptions.max_parser_recursion_depth(options, 256);
			MtlrGlslOptions.flags(options, 1);
			MemorySegment result = Mtlr.mtlr_glsl_compile(stage.ordinal(), sourceArray, sources.size(), defineArray, defines.size(), options);
			try {
				if (!Mtlr.mtlr_glsl_result_ok(result)) throw new GlslCompileException(new String(read(result, Mtlr::mtlr_glsl_result_error), StandardCharsets.UTF_8));
				String msl = new String(read(result, Mtlr::mtlr_glsl_result_msl), StandardCharsets.UTF_8);
				Reflection reflection = Reflection.decode(read(result, Mtlr::mtlr_glsl_result_reflection));
				DepthUsage depthUsage = sources.stream().map(Source::text).map(DepthUsageAnalyzer::analyze)
					.reduce(new DepthUsage(java.util.List.of(), false), GlslCompiler::mergeDepthUsage);
				return new CompiledShader(msl, reflection, depthUsage);
			} finally {
				Mtlr.mtlr_glsl_result_destroy(result);
			}
		}
	}

	private static DepthUsage mergeDepthUsage(DepthUsage left, DepthUsage right) {
		java.util.LinkedHashMap<String, dev.mikuteto.mtlrender.pack.glsl.DepthResource> resources = new java.util.LinkedHashMap<>();
		for (var resource : left.resources()) resources.put(resource.name(), resource);
		for (var resource : right.resources()) resources.put(resource.name(), resource);
		return new DepthUsage(java.util.List.copyOf(resources.values()), left.writesFragmentDepth() || right.writesFragmentDepth());
	}

	private static byte[] read(MemorySegment result, Reader reader) {
		long size = reader.size(result, MemorySegment.NULL, 0);
		if (size < 0 || size > Integer.MAX_VALUE) throw new IllegalArgumentException("native GLSL result is too large");
		MemorySegment output = Arena.ofAuto().allocate(size);
		long written = reader.size(result, output, size);
		if (written != size) throw new IllegalArgumentException("native GLSL result changed while reading");
		return output.toArray(ValueLayout.JAVA_BYTE);
	}

	@FunctionalInterface private interface Reader { long size(MemorySegment result, MemorySegment output, long capacity); }
}
