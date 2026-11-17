package dev.mikuteto.mtlrender.terrain;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

public interface TerrainMeshShader {
	String UNITS = "MtlrUnits";
	String ENTRY_POINT = "main0";
	String OBJECT_ENTRY_POINT = ENTRY_POINT + "_object";
	String MESH_ENTRY_POINT = ENTRY_POINT + "_mesh";
	String FRAGMENT_ENTRY_POINT = ENTRY_POINT + "_fragment";
	int OBJECT_STAGE = 1;
	int MESH_STAGE = 2;
	int FRAGMENT_STAGE = 4;
	int MAX_QUADS = 32;
	int MAX_VERTICES = 128;
	int MAX_TRIANGLES = 64;
	int OBJECT_THREADS = 1;
	int MESH_THREADS = 128;

	record Program(
		Sources sources,
		EntryPoints entryPoints,
		Size objectDispatch,
		Size objectThreadgroup,
		Size meshThreadgroup,
		UnitLayout units,
		List<Varying> varyings,
		List<Binding> bindings
	) {
		public Program {
			Objects.requireNonNull(sources);
			Objects.requireNonNull(entryPoints);
			Objects.requireNonNull(objectDispatch);
			Objects.requireNonNull(objectThreadgroup);
			Objects.requireNonNull(meshThreadgroup);
			Objects.requireNonNull(units);
			varyings = List.copyOf(varyings);
			bindings = List.copyOf(bindings);
		}

		public OptionalInt unitsBinding() {
			return bindings.stream()
				.filter(binding -> binding.name().equals(UNITS))
				.mapToInt(Binding::index)
				.findFirst();
		}
	}

	record Sources(String object, String mesh, String fragment) {
		public Sources {
			Objects.requireNonNull(object);
			Objects.requireNonNull(mesh);
			Objects.requireNonNull(fragment);
		}
	}

	record EntryPoints(String object, String mesh, String fragment) {
		public EntryPoints {
			Objects.requireNonNull(object);
			Objects.requireNonNull(mesh);
			Objects.requireNonNull(fragment);
		}
	}

	record Size(int x, int y, int z) {
		public Size {
			if (x <= 0 || y <= 0 || z <= 0) {
				throw new IllegalArgumentException("mesh size must be positive");
			}
		}
	}

	record UnitLayout(int stride, int quadsPerUnit, List<DecodeVariant> variants) {
		public UnitLayout {
			if (stride <= 0 || quadsPerUnit <= 0) {
				throw new IllegalArgumentException("unit layout size must be positive");
			}
			variants = List.copyOf(variants);
		}
	}

	enum DecodeVariant {
		BLOCK,
		COMPACT
	}

	record Varying(
		String name,
		int location,
		int vectorWidth,
		int scalarWidth,
		ScalarType scalarType,
		Interpolation interpolation
	) {
		public Varying {
			Objects.requireNonNull(name);
			Objects.requireNonNull(scalarType);
			Objects.requireNonNull(interpolation);
			if (location < 0 || vectorWidth <= 0 || scalarWidth <= 0) {
				throw new IllegalArgumentException("invalid terrain varying metadata");
			}
		}
	}

	enum ScalarType {
		FLOAT
	}

	enum Interpolation {
		FLAT,
		SMOOTH
	}

	record Binding(String name, int index, int stages) {
		public Binding {
			Objects.requireNonNull(name);
			if (index < 0 || stages == 0) {
				throw new IllegalArgumentException("invalid terrain binding metadata");
			}
		}
	}

	static Program program(boolean half) {
		return program(half, List.of(
			new Binding(UNITS, 0, OBJECT_STAGE | MESH_STAGE),
			new Binding("TerrainParams", 1, OBJECT_STAGE | MESH_STAGE | FRAGMENT_STAGE)
		));
	}

	static Program program(boolean half, List<Binding> bindings) {
		int units = binding(bindings, UNITS);
		int params = binding(bindings, "TerrainParams");
		return new Program(
			new Sources(objectSource(units), meshSource(half, units, params), fragmentSource(half)),
			new EntryPoints(OBJECT_ENTRY_POINT, MESH_ENTRY_POINT, FRAGMENT_ENTRY_POINT),
			new Size(1, 1, 1),
			new Size(OBJECT_THREADS, 1, 1),
			new Size(MESH_THREADS, 1, 1),
			unitLayout(),
			varyings(half),
			bindings
		);
	}

	record InterfaceCheck(boolean supported, String failure) {
		public InterfaceCheck {
			Objects.requireNonNull(failure);
		}
	}

	static InterfaceCheck checkInterface(boolean half, List<Varying> actual) {
		if (varyings(half).equals(actual)) {
			return new InterfaceCheck(true, "");
		}
		return new InterfaceCheck(false, "translated terrain fragment interface is unsupported");
	}

