package dev.mikuteto.mtlrender.pack.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PackTerrainVertexWrapper {
	public static final String ENTRY = "mtlr_terrain_vertex";
	private static final Pattern ENTRY_SIGNATURE = Pattern.compile("(?s)\\bvertex\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*?)\\)\\s*\\{");
	private static final List<String> POSITION = List.of("Position", "gl_Vertex", "mtlr_vertex");
	private static final List<String> COLOR = List.of("Color", "gl_Color", "mtlr_color");
	private static final List<String> UV0 = List.of("UV0", "gl_MultiTexCoord0", "mtlr_texcoord0");
	private static final List<String> UV2 = List.of("UV2", "gl_MultiTexCoord2", "mtlr_texcoord2");
	private static final List<String> NORMAL = List.of("Normal", "gl_Normal", "mtlr_normal");
	private static final List<String> ENTITY = List.of("mc_Entity", "mtlr_entity");
	private static final List<String> MID_TEX = List.of("mc_midTexCoord", "mtlr_mid_texcoord");
	private static final List<String> TANGENT = List.of("at_tangent", "mtlr_tangent");
	private static final List<String> MID_BLOCK = List.of("at_midBlock", "mtlr_mid_block");

	public record Result(String msl, String entry, List<String> reads) {
		public Result {
			Objects.requireNonNull(msl, "MSL");
			Objects.requireNonNull(entry, "entry");
			reads = List.copyOf(Objects.requireNonNull(reads, "reads"));
		}
	}

	private PackTerrainVertexWrapper() {
	}

	public static Optional<Result> wrap(Optional<PackTranslationArtifact> artifact) {
		Objects.requireNonNull(artifact, "artifact");
		return artifact.map(PackTerrainVertexWrapper::wrap);
	}

	public static Result wrap(PackTranslationArtifact artifact) {
		Objects.requireNonNull(artifact, "artifact");
		Matcher matcher = ENTRY_SIGNATURE.matcher(artifact.vertexMsl());
		if (!matcher.find()) throw failure(artifact, "translated vertex entry was not found");
		String returnType = matcher.group(1);
		String oldEntry = matcher.group(2);
		if (!oldEntry.equals(artifact.vertexEntry())) throw failure(artifact, "translated vertex entry name changed");
		String parameters = matcher.group(3).trim();
		if (parameters.isEmpty() || !parameters.startsWith("MtlrVertexIn ")) {
			throw failure(artifact, "translated vertex entry has no terrain stage input");
		}
		List<PackTranslationArtifact.VertexInput> inputs = new ArrayList<>(artifact.reflection().vertexInputs());
		inputs.sort(Comparator.comparingInt(PackTranslationArtifact.VertexInput::location));
		List<String> assignments = new ArrayList<>();
		for (PackTranslationArtifact.VertexInput input : inputs) {
			String source = sourceFor(input.name());
			String type = canonicalType(input.format());
			validateType(input.name(), type, artifact);
			assignments.add("    mtlr_terrain." + input.name() + " = " + source + ";");
		}
		String renamed = artifact.vertexMsl().substring(0, matcher.start(2)) + "mtlr_pack_vertex_main"
			+ artifact.vertexMsl().substring(matcher.end(2));
		StringBuilder source = new StringBuilder(renamed);
		source.append("\nvertex ").append(returnType).append(' ').append(ENTRY).append('(').append(parameters).append(") {\n");
		source.append("    MtlrVertexIn mtlr_terrain{};\n");
		for (String assignment : assignments) source.append(assignment).append('\n');
		source.append("    return mtlr_pack_vertex_main(mtlr_terrain");
		int separator = parameters.indexOf(',');
		String callArguments = separator < 0 ? "" : parameters.substring(separator + 1);
		for (String argument : argumentNames(callArguments)) source.append(", ").append(argument);
		source.append(");\n}\n");
		return new Result(source.toString(), ENTRY, inputs.stream().map(PackTranslationArtifact.VertexInput::name).toList());
	}

	private static String sourceFor(String name) {
		if (POSITION.contains(name) || COLOR.contains(name) || UV0.contains(name) || UV2.contains(name)
			|| NORMAL.contains(name) || ENTITY.contains(name) || MID_TEX.contains(name)
			|| TANGENT.contains(name) || MID_BLOCK.contains(name)) return "mtlr_in." + name;
		throw new IllegalArgumentException("pack terrain vertex input is unsupported: " + name);
	}

	private static String canonicalType(String format) {
		return switch (format) {
			case "float", "float2", "float3", "float4", "vec2", "vec3", "vec4" -> format.replace("vec", "float");
			case "int", "int2", "int3", "int4", "ivec2", "ivec3", "ivec4" -> format.replace("ivec", "int");
			case "uint", "uint2", "uint3", "uint4", "uvec2", "uvec3", "uvec4" -> format.replace("uvec", "uint");
			default -> throw new IllegalArgumentException("pack terrain vertex input type is unsupported: " + format);
		};
	}

	private static void validateType(String name, String type, PackTranslationArtifact artifact) {
		boolean valid = (POSITION.contains(name) && (type.equals("float3") || type.equals("float4")))
			|| (COLOR.contains(name) && type.equals("float4"))
			|| (UV0.contains(name) && (type.equals("float2") || type.equals("float4")))
			|| (UV2.contains(name) && (type.equals("int2") || type.equals("int4")))
			|| (NORMAL.contains(name) && (type.equals("float3") || type.equals("float4")))
			|| (ENTITY.contains(name) && (type.equals("uint") || type.equals("int") || type.equals("float4")))
			|| (MID_TEX.contains(name) && type.equals("float2"))
			|| (TANGENT.contains(name) && type.equals("float4"))
			|| (MID_BLOCK.contains(name) && (type.equals("float3") || type.equals("float4")));
		if (!valid) throw failure(artifact, "incompatible type " + type + " for " + name);
	}

	private static List<String> argumentNames(String parameters) {
		if (parameters.isBlank()) return List.of();
		List<String> result = new ArrayList<>();
		for (String parameter : parameters.split(",")) {
			String value = parameter.replaceAll("\\[\\[[^]]*]]", "").trim();
			String[] words = value.split("\\s+");
			if (words.length == 0 || words[words.length - 1].isEmpty()) throw new IllegalArgumentException("invalid translated vertex parameter");
			result.add(words[words.length - 1]);
		}
		return result;
	}

	private static IllegalArgumentException failure(PackTranslationArtifact artifact, String message) {
		return new IllegalArgumentException("pack terrain vertex wrapper for " + artifact.vertexEntry() + ": " + message);
	}
}
