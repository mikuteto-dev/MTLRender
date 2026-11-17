layout(lines) in vec4 inputData[]; layout(line_strip, max_vertices = 4) out vec4 outputData; in vec4 v_color[]; void main() { EmitVertex(); EmitVertex(); EmitVertex(); EmitVertex(); EndPrimitive(); }
