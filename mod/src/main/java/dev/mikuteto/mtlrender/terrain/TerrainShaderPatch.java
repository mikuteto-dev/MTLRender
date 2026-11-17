package dev.mikuteto.mtlrender.terrain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TerrainShaderPatch {
	static final int QUADS_PER_UNIT = TerrainPreparation.QUADS_PER_UNIT;
	static final int VERTICES_PER_UNIT = QUADS_PER_UNIT * 6;
	static final int UNIT_STRIDE = TerrainPreparation.UNIT_STRIDE;

	private static final Pattern VERSION = Pattern.compile("^#version\\s+\\d+[^\\n]*\\n", Pattern.MULTILINE);
	private static final Pattern CHUNK_SECTION = Pattern.compile(
		"layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+ChunkSection\\s*\\{([^}]*)\\}\\s*;"
	);
	private static final Pattern EXTRA_VERSION = Pattern.compile("^#version[^\\n]*\\n", Pattern.MULTILINE);
	private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)");
	private static final Pattern VERTEX_INPUT = Pattern.compile(
		"\\bin\\s+(vec3|vec4|vec2|ivec2)\\s+(Position|Color|UV0|UV2)\\s*;"
	);
	private static final Pattern VERTEX_SPHERICAL = Pattern.compile("\\bout\\s+float\\s+sphericalVertexDistance\\s*;");
	private static final Pattern VERTEX_CYLINDRICAL = Pattern.compile("\\bout\\s+float\\s+cylindricalVertexDistance\\s*;");
	private static final Pattern VERTEX_COLOR = Pattern.compile("\\bout\\s+vec4\\s+vertexColor\\s*;");
	private static final Pattern FRAGMENT_SPHERICAL = Pattern.compile("\\bin\\s+float\\s+sphericalVertexDistance\\s*;");
	private static final Pattern FRAGMENT_CYLINDRICAL = Pattern.compile("\\bin\\s+float\\s+cylindricalVertexDistance\\s*;");
	private static final Pattern FRAGMENT_COLOR = Pattern.compile("\\bin\\s+vec4\\s+vertexColor\\s*;");

	private static final String HEADER = """
		#version 460
		#extension GL_EXT_buffer_reference : require
		#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
		""";

	private static final String EXTENSIONS_HALF = """
		#extension GL_EXT_shader_explicit_arithmetic_types_float16 : require
		#extension GL_EXT_shader_16bit_storage : require
		""";

	private static final String RGSS_BLEND_FACTOR = "float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);";
	private static final String RGSS_EARLY_RETURN = "\n    if (blendFactor == 0.0) {\n"
		+ "        return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);\n"
		+ "    }";

	private static final String INVARIANT_POSITION = "invariant gl_Position;\n";
	private static final String SEQUENTIAL_SLOT = "uint mtlrUnitIndex = uint(gl_InstanceIndex) + uint(gl_VertexIndex) / " + VERTICES_PER_UNIT + "u;\n"
		+ "\tMtlrUnit unit = mtlr_units[mtlrUnitIndex];\n"
		+ "\tuint local = uint(gl_VertexIndex) - (mtlrUnitIndex - uint(gl_InstanceIndex)) * " + VERTICES_PER_UNIT + "u;\n"
		+ "\tuint quad = local / 6u;\n\tuint slot = local - quad * 6u;";
	private static final String INDEXED_SLOT = "MtlrUnit unit = mtlr_units[gl_InstanceIndex];\n"
		+ "\tuint local = uint(gl_VertexIndex);\n"
		+ "\tuint quad = local / 4u;\n\tuint slot = local - quad * 4u;\n\tslot = slot == 3u ? 4u : slot;";

	private static final String BLOCK_DECODE = """
		uint base = vertex * %du;
		Position = uintBitsToFloat(uvec3(vertices.data[base], vertices.data[base + 1u], vertices.data[base + 2u]));
		Color = unpackUnorm4x8(vertices.data[base + 3u]);
		UV0 = uintBitsToFloat(uvec2(vertices.data[base + 4u], vertices.data[base + 5u]));
		uint light = vertices.data[base + 6u];
		UV2 = ivec2(int(light << 16u) >> 16, int(light) >> 16);
		""".formatted(TerrainVertex.BLOCK.words);

	private static final String COMPACT_DECODE = """
		if (unit.vertexFormat == %du) {
			uint base = vertex * %du;
			uint word0 = vertices.data[base];
			uint word1 = vertices.data[base + 1u];
			uint word2 = vertices.data[base + 2u];
			uint word3 = vertices.data[base + 3u];
			uint word4 = vertices.data[base + 4u];
			Position = vec3(uvec3(word0, word1, word2) & %du) * (1.0 / %d.0) - %d.0;
			Color = unpackUnorm4x8((word0 >> 20u & 0xFFu) | (word1 >> 12u & 0xFF00u) | (word2 >> 4u & 0xFF0000u) | 0xFF000000u);
			UV0 = uintBitsToFloat(uvec2(word3, word4) & 0x3FFFFFFFu);
			UV2 = ivec2(word2 >> 28u | word3 >> 30u << 4u | word4 >> 30u << 6u, word0 >> 28u | word1 >> 28u << 4u);
		} else {
		%s}
		""".formatted(
		TerrainVertex.COMPACT.ordinal(),
		TerrainVertex.COMPACT.words,
		(1 << TerrainVertex.POSITION_BITS) - 1,
		1 << TerrainVertex.POSITION_FRACTION_BITS,
		TerrainVertex.POSITION_BIAS,
		BLOCK_DECODE
	);

	private static final String VERTEX_OUT_HALF = """
		flat out float16_t mtlr_visibility;
		out f16vec4 mtlr_color;
		out f16vec2 mtlr_fog;
		""";
	private static final String VERTEX_OUT_FULL = "flat out float mtlr_visibility;";
	private static final String VISIBILITY_CLEAR_HALF = "mtlr_visibility = float16_t(0.0);";
	private static final String VISIBILITY_CLEAR_FULL = "mtlr_visibility = 0.0;";
	private static final String VISIBILITY_WRITE_HALF = "mtlr_visibility = float16_t(unit.visibility);";
	private static final String VISIBILITY_WRITE_FULL = "mtlr_visibility = unit.visibility;";
	private static final String VARYING_COPY_HALF = """
		mtlr_color = f16vec4(vertexColor);
		mtlr_fog = f16vec2(sphericalVertexDistance, cylindricalVertexDistance);
		""";
	private static final String VARYING_COPY_FULL = "";

	private static final String VERTEX_PULL = """

		layout(buffer_reference, std430, buffer_reference_align = 4) readonly buffer MtlrWords {
			uint data[];
		};

		struct MtlrUnit {
			uint64_t vertices;
			uint64_t indices;
			ivec3 position;
			float visibility;
			uint quads;
			uint indexType;
			uint vertexFormat;
			uint reserved;
		};

		layout(std430) readonly buffer MtlrUnits {
			MtlrUnit mtlr_units[];
		};

		%s

		void main() {
			%s
			if (quad >= unit.quads) {
				gl_Position = vec4(0.0, 0.0, 0.0, 0.0);
				%s
				return;
			}
			uint vertex;
			if (unit.indexType == 0u) {
				uint corner = slot < 3u ? slot : (slot == 3u ? 2u : (slot == 4u ? 3u : 0u));
				vertex = quad * 4u + corner;
			} else {
				uint index = quad * 6u + slot;
				MtlrWords indices = MtlrWords(unit.indices);
				if (unit.indexType == 1u) {
					uint word = indices.data[index >> 1u];
					vertex = (index & 1u) == 0u ? (word & 0xFFFFu) : (word >> 16u);
				} else {
					vertex = indices.data[index];
				}
			}
			MtlrWords vertices = MtlrWords(unit.vertices);
			%s
			mtlr_chunk_position = unit.position;
			mtlr_chunk_visibility = unit.visibility;
			%s
			mtlr_main();
			%s
		}
		""";

	private static final String FRAGMENT_MAIN = """

		void main() {
		    vertexColor = vec4(mtlr_color);
		    sphericalVertexDistance = float(mtlr_fog.x);
		    cylindricalVertexDistance = float(mtlr_fog.y);
		    mtlr_fragment_main();
		}
		""";

	private static final String DEPTH_BODY = """
		vec4 mtlr_texel = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize) : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;
		mtlr_texel = mix(FogColor * vec4(1, 1, 1, mtlr_texel.a), mtlr_texel, ChunkVisibility);
		if (mtlr_texel.a < ALPHA_CUTOUT) {
		    discard;
		}
		fragColor = vec4(0.0);
		""";

	private static final String DEPTH_MAIN_HALF = """
		void main() {
		    vertexColor = vec4(mtlr_color);
		    sphericalVertexDistance = float(mtlr_fog.x);
		    cylindricalVertexDistance = float(mtlr_fog.y);
		    %s
		}
		""";

	private static final String DEPTH_MAIN_FULL = """
		void main() {
		    %s
		}
		""";

	private static final String FRAGMENT_INTERFACE_HALF = """
		flat in float16_t mtlr_visibility;
		in f16vec4 mtlr_color;
		in f16vec2 mtlr_fog;
		#define ChunkVisibility float(mtlr_visibility)
		""";
	private static final String FRAGMENT_INTERFACE_FULL = """
		flat in float mtlr_visibility;
		#define ChunkVisibility mtlr_visibility
		""";

	private TerrainShaderPatch() {
	}

	static String vertex(String source, boolean indexed, boolean compact, boolean invariant, boolean half) {
		Matcher block = CHUNK_SECTION.matcher(source);
		if (!block.find() || !MAIN.matcher(source).find() || !VERSION.matcher(source).find()) {
			return null;
		}
		String renamed = renameMembers(source, block, """
			ivec3 mtlr_chunk_position;
			float mtlr_chunk_visibility;
			#define ChunkPosition mtlr_chunk_position
			#define ChunkVisibility mtlr_chunk_visibility
			""");
		String withGlobals = unpackGlobals(renamed, VERTEX_SPHERICAL, VERTEX_CYLINDRICAL, VERTEX_COLOR, half);
		if (withGlobals == null) {
			return null;
		}
		String withInputs = VERTEX_INPUT.matcher(withGlobals).replaceAll("$1 $2;");
		String withMain = MAIN.matcher(withInputs).replaceFirst("void mtlr_main()");
		return header(withMain, invariant ? INVARIANT_POSITION : "", half)
			+ VERTEX_PULL.formatted(
				half ? VERTEX_OUT_HALF : VERTEX_OUT_FULL,
				indexed ? INDEXED_SLOT : SEQUENTIAL_SLOT,
				half ? VISIBILITY_CLEAR_HALF : VISIBILITY_CLEAR_FULL,
				compact ? COMPACT_DECODE : BLOCK_DECODE,
				half ? VISIBILITY_WRITE_HALF : VISIBILITY_WRITE_FULL,
				half ? VARYING_COPY_HALF : VARYING_COPY_FULL
			);
	}

	static String fragment(String source, boolean half) {
		Matcher block = CHUNK_SECTION.matcher(source);
		if (!block.find() || !VERSION.matcher(source).find() || !MAIN.matcher(source).find()) {
			return null;
		}
		String renamed = renameMembers(source, block, half ? FRAGMENT_INTERFACE_HALF : FRAGMENT_INTERFACE_FULL);
		String withGlobals = unpackGlobals(renamed, FRAGMENT_SPHERICAL, FRAGMENT_CYLINDRICAL, FRAGMENT_COLOR, half);
		if (withGlobals == null) {
			return null;
		}
		String withMain = half ? MAIN.matcher(withGlobals).replaceFirst("void mtlr_fragment_main()") + FRAGMENT_MAIN : withGlobals;
		String patched = header(withMain, "", half);
		return patched.replace(RGSS_BLEND_FACTOR, RGSS_BLEND_FACTOR + RGSS_EARLY_RETURN);
	}

	static String depthFragment(String source, boolean half) {
		Matcher block = CHUNK_SECTION.matcher(source);
		if (!block.find() || !VERSION.matcher(source).find() || !MAIN.matcher(source).find()) {
			return null;
		}
		String renamed = renameMembers(source, block, half ? FRAGMENT_INTERFACE_HALF : FRAGMENT_INTERFACE_FULL);
		String withGlobals = unpackGlobals(renamed, FRAGMENT_SPHERICAL, FRAGMENT_CYLINDRICAL, FRAGMENT_COLOR, half);
		if (withGlobals == null) {
			return null;
		}
		Matcher main = MAIN.matcher(withGlobals);
		if (!main.find()) {
			return null;
		}
		String replacement = half ? DEPTH_MAIN_HALF.formatted(DEPTH_BODY) : DEPTH_MAIN_FULL.formatted(DEPTH_BODY);
		String withMain = replaceMain(withGlobals, main, replacement);
		if (withMain == null) {
			return null;
		}
		String patched = header(withMain, "", half);
		return patched.replace(RGSS_BLEND_FACTOR, RGSS_BLEND_FACTOR + RGSS_EARLY_RETURN);
	}

	private static String replaceMain(String source, Matcher main, String replacement) {
		int open = source.indexOf('{', main.end());
		if (open < 0) {
			return null;
		}
		int depth = 0;
		for (int i = open; i < source.length(); i++) {
			char c = source.charAt(i);
			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return source.substring(0, main.start()) + replacement + source.substring(i + 1);
				}
			}
		}
		return null;
	}

	private static String unpackGlobals(String source, Pattern spherical, Pattern cylindrical, Pattern color, boolean half) {
		if (!half) {
			return source;
		}
		String globals = replaceDeclaration(source, spherical, "float sphericalVertexDistance;");
		if (globals == null) {
			return null;
		}
		globals = replaceDeclaration(globals, cylindrical, "float cylindricalVertexDistance;");
		if (globals == null) {
			return null;
		}
		return replaceDeclaration(globals, color, "vec4 vertexColor;");
	}

	private static String replaceDeclaration(String source, Pattern pattern, String replacement) {
		if (!pattern.matcher(source).find()) {
			return null;
		}
		return pattern.matcher(source).replaceFirst(replacement);
	}

	private static String header(String source, String declarations, boolean half) {
		return HEADER + (half ? EXTENSIONS_HALF : "") + declarations + EXTRA_VERSION.matcher(source).replaceAll("");
	}

	private static String renameMembers(String source, Matcher block, String declarations) {
		String members = block.group(1)
			.replaceAll("\\bChunkVisibility\\b", "mtlr_unused_visibility")
			.replaceAll("\\bChunkPosition\\b", "mtlr_unused_position");
		String replacement = "layout(std140) uniform ChunkSection {" + members + "};\n" + declarations;
		return source.substring(0, block.start()) + replacement + source.substring(block.end());
	}
}
