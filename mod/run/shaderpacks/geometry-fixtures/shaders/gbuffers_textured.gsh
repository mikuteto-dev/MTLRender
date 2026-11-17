layout(points) in vec4 inputData[]; layout(points, max_vertices = 2) out vec4 outputData; in vec4 v_color[]; void main() { EmitVertex(); EmitVertex(); EndPrimitive(); }
