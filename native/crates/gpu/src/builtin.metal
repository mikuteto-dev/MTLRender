#include <metal_stdlib>
using namespace metal;

kernel void geometry_scan_groups(const device uint* counts [[buffer(0)]],
                                    device uint* offsets [[buffer(1)]],
                                    device uint* totals [[buffer(2)]],
                                    constant uint& count [[buffer(3)]],
                                    uint gid [[thread_position_in_grid]]) {
    if (gid != 0u) return;
    uint sum = 0u;
    for (uint i = 0u; i < count; ++i) {
        offsets[i] = sum;
        sum += counts[i];
    }
    totals[0] = sum;
}

kernel void geometry_scan_offsets(const device uint* group_totals [[buffer(0)]],
                                     device uint* group_offsets [[buffer(1)]],
                                     constant uint& groups [[buffer(2)]],
                                     uint gid [[thread_position_in_grid]]) {
    if (gid != 0u) return;
    uint sum = 0u;
    for (uint i = 0u; i < groups; ++i) {
        group_offsets[i] = sum;
        sum += group_totals[i];
    }
}

kernel void geometry_compact(const device uint* counts [[buffer(0)]],
                                const device uint* offsets [[buffer(1)]],
                                device uint* output [[buffer(2)]],
                                constant uint& count [[buffer(3)]],
                                uint gid [[thread_position_in_grid]]) {
    if (gid >= count || counts[gid] == 0u) return;
    output[offsets[gid]] = gid;
}

kernel void geometry_indirect(const device uint* counts [[buffer(0)]],
                                 device uint* arguments [[buffer(1)]],
                                 constant uint& count [[buffer(2)]],
                                 uint gid [[thread_position_in_grid]]) {
    if (gid != 0u) return;
    uint primitives = 0u;
    for (uint i = 0u; i < count; ++i) primitives += counts[i];
    arguments[0] = primitives * 3u;
    arguments[1] = primitives == 0u ? 0u : 1u;
    arguments[2] = 0u;
    arguments[3] = 0u;
    arguments[4] = 0u;
}

struct FullscreenOut {
    float4 position [[position]];
};

vertex FullscreenOut fullscreen_vs(uint vid [[vertex_id]]) {
    float2 p = float2(float((vid << 1) & 2), float(vid & 2));
    FullscreenOut out;
    out.position = float4(p * 2.0 - 1.0, 0.0, 1.0);
    return out;
}

struct CalibrationVertex {
    float4 position [[position]];
};

struct CalibrationPayload {
    uint value;
};

[[object]] void calibration_object(uint tid [[thread_position_in_grid]],
                                  object_data CalibrationPayload& payload [[payload]],
                                  mesh_grid_properties grid) {
    if (tid == 0u) {
        payload.value = 0u;
        grid.set_threadgroups_per_grid(uint3(1u, 1u, 1u));
    }
}

[[mesh]] void calibration_mesh(object_data const CalibrationPayload& payload [[payload]],
                              uint tid [[thread_index_in_threadgroup]],
                              mesh<CalibrationVertex, void, 3, 1, topology::triangle> output) {
    if (tid == 0u) {
        output.set_primitive_count(1u);
        output.set_vertex(0u, CalibrationVertex{float4(-0.8, -0.8, 0.0, 1.0)});
        output.set_vertex(1u, CalibrationVertex{float4(0.8, -0.8, 0.0, 1.0)});
        output.set_vertex(2u, CalibrationVertex{float4(0.0, 0.8, 0.0, 1.0)});
        output.set_index(0u, 0u);
        output.set_index(1u, 1u);
        output.set_index(2u, 2u);
    }
}

fragment float4 calibration_fs() {
    return float4(1.0);
}

fragment float4 blit_flip_fs(FullscreenOut in [[stage_in]], texture2d<float> source [[texture(0)]]) {
    uint2 pixel = uint2(in.position.xy);
    return source.read(uint2(pixel.x, source.get_height() - 1 - pixel.y));
}

struct ClearOut {
    float4 color [[color(0)]];
    float depth [[depth(any)]];
};

fragment ClearOut clear_fs(constant float4 *values [[buffer(0)]]) {
    ClearOut out;
    out.color = values[0];
    out.depth = values[1].x;
    return out;
}

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

struct MtlrPackUnit {
    ulong vertices;
    ulong indices;
    packed_int3 position;
    float visibility;
    uint quads;
    uint index_type;
    uint vertex_format;
    uint slot;
    ulong metadata;
    uint metadata_stride;
    uint quad_index;
};

struct TerrainDecodedVertex {
    float3 position;
    float4 color;
    float2 uv0;
    int2 lightmap;
};

struct TerrainPackVertex {
    TerrainDecodedVertex base;
    float4 normal;
    float4 entity;
    float2 mid_texcoord;
    float4 tangent;
    float3 mid_block;
};

static TerrainDecodedVertex terrain_decode_block(device const uint* words, uint vertex_index) {
    uint base = vertex_index * 7u;
    TerrainDecodedVertex result;
    result.position = float3(as_type<float>(words[base]), as_type<float>(words[base + 1u]), as_type<float>(words[base + 2u]));
    uint color = words[base + 3u];
    result.color = float4(float(color & 0xffu), float((color >> 8u) & 0xffu), float((color >> 16u) & 0xffu), float(color >> 24u)) / 255.0f;
    result.uv0 = as_type<float2>(uint2(words[base + 4u], words[base + 5u]));
    uint light = words[base + 6u];
    result.lightmap = int2(int(int16_t(light & 0xffffu)), int(int16_t(light >> 16u)));
    return result;
}

static TerrainDecodedVertex terrain_decode_compact(device const uint* words, uint vertex_index) {
    uint base = vertex_index * 5u;
    uint word0 = words[base];
    uint word1 = words[base + 1u];
    uint word2 = words[base + 2u];
    uint word3 = words[base + 3u];
    uint word4 = words[base + 4u];
    TerrainDecodedVertex result;
    result.position = float3(uint3(word0, word1, word2) & 0xfffffu) * (1.0f / 32768.0f) - 8.0f;
    uint color = ((word0 >> 20u) & 0xffu) | ((word1 >> 12u) & 0xff00u) | ((word2 >> 4u) & 0xff0000u) | 0xff000000u;
    result.color = float4(float(color & 0xffu), float((color >> 8u) & 0xffu), float((color >> 16u) & 0xffu), float(color >> 24u)) / 255.0f;
    result.uv0 = as_type<float2>(uint2(word3, word4) & 0x3ffffffu);
    result.lightmap = int2(
        int((word2 >> 28u) | ((word3 >> 30u) << 4u) | ((word4 >> 30u) << 6u)),
        int((word0 >> 28u) | ((word1 >> 28u) << 4u)));
    return result;
}

static float3 terrain_decode_oct(uint value) {
    float2 encoded = float2(float(value & 0xfffu), float((value >> 12u) & 0xfffu)) / 2047.0f - 1.0f;
    float3 vector = float3(encoded, 1.0f - abs(encoded.x) - abs(encoded.y));
    if (vector.z < 0.0f) {
        vector.xy = (1.0f - abs(vector.yx)) * select(float2(-1.0f), float2(1.0f), vector.xy >= 0.0f);
    }
    return normalize(vector);
}

static bool terrain_decode_pack(device const uint* metadata, ulong address, uint stride, uint quad,
                                thread TerrainPackVertex& result) {
    if (address == 0ul || stride != 16u || (address & 3ul) != 0ul) {
        return false;
    }
    ulong offset = ulong(quad) * ulong(stride);
    if (offset > 0xfffffffffffffffful - address) {
        return false;
    }
    device const uint* record = (device const uint*)((ulong)metadata + offset);
    uint mid = record[1];
    uint packed = record[2];
    uint block = record[3];
    result.entity = float4(float(record[0]), 0.0f, 0.0f, 1.0f);
    result.mid_texcoord = float2(float(mid & 0xffffu), float(mid >> 16u)) / 8192.0f;
    result.normal = float4(terrain_decode_oct(packed), 0.0f);
    result.tangent = float4(terrain_decode_oct(packed), 1.0f);
    result.mid_block = float3(float(block & 0xffu), float((block >> 8u) & 0xffu), float((block >> 16u) & 0xffu)) / 64.0f;
    return true;
}

constant uint CULL_COUNT = 22;
constant uint CULL_PHASE = 23;
constant uint CULL_FIRST = 24;
constant uint CULL_DEPTH_WIDTH = 28;
constant uint CULL_DEPTH_HEIGHT = 29;
constant uint CULL_LEVELS = 30;
constant uint CULL_SECTIONS = 31;
constant uint CULL_INDICES = 32;
constant uint CULL_GROUPS = 33;
constant uint CULL_FROM_ARGS = 34;
constant uint CULL_MODE = 35;
constant uint CULL_PREVIOUS_CLIP = 36;
constant uint CULL_GROUP_SIZE = 256;
constant uint CULL_SIMDS = CULL_GROUP_SIZE / 32;
constant uint CULL_TRANSLUCENT = 2;
constant uint HIZ_TILE = 8;