	private static int binding(List<Binding> bindings, String name) {
		return bindings.stream()
			.filter(value -> value.name().equals(name))
			.mapToInt(Binding::index)
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("terrain mesh contract is missing binding " + name));
	}

	static String objectSource() {
		return objectSource(0);
	}

	static String objectSource(int unitsBinding) {
		return MSL_HEADER + MSL_UNIT + """

		struct MtlrPayload {
			uint unitIndex;
			uint quads;
		};

		object void main0_object(
			uint3 threadgroupPosition [[threadgroup_position_in_grid]],
			uint3 threadPosition [[thread_position_in_threadgroup]],
			device const MtlrUnit *units [[buffer(%d)]],
			threadgroup_payload MtlrPayload payload [[payload]],
			mesh_grid_properties grid [[mesh_grid_properties]]) {
			if (threadPosition.x == 0u) {
				uint unitIndex = threadgroupPosition.x;
				payload.unitIndex = unitIndex;
				payload.quads = min(units[unitIndex].quads, 32u);
				grid.set_threadgroups_per_grid(uint3(1u, 1u, 1u));
			}
		}
		""".formatted(unitsBinding);
	}

	static String meshSource(boolean half) {
		return meshSource(half, 0, 1);
	}

	static String meshSource(boolean half, int unitsBinding, int paramsBinding) {
		String varying = half ? """
			 half visibility [[user(locn0), flat]];
			 half4 color [[user(locn1)]];
			 half2 fog [[user(locn2)]];
			 half2 texCoord0 [[user(locn3)]];
			""" : """
			 float sphericalDistance [[user(locn0)]];
			 float cylindricalDistance [[user(locn1)]];
			 float4 vertexColor [[user(locn2)]];
			 float2 texCoord0 [[user(locn3)]];
			 float visibility [[user(locn4), flat]];
			""";
		return MSL_HEADER + MSL_UNIT + MSL_DECODE + """

		struct TerrainParams {
			float4x4 modelViewProjection;
			int3 cameraBlock;
			float3 cameraFraction;
		};

		struct MtlrPayload {
			uint unitIndex;
			uint quads;
		};

		struct MtlrVertex {
			float4 position [[position]];
			int2 lightmap;
			""" + varying + """
		};

		using MtlrMesh = mesh<MtlrVertex, uint3, 128, 64>;

		mesh void main0_mesh(
			uint3 threadPosition [[thread_position_in_threadgroup]],
			device const MtlrUnit *units [[buffer(%d)]],
			constant TerrainParams &params [[buffer(%d)]],
			threadgroup_payload MtlrPayload payload [[payload]],
			MtlrMesh out [[stage_out]]) {
			uint quads = min(payload.quads, 32u);
			uint vertexCount = quads * 4u;
			uint triangleCount = quads * 2u;
			out.set_vertex_count(vertexCount);
			out.set_primitive_count(triangleCount);
			if (threadPosition.x < vertexCount) {
				MtlrUnit unit = units[payload.unitIndex];
				uint quad = threadPosition.x >> 2u;
				uint corner = threadPosition.x & 3u;
				uint vertex;
				if (unit.index_type == 0u) {
					vertex = quad * 4u + corner;
				} else {
					uint slot = corner == 3u ? 4u : corner;
					uint index = quad * 6u + slot;
					vertex = mtlr_index(unit.indices, index, unit.index_type);
				}
				MtlrDecoded decoded = mtlr_decode(unit, vertex);
				MtlrVertex value;
				float3 translated = float3(unit.position) + decoded.position - float3(params.cameraBlock) + params.cameraFraction;
				value.position = params.modelViewProjection * float4(translated, 1.0f);
				value.lightmap = decoded.lightmap;
				if (%s) {
					value.texCoord0 = half2(decoded.uv);
					value.color = half4(decoded.color);
					value.fog = half2(length(translated), length(translated.xz));
				} else {
					value.sphericalDistance = length(translated);
					value.cylindricalDistance = length(translated.xz);
					value.vertexColor = decoded.color;
					value.texCoord0 = decoded.uv;
				}
				value.visibility = %s;
				out[threadPosition.x] = value;
			}
			if (threadPosition.x < triangleCount) {
				uint base = (threadPosition.x >> 1u) * 4u;
				uint3 indices = (threadPosition.x & 1u) == 0u
					? uint3(base, base + 1u, base + 2u)
					: uint3(base + 2u, base + 3u, base);
				out.set_index(threadPosition.x, indices);
			}
		}
		""".formatted(unitsBinding, paramsBinding, half, half ? "half(unit.visibility)" : "unit.visibility");
	}

	static String fragmentSource(boolean half) {
		String input = half ? """
			half visibility [[user(locn0), flat]];
			half4 color [[user(locn1)]];
			half2 fog [[user(locn2)]];
			half2 texCoord0 [[user(locn3)]];
			""" : """
			float sphericalDistance [[user(locn0)]];
			float cylindricalDistance [[user(locn1)]];
			float4 vertexColor [[user(locn2)]];
			float2 texCoord0 [[user(locn3)]];
			float visibility [[user(locn4), flat]];
			""";
		return MSL_HEADER + """
		struct MtlrVertex {
			float4 position [[position]];
			""" + input + """
		};

		struct MtlrFragment {
			float4 color [[color(0)]];
		};

		fragment MtlrFragment main0_fragment(MtlrVertex in [[stage_in]]) {
			MtlrFragment out;
			out.color = float4(1.0f);
			return out;
		}
		""";
	}

	String MSL_HEADER = """
		#include <metal_stdlib>
		using namespace metal;
		""";

	String MSL_UNIT = """
		struct MtlrUnit {
			ulong vertices;
			ulong indices;
			packed_int3 position;
			float visibility;
			uint quads;
			uint index_type;
			uint vertex_format;
			uint slot;
		};
		""";

	String MSL_DECODE = """
		struct MtlrDecoded {
			float3 position;
			float4 color;
			float2 uv;
			int2 lightmap;
		};

		static uint mtlr_index(ulong address, uint index, uint type) {
			device const uint *words = reinterpret_cast<device const uint *>(address);
			if (type == 1u) {
				uint word = words[index >> 1u];
				return (index & 1u) == 0u ? (word & 0xFFFFu) : (word >> 16u);
			}
			return words[index];
		}

		static MtlrDecoded mtlr_decode(MtlrUnit unit, uint vertex) {
			MtlrDecoded out;
			device const uint *words = reinterpret_cast<device const uint *>(unit.vertices);
			if (unit.vertex_format == 1u) {
				uint base = vertex * 5u;
				uint word0 = words[base];
				uint word1 = words[base + 1u];
				uint word2 = words[base + 2u];
				uint word3 = words[base + 3u];
				uint word4 = words[base + 4u];
				out.position = float3(word0 & 0xFFFFFu, word1 & 0xFFFFFu, word2 & 0xFFFFFu) * (1.0f / 32768.0f) - 8.0f;
				out.color = unpack_unorm4x8((word0 >> 20u & 0xFFu) | (word1 >> 12u & 0xFF00u) | (word2 >> 4u & 0xFF0000u) | 0xFF000000u);
				out.uv = as_type<float2>(uint2(word3, word4) & 0x3FFFFFFFu);
				out.lightmap = int2(word2 >> 28u | (word3 >> 30u) << 4u | (word4 >> 30u) << 6u, word0 >> 28u | (word1 >> 28u) << 4u);
				return out;
			}
			uint base = vertex * 7u;
			out.position = as_type<float3>(uint3(words[base], words[base + 1u], words[base + 2u]));
			out.color = unpack_unorm4x8(words[base + 3u]);
			out.uv = as_type<float2>(uint2(words[base + 4u], words[base + 5u]));
			uint light = words[base + 6u];
			out.lightmap = int2(as_type<int>(light << 16u) >> 16, as_type<int>(light) >> 16);
			return out;
		}
		""";

	static UnitLayout unitLayout() {
		return new UnitLayout(
			TerrainShaderPatch.UNIT_STRIDE,
			TerrainShaderPatch.QUADS_PER_UNIT,
			List.of(DecodeVariant.BLOCK, DecodeVariant.COMPACT)
		);
	}

	static List<Varying> varyings(boolean half) {
		if (!half) {
			return List.of(
				new Varying("sphericalVertexDistance", 0, 1, 32, ScalarType.FLOAT, Interpolation.SMOOTH),
				new Varying("cylindricalVertexDistance", 1, 1, 32, ScalarType.FLOAT, Interpolation.SMOOTH),
				new Varying("vertexColor", 2, 4, 32, ScalarType.FLOAT, Interpolation.SMOOTH),
				new Varying("texCoord0", 3, 2, 32, ScalarType.FLOAT, Interpolation.SMOOTH),
				new Varying("mtlr_visibility", 4, 1, 32, ScalarType.FLOAT, Interpolation.FLAT)
			);
		}
		return List.of(
			new Varying("mtlr_visibility", 0, 1, 16, ScalarType.FLOAT, Interpolation.FLAT),
			new Varying("mtlr_color", 1, 4, 16, ScalarType.FLOAT, Interpolation.SMOOTH),
			new Varying("mtlr_fog", 2, 2, 16, ScalarType.FLOAT, Interpolation.SMOOTH),
			new Varying("texCoord0", 3, 2, 32, ScalarType.FLOAT, Interpolation.SMOOTH)
		);
	}
}
