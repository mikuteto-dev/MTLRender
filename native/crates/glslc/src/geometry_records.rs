use std::fmt::{Display, Formatter};
use std::mem::size_of;

pub const RECORD_ALIGNMENT: u64 = 16;
pub const VS_OUTPUT_RECORD_STRIDE: u64 = 32;
pub const COUNT_RECORD_STRIDE: u64 = 16;
pub const PREFIX_OFFSET_STRIDE: u64 = 4;
pub const GS_PRIMITIVE_RECORD_STRIDE: u64 = 32;
pub const INDEXED_INDIRECT_ARGUMENT_STRIDE: u64 = 20;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Topology {
    Points,
    Lines,
    Triangles,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PrimitiveBoundary {
    None,
    Restart,
    End,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PrimitiveIdentity {
    pub primitive_id: u32,
    pub layer: i32,
    pub boundary: PrimitiveBoundary,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct VaryingLayout {
    pub offset: u32,
    pub size: u32,
    pub stride: u32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct VsOutputRecord {
    pub position_offset: u32,
    pub primitive_id_offset: u32,
    pub layer_offset: u32,
    pub varying_offset: u32,
    pub varying_size_offset: u32,
    pub stride: u32,
}

impl VsOutputRecord {
    pub const fn layout() -> Self {
        Self {
            position_offset: 0,
            primitive_id_offset: 16,
            layer_offset: 20,
            varying_offset: 24,
            varying_size_offset: 28,
            stride: VS_OUTPUT_RECORD_STRIDE as u32,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CountRecord {
    pub vertex_count_offset: u32,
    pub primitive_count_offset: u32,
    pub flags_offset: u32,
    pub stride: u32,
}

impl CountRecord {
    pub const fn layout() -> Self {
        Self {
            vertex_count_offset: 0,
            primitive_count_offset: 4,
            flags_offset: 8,
            stride: COUNT_RECORD_STRIDE as u32,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct GsOutputRecord {
    pub position_offset: u32,
    pub primitive_id_offset: u32,
    pub layer_offset: u32,
    pub varying_offset: u32,
    pub varying_size_offset: u32,
    pub stride: u32,
}

impl GsOutputRecord {
    pub const fn vertex_layout() -> Self {
        VsOutputRecord::layout().into_output()
    }

    pub const fn primitive_layout() -> Self {
        Self {
            position_offset: 0,
            primitive_id_offset: 16,
            layer_offset: 20,
            varying_offset: 24,
            varying_size_offset: 28,
            stride: GS_PRIMITIVE_RECORD_STRIDE as u32,
        }
    }
}

impl VsOutputRecord {
    const fn into_output(self) -> GsOutputRecord {
        GsOutputRecord {
            position_offset: self.position_offset,
            primitive_id_offset: self.primitive_id_offset,
            layer_offset: self.layer_offset,
            varying_offset: self.varying_offset,
            varying_size_offset: self.varying_size_offset,
            stride: self.stride,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SideEffectKind {
    Image,
    Ssbo,
    Atomic,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SideEffectAccess {
    Read,
    Write,
    ReadWrite,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SideEffectAccessDescriptor {
    pub kind: SideEffectKind,
    pub access: SideEffectAccess,
    pub binding: u32,
    pub byte_offset: u64,
    pub byte_size: u64,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct IndexedIndirectArguments {
    pub index_count: u32,
    pub instance_count: u32,
    pub first_index: u32,
    pub base_vertex: i32,
    pub base_instance: u32,
}

impl IndexedIndirectArguments {
    pub const fn zero() -> Self {
        Self {
            index_count: 0,
            instance_count: 0,
            first_index: 0,
            base_vertex: 0,
            base_instance: 0,
        }
    }

    pub fn indexed(index_count: u64, first_index: u64) -> Result<Self, LayoutError> {
        if index_count == 0 {
            return Ok(Self::zero());
        }
        let index_count = u32::try_from(index_count).map_err(|_| LayoutError::Invalid {
            context: "indirect.index_count",
        })?;
        let first_index = u32::try_from(first_index).map_err(|_| LayoutError::Invalid {
            context: "indirect.first_index",
        })?;
        Ok(Self {
            index_count,
            instance_count: 1,
            first_index,
            base_vertex: 0,
            base_instance: 0,
        })
    }

    pub fn validate(&self) -> Result<(), LayoutError> {
        if self.index_count == 0 {
            if self.instance_count != 0 {
                return Err(LayoutError::Invalid {
                    context: "indirect.zero_output_instance_count",
                });
            }
            return Ok(());
        }
        if self.instance_count == 0 {
            return Err(LayoutError::Invalid {
                context: "indirect.nonzero_output_instance_count",
            });
        }
        Ok(())
    }

    pub const fn draws(&self) -> bool {
        self.index_count != 0 && self.instance_count != 0
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct GeometryLayoutInput {
    pub input_primitives: u64,
    pub output_vertices: u64,
    pub output_primitives: u64,
    pub varying_stride: u32,
    pub device_buffer_limit: u64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct BufferLayout {
    pub offset: u64,
    pub size: u64,
    pub stride: u64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct GeometryRecordLayout {
    pub vs_outputs: BufferLayout,
    pub counts: BufferLayout,
    pub prefix_offsets: BufferLayout,
    pub gs_vertices: BufferLayout,
    pub gs_primitives: BufferLayout,
    pub indirect_arguments: BufferLayout,
    pub total_size: u64,
}

impl GeometryRecordLayout {
    pub fn new(input: GeometryLayoutInput) -> Result<Self, LayoutError> {
        if input.input_primitives == 0 {
            return Err(LayoutError::Invalid {
                context: "input_primitives",
            });
        }
        if input.input_primitives > u64::from(u32::MAX) {
            return Err(LayoutError::Invalid {
                context: "input_primitives_max",
            });
        }
        if input.output_vertices > u64::from(u32::MAX) {
            return Err(LayoutError::Invalid {
                context: "output_vertices_max",
            });
        }
        if input.output_primitives > u64::from(u32::MAX) {
            return Err(LayoutError::Invalid {
                context: "output_primitives_max",
            });
        }
        let varying = u64::from(input.varying_stride);
        let record_stride = align(VS_OUTPUT_RECORD_STRIDE.checked_add(varying).ok_or(
            LayoutError::Overflow {
                context: "vs_output_record_stride",
            },
        )?)?;
        let vs_size = bytes(input.input_primitives, record_stride, "vs_outputs")?;
        let counts = bytes(input.input_primitives, COUNT_RECORD_STRIDE, "counts")?;
        let prefix_count = input
            .input_primitives
            .checked_add(1)
            .ok_or(LayoutError::Overflow {
                context: "prefix_offsets_count",
            })?;
        let prefixes = bytes(prefix_count, PREFIX_OFFSET_STRIDE, "prefix_offsets")?;
        let vertices = bytes(input.output_vertices, record_stride, "gs_vertices")?;
        let primitives = bytes(
            input.output_primitives,
            GS_PRIMITIVE_RECORD_STRIDE,
            "gs_primitives",
        )?;
        let indirect = align(INDEXED_INDIRECT_ARGUMENT_STRIDE)?;
        let mut cursor = 0_u64;
        let vs_outputs = place(&mut cursor, vs_size, record_stride, "vs_outputs")?;
        let counts_layout = place(&mut cursor, counts, COUNT_RECORD_STRIDE, "counts")?;
        let prefix_offsets = place(
            &mut cursor,
            prefixes,
            PREFIX_OFFSET_STRIDE,
            "prefix_offsets",
        )?;
        let gs_vertices = place(&mut cursor, vertices, record_stride, "gs_vertices")?;
        let gs_primitives = place(
            &mut cursor,
            primitives,
            GS_PRIMITIVE_RECORD_STRIDE,
            "gs_primitives",
        )?;
        let indirect_arguments = place(
            &mut cursor,
            indirect,
            INDEXED_INDIRECT_ARGUMENT_STRIDE,
            "indirect_arguments",
        )?;
        if cursor > input.device_buffer_limit {
            return Err(LayoutError::DeviceLimit {
                context: "geometry_record_buffers",
                requested: cursor,
                limit: input.device_buffer_limit,
            });
        }
        Ok(Self {
            vs_outputs,
            counts: counts_layout,
            prefix_offsets,
            gs_vertices,
            gs_primitives,
            indirect_arguments,
            total_size: cursor,
        })
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum LayoutError {
    Invalid {
        context: &'static str,
    },
    Overflow {
        context: &'static str,
    },
    DeviceLimit {
        context: &'static str,
        requested: u64,
        limit: u64,
    },
}

impl Display for LayoutError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Invalid { context } => write!(formatter, "invalid geometry layout: {context}"),
            Self::Overflow { context } => write!(formatter, "geometry layout overflow: {context}"),
            Self::DeviceLimit {
                context,
                requested,
                limit,
            } => {
                write!(
                    formatter,
                    "geometry layout device limit: {context} requested {requested}, limit {limit}"
                )
            }
        }
    }
}

pub fn exclusive_prefix(counts: &[u32]) -> Result<Vec<u32>, LayoutError> {
    let mut total = 0_u32;
    let mut result =
        Vec::with_capacity(counts.len().checked_add(1).ok_or(LayoutError::Overflow {
            context: "exclusive_prefix_count",
        })?);
    for count in counts {
        result.push(total);
        total = total.checked_add(*count).ok_or(LayoutError::Overflow {
            context: "exclusive_prefix",
        })?;
    }
    result.push(total);
    Ok(result)
}

fn align(value: u64) -> Result<u64, LayoutError> {
    let remainder = value % RECORD_ALIGNMENT;
    if remainder == 0 {
        return Ok(value);
    }
    value
        .checked_add(RECORD_ALIGNMENT - remainder)
        .ok_or(LayoutError::Overflow {
            context: "alignment",
        })
}

fn bytes(count: u64, stride: u64, context: &'static str) -> Result<u64, LayoutError> {
    count
        .checked_mul(stride)
        .ok_or(LayoutError::Overflow { context })
}

fn place(
    cursor: &mut u64,
    size: u64,
    stride: u64,
    context: &'static str,
) -> Result<BufferLayout, LayoutError> {
    *cursor = align(*cursor)?;
    let offset = *cursor;
    *cursor = cursor
        .checked_add(size)
        .ok_or(LayoutError::Overflow { context })?;
    Ok(BufferLayout {
        offset,
        size,
        stride,
    })
}

const _: () =
    assert!(size_of::<IndexedIndirectArguments>() == INDEXED_INDIRECT_ARGUMENT_STRIDE as usize);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn record_offsets_and_strides_are_stable() {
        let layout = GeometryRecordLayout::new(GeometryLayoutInput {
            input_primitives: 2,
            output_vertices: 3,
            output_primitives: 1,
            varying_stride: 8,
            device_buffer_limit: 4096,
        })
        .ok();
        assert_eq!(VsOutputRecord::layout().position_offset, 0);
        assert_eq!(VsOutputRecord::layout().varying_size_offset, 28);
        assert_eq!(layout.map(|value| value.vs_outputs.stride), Some(48));
        assert_eq!(layout.map(|value| value.counts.offset), Some(96));
        assert_eq!(layout.map(|value| value.prefix_offsets.offset), Some(128));
        assert_eq!(layout.map(|value| value.gs_vertices.offset), Some(144));
        assert_eq!(layout.map(|value| value.gs_primitives.offset), Some(288));
        assert_eq!(layout.map(|value| value.indirect_arguments.size), Some(32));
    }

    #[test]
    fn prefix_is_exclusive_and_stable() {
        assert_eq!(exclusive_prefix(&[2, 0, 3]).ok(), Some(vec![0, 2, 2, 5]));
    }

    #[test]
    fn zero_output_is_not_a_draw() {
        let zero = IndexedIndirectArguments::indexed(0, 0).ok();
        assert_eq!(zero.map(|value| value.draws()), Some(false));
        assert_eq!(zero.and_then(|value| value.validate().ok()), Some(()));
        assert!(
            IndexedIndirectArguments::indexed(4, 2)
                .ok()
                .map(|value| value.draws())
                .unwrap_or(false)
        );
    }

    #[test]
    fn limits_and_overflow_have_context() {
        let limited = GeometryRecordLayout::new(GeometryLayoutInput {
            input_primitives: 1,
            output_vertices: 1,
            output_primitives: 1,
            varying_stride: 0,
            device_buffer_limit: 1,
        });
        assert_eq!(
            limited,
            Err(LayoutError::DeviceLimit {
                context: "geometry_record_buffers",
                requested: 160,
                limit: 1,
            })
        );
        assert_eq!(
            exclusive_prefix(&[u32::MAX, 1]),
            Err(LayoutError::Overflow {
                context: "exclusive_prefix"
            })
        );
    }
}