static float cull_float(constant uint* p, uint i) {
    return as_type<float>(p[i]);
}

static float4x4 cull_clip(constant uint* p) {
    return float4x4(
        float4(cull_float(p, 0), cull_float(p, 1), cull_float(p, 2), cull_float(p, 3)),
        float4(cull_float(p, 4), cull_float(p, 5), cull_float(p, 6), cull_float(p, 7)),
        float4(cull_float(p, 8), cull_float(p, 9), cull_float(p, 10), cull_float(p, 11)),
        float4(cull_float(p, 12), cull_float(p, 13), cull_float(p, 14), cull_float(p, 15)));
}

struct CullRange {
    uint count;
    uint first[4];
};

static CullRange cull_range(constant uint* p, device const uint* source_args) {
    CullRange range;
    if (p[CULL_FROM_ARGS] != 0u) {
        range.count = source_args[2u * 5u + 4u] + source_args[2u * 5u + 1u];
        for (uint layer = 0u; layer < 3u; layer++) {
            range.first[layer] = source_args[layer * 5u + 4u];
        }
        range.first[3] = range.count;
    } else {
        range.count = p[CULL_COUNT];
        for (uint layer = 0u; layer < 4u; layer++) {
            range.first[layer] = p[CULL_FIRST + layer];
        }
    }
    return range;
}

static bool cull_hiz_visible(MtlrUnit unit, constant uint* p, texture2d<float, access::read> hiz) {
    float4x4 clip_from_pos = cull_clip(p);
    int3 block = int3(as_type<int>(p[16]), as_type<int>(p[17]), as_type<int>(p[18]));
    float3 offset = float3(cull_float(p, 19), cull_float(p, 20), cull_float(p, 21));
    float3 lo = float3(int3(unit.position) - block) + offset;
    float3 hi = lo + 16.0;
    float2 ndc_min = float2(INFINITY);
    float2 ndc_max = float2(-INFINITY);
    float nearest = 0.0;
    for (uint corner = 0; corner < 8u; corner++) {
        float3 point = select(lo, hi, bool3((corner & 1u) != 0u, (corner & 2u) != 0u, (corner & 4u) != 0u));
        float4 clip = clip_from_pos * float4(point, 1.0);
        if (clip.w <= 1.0e-5) {
            return true;
        }
        float3 ndc = clip.xyz / clip.w;
        ndc_min = min(ndc_min, ndc.xy);
        ndc_max = max(ndc_max, ndc.xy);
        nearest = max(nearest, ndc.z);
    }
    if (ndc_max.x < -1.0 || ndc_min.x > 1.0 || ndc_max.y < -1.0 || ndc_min.y > 1.0) {
        return false;
    }
    uint width = p[CULL_DEPTH_WIDTH];
    uint height = p[CULL_DEPTH_HEIGHT];
    float2 size = float2(float(width), float(height));
    float2 uv_min = clamp(ndc_min * 0.5 + 0.5, 0.0, 1.0);
    float2 uv_max = clamp(ndc_max * 0.5 + 0.5, 0.0, 1.0);
    uint2 limit = uint2(width - 1u, height - 1u);
    uint2 a = min(uint2(uv_min * size), limit) >> 1u;
    uint2 b = min(uint2(uv_max * size), limit) >> 1u;
    uint level = 0u;
    uint levels = p[CULL_LEVELS];
    while (level + 1u < levels && (b.x - a.x > 1u || b.y - a.y > 1u)) {
        a >>= 1u;
        b >>= 1u;
        level++;
    }
    if (b.x - a.x > 1u || b.y - a.y > 1u) {
        return true;
    }
    float farthest = min(
        min(hiz.read(a, level).x, hiz.read(uint2(b.x, a.y), level).x),
        min(hiz.read(uint2(a.x, b.y), level).x, hiz.read(b, level).x));
    return nearest >= farthest;
}

static bool cull_reproject_visible(MtlrUnit unit,
                                   constant uint* p,
                                   texture2d<float, access::read> snapshot) {
    float4x4 previous_clip = float4x4(
        float4(cull_float(p, CULL_PREVIOUS_CLIP + 0u), cull_float(p, CULL_PREVIOUS_CLIP + 1u), cull_float(p, CULL_PREVIOUS_CLIP + 2u), cull_float(p, CULL_PREVIOUS_CLIP + 3u)),
        float4(cull_float(p, CULL_PREVIOUS_CLIP + 4u), cull_float(p, CULL_PREVIOUS_CLIP + 5u), cull_float(p, CULL_PREVIOUS_CLIP + 6u), cull_float(p, CULL_PREVIOUS_CLIP + 7u)),
        float4(cull_float(p, CULL_PREVIOUS_CLIP + 8u), cull_float(p, CULL_PREVIOUS_CLIP + 9u), cull_float(p, CULL_PREVIOUS_CLIP + 10u), cull_float(p, CULL_PREVIOUS_CLIP + 11u)),
        float4(cull_float(p, CULL_PREVIOUS_CLIP + 12u), cull_float(p, CULL_PREVIOUS_CLIP + 13u), cull_float(p, CULL_PREVIOUS_CLIP + 14u), cull_float(p, CULL_PREVIOUS_CLIP + 15u)));
    float4x4 current_clip = cull_clip(p);
    int3 block = int3(as_type<int>(p[16]), as_type<int>(p[17]), as_type<int>(p[18]));
    float3 offset = float3(cull_float(p, 19), cull_float(p, 20), cull_float(p, 21));
    float3 lo = float3(int3(unit.position) - block) + offset;
    float3 hi = lo + 16.0;
    float2 previous_min = float2(INFINITY);
    float2 previous_max = float2(-INFINITY);
    float nearest = 0.0;
    for (uint corner = 0u; corner < 8u; corner++) {
        float3 point = select(lo, hi, bool3((corner & 1u) != 0u, (corner & 2u) != 0u, (corner & 4u) != 0u));
        float4 old_point = previous_clip * float4(point, 1.0);
        float4 new_point = current_clip * float4(point, 1.0);
        if (old_point.w <= 0.0 || new_point.w <= 0.0) {
            return true;
        }
        float3 old_ndc = old_point.xyz / old_point.w;
        float3 new_ndc = new_point.xyz / new_point.w;
        if (!all(isfinite(old_ndc)) || !all(isfinite(new_ndc))) {
            return true;
        }
        previous_min = min(previous_min, old_ndc.xy);
        previous_max = max(previous_max, old_ndc.xy);
        nearest = max(nearest, new_ndc.z);
    }
    if (previous_max.x < -1.0 || previous_min.x > 1.0 || previous_max.y < -1.0 || previous_min.y > 1.0) {
        return true;
    }
    if (previous_max.x - previous_min.x >= 2.0 || previous_max.y - previous_min.y >= 2.0) {
        return true;
    }
    uint width = snapshot.get_width();
    uint height = snapshot.get_height();
    if (width == 0u || height == 0u) {
        return true;
    }
    float2 pixel_min = clamp(previous_min * 0.5 + 0.5, 0.0, 1.0) * float2(width, height);
    float2 pixel_max = clamp(previous_max * 0.5 + 0.5, 0.0, 1.0) * float2(width, height);
    float2 samples[5] = {pixel_min, float2(pixel_max.x, pixel_min.y), float2(pixel_min.x, pixel_max.y), pixel_max, (pixel_min + pixel_max) * 0.5};
    for (uint i = 0u; i < 5u; i++) {
        uint2 pixel = min(uint2(samples[i]), uint2(width - 1u, height - 1u));
        float depth = snapshot.read(pixel).x;
        if (!isfinite(depth) || depth <= 0.0) {
            return true;
        }
        float2 previous_uv = (float2(pixel) + 0.5) / float2(width, height);
        float2 previous_span = previous_max - previous_min;
        if (any(previous_span <= 0.0) || !all(isfinite(previous_span))) {
            return true;
        }
        float2 current_uv = (previous_uv - (previous_min * 0.5 + 0.5)) / (previous_span * 0.5);
        if (!all(isfinite(current_uv))) {
            return true;
        }
        float3 current_ndc = float3(current_uv * 2.0 - 1.0, depth);
        if (!all(isfinite(current_ndc))) {
            return true;
        }
        if (nearest >= current_ndc.z) {
            return true;
        }
    }
    return false;
}

