layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 color;
uniform Scene { mat4 view; } scene;
vec4 shade_value() { return scene.view[0]; }
void main() { color = shade_value() + vec4(uv, 0.0, 0.0); }
