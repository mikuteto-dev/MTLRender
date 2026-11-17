layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 color;
uniform sampler2D source_texture;
vec4 sample_value(vec2 coordinate) { return texture(source_texture, coordinate); }
void main() { color = sample_value(uv); }
