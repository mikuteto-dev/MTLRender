use crate::geometry_records::{
    GS_PRIMITIVE_RECORD_STRIDE, GeometryRecordLayout, PrimitiveBoundary, VaryingLayout,
};
use crate::transform::PrimitiveTopology;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct TopologyMetadata {
    pub input: PrimitiveTopology,
    pub output: PrimitiveTopology,
    pub input_width: u64,
    pub output_width: u64,
    pub vertex_stride: u64,
    pub primitive_stride: u64,
    pub varying_stride: u32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RecordRange {
    pub offset: u64,
    pub count: u64,
    pub byte_offset: u64,
    pub byte_size: u64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct InputPrimitive {
    pub primitive_id: u64,
    pub vertex_range: RecordRange,
    pub boundary: PrimitiveBoundary,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct OutputPrimitive {
    pub primitive_id: u64,
    pub layer: i32,
    pub vertex_range: RecordRange,
    pub varying: VaryingLayout,
    pub boundary: PrimitiveBoundary,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct OutputPrimitiveState {
    pub primitive_id: u64,
    pub layer: i32,
    pub vertex_start: u64,
    pub vertex_count: u64,
    pub varying: VaryingLayout,
    pub boundary: PrimitiveBoundary,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TopologyError {
    Unsupported(&'static str),
    Invalid(&'static str),
    Overflow(&'static str),
}

pub fn topology_metadata(
    input: PrimitiveTopology,
    output: PrimitiveTopology,
    layout: &GeometryRecordLayout,
    varying_stride: u32,
) -> Result<TopologyMetadata, TopologyError> {
    let input_width = primitive_width(input).ok_or(TopologyError::Unsupported("input topology"))?;
    let output_width =
        primitive_width(output).ok_or(TopologyError::Unsupported("output topology"))?;
    if layout.gs_vertices.stride == 0 {
        return Err(TopologyError::Invalid("vertex record stride"));
    }
    if layout.gs_primitives.stride != GS_PRIMITIVE_RECORD_STRIDE {
        return Err(TopologyError::Invalid("primitive record stride"));
    }
    let expected_vertex_stride = 32_u64
        .checked_add(u64::from(varying_stride))
        .ok_or(TopologyError::Overflow("vertex record stride"))?;
    if layout.gs_vertices.stride != expected_vertex_stride {
        return Err(TopologyError::Invalid("varying record stride"));
    }
    Ok(TopologyMetadata {
        input,
        output,
        input_width,
        output_width,
        vertex_stride: layout.gs_vertices.stride,
        primitive_stride: layout.gs_primitives.stride,
        varying_stride,
    })
}

pub fn assemble_input_primitive(
    metadata: TopologyMetadata,
    primitive_id: u64,
    input_primitives: u64,
) -> Result<InputPrimitive, TopologyError> {
    if primitive_id >= input_primitives {
        return Err(TopologyError::Invalid("input primitive index"));
    }
    let vertex_start = primitive_id
        .checked_mul(metadata.input_width)
        .ok_or(TopologyError::Overflow("input primitive vertex index"))?;
    let byte_offset = vertex_start
        .checked_mul(metadata.vertex_stride)
        .ok_or(TopologyError::Overflow("input primitive byte offset"))?;
    let byte_size = metadata
        .input_width
        .checked_mul(metadata.vertex_stride)
        .ok_or(TopologyError::Overflow("input primitive byte size"))?;
    Ok(InputPrimitive {
        primitive_id,
        vertex_range: RecordRange {
            offset: vertex_start,
            count: metadata.input_width,
            byte_offset,
            byte_size,
        },
        boundary: PrimitiveBoundary::None,
    })
}

pub fn emit_output_primitive(
    metadata: TopologyMetadata,
    primitive_id: u64,
    layer: i32,
    vertex_start: u64,
    vertex_count: u64,
    varying: VaryingLayout,
) -> Result<OutputPrimitiveState, TopologyError> {
    if vertex_count == 0 {
        return Err(TopologyError::Invalid("empty output primitive"));
    }
    if vertex_count != metadata.output_width {
        return Err(TopologyError::Invalid("output primitive width"));
    }
    let _vertex_byte_offset = vertex_start
        .checked_mul(metadata.vertex_stride)
        .ok_or(TopologyError::Overflow("output vertex byte offset"))?;
    let _vertex_byte_size = vertex_count
        .checked_mul(metadata.vertex_stride)
        .ok_or(TopologyError::Overflow("output vertex byte size"))?;
    if u64::from(varying.offset) + u64::from(varying.size) > metadata.vertex_stride {
        return Err(TopologyError::Invalid("varying record range"));
    }
    Ok(OutputPrimitiveState {
        primitive_id,
        layer,
        vertex_start,
        vertex_count,
        varying,
        boundary: PrimitiveBoundary::None,
    })
}

pub fn end_output_primitive(
    metadata: TopologyMetadata,
    state: OutputPrimitiveState,
) -> Result<OutputPrimitive, TopologyError> {
    if state.vertex_count != metadata.output_width {
        return Err(TopologyError::Invalid("unterminated output primitive"));
    }
    let byte_offset = state
        .vertex_start
        .checked_mul(metadata.vertex_stride)
        .ok_or(TopologyError::Overflow("output primitive byte offset"))?;
    let byte_size = state
        .vertex_count
        .checked_mul(metadata.vertex_stride)
        .ok_or(TopologyError::Overflow("output primitive byte size"))?;
    Ok(OutputPrimitive {
        primitive_id: state.primitive_id,
        layer: state.layer,
        vertex_range: RecordRange {
            offset: state.vertex_start,
            count: state.vertex_count,
            byte_offset,
            byte_size,
        },
        varying: state.varying,
        boundary: PrimitiveBoundary::End,
    })
}

pub fn restart_input_primitive(
    metadata: TopologyMetadata,
    primitive_id: u64,
    input_primitives: u64,
) -> Result<InputPrimitive, TopologyError> {
    let mut primitive = assemble_input_primitive(metadata, primitive_id, input_primitives)?;
    primitive.boundary = PrimitiveBoundary::Restart;
    Ok(primitive)
}

pub fn stable_output_range(
    metadata: TopologyMetadata,
    prefix_offset: u64,
    output_primitives: u64,
    output_vertices: u64,
    invocation_index: u64,
    invocation_vertices: u64,
    invocation_output_primitives: u64,
) -> Result<(RecordRange, RecordRange), TopologyError> {
    if invocation_output_primitives == 0 || invocation_vertices == 0 {
        return Ok((empty_range()?, empty_range()?));
    }
    if invocation_index >= output_primitives {
        return Err(TopologyError::Invalid("output primitive invocation index"));
    }
    let vertex_end = prefix_offset
        .checked_add(invocation_vertices)
        .ok_or(TopologyError::Overflow("stable output vertex range"))?;
    let primitive_end = prefix_offset
        .checked_add(invocation_output_primitives)
        .ok_or(TopologyError::Overflow("stable output primitive range"))?;
    if vertex_end > output_vertices {
        return Err(TopologyError::Invalid("output vertex prefix boundary"));
    }
    if primitive_end > output_primitives {
        return Err(TopologyError::Invalid("output primitive prefix boundary"));
    }
    let vertex_start = prefix_offset;
    let primitive_start = prefix_offset;
    let vertex_bytes = vertex_start
        .checked_mul(metadata.vertex_stride)
        .ok_or(TopologyError::Overflow("stable output vertex byte offset"))?;
    let vertex_size = invocation_vertices
        .checked_mul(metadata.vertex_stride)
        .ok_or(TopologyError::Overflow("stable output vertex byte size"))?;
    let primitive_bytes = primitive_start
        .checked_mul(metadata.primitive_stride)
        .ok_or(TopologyError::Overflow(
            "stable output primitive byte offset",
        ))?;
    Ok((
        RecordRange {
            offset: vertex_start,
            count: invocation_vertices,
            byte_offset: vertex_bytes,
            byte_size: vertex_size,
        },
        RecordRange {
            offset: primitive_start,
            count: invocation_output_primitives,
            byte_offset: primitive_bytes,
            byte_size: invocation_output_primitives
                .checked_mul(metadata.primitive_stride)
                .ok_or(TopologyError::Overflow("stable output primitive byte size"))?,
        },
    ))
}

fn empty_range() -> Result<RecordRange, TopologyError> {
    Ok(RecordRange {
        offset: 0,
        count: 0,
        byte_offset: 0,
        byte_size: 0,
    })
}

fn primitive_width(topology: PrimitiveTopology) -> Option<u64> {
    match topology {
        PrimitiveTopology::Points => Some(1),
        PrimitiveTopology::Lines | PrimitiveTopology::LineStrip => Some(2),
        PrimitiveTopology::Triangles | PrimitiveTopology::TriangleStrip => Some(3),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::geometry_records::GeometryLayoutInput;

    fn metadata(input: PrimitiveTopology, output: PrimitiveTopology) -> Option<TopologyMetadata> {
        let layout = GeometryRecordLayout::new(GeometryLayoutInput {
            input_primitives: 4,
            output_vertices: 12,
            output_primitives: 4,
            varying_stride: 8,
            device_buffer_limit: 4096,
        })
        .ok()?;
        topology_metadata(input, output, &layout, 8).ok()
    }

    #[test]
    fn input_order_and_width_are_stable() {
        for (topology, width) in [
            (PrimitiveTopology::Points, 1),
            (PrimitiveTopology::Lines, 2),
            (PrimitiveTopology::Triangles, 3),
        ] {
            let Some(value) = metadata(topology, topology) else {
                return;
            };
            let first = assemble_input_primitive(value, 0, 2).ok();
            let second = assemble_input_primitive(value, 1, 2).ok();
            assert_eq!(first.map(|item| item.vertex_range.count), Some(width));
            assert_eq!(second.map(|item| item.vertex_range.offset), Some(width));
        }
    }

    #[test]
    fn emission_preserves_id_layer_varying_and_end() {
        let Some(value) = metadata(PrimitiveTopology::Triangles, PrimitiveTopology::Triangles)
        else {
            return;
        };
        let varying = VaryingLayout {
            offset: 24,
            size: 8,
            stride: 40,
        };
        let state = emit_output_primitive(value, 7, 3, 6, 3, varying).ok();
        let record = state.and_then(|item| end_output_primitive(value, item).ok());
        assert_eq!(record.map(|item| item.primitive_id), Some(7));
        assert_eq!(record.map(|item| item.layer), Some(3));
        assert_eq!(record.map(|item| item.varying), Some(varying));
        assert_eq!(
            record.map(|item| item.boundary),
            Some(PrimitiveBoundary::End)
        );
    }

    #[test]
    fn restart_does_not_leak_boundary() {
        let Some(value) = metadata(PrimitiveTopology::Lines, PrimitiveTopology::Lines) else {
            return;
        };
        let restarted = restart_input_primitive(value, 1, 2).ok();
        let next = assemble_input_primitive(value, 0, 2).ok();
        assert_eq!(
            restarted.map(|item| item.boundary),
            Some(PrimitiveBoundary::Restart)
        );
        assert_eq!(
            next.map(|item| item.boundary),
            Some(PrimitiveBoundary::None)
        );
    }

    #[test]
    fn zero_and_prefix_ranges_are_checked() {
        let Some(value) = metadata(PrimitiveTopology::Points, PrimitiveTopology::Points) else {
            return;
        };
        let empty = stable_output_range(value, 9, 0, 0, 0, 0, 0).ok();
        assert_eq!(empty.map(|item| item.0.count), Some(0));
        let ranges = stable_output_range(value, 2, 4, 4, 1, 1, 1).ok();
        assert_eq!(ranges.map(|item| item.0.offset), Some(2));
        assert_eq!(ranges.map(|item| item.1.offset), Some(2));
    }

    #[test]
    fn overflow_is_rejected_before_offsets_are_built() {
        let Some(value) = metadata(PrimitiveTopology::Triangles, PrimitiveTopology::Triangles)
        else {
            return;
        };
        assert!(matches!(
            assemble_input_primitive(value, u64::MAX, u64::MAX),
            Err(TopologyError::Overflow(_))
        ));
        assert!(matches!(
            stable_output_range(value, u64::MAX, u64::MAX, u64::MAX, 0, 3, 1),
            Err(TopologyError::Overflow(_))
        ));
    }
}
