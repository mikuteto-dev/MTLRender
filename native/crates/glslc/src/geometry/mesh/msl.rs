use super::{
    MAX_OUTPUT_PRIMITIVES, MAX_OUTPUT_VERTICES, MeshLoweringError, MeshLoweringErrorReason,
    MeshPipelineMetadata, PrimitiveTopology,
};
use crate::reflect::ResourceKind;

pub fn generate(
    metadata: &MeshPipelineMetadata,
    mesh_threadgroups: [u32; 3],
) -> Result<String, MeshLoweringError> {
    validate(metadata, mesh_threadgroups)?;
    let vertex_type = format!("{}Vertex", metadata.names.mesh);
    let primitive_type = primitive_type();
    let mut text = String::from("struct ");
    text.push_str(vertex_type.as_str());
    text.push_str(" {\n    float4 position [[position]];\n");
    for varying in &metadata.varyings {
        if varying.direction != crate::reflect::InterfaceDirection::Output {
            continue;
        }
        let Some(location) = varying.location else {
            continue;
        };
        text.push_str("    ");
        text.push_str(msl_type(varying.ty.as_str()));
        text.push(' ');
        text.push_str(varying.name.as_str());
        text.push_str(" [[user(locn");
        text.push_str(&location.to_string());
        text.push_str(")]];\n");
    }
    text.push_str("};\n\n");
    text.push_str("using ");
    text.push_str(metadata.names.mesh.as_str());
    text.push_str("Output = mesh<");
    text.push_str(vertex_type.as_str());
    text.push_str(", ");
    text.push_str(primitive_type);
    text.push_str(", ");
    text.push_str(&MAX_OUTPUT_VERTICES.to_string());
    text.push_str(", ");
    text.push_str(&MAX_OUTPUT_PRIMITIVES.to_string());
    text.push_str(", topology::");
    text.push_str(topology_name(metadata.output_topology));
    text.push_str(">;\n\n");
    text.push_str("[[mesh]] void ");
    text.push_str(metadata.names.mesh.as_str());
    text.push_str("(object_data const MtlrObjectPayload& payload [[payload]], uint3 tid [[thread_position_in_threadgroup]], ");
    text.push_str(metadata.names.mesh.as_str());
    text.push_str("Output output");
    append_resource_parameters(&mut text, metadata);
    text.push_str(") {\n");
    text.push_str("    if (tid.x == 0u) {\n");
    text.push_str("        output.set_primitive_count(");
    text.push_str(&metadata.output_primitives.to_string());
    text.push_str("u);\n    }\n");
    text.push_str("    if (tid.x < ");
    text.push_str(&metadata.output_vertices.to_string());
    text.push_str("u) {\n        ");
    text.push_str(vertex_type.as_str());
    text.push_str(" vertex;\n        vertex.position = float4(0.0f);\n");
    for varying in &metadata.varyings {
        if varying.direction != crate::reflect::InterfaceDirection::Output {
            continue;
        }
        let Some(_) = varying.location else { continue };
        text.push_str("        vertex.");
        text.push_str(varying.name.as_str());
        text.push_str(" = ");
        text.push_str(zero_value(msl_type(varying.ty.as_str())));
        text.push_str(";\n");
    }
    text.push_str("        output.set_vertex(tid.x, vertex);\n    }\n");
    append_side_effects(&mut text, metadata);
    text.push_str("    if (tid.x < ");
    text.push_str(&metadata.output_primitives.to_string());
    text.push_str("u) {\n        ");
    text.push_str(primitive_value(metadata.output_topology));
    text.push_str("\n    }\n}\n");
    Ok(text)
}

fn validate(
    metadata: &MeshPipelineMetadata,
    mesh_threadgroups: [u32; 3],
) -> Result<(), MeshLoweringError> {
    let Some(x) = mesh_threadgroups.first() else {
        return Err(error("mesh threadgroup dimensions are incomplete"));
    };
    let Some(y) = mesh_threadgroups.get(1) else {
        return Err(error("mesh threadgroup dimensions are incomplete"));
    };
    let Some(z) = mesh_threadgroups.get(2) else {
        return Err(error("mesh threadgroup dimensions are incomplete"));
    };
    let Some(threads) = x.checked_mul(*y).and_then(|value| value.checked_mul(*z)) else {
        return Err(error("mesh threadgroup dimensions overflow"));
    };
    if threads == 0 || threads > 1024 {
        return Err(error("mesh threadgroup dimensions exceed the Metal limit"));
    }
    if metadata.output_vertices == 0
        || metadata.output_vertices > MAX_OUTPUT_VERTICES
        || metadata.output_primitives == 0
        || metadata.output_primitives > MAX_OUTPUT_PRIMITIVES
    {
        return Err(error("mesh output exceeds the Metal mesh budget"));
    }
    if metadata.side_effects.barriers || metadata.side_effects.ordering_sensitive {
        return Err(MeshLoweringError::new(
            MeshLoweringErrorReason::ComputeCandidate,
            "mesh side effects require ordering unavailable in a mesh invocation",
        ));
    }
    validate_side_effect_resources(metadata)?;
    Ok(())
}

