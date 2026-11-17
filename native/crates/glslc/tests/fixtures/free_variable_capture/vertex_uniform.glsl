layout(location = 2) in vec3 position;
layout(location = 1) out vec2 uv;
uniform float transform;
float project_value(float value) { return transform * value; }
void main() { gl_Position = vec4(project_value(position.x)); uv = vec2(0.0); }
