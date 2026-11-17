use super::{MeshLoweringError, MeshLoweringErrorReason, MeshLoweringInput, MeshPipelineMetadata};

pub const MAX_PAYLOAD_BYTES: u32 = 16 * 1024;

#[derive(Debug, Eq, PartialEq)]
pub struct ObjectLowering {
    pub msl: String,
    pub metadata: MeshPipelineMetadata,
}

pub fn lower(
    input: MeshLoweringInput<'_>,
    object_threadgroups: [u32; 3],
    mesh_threadgroups: [u32; 3],
) -> Result<ObjectLowering, MeshLoweringError> {
    let context = format!(
        "pack={} dimension={} program={} stage=object",
        input.pack, input.dimension, input.program
    );
    let metadata = input.build().map_err(|error| {
        MeshLoweringError::new(error.reason, format!("{}: {}", context, error.detail))
    })?;
    let msl = generate(&metadata, object_threadgroups, mesh_threadgroups).map_err(|error| {
        MeshLoweringError::new(error.reason, format!("{}: {}", context, error.detail))
    })?;
    Ok(ObjectLowering { msl, metadata })
}

pub fn generate(
    metadata: &MeshPipelineMetadata,
    object_threadgroups: [u32; 3],
    mesh_threadgroups: [u32; 3],
) -> Result<String, MeshLoweringError> {
    validate(metadata, object_threadgroups, mesh_threadgroups)?;
    let words = metadata.payload.size / 4;
    let mut text = String::from("#include <metal_stdlib>\nusing namespace metal;\n\n");
    text.push_str("struct MtlrObjectPayload {\n");
    text.push_str("    uint source_vertex;\n");
    text.push_str("    uint primitive_id;\n");
    text.push_str("    uint instance_id;\n");
    text.push_str("    uint base_instance;\n");
    if words > 4 {
        text.push_str("    uint payload_words[");
        text.push_str(&(words - 4).to_string());
        text.push_str("];\n");
    }
    text.push_str("};\n\n");
    text.push_str("[[object]] void ");
    text.push_str(metadata.names.object.as_str());
    text.push_str("(uint3 tid [[thread_position_in_grid]], object_data MtlrObjectPayload& payload [[payload]], mesh_grid_properties grid) {\n");
    text.push_str("    if (tid.x == 0u) {\n");
    let Some(mesh_x) = mesh_threadgroups.first() else {
        return Err(MeshLoweringError::new(
            MeshLoweringErrorReason::InvalidDispatch,
            "mesh threadgroup dimensions are incomplete",
        ));
    };
    let Some(mesh_y) = mesh_threadgroups.get(1) else {
        return Err(MeshLoweringError::new(
            MeshLoweringErrorReason::InvalidDispatch,
            "mesh threadgroup dimensions are incomplete",
        ));
    };
    let Some(mesh_z) = mesh_threadgroups.get(2) else {
        return Err(MeshLoweringError::new(
            MeshLoweringErrorReason::InvalidDispatch,
            "mesh threadgroup dimensions are incomplete",
        ));
    };
    text.push_str("        grid.set_threadgroups_per_grid(uint3(");
    text.push_str(&mesh_x.to_string());
    text.push_str("u, ");
    text.push_str(&mesh_y.to_string());
    text.push_str("u, ");
    text.push_str(&mesh_z.to_string());
    text.push_str("u));\n");
    text.push_str("    }\n");
    text.push_str("    payload.source_vertex = tid.x;\n");
    text.push_str("    payload.primitive_id = tid.x / ");
    text.push_str(&primitive_width(metadata.input_topology).to_string());
    text.push_str("u;\n");
    text.push_str("    payload.instance_id = 0u;\n");
    text.push_str("    payload.base_instance = 0u;\n");
    text.push_str("}\n");
    Ok(text)
}

fn validate(
    metadata: &MeshPipelineMetadata,
    object_threadgroups: [u32; 3],
    mesh_threadgroups: [u32; 3],
) -> Result<(), MeshLoweringError> {
    if metadata.payload.size < 16
        || !metadata.payload.size.is_multiple_of(4)
        || metadata.payload.size > MAX_PAYLOAD_BYTES
    {
        return Err(MeshLoweringError::new(
            MeshLoweringErrorReason::InvalidLayout,
            "object payload must be a four-byte-aligned record of at least 16 bytes and at most 16 KiB",
        ));
    }
    if !valid_threadgroups(object_threadgroups) || !valid_threadgroups(mesh_threadgroups) {
        return Err(MeshLoweringError::new(
            MeshLoweringErrorReason::InvalidDispatch,
            "object and mesh threadgroup dimensions must be nonzero and contain at most 1024 threads",
        ));
    }
    if metadata.output_vertices == 0 || metadata.output_primitives == 0 {
        return Err(MeshLoweringError::new(
            MeshLoweringErrorReason::ObjectStageRejected,
            "object lowering requires a nonempty mesh output contract",
        ));
    }
    Ok(())
}

fn valid_threadgroups(dimensions: [u32; 3]) -> bool {
    let Some(first) = dimensions.first() else {
        return false;
    };
    let Some(second) = dimensions.get(1) else {
        return false;
    };
    let Some(third) = dimensions.get(2) else {
        return false;
    };
    *first != 0
        && *second != 0
        && *third != 0
        && first
            .checked_mul(*second)
            .and_then(|value| value.checked_mul(*third))
            .is_some_and(|value| value <= 1024)
}

fn primitive_width(topology: super::PrimitiveTopology) -> u32 {
    match topology {
        super::PrimitiveTopology::Points => 1,
        super::PrimitiveTopology::Lines | super::PrimitiveTopology::LineStrip => 2,
        super::PrimitiveTopology::Triangles | super::PrimitiveTopology::TriangleStrip => 3,
    }
}