static void cull_hiz_tile(float value,
                          uint2 gid,
                          uint2 lid,
                          uint levels,
                          texture2d<float, access::write> dst1,
                          texture2d<float, access::write> dst2,
                          texture2d<float, access::write> dst3,
                          threadgroup float* tile) {
    uint index = lid.y * HIZ_TILE + lid.x;
    tile[index] = value;
    threadgroup_barrier(mem_flags::mem_threadgroup);
    bool even1 = (lid.x & 1u) == 0u && (lid.y & 1u) == 0u;
    float level1 = INFINITY;
    if (even1) {
        level1 = min(min(tile[index], tile[index + 1u]), min(tile[index + HIZ_TILE], tile[index + HIZ_TILE + 1u]));
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (even1) {
        tile[index] = level1;
        uint2 at = gid >> 1u;
        if (levels > 1u && at.x < dst1.get_width() && at.y < dst1.get_height()) {
            dst1.write(float4(level1), at);
        }
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    bool even2 = (lid.x & 3u) == 0u && (lid.y & 3u) == 0u;
    float level2 = INFINITY;
    if (even2) {
        level2 = min(min(tile[index], tile[index + 2u]), min(tile[index + 2u * HIZ_TILE], tile[index + 2u * HIZ_TILE + 2u]));
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (even2) {
        tile[index] = level2;
        uint2 at = gid >> 2u;
        if (levels > 2u && at.x < dst2.get_width() && at.y < dst2.get_height()) {
            dst2.write(float4(level2), at);
        }
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (lid.x == 0u && lid.y == 0u) {
        float level3 = min(min(tile[0], tile[4]), min(tile[4u * HIZ_TILE], tile[4u * HIZ_TILE + 4u]));
        uint2 at = gid >> 3u;
        if (levels > 3u && at.x < dst3.get_width() && at.y < dst3.get_height()) {
            dst3.write(float4(level3), at);
        }
    }
}

kernel void calibration_synthetic_depth(texture2d<float, access::write> depth [[texture(0)]],
                                         uint2 gid [[thread_position_in_grid]]) {
    if (gid.x >= depth.get_width() || gid.y >= depth.get_height()) {
        return;
    }
    float x = float(gid.x) / max(float(depth.get_width()), 1.0);
    float ndc_y = (2.0 * (float(gid.y) + 0.5) / max(float(depth.get_height()), 1.0)) - 1.0;
    float y = (ndc_y + 1.0) * 0.5;
    depth.write(float4(max(0.0, 1.0 - 0.5 * (x + y))), gid);
}

kernel void calibration_single_phase_reproject(texture2d<float, access::read> current [[texture(0)]],
                                                texture2d<float, access::read> snapshot [[texture(1)]],
                                                texture2d<float, access::write> visible [[texture(2)]],
                                                constant uint* motion [[buffer(10)]],
                                                uint2 gid [[thread_position_in_grid]]) {
    if (gid.x >= visible.get_width() || gid.y >= visible.get_height()) {
        return;
    }
    uint2 limit = uint2(snapshot.get_width() - 1u, snapshot.get_height() - 1u);
    uint2 previous = min(gid + uint2(motion[0], motion[1]), limit);
    float current_depth = current.read(gid).x;
    float previous_depth = snapshot.read(previous).x;
    float result = isfinite(current_depth) && isfinite(previous_depth) && current_depth >= previous_depth ? 1.0 : 0.0;
    visible.write(float4(result), gid);
}

kernel void cull_snapshot_d32_to_r32(depth2d<float, access::read> depth [[texture(0)]],
                                      texture2d<float, access::write> snapshot [[texture(1)]],
                                      uint2 gid [[thread_position_in_grid]]) {
    if (gid.x < snapshot.get_width() && gid.y < snapshot.get_height()) {
        snapshot.write(float4(depth.read(gid)), gid);
    }
}

kernel void pack_depth_convert(depth2d<float, access::read> source [[texture(0)]],
                                  texture2d<float, access::write> destination [[texture(1)]],
                                  uint2 gid [[thread_position_in_grid]]) {
    if (gid.x < destination.get_width() && gid.y < destination.get_height()) {
        destination.write(float4(1.0 - source.read(gid)), gid);
    }
}

kernel void calibration_hiz_reduce(depth2d<float, access::read> depth [[texture(0)]],
                                    texture2d<float, access::write> reduced [[texture(1)]],
                                    uint2 gid [[thread_position_in_grid]]) {
    if (gid.x >= reduced.get_width() || gid.y >= reduced.get_height()) {
        return;
    }
    uint2 limit = uint2(depth.get_width() - 1u, depth.get_height() - 1u);
    uint2 a = min(gid * 2u, limit);
    uint2 b = min(gid * 2u + 1u, limit);
    float nearest = min(
        min(depth.read(a), depth.read(uint2(b.x, a.y))),
        min(depth.read(uint2(a.x, b.y)), depth.read(b)));
    reduced.write(float4(nearest), gid);
}

kernel void cull_hiz_first4(depth2d<float, access::read> depth [[texture(0)]],
                            texture2d<float, access::write> dst0 [[texture(1)]],
                            texture2d<float, access::write> dst1 [[texture(2)]],
                            texture2d<float, access::write> dst2 [[texture(3)]],
                            texture2d<float, access::write> dst3 [[texture(4)]],
                            constant uint* levels [[buffer(10)]],
                            uint2 gid [[thread_position_in_grid]],
                            uint2 lid [[thread_position_in_threadgroup]]) {
    threadgroup float tile[HIZ_TILE * HIZ_TILE];
    float value = INFINITY;
    if (gid.x < dst0.get_width() && gid.y < dst0.get_height()) {
        uint2 limit = uint2(depth.get_width() - 1u, depth.get_height() - 1u);
        uint2 a = min(gid * 2u, limit);
        uint2 b = min(gid * 2u + 1u, limit);
        value = min(
            min(depth.read(a), depth.read(uint2(b.x, a.y))),
            min(depth.read(uint2(a.x, b.y)), depth.read(b)));
        dst0.write(float4(value), gid);
    }
    cull_hiz_tile(value, gid, lid, levels[0], dst1, dst2, dst3, tile);
}

kernel void cull_hiz_reduce4(texture2d<float, access::read> src [[texture(0)]],
                             texture2d<float, access::write> dst0 [[texture(1)]],
                             texture2d<float, access::write> dst1 [[texture(2)]],
                             texture2d<float, access::write> dst2 [[texture(3)]],
                             texture2d<float, access::write> dst3 [[texture(4)]],
                             constant uint* levels [[buffer(10)]],
                             uint2 gid [[thread_position_in_grid]],
                             uint2 lid [[thread_position_in_threadgroup]]) {
    threadgroup float tile[HIZ_TILE * HIZ_TILE];
    float value = INFINITY;
    if (gid.x < dst0.get_width() && gid.y < dst0.get_height()) {
        uint2 limit = uint2(src.get_width() - 1u, src.get_height() - 1u);
        uint2 a = min(gid * 2u, limit);
        uint2 b = min(gid * 2u + 1u, limit);
        value = min(
            min(src.read(a).x, src.read(uint2(b.x, a.y)).x),
            min(src.read(uint2(a.x, b.y)).x, src.read(b).x));
        dst0.write(float4(value), gid);
    }
    cull_hiz_tile(value, gid, lid, levels[0], dst1, dst2, dst3, tile);
}

kernel void cull_mark(device const MtlrUnit* units [[buffer(0)]],
                      constant uint* p [[buffer(1)]],
                      device uint* marks [[buffer(5)]],
                      device uint* visible_a [[buffer(6)]],
                      device const uint* history [[buffer(7)]],
                      device atomic_uint* history_next [[buffer(8)]],
                      device const uint* source_args [[buffer(11)]],
                      texture2d<float, access::read> hiz [[texture(0)]],
                      texture2d<float, access::read> snapshot [[texture(1)]],
                      uint i [[thread_position_in_grid]]) {
    CullRange range = cull_range(p, source_args);
    if (p[CULL_PHASE] == 0u && i < p[CULL_SECTIONS]) {
        atomic_store_explicit(&history_next[i], 0u, memory_order_relaxed);
    }
    if (i >= range.count) {
        return;
    }
    MtlrUnit unit = units[i];
    if (p[CULL_MODE] == 2u) {
        bool visible = cull_reproject_visible(unit, p, snapshot);
        uint section = unit.slot >> 2u;
        uint layer = unit.slot & 3u;
        if (visible && section < p[CULL_SECTIONS]) {
            atomic_fetch_or_explicit(&history_next[section], 1u << layer, memory_order_relaxed);
        }
        marks[i] = visible ? 1u : 0u;
        return;
    }
    uint section = unit.slot >> 2u;
    uint layer = unit.slot & 3u;
    bool known = section < p[CULL_SECTIONS];
    if (p[CULL_PHASE] == 0u) {
        uint drawn = (layer != CULL_TRANSLUCENT && known && ((history[section] >> layer) & 1u) != 0u) ? 1u : 0u;
        visible_a[i] = drawn;
        marks[i] = drawn;
        return;
    }
    bool visible = cull_hiz_visible(unit, p, hiz);
    if (visible && known) {
        atomic_fetch_or_explicit(&history_next[section], 1u << layer, memory_order_relaxed);
    }
    marks[i] = (visible && visible_a[i] == 0u) ? 1u : 0u;
}

kernel void cull_scan_groups(constant uint* p [[buffer(1)]],
                             device uint* group_sums [[buffer(2)]],
                             device const uint* marks [[buffer(5)]],
                             device const uint* source_args [[buffer(11)]],
                             uint i [[thread_position_in_grid]],
                             uint group [[threadgroup_position_in_grid]],
                             uint lane [[thread_index_in_simdgroup]],
                             uint simd [[simdgroup_index_in_threadgroup]]) {
    CullRange range = cull_range(p, source_args);
    threadgroup uint partial[CULL_SIMDS];
    uint value = i < range.count ? marks[i] : 0u;
    uint sum = simd_sum(value);
    if (lane == 0u) {
        partial[simd] = sum;
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (simd == 0u) {
        uint total = simd_sum(lane < CULL_SIMDS ? partial[lane] : 0u);
        if (lane == 0u) {
            group_sums[group] = total;
        }
    }
}

kernel void cull_scan_offsets(constant uint* p [[buffer(1)]],
                              device uint* group_sums [[buffer(2)]],
                              device uint* totals [[buffer(3)]],
                              device const uint* marks [[buffer(5)]],
                              device uint* args [[buffer(9)]],
                              device const uint* source_args [[buffer(11)]],
                              uint i [[thread_position_in_grid]]) {
    if (i != 0u) {
        return;
    }
    CullRange range = cull_range(p, source_args);
    uint running = 0u;
    for (uint g = 0u; g < p[CULL_GROUPS]; g++) {
        uint sum = group_sums[g];
        group_sums[g] = running;
        running += sum;
    }
    totals[0] = running;
    uint count = range.count;
    uint starts[4];
    for (uint layer = 0u; layer < 4u; layer++) {
        uint first = range.first[layer];
        uint start = running;
        if (first < count) {
            uint group = first / CULL_GROUP_SIZE;
            start = group_sums[group];
            for (uint k = group * CULL_GROUP_SIZE; k < first; k++) {
                start += marks[k];
            }
        }
        starts[layer] = start;
    }
    for (uint layer = 0u; layer < 3u; layer++) {
        args[layer * 5u + 0u] = p[CULL_INDICES];
        args[layer * 5u + 1u] = starts[layer + 1u] - starts[layer];
        args[layer * 5u + 2u] = 0u;
        args[layer * 5u + 3u] = 0u;
        args[layer * 5u + 4u] = starts[layer];
    }
}

kernel void cull_scatter(device const MtlrUnit* units [[buffer(0)]],
                         constant uint* p [[buffer(1)]],
                         device const uint* group_offsets [[buffer(2)]],
                         device MtlrUnit* out [[buffer(4)]],
                         device const uint* marks [[buffer(5)]],
                         device const uint* source_args [[buffer(11)]],
                         uint i [[thread_position_in_grid]],
                         uint group [[threadgroup_position_in_grid]],
                         uint lane [[thread_index_in_simdgroup]],
                         uint simd [[simdgroup_index_in_threadgroup]]) {
    threadgroup uint partial[CULL_SIMDS];
    CullRange range = cull_range(p, source_args);
    uint count = range.count;
    uint value = i < count ? marks[i] : 0u;
    uint prefix = simd_prefix_exclusive_sum(value);
    uint sum = simd_sum(value);
    if (lane == 0u) {
        partial[simd] = sum;
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (simd == 0u) {
        uint x = lane < CULL_SIMDS ? partial[lane] : 0u;
        uint exclusive = simd_prefix_exclusive_sum(x);
        if (lane < CULL_SIMDS) {
            partial[lane] = exclusive;
        }
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (i < count && value != 0u) {
        out[group_offsets[group] + partial[simd] + prefix] = units[i];
    }
}

struct MtlrRecord {
    ulong vertices;
    ulong indices;
    int x;
    int y;
    int z;
    float visibility;
    uint quads;
    uint flags;
    uint slot;
    uint reserved;
    uint faces[7];
    uint padding;
};

struct MtlrPackRecord {
    MtlrRecord base;
    ulong metadata;
    uint metadata_stride;
    uint metadata_reserved;
};

constant uint EXPAND_UPDATE_WORDS = 22;
constant uint EXPAND_PACK_UPDATE_WORDS = 26;
constant uint EXPAND_RECORD_WORDS = 20;
constant uint EXPAND_PACK_RECORD_WORDS = 24;
constant uint EXPAND_SECTIONS = 14;

kernel void records_scatter(device const uint* updates [[buffer(0)]],
                            constant uint* p [[buffer(1)]],
                            device uint* records [[buffer(7)]],
                            uint i [[thread_position_in_grid]]) {
    uint limit = p[EXPAND_SECTIONS] * 3u;
    bool pack = (p[17] & 2u) != 0u;
    uint update_words = pack ? EXPAND_PACK_UPDATE_WORDS : EXPAND_UPDATE_WORDS;
    uint record_words = pack ? EXPAND_PACK_RECORD_WORDS : EXPAND_RECORD_WORDS;
    uint base = i * update_words;
    uint slot = updates[base];
    if (slot >= limit) {
        return;
    }
    for (uint word = 0u; word < record_words; word++) {
        records[slot * record_words + word] = updates[base + 2u + word];
    }
}

constant uint EXPAND_COUNT = 0;
constant uint EXPAND_FIRST = 1;
constant uint EXPAND_INDICES = 4;
constant uint EXPAND_CAPACITY = 5;
constant uint EXPAND_GROUPS = 6;
constant uint EXPAND_CAMERA_BLOCK = 7;
constant uint EXPAND_CAMERA_OFFSET = 10;
constant uint EXPAND_VISIBLE = 13;
constant uint EXPAND_CULL_MASK = 15;
constant uint EXPAND_LAYER_MASK = 16;
constant uint EXPAND_OPTIONS = 17;
constant uint EXPAND_GROUP_SIZE = 256;
constant uint EXPAND_SIMDS = EXPAND_GROUP_SIZE / 32;
constant uint EXPAND_UNASSIGNED = 3;
constant uint EXPAND_QUADS_PER_UNIT = 32;

struct ExpandAmount {
    uint units;
    uint quads;
};

struct ExpandWork {
    uint index;
    uint layer;
    float visibility;
};

static ExpandWork expand_work(device const uint* visible, constant uint* p, uint i) {
    uint count = p[EXPAND_VISIBLE];
    uint layer = i / count;
    uint k = i - layer * count;
    uint v = layer == 2u ? count - 1u - k : k;
    ExpandWork work;
    work.index = visible[v * 2u];
    work.layer = layer;
    work.visibility = as_type<float>(visible[v * 2u + 1u]);
    return work;
}

static int expand_origin(MtlrRecord r, uint axis) {
    if (axis == 0u) {
        return r.x;
    }
    if (axis == 1u) {
        return r.y;
    }
    return r.z;
}

static uint expand_mask(MtlrRecord r, constant uint* p) {
    uint mask = 1u << EXPAND_UNASSIGNED;
    for (uint axis = 0u; axis < 3u; axis++) {
        int block = as_type<int>(p[EXPAND_CAMERA_BLOCK + axis]);
        float offset = as_type<float>(p[EXPAND_CAMERA_OFFSET + axis]);
        int origin = expand_origin(r, axis);
        float camera = float(block - origin) - offset;
        if (camera > -1.0f) {
            mask |= 1u << axis;
        }
        if (camera < 17.0f) {
            mask |= 1u << (6u - axis);
        }
    }
    if ((p[EXPAND_OPTIONS] & 1u) != 0u) {
        mask &= ~(1u << EXPAND_UNASSIGNED);
    }
    if ((r.flags & 32u) != 0u) {
        mask &= ~(1u << EXPAND_UNASSIGNED);
    }
    return mask;
}

static ExpandAmount expand_amount(MtlrRecord r, constant uint* p, bool use_faces) {
    ExpandAmount amount;
    amount.units = 0u;
    amount.quads = 0u;
    uint quads = r.quads;
    uint index_type = r.flags & 3u;
    if (index_type != 0u || !use_faces) {
        amount.units = (quads + EXPAND_QUADS_PER_UNIT - 1u) / EXPAND_QUADS_PER_UNIT;
        amount.quads = quads;
        return amount;
    }
    uint mask = expand_mask(r, p);
    uint position = 0u;
    uint run_start = 0u;
    uint run_end = 0u;
    for (uint bucket = 0u; bucket < 7u; bucket++) {
        uint next = position + r.faces[bucket];
        if ((mask & (1u << bucket)) != 0u) {
            if (run_end != position) {
                amount.units += (run_end - run_start + EXPAND_QUADS_PER_UNIT - 1u) / EXPAND_QUADS_PER_UNIT;
                amount.quads += run_end - run_start;
                run_start = position;
            }
            run_end = next;
        }
        position = next;
    }
    amount.units += (run_end - run_start + EXPAND_QUADS_PER_UNIT - 1u) / EXPAND_QUADS_PER_UNIT;
    amount.quads += run_end - run_start;
    return amount;
}

static bool expand_pack_valid(MtlrPackRecord r);

kernel void expand_count(constant uint* p [[buffer(1)]],
                         device uint* groups [[buffer(2)]],
                         device uint* locals [[buffer(5)]],
                         device uint* quads [[buffer(6)]],
                         device const uint* record_words [[buffer(7)]],
                         device const uint* visible [[buffer(8)]],
                         uint i [[thread_position_in_grid]],
                         uint group [[threadgroup_position_in_grid]],
                         uint lane [[thread_index_in_simdgroup]],
                         uint simd [[simdgroup_index_in_threadgroup]]) {
    threadgroup uint partial_units[EXPAND_SIMDS];
    threadgroup uint partial_quads[EXPAND_SIMDS];
    uint count = p[EXPAND_COUNT];
    uint unit_value = 0u;
    uint quad_value = 0u;
    if (i < count) {
        ExpandWork work = expand_work(visible, p, i);
        if (work.index < p[EXPAND_SECTIONS]
            && (p[EXPAND_LAYER_MASK] & (1u << work.layer)) != 0u) {
            bool pack = (p[EXPAND_OPTIONS] & 2u) != 0u;
            MtlrPackRecord pack_record;
            MtlrRecord r;
            if (pack) {
                pack_record = ((device const MtlrPackRecord*)record_words)[work.index * 3u + work.layer];
                if (!expand_pack_valid(pack_record)) {
                    return;
                }
                r = pack_record.base;
            } else {
                r = ((device const MtlrRecord*)record_words)[work.index * 3u + work.layer];
            }
            r.visibility = work.visibility;
            bool use_faces = (r.flags & 16u) != 0u
                && (p[EXPAND_CULL_MASK] & (1u << work.layer)) != 0u;
            ExpandAmount amount = expand_amount(r, p, use_faces);
            unit_value = amount.units;
            quad_value = amount.quads;
        }
    }
    uint prefix = simd_prefix_exclusive_sum(unit_value);
    uint sum = simd_sum(unit_value);
    uint quad_sum = simd_sum(quad_value);
    if (lane == 0u) {
        partial_units[simd] = sum;
        partial_quads[simd] = quad_sum;
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    uint total_units = 0u;
    uint total_quads = 0u;
    if (simd == 0u) {
        uint units = lane < EXPAND_SIMDS ? partial_units[lane] : 0u;
        uint quads_in = lane < EXPAND_SIMDS ? partial_quads[lane] : 0u;
        uint exclusive = simd_prefix_exclusive_sum(units);
        total_units = simd_sum(units);
        total_quads = simd_sum(quads_in);
        if (lane < EXPAND_SIMDS) {
            partial_units[lane] = exclusive;
        }
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (i < count) {
        locals[i] = partial_units[simd] + prefix;
    }
    if (simd == 0u && lane == 0u) {
        groups[group] = total_units;
        quads[group] = total_quads;
    }
}

kernel void expand_offsets(constant uint* p [[buffer(1)]],
                           device uint* groups [[buffer(2)]],
                           device uint* totals [[buffer(3)]],
                           device const uint* locals [[buffer(5)]],
                           device const uint* quads [[buffer(6)]],
                           device uint* args [[buffer(9)]],
                           uint i [[thread_position_in_grid]]) {
    if (i != 0u) {
        return;
    }
    uint running = 0u;
    uint quad_running = 0u;
    uint group_count = p[EXPAND_GROUPS];
    for (uint g = 0u; g < group_count; g++) {
        uint sum = groups[g];
        groups[g] = running;
        running += sum;
        quad_running += quads[g];
    }
    totals[0] = running;
    totals[1] = quad_running;
    uint count = p[EXPAND_COUNT];
    uint capacity = p[EXPAND_CAPACITY];
    uint starts[4];
    for (uint layer = 0u; layer < 4u; layer++) {
        uint boundary = layer < 3u ? p[EXPAND_FIRST + layer] : count;
        uint start = running;
        if (boundary < count) {
            uint group = boundary / EXPAND_GROUP_SIZE;
            start = groups[group] + locals[boundary];
        }
        starts[layer] = min(start, capacity);
    }
    for (uint layer = 0u; layer < 3u; layer++) {
        args[layer * 5u + 0u] = p[EXPAND_INDICES];
        args[layer * 5u + 1u] = starts[layer + 1u] - starts[layer];
        args[layer * 5u + 2u] = 0u;
        args[layer * 5u + 3u] = 0u;
        args[layer * 5u + 4u] = starts[layer];
    }
}

static bool expand_pack_valid(MtlrPackRecord r) {
    return r.metadata != 0ul && r.metadata_stride == 16u && (r.metadata & 3ul) == 0ul
        && ulong(r.base.quads) <= (0xfffffffffffffffful - r.metadata) / ulong(r.metadata_stride);
}

static uint expand_emit_pack_run(MtlrPackRecord r,
                                 uint start,
                                 uint end,
                                 uint at,
                                 uint capacity,
                                 device MtlrPackUnit* out) {
    uint vertex_format = (r.base.flags >> 2u) & 3u;
    uint vertex_size = vertex_format == 1u ? 20u : 28u;
    for (uint q = start; q < end; q += EXPAND_QUADS_PER_UNIT) {
        uint count = min(EXPAND_QUADS_PER_UNIT, end - q);
        if (at < capacity) {
            MtlrPackUnit unit;
            unit.vertices = r.base.vertices + ulong(q) * 4ul * ulong(vertex_size);
            unit.indices = 0ul;
            unit.position = int3(r.base.x, r.base.y, r.base.z);
            unit.visibility = r.base.visibility;
            unit.quads = count;
            unit.index_type = 0u;
            unit.vertex_format = vertex_format;
            unit.slot = r.base.slot;
            unit.metadata = r.metadata + ulong(q) * ulong(r.metadata_stride);
            unit.metadata_stride = r.metadata_stride;
            unit.quad_index = q;
            out[at] = unit;
        }
        at += 1u;
    }
    return at;
}

static uint expand_emit_run(MtlrRecord r,
                            uint start,
                            uint end,
                            uint at,
                            uint capacity,
                            device MtlrUnit* out) {
    uint vertex_format = (r.flags >> 2u) & 3u;
    uint vertex_size = vertex_format == 1u ? 20u : 28u;
    for (uint q = start; q < end; q += EXPAND_QUADS_PER_UNIT) {
        uint count = min(EXPAND_QUADS_PER_UNIT, end - q);
        if (at < capacity) {
            MtlrUnit unit;
            unit.vertices = r.vertices + ulong(q) * 4ul * ulong(vertex_size);
            unit.indices = 0ul;
            unit.position = int3(r.x, r.y, r.z);
            unit.visibility = r.visibility;
            unit.quads = count;
            unit.index_type = 0u;
            unit.vertex_format = vertex_format;
            unit.slot = r.slot;
            out[at] = unit;
        }
        at += 1u;
    }
    return at;
}

kernel void expand_write(constant uint* p [[buffer(1)]],
                         device const uint* groups [[buffer(2)]],
                         device MtlrUnit* out [[buffer(4)]],
                         device const uint* locals [[buffer(5)]],
                         device const uint* record_words [[buffer(7)]],
                         device const uint* visible [[buffer(8)]],
                         uint i [[thread_position_in_grid]]) {
    uint count = p[EXPAND_COUNT];
    if (i >= count) {
        return;
    }
    uint capacity = p[EXPAND_CAPACITY];
    uint base = groups[i / EXPAND_GROUP_SIZE] + locals[i];
    if (base >= capacity) {
        return;
    }
    ExpandWork work = expand_work(visible, p, i);
    if (work.index >= p[EXPAND_SECTIONS]
        || (p[EXPAND_LAYER_MASK] & (1u << work.layer)) == 0u) {
        return;
    }
    bool pack = (p[EXPAND_OPTIONS] & 2u) != 0u;
    MtlrPackRecord pack_record;
    MtlrRecord r;
    if (pack) {
        pack_record = ((device const MtlrPackRecord*)record_words)[work.index * 3u + work.layer];
        if (!expand_pack_valid(pack_record)) {
            return;
        }
        r = pack_record.base;
    } else {
        r = ((device const MtlrRecord*)record_words)[work.index * 3u + work.layer];
    }
    r.visibility = work.visibility;
    pack_record.base.visibility = work.visibility;
    uint quads = r.quads;
    uint index_type = r.flags & 3u;
    uint vertex_format = (r.flags >> 2u) & 3u;
    uint at = base;
    if (pack) {
        if (index_type != 0u) {
            uint stride = index_type == 1u ? 2u : 4u;
            for (uint q = 0u; q < quads; q += EXPAND_QUADS_PER_UNIT) {
                uint unit_count = min(EXPAND_QUADS_PER_UNIT, quads - q);
                if (at < capacity) {
                    MtlrPackUnit unit;
                    unit.vertices = r.vertices;
                    unit.indices = r.indices + ulong(q) * 6ul * ulong(stride);
                    unit.position = int3(r.x, r.y, r.z);
                    unit.visibility = r.visibility;
                    unit.quads = unit_count;
                    unit.index_type = index_type;
                    unit.vertex_format = vertex_format;
                    unit.slot = r.slot;
                    unit.metadata = pack_record.metadata + ulong(q) * ulong(pack_record.metadata_stride);
                    unit.metadata_stride = pack_record.metadata_stride;
                    unit.quad_index = q;
                    ((device MtlrPackUnit*)out)[at] = unit;
                }
                at += 1u;
            }
            return;
        }
        expand_emit_pack_run(pack_record, 0u, quads, at, capacity, (device MtlrPackUnit*)out);
        return;
    }
    if (index_type != 0u) {
        uint stride = index_type == 1u ? 2u : 4u;
        for (uint q = 0u; q < quads; q += EXPAND_QUADS_PER_UNIT) {
            uint unit_count = min(EXPAND_QUADS_PER_UNIT, quads - q);
            if (at < capacity) {
                MtlrUnit unit;
                unit.vertices = r.vertices;
                unit.indices = r.indices + ulong(q) * 6ul * ulong(stride);
                unit.position = int3(r.x, r.y, r.z);
                unit.visibility = r.visibility;
                unit.quads = unit_count;
                unit.index_type = index_type;
                unit.vertex_format = vertex_format;
                unit.slot = r.slot;
                out[at] = unit;
            }
            at += 1u;
        }
        return;
    }
    bool use_faces = (r.flags & 16u) != 0u
        && (p[EXPAND_CULL_MASK] & (1u << work.layer)) != 0u;
    if (!use_faces) {
        expand_emit_run(r, 0u, quads, at, capacity, out);
        return;
    }
    uint mask = expand_mask(r, p);
    uint position = 0u;
    uint run_start = 0u;
    uint run_end = 0u;
    for (uint bucket = 0u; bucket < 7u; bucket++) {
        uint next = position + r.faces[bucket];
        if ((mask & (1u << bucket)) != 0u) {
            if (run_end != position) {
                at = expand_emit_run(r, run_start, run_end, at, capacity, out);
                run_start = position;
            }
            run_end = next;
        }
        position = next;
    }
    expand_emit_run(r, run_start, run_end, at, capacity, out);
}

constant uint CHECK_COUNT = 0;
constant uint CHECK_FIRST = 1;
constant uint CHECK_GPU_BYTES = 4;
constant uint CHECK_UNIT_WORDS = 12;
constant uint CHECK_UNIT_BYTES = 48;

kernel void expand_check(device const uint* cpu [[buffer(0)]],
                         constant uint* p [[buffer(1)]],
                         device atomic_uint* mismatch [[buffer(2)]],
                         device const uint* gpu [[buffer(4)]],
                         device const uint* args [[buffer(9)]],
                         uint i [[thread_position_in_grid]]) {
    uint count = p[CHECK_COUNT];
    if (i == 0u) {
        uint firsts[4] = {p[CHECK_FIRST], p[CHECK_FIRST + 1u], p[CHECK_FIRST + 2u], count};
        for (uint layer = 0u; layer < 3u; layer++) {
            uint cpu_first = firsts[layer];
            uint cpu_count = firsts[layer + 1u] - cpu_first;
            uint gpu_count = args[layer * 5u + 1u];
            uint gpu_first = args[layer * 5u + 4u];
            if (cpu_count != gpu_count || cpu_first != gpu_first) {
                atomic_fetch_add_explicit(&mismatch[1], 1u, memory_order_relaxed);
            }
        }
        atomic_fetch_add_explicit(&mismatch[2], count, memory_order_relaxed);
    }
    if (i >= count) {
        return;
    }
    if ((i + 1u) * CHECK_UNIT_BYTES > p[CHECK_GPU_BYTES]) {
        atomic_fetch_add_explicit(&mismatch[0], 1u, memory_order_relaxed);
        return;
    }
    uint base = i * CHECK_UNIT_WORDS;
    for (uint word = 0u; word < CHECK_UNIT_WORDS; word++) {
        if (cpu[base + word] != gpu[base + word]) {
            atomic_fetch_add_explicit(&mismatch[0], 1u, memory_order_relaxed);
            return;
        }
    }
}

constant uint FEATURE_MATRIX_WORDS = 16u;
constant uint FEATURE_OUTPUT_VISIBLE = 1u;
constant uint FEATURE_OUTPUT_PENDING = 2u;

static float feature_float(constant uint* p, uint index) {
    return as_type<float>(p[index]);
}

static float4x4 feature_matrix(constant uint* p, uint base) {
    return float4x4(
        float4(feature_float(p, base), feature_float(p, base + 1u), feature_float(p, base + 2u), feature_float(p, base + 3u)),
        float4(feature_float(p, base + 4u), feature_float(p, base + 5u), feature_float(p, base + 6u), feature_float(p, base + 7u)),
        float4(feature_float(p, base + 8u), feature_float(p, base + 9u), feature_float(p, base + 10u), feature_float(p, base + 11u)),
        float4(feature_float(p, base + 12u), feature_float(p, base + 13u), feature_float(p, base + 14u), feature_float(p, base + 15u)));
}

static bool feature_finite(constant uint* p) {
    for (uint i = 0u; i < FEATURE_MATRIX_WORDS * 2u; i++) {
        if (!isfinite(feature_float(p, i))) {
            return false;
        }
    }
    return true;
}

static bool feature_hiz_visible(device const float* aabb,
                                constant uint* p,
                                texture2d<float, access::read> hiz) {
    constexpr float expansion = 1.0 / 256.0;
    float3 lo = float3(aabb[0], aabb[1], aabb[2]) - expansion;
    float3 hi = float3(aabb[3], aabb[4], aabb[5]) + expansion;
    for (uint i = 0u; i < 6u; i++) {
        if (!isfinite(aabb[i])) {
            return true;
        }
    }
    if (any(lo > hi)) {
        return true;
    }
    float2 ndc_min = float2(INFINITY);
    float2 ndc_max = float2(-INFINITY);
    float nearest = -INFINITY;
    float4x4 clip_from_pos = feature_matrix(p, 0u);
    for (uint corner = 0u; corner < 8u; corner++) {
        float3 point = select(lo, hi, bool3((corner & 1u) != 0u, (corner & 2u) != 0u, (corner & 4u) != 0u));
        float4 projected = clip_from_pos * float4(point, 1.0);
        if (!isfinite(projected.x) || !isfinite(projected.y) || !isfinite(projected.z) || !isfinite(projected.w)
            || projected.w <= 1.0e-5) {
            return true;
        }
        float3 ndc = projected.xyz / projected.w;
        if (!all(isfinite(ndc))) {
            return true;
        }
        ndc_min = min(ndc_min, ndc.xy);
        ndc_max = max(ndc_max, ndc.xy);
        nearest = max(nearest, ndc.z);
    }
    if (ndc_max.x < -1.0 || ndc_min.x > 1.0 || ndc_max.y < -1.0 || ndc_min.y > 1.0) {
        return false;
    }
    if (ndc_min.x <= -1.0 || ndc_max.x >= 1.0 || ndc_min.y <= -1.0 || ndc_max.y >= 1.0) {
        return true;
    }
    uint width = hiz.get_width();
    uint height = hiz.get_height();
    if (width == 0u || height == 0u) {
        return true;
    }
    float2 size = float2(width, height);
    float2 uv_min = clamp(ndc_min * 0.5 + 0.5, 0.0, 1.0);
    float2 uv_max = clamp(ndc_max * 0.5 + 0.5, 0.0, 1.0);
    float2 span = (uv_max - uv_min) * size;
    if (any(!isfinite(span)) || any(span > 2.0)) {
        return true;
    }
    uint2 limit = uint2(width - 1u, height - 1u);
    uint2 a = min(uint2(uv_min * size), limit);
    uint2 b = min(uint2(max(uv_max * size - 1.0, uv_min * size)), limit);
    if (b.x - a.x > 1u || b.y - a.y > 1u) {
        return true;
    }
    float farthest = hiz.read(a).x;
    farthest = min(farthest, hiz.read(uint2(b.x, a.y)).x);
    farthest = min(farthest, hiz.read(uint2(a.x, b.y)).x);
    farthest = min(farthest, hiz.read(b).x);
    return !isfinite(farthest) || nearest >= farthest;
}

kernel void feature_mark(device const float* aabbs [[buffer(0)]],
                         constant uint* p [[buffer(1)]],
                         device uint* results [[buffer(2)]],
                         device uint* history [[buffer(3)]],
                         texture2d<float, access::read> hiz [[texture(0)]],
                         uint i [[thread_position_in_grid]]) {
    uint count = p[32u];
    if (i >= count) {
        return;
    }
    if (!feature_finite(p)) {
        results[i] = FEATURE_OUTPUT_VISIBLE;
        return;
    }
    device const float* aabb = aabbs + i * 6u;
    bool visible = feature_hiz_visible(aabb, p, hiz);
    uint old = history[i];
    if (visible) {
        results[i] = FEATURE_OUTPUT_VISIBLE;
        history[i] = 0u;
    } else if ((old & FEATURE_OUTPUT_PENDING) != 0u) {
        results[i] = 0u;
        history[i] = FEATURE_OUTPUT_PENDING;
    } else {
        results[i] = FEATURE_OUTPUT_VISIBLE | FEATURE_OUTPUT_PENDING;
        history[i] = FEATURE_OUTPUT_PENDING;
    }
}

kernel void fx_motion(depth2d<float, access::read> depth [[texture(0)]],
                      texture2d<half, access::write> motion [[texture(1)]],
                      constant float4x4& current_inverse [[buffer(0)]],
                      constant float4x4& previous_clip [[buffer(1)]],
                      uint2 gid [[thread_position_in_grid]]) {
    if (gid.x >= depth.get_width() || gid.y >= depth.get_height()) {
        return;
    }
    float2 size = float2(depth.get_width(), depth.get_height());
    float2 uv = (float2(gid) + 0.5) / size;
    float4 current = float4(uv * 2.0 - 1.0, depth.read(gid), 1.0);
    float4 world = current_inverse * current;
    world /= max(abs(world.w), 1.0e-6);
    float4 previous = previous_clip * world;
    float2 previous_uv = previous.xy / max(abs(previous.w), 1.0e-6) * 0.5 + 0.5;
    float2 delta = (previous_uv - uv) * size;
    motion.write(half4(half2(delta), half(0.0), half(0.0)), gid);
}

struct MtlrSortRecord {
    ulong vertices;
    ulong indices;
    uint quads;
    uint padded;
    uint index_type;
    uint vertex_format;
    uint ids_offset;
    uint indices_offset;
    int expected_offset;
    int x;
    int y;
    int z;
};

constant uint SORT_COUNT = 0;
constant uint SORT_CAMERA_X = 5;
constant uint SORT_CAMERA_Y = 6;
constant uint SORT_CAMERA_Z = 7;

static float3 sort_position(MtlrSortRecord r, uint vertexIndex) {
    device const uint* words = reinterpret_cast<device const uint*>(r.vertices);
    if (r.vertex_format == 1u) {
        uint base = vertexIndex * 5u;
        return float3(words[base] & 0xFFFFFu, words[base + 1u] & 0xFFFFFu, words[base + 2u] & 0xFFFFFu)
            * (1.0f / 32768.0f) - 8.0f;
    }
    uint base = vertexIndex * 7u;
    return float3(as_type<float>(words[base]), as_type<float>(words[base + 1u]), as_type<float>(words[base + 2u]));
}

static float sort_key(MtlrSortRecord r, device const uint* ids, device const uint* indices, uint id, constant uint* p) {
    if (id >= r.quads) {
        return -INFINITY;
    }
    uint quad = ids[r.ids_offset + id];
    uint first = indices[r.indices_offset + quad * 6u];
    uint third = indices[r.indices_offset + quad * 6u + 2u];
    float3 center = (sort_position(r, first) + sort_position(r, third)) * 0.5f + float3(r.x, r.y, r.z)
        - float3(as_type<float>(p[SORT_CAMERA_X]), as_type<float>(p[SORT_CAMERA_Y]), as_type<float>(p[SORT_CAMERA_Z]));
    return dot(center, center);
}

static bool sort_before(MtlrSortRecord r, device const uint* ids, device const uint* indices, uint a, uint b, constant uint* p) {
    uint left_id = ids[r.ids_offset + a];
    uint right_id = ids[r.ids_offset + b];
    if (left_id >= r.quads) {
        return false;
    }
    if (right_id >= r.quads) {
        return true;
    }
    float left = sort_key(r, ids, indices, a, p);
    float right = sort_key(r, ids, indices, b, p);
    return left > right || (left == right && left_id < right_id);
}

kernel void sort_copy(device const MtlrSortRecord* records [[buffer(0)]],
                      device uint* indices [[buffer(2)]],
                      constant uint* p [[buffer(5)]],
                      uint2 gid [[thread_position_in_grid]]) {
    if (gid.y >= p[SORT_COUNT]) {
        return;
    }
    MtlrSortRecord r = records[gid.y];
    if (gid.x >= r.quads) {
        return;
    }
    device const ushort* source_short = reinterpret_cast<device const ushort*>(r.indices);
    device const uint* source_int = reinterpret_cast<device const uint*>(r.indices);
    uint base = r.indices_offset + gid.x * 6u;
    for (uint corner = 0u; corner < 6u; corner++) {
        indices[base + corner] = r.index_type == 2u ? source_int[gid.x * 6u + corner] : uint(source_short[gid.x * 6u + corner]);
    }
}

kernel void sort_init(device const MtlrSortRecord* records [[buffer(0)]],
                      device uint* ids [[buffer(1)]],
                      device atomic_uint* mismatch [[buffer(4)]],
                      constant uint* p [[buffer(5)]],
                      uint2 gid [[thread_position_in_grid]]) {
    if (gid.x == 0u && gid.y == 0u) {
        atomic_store_explicit(&mismatch[0], 0u, memory_order_relaxed);
        atomic_store_explicit(&mismatch[1], 0u, memory_order_relaxed);
    }
    if (gid.y >= p[SORT_COUNT]) {
        return;
    }
    MtlrSortRecord r = records[gid.y];
    if (gid.x < r.padded) {
        ids[r.ids_offset + gid.x] = gid.x < r.quads ? gid.x : r.quads;
    }
}

kernel void sort_stage(device const MtlrSortRecord* records [[buffer(0)]],
                       device uint* ids [[buffer(1)]],
                       device const uint* indices [[buffer(2)]],
                       constant uint* p [[buffer(5)]],
                       uint2 gid [[thread_position_in_grid]]) {
    if (gid.y >= p[2]) {
        return;
    }
    MtlrSortRecord r = records[gid.y];
    if (gid.x >= r.padded) {
        return;
    }
    uint k = p[0];
    uint j = p[1];
    uint partner = gid.x ^ j;
    if (partner <= gid.x || partner >= r.padded) {
        return;
    }
    bool before = sort_before(r, ids, indices, gid.x, partner, p);
    bool ascending = (gid.x & k) == 0u;
    if ((ascending && !before) || (!ascending && before)) {
        uint left = ids[r.ids_offset + gid.x];
        ids[r.ids_offset + gid.x] = ids[r.ids_offset + partner];
        ids[r.ids_offset + partner] = left;
    }
}

kernel void sort_write(device const MtlrSortRecord* records [[buffer(0)]],
                       device const uint* ids [[buffer(1)]],
                       device const uint* indices [[buffer(2)]],
                       constant uint* p [[buffer(5)]],
                       uint2 gid [[thread_position_in_grid]]) {
    if (gid.y >= p[SORT_COUNT]) {
        return;
    }
    MtlrSortRecord r = records[gid.y];
    if (gid.x >= r.quads) {
        return;
    }
    uint source_quad = ids[r.ids_offset + gid.x];
    uint source = r.indices_offset + source_quad * 6u;
    uint destination = gid.x * 6u;
    device ushort* destination_short = reinterpret_cast<device ushort*>(r.indices);
    device uint* destination_int = reinterpret_cast<device uint*>(r.indices);
    for (uint corner = 0u; corner < 6u; corner++) {
        uint value = indices[source + corner];
        if (r.index_type == 2u) {
            destination_int[destination + corner] = value;
        } else {
            destination_short[destination + corner] = ushort(value);
        }
    }
}

kernel void sort_check(device const MtlrSortRecord* records [[buffer(0)]],
                       device const uint* indices [[buffer(2)]],
                       device const uint* expected [[buffer(3)]],
                       device atomic_uint* mismatch [[buffer(4)]],
                       constant uint* p [[buffer(5)]],
                       uint2 gid [[thread_position_in_grid]]) {
    if (gid.y >= p[SORT_COUNT]) {
        return;
    }
    MtlrSortRecord r = records[gid.y];
    if (r.expected_offset < 0 || gid.x >= r.quads) {
        return;
    }
    if (gid.x == 0u) {
        atomic_fetch_add_explicit(&mismatch[1], r.quads, memory_order_relaxed);
    }
    device const ushort* source_short = reinterpret_cast<device const ushort*>(r.indices);
    device const uint* source_int = reinterpret_cast<device const uint*>(r.indices);
    uint value = r.index_type == 2u ? source_int[gid.x * 6u] : uint(source_short[gid.x * 6u]);
    if (value / 4u != expected[uint(r.expected_offset) + gid.x]) {
        atomic_fetch_add_explicit(&mismatch[0], 1u, memory_order_relaxed);
    }
}

struct ParticlePullingRecord {
    packed_float3 position;
    packed_float4 rotation;
    float scale;
    float u0;
    float u1;
    float v0;
    float v1;
    uint color;
    uint light;
    uint padding0;
    uint padding1;
};

struct ParticlePullingVertex {
    float4 position [[position]];
    float2 uv;
    float4 color;
    uint light;
};

static float3 particle_rotate(float4 q, float3 p) {
    return p + 2.0 * cross(q.xyz, cross(q.xyz, p) + q.w * p);
}

struct WeatherPullingRecord {
    int x;
    int z;
    int bottom;
    int top;
    float u_offset;
    float v_offset;
    uint light;
    uint padding;
};

struct WeatherPullingParams {
    float3 camera;
    int2 camera_block;
    int radius;
    float intensity;
    float max_alpha;
};

struct WeatherPullingVertex {
    float4 position [[position]];
    float2 uv;
    float4 color;
    uint light;
};

vertex WeatherPullingVertex weather_pulling_vs(
    device const WeatherPullingRecord* records [[buffer(0)]],
    constant float4x4& model_view_projection [[buffer(1)]],
    constant WeatherPullingParams& params [[buffer(2)]],
    constant uint& snow [[buffer(3)]],
    uint vertex_id [[vertex_id]],
    uint instance_id [[instance_id]]) {
    WeatherPullingRecord record = records[instance_id];
    uint corner = vertex_id & 3u;
    float delta_x = float(record.x - params.camera_block.x + 16);
    float delta_z = float(record.z - params.camera_block.y + 16);
    float length = sqrt(delta_x * delta_x + delta_z * delta_z);
    float half_x = -delta_z / length * 0.5;
    float half_z = delta_x / length * 0.5;
    float relative_x = float(record.x) + 0.5 - params.camera.x;
    float relative_z = float(record.z) + 0.5 - params.camera.z;
    bool left = corner == 0u || corner == 3u;
    bool top = corner < 2u;
    float y = float(top ? record.top : record.bottom) - params.camera.y;
    float distance_squared = relative_x * relative_x + relative_z * relative_z;
    float radius_squared = float(params.radius * params.radius);
    float alpha = mix(params.max_alpha, 0.5, min(distance_squared / radius_squared, 1.0)) * params.intensity;
    WeatherPullingVertex out;
    out.position = model_view_projection * float4(relative_x + (left ? -half_x : half_x), y, relative_z + (left ? -half_z : half_z), 1.0);
    out.uv = float2(record.u_offset + (left ? 0.0 : 1.0), float(top ? record.bottom : record.top) * 0.25 + record.v_offset);
    out.color = float4(1.0, 1.0, 1.0, alpha);
    out.light = record.light;
    return out;
}

vertex ParticlePullingVertex particle_pulling_vs(
    device const ParticlePullingRecord* records [[buffer(0)]],
    constant float4x4& model_view_projection [[buffer(1)]],
    uint vertex_id [[vertex_id]],
    uint instance_id [[instance_id]]) {
    ParticlePullingRecord record = records[instance_id];
    uint corner = vertex_id & 3u;
    float2 sign = corner == 0u ? float2(1.0, -1.0) : corner == 1u ? float2(1.0, 1.0) : corner == 2u ? float2(-1.0, 1.0) : float2(-1.0, -1.0);
    float3 position = record.position + particle_rotate(record.rotation, float3(sign, 0.0) * record.scale);
    ParticlePullingVertex out;
    out.position = model_view_projection * float4(position, 1.0);
    out.uv = corner == 0u ? float2(record.u1, record.v1) : corner == 1u ? float2(record.u1, record.v0) : corner == 2u ? float2(record.u0, record.v0) : float2(record.u0, record.v1);
    out.color = float4(
        float(record.color & 0xFFu),
        float((record.color >> 8u) & 0xFFu),
        float((record.color >> 16u) & 0xFFu),
        float((record.color >> 24u) & 0xFFu)) / 255.0;
    out.light = record.light;
    return out;
}

struct OitLayer {
    float3 color;
    float alpha;
    float depth;
};

struct OitImageblock {
    float4 color0 [[raster_order_group(0)]];
    float depth0 [[raster_order_group(0)]];
    float4 color1 [[raster_order_group(0)]];
    float depth1 [[raster_order_group(0)]];
    float4 color2 [[raster_order_group(0)]];
    float depth2 [[raster_order_group(0)]];
    float4 color3 [[raster_order_group(0)]];
    float depth3 [[raster_order_group(0)]];
    float4 color4 [[raster_order_group(0)]];
    float depth4 [[raster_order_group(0)]];
    float4 color5 [[raster_order_group(0)]];
    float depth5 [[raster_order_group(0)]];
    float4 color6 [[raster_order_group(0)]];
    float depth6 [[raster_order_group(0)]];
    float4 color7 [[raster_order_group(0)]];
    float depth7 [[raster_order_group(0)]];
};

static OitLayer oit_over(OitLayer far, OitLayer near) {
    float remaining = 1.0 - far.alpha;
    OitLayer result;
    result.color = far.color + near.color * remaining;
    result.alpha = far.alpha + near.alpha * remaining;
    result.depth = near.depth;
    return result;
}

static void oit_insert(thread OitLayer* layers, thread uint& count, uint capacity, float4 color, float depth) {
    if (capacity < 4u || capacity > 8u) {
        return;
    }
    OitLayer incoming;
    incoming.color = color.rgb * color.a;
    incoming.alpha = color.a;
    incoming.depth = depth;
    if (count == capacity) {
        if (depth < layers[0].depth) {
            incoming = oit_over(incoming, layers[0]);
        } else {
            layers[0] = oit_over(layers[0], layers[1]);
            for (uint i = 1u; i + 1u < count; i++) {
                layers[i] = layers[i + 1u];
            }
        }
        count--;
    }
    uint position = count;
    for (uint i = 0u; i < count; i++) {
        if (layers[i].depth > depth) {
            position = i;
            break;
        }
    }
    for (uint i = count; i > position; i--) {
        layers[i] = layers[i - 1u];
    }
    layers[position] = incoming;
    count++;
}

static float4 oit_resolve(thread const OitLayer* layers, uint count) {
    float3 color = float3(0.0);
    float alpha = 0.0;
    for (uint i = 0u; i < count; i++) {
        float remaining = 1.0 - alpha;
        color += layers[i].color * remaining;
        alpha += layers[i].alpha * remaining;
    }
    return float4(color, alpha);
}

static void oit_load(const thread OitImageblock& block, thread OitLayer* layers) {
    layers[0] = OitLayer{block.color0.rgb, block.color0.a, block.depth0};
    layers[1] = OitLayer{block.color1.rgb, block.color1.a, block.depth1};
    layers[2] = OitLayer{block.color2.rgb, block.color2.a, block.depth2};
    layers[3] = OitLayer{block.color3.rgb, block.color3.a, block.depth3};
    layers[4] = OitLayer{block.color4.rgb, block.color4.a, block.depth4};
    layers[5] = OitLayer{block.color5.rgb, block.color5.a, block.depth5};
    layers[6] = OitLayer{block.color6.rgb, block.color6.a, block.depth6};
    layers[7] = OitLayer{block.color7.rgb, block.color7.a, block.depth7};
}

static void oit_store(thread OitImageblock& block, thread const OitLayer* layers, uint count) {
    OitLayer empty = OitLayer{float3(0.0), 0.0, 0.0};
    OitLayer l0 = count > 0u ? layers[0] : empty;
    OitLayer l1 = count > 1u ? layers[1] : empty;
    OitLayer l2 = count > 2u ? layers[2] : empty;
    OitLayer l3 = count > 3u ? layers[3] : empty;
    OitLayer l4 = count > 4u ? layers[4] : empty;
    OitLayer l5 = count > 5u ? layers[5] : empty;
    OitLayer l6 = count > 6u ? layers[6] : empty;
    OitLayer l7 = count > 7u ? layers[7] : empty;
    block.color0 = float4(l0.color, l0.alpha); block.depth0 = l0.depth;
    block.color1 = float4(l1.color, l1.alpha); block.depth1 = l1.depth;
    block.color2 = float4(l2.color, l2.alpha); block.depth2 = l2.depth;
    block.color3 = float4(l3.color, l3.alpha); block.depth3 = l3.depth;
    block.color4 = float4(l4.color, l4.alpha); block.depth4 = l4.depth;
    block.color5 = float4(l5.color, l5.alpha); block.depth5 = l5.depth;
    block.color6 = float4(l6.color, l6.alpha); block.depth6 = l6.depth;
    block.color7 = float4(l7.color, l7.alpha); block.depth7 = l7.depth;
}

fragment void oit_kbuffer_insert_fs(float4 color [[user(locn0)]],
                                    float4 position [[position]],
                                    OitImageblock block [[imageblock_data]]) {
    OitLayer layers[8];
    oit_load(block, layers);
    uint count = 8u;
    oit_insert(layers, count, 8u, color, position.z);
    oit_store(block, layers, count);
}

fragment float4 oit_kbuffer_resolve_fs(OitImageblock block [[imageblock_data]]) {
    OitLayer layers[8];
    oit_load(block, layers);
    return oit_resolve(layers, 8u);
}
