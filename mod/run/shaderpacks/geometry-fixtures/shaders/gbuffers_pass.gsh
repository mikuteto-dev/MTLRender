layout(triangles) in vec4 inputData[]; layout(triangle_strip, max_vertices = 3) out vec4 outputData; void main() { EmitVertex(); EmitVertex(); EmitVertex(); EndPrimitive(); }
