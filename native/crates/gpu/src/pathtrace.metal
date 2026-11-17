#include <metal_stdlib>
using namespace metal;

constant uint PATH_WIDTH = 0;
constant uint PATH_HEIGHT = 1;
constant uint PATH_STEP = 2;
constant uint PATH_BOUNCES = 3;
constant uint PATH_CAMERA = 4;
constant uint PATH_TABLE_ORIGIN = 7;
constant uint PATH_INVERSE_CLIP = 10;
constant uint PATH_PREVIOUS_CLIP = 26;
constant uint PATH_HISTORY_VALID = 42;
constant uint PATH_SUN_DIRECTION = 43;
constant uint PATH_SUN_COLOR = 46;
constant uint PATH_MOON_DIRECTION = 49;
constant uint PATH_MOON_COLOR = 52;
constant uint PATH_DIAMETER = 9;
constant uint PATH_SIZE = 16;
constant uint PATH_VOXELS = 4096;

static float path_float(constant uint* p, uint index) {
    return as_type<float>(p[index]);
}

static float4x4 path_previous_matrix(constant uint* p) {
    return float4x4(
        float4(path_float(p, PATH_PREVIOUS_CLIP), path_float(p, PATH_PREVIOUS_CLIP + 1u), path_float(p, PATH_PREVIOUS_CLIP + 2u), path_float(p, PATH_PREVIOUS_CLIP + 3u)),
        float4(path_float(p, PATH_PREVIOUS_CLIP + 4u), path_float(p, PATH_PREVIOUS_CLIP + 5u), path_float(p, PATH_PREVIOUS_CLIP + 6u), path_float(p, PATH_PREVIOUS_CLIP + 7u)),
        float4(path_float(p, PATH_PREVIOUS_CLIP + 8u), path_float(p, PATH_PREVIOUS_CLIP + 9u), path_float(p, PATH_PREVIOUS_CLIP + 10u), path_float(p, PATH_PREVIOUS_CLIP + 11u)),
        float4(path_float(p, PATH_PREVIOUS_CLIP + 12u), path_float(p, PATH_PREVIOUS_CLIP + 13u), path_float(p, PATH_PREVIOUS_CLIP + 14u), path_float(p, PATH_PREVIOUS_CLIP + 15u)));
}

static int path_int(constant uint* p, uint index) {
    return as_type<int>(p[index]);
}

static float4x4 path_matrix(constant uint* p) {
    return float4x4(
        float4(path_float(p, PATH_INVERSE_CLIP), path_float(p, PATH_INVERSE_CLIP + 1u), path_float(p, PATH_INVERSE_CLIP + 2u), path_float(p, PATH_INVERSE_CLIP + 3u)),
        float4(path_float(p, PATH_INVERSE_CLIP + 4u), path_float(p, PATH_INVERSE_CLIP + 5u), path_float(p, PATH_INVERSE_CLIP + 6u), path_float(p, PATH_INVERSE_CLIP + 7u)),
        float4(path_float(p, PATH_INVERSE_CLIP + 8u), path_float(p, PATH_INVERSE_CLIP + 9u), path_float(p, PATH_INVERSE_CLIP + 10u), path_float(p, PATH_INVERSE_CLIP + 11u)),
        float4(path_float(p, PATH_INVERSE_CLIP + 12u), path_float(p, PATH_INVERSE_CLIP + 13u), path_float(p, PATH_INVERSE_CLIP + 14u), path_float(p, PATH_INVERSE_CLIP + 15u)));
}

static uint path_material(device const uint* bricks, device const uint* table, int3 voxel, constant uint* p) {
    int3 section = int3(floor(float3(voxel) / float3(PATH_SIZE)));
    int3 local = voxel - section * PATH_SIZE;
    int3 origin = int3(path_int(p, PATH_TABLE_ORIGIN), path_int(p, PATH_TABLE_ORIGIN + 1u), path_int(p, PATH_TABLE_ORIGIN + 2u));
    int3 at = section - origin;
    if (any(at < int3(0)) || any(at >= int3(PATH_DIAMETER))) {
        return 0u;
    }
    uint slot = uint(at.x + PATH_DIAMETER * (at.y + PATH_DIAMETER * at.z));
    if (table[slot] == 0u) {
        return 0u;
    }
    uint voxel_index = uint(local.x + PATH_SIZE * (local.y + PATH_SIZE * local.z));
    return bricks[slot * PATH_VOXELS + voxel_index];
}

static float3 path_direction(constant uint* p, uint index) {
    float3 direction = float3(path_float(p, index), path_float(p, index + 1u), path_float(p, index + 2u));
    return length_squared(direction) > 0.0 ? normalize(direction) : float3(0.0, 1.0, 0.0);
}

static float3 path_albedo(uint material) {
    if (material == 0u) {
        return float3(1.0);
    }
    return float3(
        float(material & 255u),
        float((material >> 8u) & 255u),
        float((material >> 16u) & 255u)) / 255.0;
}

static bool path_blocked(device const uint* bricks, device const uint* table, float3 origin, float3 direction, constant uint* p) {
    for (uint step = 1u; step < 96u; step++) {
        float3 point = origin + direction * (float(step) * 0.5);
        if (path_material(bricks, table, int3(floor(point)), p) != 0u) {
            return true;
        }
    }
    return false;
}