fn append_resource_parameters(text: &mut String, metadata: &MeshPipelineMetadata) {
    for resource in &metadata.resources {
        if !resource.writable && resource.kind != ResourceKind::AtomicCounter {
            continue;
        }
        match resource.kind {
            ResourceKind::Image => {
                text.push_str(", texture2d<float, access::read_write> mtlr_image_");
                text.push_str(&resource.binding.to_string());
                text.push_str(" [[texture(");
                text.push_str(&resource.binding.to_string());
                text.push_str(")]]");
            }
            ResourceKind::StorageBuffer => {
                text.push_str(", device volatile uint *mtlr_ssbo_");
                text.push_str(&resource.binding.to_string());
                text.push_str(" [[buffer(");
                text.push_str(&resource.binding.to_string());
                text.push_str(")]]");
            }
            ResourceKind::AtomicCounter => {
                text.push_str(", device atomic_uint *mtlr_atomic_");
                text.push_str(&resource.binding.to_string());
                text.push_str(" [[buffer(");
                text.push_str(&resource.binding.to_string());
                text.push_str(")]]");
            }
            ResourceKind::UniformBuffer
            | ResourceKind::Texture
            | ResourceKind::ShadowTexture
            | ResourceKind::Sampler => {}
        }
    }
}

fn append_side_effects(text: &mut String, metadata: &MeshPipelineMetadata) {
    if !metadata.side_effects.any() {
        return;
    }
    text.push_str("    if (tid.x == 0u) {\n");
    for resource in &metadata.resources {
        if !resource.writable && resource.kind != ResourceKind::AtomicCounter {
            continue;
        }
        match resource.kind {
            ResourceKind::Image if metadata.side_effects.image_writes => {
                text.push_str("        mtlr_image_");
                text.push_str(&resource.binding.to_string());
                text.push_str(".write(float4(0.0f), uint2(payload.primitive_id, 0u));\n");
            }
            ResourceKind::StorageBuffer if metadata.side_effects.ssbo_writes => {
                text.push_str("        mtlr_ssbo_");
                text.push_str(&resource.binding.to_string());
                text.push_str("[payload.primitive_id] = mtlr_ssbo_");
                text.push_str(&resource.binding.to_string());
                text.push_str("[payload.primitive_id];\n");
            }
            ResourceKind::AtomicCounter if metadata.side_effects.atomics => {
                text.push_str("        atomic_fetch_add_explicit(mtlr_atomic_");
                text.push_str(&resource.binding.to_string());
                text.push_str(", 1u, memory_order_seq_cst);\n");
            }
            _ => {}
        }
    }
    text.push_str("    }\n");
}

fn validate_side_effect_resources(
    metadata: &MeshPipelineMetadata,
) -> Result<(), MeshLoweringError> {
    if metadata.side_effects.image_writes
        && !metadata
            .resources
            .iter()
            .any(|resource| resource.kind == ResourceKind::Image && resource.writable)
    {
        return Err(MeshLoweringError::new(
            MeshLoweringErrorReason::ComputeCandidate,
            "mesh side effects require a writable image resource",
        ));
    }
    if metadata.side_effects.ssbo_writes
        && !metadata
            .resources
            .iter()
            .any(|resource| resource.kind == ResourceKind::StorageBuffer && resource.writable)
    {
        return Err(MeshLoweringError::new(
            MeshLoweringErrorReason::ComputeCandidate,
            "mesh side effects require a writable SSBO resource",
        ));
    }
    if metadata.side_effects.atomics
        && !metadata
            .resources
            .iter()
            .any(|resource| resource.kind == ResourceKind::AtomicCounter)
    {
        return Err(MeshLoweringError::new(
            MeshLoweringErrorReason::ComputeCandidate,
            "mesh atomics require a writable atomic resource",
        ));
    }
    Ok(())
}

fn error(detail: &str) -> MeshLoweringError {
    MeshLoweringError::new(MeshLoweringErrorReason::ObjectStageRejected, detail)
}

fn primitive_type() -> &'static str {
    "void"
}

fn topology_name(topology: PrimitiveTopology) -> &'static str {
    match topology {
        PrimitiveTopology::Points => "point",
        PrimitiveTopology::Lines | PrimitiveTopology::LineStrip => "line",
        PrimitiveTopology::Triangles | PrimitiveTopology::TriangleStrip => "triangle",
    }
}

fn primitive_value(topology: PrimitiveTopology) -> &'static str {
    match topology {
        PrimitiveTopology::Points => "output.set_index(tid.x, uchar(tid.x));",
        PrimitiveTopology::Lines | PrimitiveTopology::LineStrip => {
            "output.set_indices(tid.x, uchar2(uchar(tid.x), uchar(tid.x + 1u)));"
        }
        PrimitiveTopology::Triangles | PrimitiveTopology::TriangleStrip => {
            "output.set_indices(tid.x, uchar4(uchar(tid.x), uchar(tid.x + 1u), uchar(tid.x + 2u), 0u));"
        }
    }
}

fn msl_type(ty: &str) -> &str {
    match ty {
        "float" | "vec2" | "vec3" | "vec4" | "int" | "ivec2" | "ivec3" | "ivec4" | "uint"
        | "uvec2" | "uvec3" | "uvec4" | "bool" => match ty {
            "vec2" => "float2",
            "vec3" => "float3",
            "vec4" => "float4",
            "ivec2" => "int2",
            "ivec3" => "int3",
            "ivec4" => "int4",
            "uvec2" => "uint2",
            "uvec3" => "uint3",
            "uvec4" => "uint4",
            other => other,
        },
        _ => "float4",
    }
}

fn zero_value(ty: &str) -> &'static str {
    match ty {
        "float" => "0.0f",
        "float2" => "float2(0.0f)",
        "float3" => "float3(0.0f)",
        "float4" => "float4(0.0f)",
        "int" => "0",
        "int2" => "int2(0)",
        "int3" => "int3(0)",
        "int4" => "int4(0)",
        "uint" => "0u",
        "uint2" => "uint2(0u)",
        "uint3" => "uint3(0u)",
        "uint4" => "uint4(0u)",
        "bool" => "false",
        _ => "float4(0.0f)",
    }
}
