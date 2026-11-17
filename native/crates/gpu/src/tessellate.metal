#include <metal_stdlib>
using namespace metal;

struct TessFactors { float outer[4]; float inner[2]; };
struct TessVertex { float2 domain; uint patch; uint invocation; };
struct TessTriangle { uint3 vertices; uint patch; uint primitive; };
struct TessArguments { uint index_count; uint vertex_start; uint primitive_count; uint reserved; };
struct TessOffsets { uint vertex_start; uint primitive_start; };
struct TessParams { uint patch_count; uint vertex_capacity; uint primitive_capacity; uint spacing; };

inline uint tess_factor(float value, uint spacing) {
    float bounded = clamp(value, 1.0, 64.0);
    uint factor = uint(ceil(bounded));
    bool even = (factor & 1u) == 0u;
    if (spacing == 1u && !even) return min(factor + 1u, 64u);
    if (spacing == 2u && even) return min(factor + 1u, 64u);
    return factor;
}
inline bool tess_range(uint base, uint count, uint capacity) { return base <= capacity && count <= capacity - base; }
inline uint2 tess_tri_coord(uint index, uint factor) {
    uint row = 0u;
    uint start = 0u;
    while (row < factor && index >= start + row + 1u) { start += row + 1u; row += 1u; }
    return uint2(index - start, row);
}

kernel void tessellate_triangle(const device TessFactors *factors [[buffer(0)]], device TessVertex *vertices [[buffer(1)]], device TessTriangle *triangles [[buffer(2)]], device TessArguments *arguments [[buffer(3)]], const device TessOffsets *offsets [[buffer(4)]], constant TessParams &params [[buffer(5)]], uint3 gid [[thread_position_in_grid]]) {
    uint patch = gid.x;
    if (patch >= params.patch_count) return;
    uint factor = tess_factor(factors[patch].outer[0], params.spacing);
    factor = max(factor, tess_factor(factors[patch].outer[1], params.spacing));
    factor = max(factor, tess_factor(factors[patch].outer[2], params.spacing));
    factor = max(factor, tess_factor(factors[patch].inner[0], params.spacing));
    factor = max(factor, tess_factor(factors[patch].inner[1], params.spacing));
    uint vertex_count = (factor + 1u) * (factor + 2u) / 2u;
    uint primitive_count = factor * factor;
    uint vertex_base = offsets[patch].vertex_start;
    uint primitive_base = offsets[patch].primitive_start;
    if (!tess_range(vertex_base, vertex_count, params.vertex_capacity) || !tess_range(primitive_base, primitive_count, params.primitive_capacity)) return;
    for (uint i = 0u; i < vertex_count; ++i) {
        uint2 c = tess_tri_coord(i, factor);
        float u = float(c.x) / float(factor);
        float v = float(c.y - c.x) / float(factor);
        vertices[vertex_base + i] = { float2(u, v), patch, i };
    }
    for (uint i = 0u; i < primitive_count; ++i) {
        uint row = uint(sqrt(float(i)));
        while ((row + 1u) * (row + 1u) <= i) row += 1u;
        while (row * row > i) row -= 1u;
        uint local = i - row * row;
        uint column = local / 2u;
        uint row_start = row * (row + 1u) / 2u;
        uint next_start = (row + 1u) * (row + 2u) / 2u;
        uint a = row_start + column;
        uint b = a + 1u;
        uint c = next_start + column;
        if ((local & 1u) != 0u) { a = b; b = next_start + column + 1u; }
        triangles[primitive_base + i] = { uint3(vertex_base + a, vertex_base + b, vertex_base + c), patch, i };
    }
    arguments[patch] = { primitive_count * 3u, vertex_base, primitive_count, 0u };
}

