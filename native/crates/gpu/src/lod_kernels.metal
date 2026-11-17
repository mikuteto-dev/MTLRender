#include <metal_stdlib>
using namespace metal;

struct LodNode {
    float3 minimum;
    float pad0;
    float3 maximum;
    float pad1;
    uint child;
    uint child_count;
    uint mesh;
    uint level;
    uint flags;
    float height_min;
    float height_max;
    uint pad2;
};

struct LodMesh {
    uint index_count;
    uint instance_count;
    uint first_index;
    int base_vertex;
    uint base_instance;
};

static float lod_float(constant uint* p, uint i) { return as_type<float>(p[i]); }
static bool lod_frustum(LodNode n, constant uint* p) {
    for (uint i = 0; i < 4u; i++) {
        float4 plane = float4(lod_float(p, i * 4u), lod_float(p, i * 4u + 1u), lod_float(p, i * 4u + 2u), lod_float(p, i * 4u + 3u));
        float3 point = float3(plane.x >= 0.0 ? n.maximum.x : n.minimum.x, plane.y >= 0.0 ? n.maximum.y : n.minimum.y, plane.z >= 0.0 ? n.maximum.z : n.minimum.z);
        if (dot(plane.xyz, point) + plane.w < 0.0) return false;
    }
    return true;
}

static bool lod_horizon(LodNode n, constant uint* p) {
    if ((n.flags & 1u) == 0u || !isfinite(n.height_min) || !isfinite(n.height_max)) return true;
    float3 camera = float3(lod_float(p, 16u), lod_float(p, 17u), lod_float(p, 18u));
    float2 delta = float2(min(n.minimum.x, n.maximum.x), min(n.minimum.z, n.maximum.z)) - camera.xz;
    return max(n.height_min, n.height_max) - camera.y >= -length(delta);
}

static bool lod_small(LodNode n, constant uint* p) {
    float3 camera = float3(lod_float(p, 16u), lod_float(p, 17u), lod_float(p, 18u));
    float3 extent = max(n.maximum - n.minimum, 0.0);
    float radius = max(length(extent) * 0.5, 0.0001);
    float3 center = (n.minimum + n.maximum) * 0.5;
    float distance = max(length(center - camera), radius);
    return radius * radius / (distance * distance) <= lod_float(p, 19u);
}

kernel void lod_seed(device uint* queue_a [[buffer(3)]], device atomic_uint* counters [[buffer(5)]], uint i [[thread_position_in_grid]]) {
    if (i == 0u) { queue_a[0] = 0u; atomic_store_explicit(&counters[0], 1u, memory_order_relaxed); atomic_store_explicit(&counters[1], 0u, memory_order_relaxed); atomic_store_explicit(&counters[2], 0u, memory_order_relaxed); atomic_store_explicit(&counters[3], 0u, memory_order_relaxed); }
}

kernel void lod_emit(device atomic_uint* counters [[buffer(5)]], uint i [[thread_position_in_grid]]) {
    if (i == 0u) atomic_store_explicit(&counters[2], atomic_load_explicit(&counters[2], memory_order_relaxed), memory_order_relaxed);
}

kernel void lod_reset(device atomic_uint* counters [[buffer(5)]], uint i [[thread_position_in_grid]]) {
    if (i == 0u) { uint count = atomic_load_explicit(&counters[1], memory_order_relaxed); atomic_store_explicit(&counters[0], count, memory_order_relaxed); atomic_store_explicit(&counters[1], 0u, memory_order_relaxed); }
}

kernel void lod_expand(device const LodNode* nodes [[buffer(0)]], device const LodMesh* meshes [[buffer(1)]], constant uint* p [[buffer(2)]], device const uint* queue_in [[buffer(3)]], device uint* queue_out [[buffer(4)]], device atomic_uint* counters [[buffer(5)]], device LodMesh* output [[buffer(6)]], uint i [[thread_position_in_grid]]) {
    uint count = atomic_load_explicit(&counters[0], memory_order_relaxed);
    if (i >= count) return;
    uint node_index = queue_in[i];
    if (node_index >= p[22u]) return;
    LodNode node = nodes[node_index];
    if (!lod_frustum(node, p) || !lod_horizon(node, p)) { atomic_fetch_add_explicit(&counters[3], 1u, memory_order_relaxed); return; }
    bool leaf = lod_small(node, p) || node.level >= p[21u] || node.child_count == 0u;
    bool missing = node.child >= p[22u] || node.child_count > p[22u] - node.child;
    if (leaf || missing) {
        uint slot = atomic_fetch_add_explicit(&counters[2], 1u, memory_order_relaxed);
        if (slot < p[24u] && node.mesh < p[23u]) output[slot] = meshes[node.mesh];
        else atomic_fetch_add_explicit(&counters[3], 1u, memory_order_relaxed);
        return;
    }
    for (uint child = 0u; child < node.child_count; child++) {
        uint slot = atomic_fetch_add_explicit(&counters[1], 1u, memory_order_relaxed);
        if (slot < 65536u) queue_out[slot] = node.child + child;
        else atomic_fetch_add_explicit(&counters[3], 1u, memory_order_relaxed);
    }
}