static float3 path_normal(device const uint* bricks, device const uint* table, int3 voxel, constant uint* p) {
    float x = float(path_material(bricks, table, voxel + int3(1, 0, 0), p) == 0u) - float(path_material(bricks, table, voxel - int3(1, 0, 0), p) == 0u);
    float y = float(path_material(bricks, table, voxel + int3(0, 1, 0), p) == 0u) - float(path_material(bricks, table, voxel - int3(0, 1, 0), p) == 0u);
    float z = float(path_material(bricks, table, voxel + int3(0, 0, 1), p) == 0u) - float(path_material(bricks, table, voxel - int3(0, 0, 1), p) == 0u);
    float3 normal = float3(x, y, z);
    return length_squared(normal) > 0.0 ? normalize(normal) : float3(0.0, 1.0, 0.0);
}

kernel void path_trace(texture2d<float, access::read_write> color [[texture(0)]],
                       depth2d<float, access::read> depth [[texture(1)]],
                       texture2d<float, access::read_write> history [[texture(2)]],
                       texture2d<float, access::read_write> history_depth [[texture(3)]],
                       device const uint* bricks [[buffer(0)]],
                       device const uint* table [[buffer(1)]],
                       constant uint* p [[buffer(2)]],
                       uint2 gid [[thread_position_in_grid]]) {
    uint step = max(1u, p[PATH_STEP]);
    uint2 origin = gid * step;
    if (origin.x >= p[PATH_WIDTH] || origin.y >= p[PATH_HEIGHT]) {
        return;
    }
    float2 size = float2(p[PATH_WIDTH], p[PATH_HEIGHT]);
    float depth_value = depth.read(origin);
    float4 current = color.read(origin);
    float3 lighting = float3(1.0);
    if (depth_value > 0.0) {
        float2 uv = (float2(origin) + 0.5) / size;
        float2 ndc = uv * 2.0 - 1.0;
        float4 world = path_matrix(p) * float4(ndc, depth_value, 1.0);
        world.xyz /= max(abs(world.w), 1.0e-5);
        float4 previous_clip = path_previous_matrix(p) * float4(world.xyz, 1.0);
        float2 previous_uv = previous_clip.xy / max(abs(previous_clip.w), 1.0e-5) * 0.5 + 0.5;
        uint2 previous_pixel = uint2(clamp(previous_uv, 0.0, 1.0) * (size - 1.0));
        float previous_depth = history_depth.read(previous_pixel).x;
        if (p[PATH_HISTORY_VALID] != 0u && abs(previous_depth - depth_value) < 0.02) {
            current = mix(current, history.read(previous_pixel), 0.85);
        }
        int3 voxel = int3(floor(world.xyz));
        uint material = path_material(bricks, table, voxel, p);
        float3 albedo = path_albedo(material);
        float3 normal = path_normal(bricks, table, voxel, p);
        float3 sun = path_direction(p, PATH_SUN_DIRECTION);
        float3 sun_color = float3(path_float(p, PATH_SUN_COLOR), path_float(p, PATH_SUN_COLOR + 1u), path_float(p, PATH_SUN_COLOR + 2u));
        float3 moon = path_direction(p, PATH_MOON_DIRECTION);
        float3 moon_color = float3(path_float(p, PATH_MOON_COLOR), path_float(p, PATH_MOON_COLOR + 1u), path_float(p, PATH_MOON_COLOR + 2u));
        bool sun_shadow = path_blocked(bricks, table, world.xyz + normal * 0.1, sun, p);
        bool moon_shadow = path_blocked(bricks, table, world.xyz + normal * 0.1, moon, p);
        lighting *= albedo * (sun_color * (sun_shadow ? 0.42 : 1.0) + moon_color * (moon_shadow ? 0.12 : 0.35));
        float emission = float((material >> 24u) & 15u) / 15.0;
        lighting += albedo * emission * 0.35;
        if (p[PATH_BOUNCES] > 0u) {
            float3 sun_diffuse = normalize(normal + sun);
            float3 moon_diffuse = normalize(normal + moon);
            bool sun_indirect_shadow = path_blocked(bricks, table, world.xyz + normal * 0.1, sun_diffuse, p);
            bool moon_indirect_shadow = path_blocked(bricks, table, world.xyz + normal * 0.1, moon_diffuse, p);
            lighting += albedo * (sun_color * (sun_indirect_shadow ? 0.03 : 0.16) + moon_color * (moon_indirect_shadow ? 0.01 : 0.06));
            float3 camera = float3(path_float(p, PATH_CAMERA), path_float(p, PATH_CAMERA + 1u), path_float(p, PATH_CAMERA + 2u));
            float3 reflection = reflect(normalize(world.xyz - camera), normal);
            bool reflection_hit = path_blocked(bricks, table, world.xyz + normal * 0.1, reflection, p);
            uint flags = (material >> 28u) & 15u;
            lighting += flags > 1u && !reflection_hit ? current.rgb * 0.12 : 0.0;
        }
    }
    float4 result = float4(current.rgb * lighting, current.a);
    for (uint y = 0u; y < step; y++) {
        for (uint x = 0u; x < step; x++) {
            uint2 pixel = origin + uint2(x, y);
            if (pixel.x < p[PATH_WIDTH] && pixel.y < p[PATH_HEIGHT]) {
                color.write(result, pixel);
                history.write(result, pixel);
                history_depth.write(depth_value, pixel);
            }
        }
    }
}
