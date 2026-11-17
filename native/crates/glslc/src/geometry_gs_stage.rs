use crate::geometry_gs::{GeometryGsLayer, GeometryGsMetadata};
use crate::geometry_records::{GeometryRecordLayout, PrimitiveBoundary};
use crate::geometry_vs::ResourceAccess;

pub const ENTRY_POINT: &str = "mtlr_geometry_gs_compute_stage";
pub const LOCAL_SIZE: [u32; 3] = [64, 1, 1];

#[derive(Debug, Eq, PartialEq)]
pub struct GeometryGsStageMetadata {
    pub entry_point: String,
    pub local_size: [u32; 3],
    pub dispatch: [u64; 3],
    pub input_primitives: u64,
    pub input_stride: u32,
    pub output_stride: u32,
    pub varying_stride: u32,
    pub input_topology: crate::transform::PrimitiveTopology,
    pub output_topology: crate::transform::PrimitiveTopology,
    pub accesses: Vec<ResourceAccess>,
}

#[derive(Debug, Eq, PartialEq)]
pub struct GeometryGsStage {
    pub msl: String,
    pub metadata: GeometryGsStageMetadata,
}

pub fn lower_geometry_invocation(
    metadata: &GeometryGsMetadata,
    records: &GeometryRecordLayout,
) -> GeometryGsStage {
    generate_geometry_compute_entry(metadata, records)
}

pub fn emit_input_record_loads(metadata: &GeometryGsMetadata) -> String {
    let mut text = String::from(" uint input_base = primitive_id * ");
    text.push_str(&metadata.topology.input_width.to_string());
    text.push_str("u; ");
    for index in 0..metadata.topology.input_width {
        text.push_str("MtlrGsRecord input_");
        text.push_str(&index.to_string());
        text.push_str(" = input_records[input_base + ");
        text.push_str(&index.to_string());
        text.push_str("u];");
    }
    text
}

pub fn emit_gs_builtins(metadata: &GeometryGsMetadata) -> String {
    let mut text = String::from(" uint gl_PrimitiveID = primitive_id; int gl_Layer = 0; ");
    match metadata.layer {
        GeometryGsLayer::Unwritten => {}
        GeometryGsLayer::Constant(value) => {
            text.push_str("gl_Layer = ");
            text.push_str(&value.to_string());
            text.push(';');
        }
        GeometryGsLayer::Invocation => text.push_str("gl_Layer = int(primitive_id);"),
    }
    text
}

pub fn emit_varying_payload(metadata: &GeometryGsMetadata) -> String {
    let mut text = String::from(" uint varying_size = ");
    text.push_str(&metadata.topology.varying_stride.to_string());
    text.push_str("u; ");
    if metadata.topology.varying_stride != 0 {
        text.push_str("for (uint varying_word = 0u; varying_word < (varying_size + 3u) / 4u; ++varying_word) { output_varyings[varying_word] = input_0.varying[varying_word]; }");
    }
    text
}

pub fn emit_output_record_store(
    metadata: &GeometryGsMetadata,
    emitted_vertices: u32,
    emitted_primitives: u32,
) -> String {
    let mut text = String::from(
        " MtlrGsRecord output_record = input_0; output_record.primitive_id = gl_PrimitiveID; output_record.layer = gl_Layer; output_record.varying_size = varying_size;",
    );
    for vertex in 0..emitted_vertices {
        text.push_str(" output_vertices[prefix_begin + ");
        text.push_str(&vertex.to_string());
        text.push_str("u] = output_record; ");
    }
    for primitive in 0..emitted_primitives {
        text.push_str(" output_primitives[prefix_begin + ");
        text.push_str(&primitive.to_string());
        text.push_str("u] = MtlrGsPrimitive(prefix_begin + ");
        text.push_str(&(primitive.saturating_mul(3)).to_string());
        text.push_str("u, ");
        text.push_str(&metadata.topology.output_width.to_string());
        text.push_str("u, gl_PrimitiveID, gl_Layer, ");
        text.push_str(&boundary_value(PrimitiveBoundary::End).to_string());
        text.push_str("u); ");
    }
    text
}

