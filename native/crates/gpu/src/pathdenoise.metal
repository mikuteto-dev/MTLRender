#include <metal_stdlib>
using namespace metal;

kernel void path_atrous(texture2d<float, access::read_write> color [[texture(0)]],
                        texture2d<float, access::read> history [[texture(2)]],
                        constant uint* p [[buffer(2)]],
                        uint2 gid [[thread_position_in_grid]]) {
    if (gid.x >= p[0] || gid.y >= p[1]) {
        return;
    }
    uint2 limit = uint2(p[0] - 1u, p[1] - 1u);
    uint2 left = uint2(gid.x > 2u ? gid.x - 2u : 0u, gid.y);
    uint2 right = uint2(min(gid.x + 2u, limit.x), gid.y);
    uint2 up = uint2(gid.x, gid.y > 2u ? gid.y - 2u : 0u);
    uint2 down = uint2(gid.x, min(gid.y + 2u, limit.y));
    float4 value = history.read(gid) * 0.5;
    value += history.read(left) * 0.125;
    value += history.read(right) * 0.125;
    value += history.read(up) * 0.125;
    value += history.read(down) * 0.125;
    color.write(value, gid);
}