kernel void tessellate_quad(const device TessFactors *factors [[buffer(0)]], device TessVertex *vertices [[buffer(1)]], device TessTriangle *triangles [[buffer(2)]], device TessArguments *arguments [[buffer(3)]], const device TessOffsets *offsets [[buffer(4)]], constant TessParams &params [[buffer(5)]], uint3 gid [[thread_position_in_grid]]) {
    uint patch = gid.x;
    if (patch >= params.patch_count) return;
    uint u_factor = max(tess_factor(factors[patch].outer[0], params.spacing), tess_factor(factors[patch].outer[2], params.spacing));
    uint v_factor = max(tess_factor(factors[patch].outer[1], params.spacing), tess_factor(factors[patch].outer[3], params.spacing));
    u_factor = max(u_factor, tess_factor(factors[patch].inner[0], params.spacing));
    v_factor = max(v_factor, tess_factor(factors[patch].inner[1], params.spacing));
    uint vertex_count = (u_factor + 1u) * (v_factor + 1u);
    uint primitive_count = u_factor * v_factor * 2u;
    uint vertex_base = offsets[patch].vertex_start;
    uint primitive_base = offsets[patch].primitive_start;
    if (!tess_range(vertex_base, vertex_count, params.vertex_capacity) || !tess_range(primitive_base, primitive_count, params.primitive_capacity)) return;
    for (uint row = 0u; row <= v_factor; ++row) for (uint column = 0u; column <= u_factor; ++column) {
        uint i = row * (u_factor + 1u) + column;
        vertices[vertex_base + i] = { float2(float(column) / float(u_factor), float(row) / float(v_factor)), patch, i };
    }
    for (uint row = 0u; row < v_factor; ++row) for (uint column = 0u; column < u_factor; ++column) {
        uint base = row * (u_factor + 1u) + column;
        uint next = base + u_factor + 1u;
        uint i = (row * u_factor + column) * 2u;
        triangles[primitive_base + i] = { uint3(vertex_base + base, vertex_base + base + 1u, vertex_base + next + 1u), patch, i };
        triangles[primitive_base + i + 1u] = { uint3(vertex_base + base, vertex_base + next + 1u, vertex_base + next), patch, i + 1u };
    }
    arguments[patch] = { primitive_count * 3u, vertex_base, primitive_count, 0u };
}

kernel void tessellate_isoline(const device TessFactors *factors [[buffer(0)]], device TessVertex *vertices [[buffer(1)]], device TessTriangle *triangles [[buffer(2)]], device TessArguments *arguments [[buffer(3)]], const device TessOffsets *offsets [[buffer(4)]], constant TessParams &params [[buffer(5)]], uint3 gid [[thread_position_in_grid]]) {
    uint patch = gid.x;
    if (patch >= params.patch_count) return;
    uint lines = tess_factor(factors[patch].outer[0], params.spacing);
    uint segments = tess_factor(factors[patch].outer[1], params.spacing);
    uint row_width = segments + 1u;
    uint vertex_count = lines * row_width;
    uint primitive_count = lines * segments;
    uint vertex_base = offsets[patch].vertex_start;
    uint primitive_base = offsets[patch].primitive_start;
    if (!tess_range(vertex_base, vertex_count, params.vertex_capacity) || !tess_range(primitive_base, primitive_count, params.primitive_capacity)) return;
    for (uint line = 0u; line < lines; ++line) for (uint segment = 0u; segment <= segments; ++segment) {
        uint i = line * row_width + segment;
        vertices[vertex_base + i] = { float2(float(segment) / float(segments), float(line) / float(max(lines, 1u))), patch, i };
    }
    for (uint line = 0u; line < lines; ++line) for (uint segment = 0u; segment < segments; ++segment) {
        uint i = line * segments + segment;
        uint first = vertex_base + line * row_width + segment;
        uint second = first + 1u;
        triangles[primitive_base + i] = { uint3(first, second, second), patch, i };
    }
    arguments[patch] = { primitive_count * 2u, vertex_base, primitive_count, 0u };
}
