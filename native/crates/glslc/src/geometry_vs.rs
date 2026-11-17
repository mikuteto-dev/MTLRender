use crate::geometry::{GeometryKind, GeometryPlan, GeometrySideEffects};
use crate::geometry_records::{GeometryLayoutInput, GeometryRecordLayout, LayoutError};

pub const ENTRY_POINT: &str = "mtlr_geometry_vs_record_stage";
pub const LOCAL_SIZE: [u32; 3] = [64, 1, 1];

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct VsInput {
    pub input_records: u64,
    pub input_vertex_stride: u32,
    pub varying_stride: u32,
    pub device_buffer_limit: u64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ResourceAccess {
    pub binding: u32,
    pub read: bool,
    pub write: bool,
    pub atomic: bool,
}

#[derive(Debug, Eq, PartialEq)]
pub struct VsMetadata {
    pub entry_point: String,
    pub local_size: [u32; 3],
    pub dispatch: [u64; 3],
    pub input_records: u64,
    pub input_stride: u32,
    pub output_stride: u32,
    pub varying_stride: u32,
    pub resources: Vec<ResourceAccess>,
    pub side_effects: GeometrySideEffects,
}

#[derive(Debug, Eq, PartialEq)]
pub struct VsStage {
    pub msl: String,
    pub metadata: VsMetadata,
    pub records: GeometryRecordLayout,
}

#[derive(Debug, Eq, PartialEq)]
pub enum Error {
    NotComputeGeometry,
    InvalidInput(&'static str),
    Overflow(&'static str),
    DeviceLimit { requested: u64, limit: u64 },
}

pub fn lower(plan: &GeometryPlan, input: VsInput) -> Result<VsStage, Error> {
    if plan.kind != GeometryKind::Compute
        && !(plan.kind == GeometryKind::BoundedMesh
            && plan.side_effects != crate::geometry::GeometrySideEffects::default())
    {
        return Err(Error::NotComputeGeometry);
    }
    if input.input_records == 0 || input.input_vertex_stride == 0 {
        return Err(Error::InvalidInput(
            "geometry VS input records and stride must be non-zero",
        ));
    }
    let output_vertices = input.input_records;
    let records = GeometryRecordLayout::new(GeometryLayoutInput {
        input_primitives: input.input_records,
        output_vertices,
        output_primitives: input.input_records,
        varying_stride: input.varying_stride,
        device_buffer_limit: input.device_buffer_limit,
    })
    .map_err(map_layout_error)?;
    let groups = input
        .input_records
        .checked_add(u64::from(LOCAL_SIZE[0] - 1))
        .ok_or(Error::Overflow("geometry VS dispatch"))?
        / u64::from(LOCAL_SIZE[0]);
    let resources = resources(plan.side_effects);
    let metadata = VsMetadata {
        entry_point: String::from(ENTRY_POINT),
        local_size: LOCAL_SIZE,
        dispatch: [groups, 1, 1],
        input_records: input.input_records,
        input_stride: input.input_vertex_stride,
        output_stride: u32::try_from(records.vs_outputs.stride)
            .map_err(|_| Error::Overflow("geometry VS output stride"))?,
        varying_stride: input.varying_stride,
        resources,
        side_effects: plan.side_effects,
    };
    Ok(VsStage {
        msl: generate_msl(&metadata),
        metadata,
        records,
    })
}

fn resources(side_effects: GeometrySideEffects) -> Vec<ResourceAccess> {
    let mut result = vec![
        ResourceAccess {
            binding: 0,
            read: true,
            write: false,
            atomic: false,
        },
        ResourceAccess {
            binding: 1,
            read: false,
            write: true,
            atomic: false,
        },
        ResourceAccess {
            binding: 2,
            read: false,
            write: false,
            atomic: false,
        },
    ];
    if side_effects.image_writes || side_effects.ssbo_writes {
        result.push(ResourceAccess {
            binding: 8,
            read: false,
            write: true,
            atomic: false,
        });
    }
    if side_effects.atomics {
        result.push(ResourceAccess {
            binding: 9,
            read: true,
            write: true,
            atomic: true,
        });
    }
    result
}

fn generate_msl(metadata: &VsMetadata) -> String {
    let mut text = String::from("#include <metal_stdlib>\nusing namespace metal;\n\n");
    text.push_str("struct MtlrGeometryVsRecord { float4 position; uint primitive_id; int layer; uint varying_size; };\n");
    text.push_str("kernel void ");
    text.push_str(metadata.entry_point.as_str());
    text.push_str("(const device float4 *input_positions [[buffer(0)]], device MtlrGeometryVsRecord *records [[buffer(1)]], device uint *varyings [[buffer(2)]]");
    if metadata.side_effects.image_writes || metadata.side_effects.ssbo_writes {
        text.push_str(", device uint *side_effect_storage [[buffer(8)]]");
    }
    if metadata.side_effects.atomics {
        text.push_str(", device atomic_uint *side_effect_atomics [[buffer(9)]]");
    }
    text.push_str(", uint3 gid [[thread_position_in_grid]]) { uint i = gid.x; if (i >= ");
    text.push_str(&metadata.input_records.to_string());
    text.push_str("u) return; records[i].position = input_positions[i]; records[i].primitive_id = i; records[i].layer = int(i); records[i].varying_size = ");
    text.push_str(&metadata.varying_stride.to_string());
    text.push_str("u;");
    if metadata.side_effects.image_writes || metadata.side_effects.ssbo_writes {
        text.push_str(" if (i == 0u) side_effect_storage[0] = i;");
    }
    if metadata.side_effects.atomics {
        text.push_str(" if (i == 0u) atomic_fetch_add_explicit(side_effect_atomics, 1u, memory_order_relaxed);");
    }
    text.push_str(" }\n");
    text
}

fn map_layout_error(error: LayoutError) -> Error {
    match error {
        LayoutError::Invalid { context } => Error::InvalidInput(context),
        LayoutError::Overflow { context } => Error::Overflow(context),
        LayoutError::DeviceLimit {
            requested, limit, ..
        } => Error::DeviceLimit { requested, limit },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::geometry::{GeometryKind, GeometryPlan};

    fn plan(side_effects: GeometrySideEffects) -> GeometryPlan {
        GeometryPlan {
            kind: GeometryKind::Compute,
            reason: String::from("fixture"),
            input_topology: String::from("triangles"),
            output_topology: String::from("triangle_strip"),
            max_vertices: 8,
            emitted_vertices: 4,
            emitted_primitives: 2,
            writes_layer: false,
            dynamic_control_flow: true,
            side_effects,
            layer_mapping: Vec::new(),
        }
    }

    #[test]
    fn emits_distinct_entry_point_and_exact_layout() {
        let result = lower(
            &plan(GeometrySideEffects::default()),
            VsInput {
                input_records: 3,
                input_vertex_stride: 32,
                varying_stride: 16,
                device_buffer_limit: 4096,
            },
        );
        assert!(result.is_ok());
        let Ok(stage) = result else { return };
        assert_eq!(stage.metadata.entry_point, ENTRY_POINT);
        assert_eq!(stage.metadata.local_size, LOCAL_SIZE);
        assert_eq!(stage.metadata.dispatch, [1, 1, 1]);
        assert_eq!(stage.metadata.input_records, 3);
        assert_eq!(stage.metadata.input_stride, 32);
        assert_eq!(stage.metadata.output_stride, 48);
        assert!(stage.msl.contains(ENTRY_POINT));
        assert!(stage.msl.contains("primitive_id = i"));
    }

    #[test]
    fn preserves_side_effect_resource_access() {
        let effects = GeometrySideEffects {
            image_writes: true,
            ssbo_writes: true,
            atomics: true,
        };
        let result = lower(
            &plan(effects),
            VsInput {
                input_records: 1,
                input_vertex_stride: 16,
                varying_stride: 0,
                device_buffer_limit: 4096,
            },
        );
        assert!(result.is_ok());
        let Ok(stage) = result else { return };
        assert_eq!(
            stage.metadata.resources.get(3).map(|value| value.binding),
            Some(8)
        );
        assert_eq!(
            stage.metadata.resources.get(4).map(|value| value.atomic),
            Some(true)
        );
    }

    #[test]
    fn rejects_non_compute_and_device_overflow() {
        let mut value = plan(GeometrySideEffects::default());
        value.kind = GeometryKind::PassThrough;
        assert_eq!(
            lower(
                &value,
                VsInput {
                    input_records: 1,
                    input_vertex_stride: 4,
                    varying_stride: 0,
                    device_buffer_limit: 4096
                }
            ),
            Err(Error::NotComputeGeometry)
        );
        assert!(matches!(
            lower(
                &plan(GeometrySideEffects::default()),
                VsInput {
                    input_records: 1,
                    input_vertex_stride: 4,
                    varying_stride: 0,
                    device_buffer_limit: 1
                }
            ),
            Err(Error::DeviceLimit { .. })
        ));
    }
}