pub fn generate_geometry_compute_entry(
    metadata: &GeometryGsMetadata,
    records: &GeometryRecordLayout,
) -> GeometryGsStage {
    let input_primitives = metadata.compaction.input_primitives;
    let emitted_vertices = metadata.compaction.output_vertex_range.count;
    let emitted_primitives = metadata.compaction.output_primitive_range.count;
    let input_stride = records.gs_vertices.stride.min(u64::from(u32::MAX)) as u32;
    let output_stride = input_stride;
    let varying_stride = metadata.topology.varying_stride;
    let dispatch = [
        input_primitives.saturating_add(u64::from(LOCAL_SIZE[0] - 1)) / u64::from(LOCAL_SIZE[0]),
        1,
        1,
    ];
    let stage_metadata = GeometryGsStageMetadata {
        entry_point: String::from(ENTRY_POINT),
        local_size: LOCAL_SIZE,
        dispatch,
        input_primitives,
        input_stride,
        output_stride,
        varying_stride,
        input_topology: metadata.input_topology,
        output_topology: metadata.output_topology,
        accesses: resources(),
    };
    let mut msl = String::from("#include <metal_stdlib>\nusing namespace metal;\n\n");
    msl.push_str("struct MtlrGsRecord { float4 position; uint primitive_id; int layer; uint varying_size; uint varying[1]; };\n");
    msl.push_str("struct MtlrGsPrimitive { uint vertex_start; uint vertex_count; uint primitive_id; int layer; uint boundary; };\n");
    msl.push_str("kernel void ");
    msl.push_str(ENTRY_POINT);
    msl.push_str("(const device MtlrGsRecord *input_records [[buffer(0)]], const device uint *prefix_offsets [[buffer(1)]], device MtlrGsRecord *output_vertices [[buffer(2)]], device MtlrGsPrimitive *output_primitives [[buffer(3)]], device uint *output_varyings [[buffer(4)]], uint3 gid [[thread_position_in_grid]]) { uint primitive_id = gid.x; if (primitive_id >= ");
    msl.push_str(&input_primitives.to_string());
    msl.push_str("u) return; uint prefix_begin = prefix_offsets[primitive_id]; uint prefix_end = prefix_offsets[primitive_id + 1u]; if (prefix_begin >= prefix_end) return;");
    msl.push_str(&emit_input_record_loads(metadata));
    msl.push_str(&emit_gs_builtins(metadata));
    msl.push_str(&emit_varying_payload(metadata));
    msl.push_str(&emit_output_record_store(
        metadata,
        emitted_vertices.min(u64::from(u32::MAX)) as u32,
        emitted_primitives.min(u64::from(u32::MAX)) as u32,
    ));
    msl.push_str(" }\n");
    GeometryGsStage {
        msl,
        metadata: stage_metadata,
    }
}

fn boundary_value(boundary: PrimitiveBoundary) -> u32 {
    match boundary {
        PrimitiveBoundary::None => 0,
        PrimitiveBoundary::Restart => 2,
        PrimitiveBoundary::End => 1,
    }
}

fn resources() -> Vec<ResourceAccess> {
    vec![
        ResourceAccess {
            binding: 0,
            read: true,
            write: false,
            atomic: false,
        },
        ResourceAccess {
            binding: 1,
            read: true,
            write: false,
            atomic: false,
        },
        ResourceAccess {
            binding: 2,
            read: false,
            write: true,
            atomic: false,
        },
        ResourceAccess {
            binding: 3,
            read: false,
            write: true,
            atomic: false,
        },
        ResourceAccess {
            binding: 4,
            read: false,
            write: true,
            atomic: false,
        },
    ]
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::geometry::{GeometryKind, GeometryPlan, GeometrySideEffects};
    use crate::geometry_gs::{
        GeometryGsContext, GeometryGsInput, GeometryGsLayer, GeometryGsPrimitiveId,
        GeometryGsVaryingIdentity, lower_geometry_gs,
    };
    use crate::geometry_records::{GeometryLayoutInput, PrimitiveBoundary};

    fn stage() -> Option<GeometryGsStage> {
        let layout = GeometryRecordLayout::new(GeometryLayoutInput {
            input_primitives: 2,
            output_vertices: 6,
            output_primitives: 2,
            varying_stride: 8,
            device_buffer_limit: 4096,
        })
        .ok()?;
        let input = GeometryGsInput {
            context: GeometryGsContext {
                pack: String::from("pack"),
                dimension: String::from("world"),
                program: String::from("program"),
                source_stage: String::from("geometry"),
            },
            plan: GeometryPlan {
                kind: GeometryKind::Compute,
                reason: String::from("test"),
                input_topology: String::from("triangles"),
                output_topology: String::from("triangles"),
                max_vertices: 3,
                emitted_vertices: 3,
                emitted_primitives: 1,
                writes_layer: true,
                dynamic_control_flow: true,
                side_effects: GeometrySideEffects::default(),
                layer_mapping: Vec::new(),
            },
            input_primitives: 2,
            invocation_index: 0,
            prefix_offset: 0,
            prefix_complete: true,
            primitive_boundary: PrimitiveBoundary::None,
            varying: GeometryGsVaryingIdentity {
                name: String::from("payload"),
                location: 2,
            },
            varying_stride: 8,
            primitive_id: GeometryGsPrimitiveId::Invocation,
            layer: GeometryGsLayer::Invocation,
            output_stride: u32::try_from(layout.gs_vertices.stride).ok()?,
            record_layout: layout,
            accesses: Vec::new(),
        };
        let output = lower_geometry_gs(input).ok()?;
        Some(lower_geometry_invocation(
            &output.metadata,
            &output.record_layout,
        ))
    }

    #[test]
    fn emits_ordered_guard_body_and_store() {
        let Some(value) = stage() else { return };
        assert_eq!(value.metadata.entry_point, ENTRY_POINT);
        let input_position = value.msl.find("input_records");
        let output_position = value.msl.find("output_vertices[prefix_begin");
        assert!(
            input_position
                .is_some_and(|position| output_position.is_some_and(|output| position < output))
        );
        assert!(value.msl.contains("prefix_offsets[primitive_id]"));
        assert!(value.msl.contains("gl_PrimitiveID"));
        assert!(value.msl.contains("boundary"));
    }
}
