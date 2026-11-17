layout(location = 0) in vec3 position;
layout(location = 0) out vec2 uv;
vec3 legacy_normal() { return gl_Normal; }
void main() { gl_Position = vec4(position, 1.0); uv = legacy_normal().xy; }
