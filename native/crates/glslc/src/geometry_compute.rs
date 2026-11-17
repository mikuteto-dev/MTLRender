use crate::geometry::{GeometryPlan, GeometrySideEffects};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Access {
    Read,
    Write,
    ReadWrite,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum BufferKind {
    InputVertices,
    VertexRecords,
    PrimitiveRecords,
    OutputCounts,
    PrefixSums,
    OutputVertices,
    OutputIndices,
    IndirectArguments,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Buffer {
    pub kind: BufferKind,
    pub offset: u64,
    pub size: u64,
    pub stride: u32,
    pub access: Access,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum StageKind {
    VertexPrepass,
    OutputCount,
    PrefixSum,
    GeometryExpansion,
    IndirectDraw,
}

#[derive(Debug, Eq, PartialEq)]
pub struct Stage {
    pub kind: StageKind,
    pub reads: Vec<BufferKind>,
    pub writes: Vec<BufferKind>,
    pub side_effects: GeometrySideEffects,
}

#[derive(Debug, Eq, PartialEq)]
pub struct Context {
    pub pack: String,
    pub dimension: String,
    pub program: String,
    pub stage: String,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Input {
    pub primitive_count: u64,
    pub vertex_stride: u32,
    pub primitive_stride: u32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Limits {
    pub max_buffer_size: u64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct DrawArguments {
    pub index_count: u32,
    pub instance_count: u32,
    pub first_index: u32,
    pub base_vertex: i32,
    pub base_instance: u32,
}

#[derive(Debug, Eq, PartialEq)]
pub enum Error {
    EmptyContext,
    InvalidInput,
    Overflow,
    DeviceLimit,
    UnsupportedGeometry,
}

#[derive(Debug, Eq, PartialEq)]
pub struct GeometryComputePlan {
    pub context: Context,
    pub stages: Vec<Stage>,
    pub buffers: Vec<Buffer>,
    pub primitive_count: u64,
    pub max_vertices: u64,
    pub max_primitives: u64,
    pub allow_concurrent_compute: bool,
}

impl GeometryComputePlan {
    pub fn build(
        context: Context,
        geometry: &GeometryPlan,
        input: Input,
        limits: Limits,
    ) -> Result<Self, Error> {
        Self::build_with_concurrency(context, geometry, input, limits, false)
    }

    pub fn build_with_concurrency(
        context: Context,
        geometry: &GeometryPlan,
        input: Input,
        limits: Limits,
        allow_concurrent_compute: bool,
    ) -> Result<Self, Error> {
        if context.pack.is_empty()
            || context.dimension.is_empty()
            || context.program.is_empty()
            || context.stage.is_empty()
        {
            return Err(Error::EmptyContext);
        }
        if input.primitive_count == 0
            || input.vertex_stride == 0
            || input.primitive_stride == 0
            || limits.max_buffer_size == 0
            || geometry.max_vertices == 0
            || geometry.emitted_primitives == 0
        {
            return Err(Error::InvalidInput);
        }
        if geometry.kind != crate::geometry::GeometryKind::Compute {
            return Err(Error::UnsupportedGeometry);
        }
        let vertices = checked_mul(input.primitive_count, u64::from(geometry.emitted_vertices))?;
        let primitives = checked_mul(
            input.primitive_count,
            u64::from(geometry.emitted_primitives),
        )?;
        let input_size = checked_mul(input.primitive_count, u64::from(input.primitive_stride))?;
        let vertex_records = checked_mul(vertices, u64::from(input.vertex_stride))?;
        let primitive_records = checked_mul(primitives, u64::from(input.primitive_stride))?;
        let counts = checked_mul(input.primitive_count, 4)?;
        let prefix = checked_mul(input.primitive_count, 4)?;
        let output_vertices = checked_mul(vertices, u64::from(input.vertex_stride))?;
        let output_indices = checked_mul(primitives, 3 * 4)?;
        let indirect = 5 * 4;
        let sizes = [
            input_size,
            vertex_records,
            primitive_records,
            counts,
            prefix,
            output_vertices,
            output_indices,
            indirect,
        ];
        let total = sizes
            .iter()
            .try_fold(0_u64, |sum, size| sum.checked_add(*size))
            .ok_or(Error::Overflow)?;
        if total > limits.max_buffer_size || sizes.iter().any(|size| *size > limits.max_buffer_size)
        {
            return Err(Error::DeviceLimit);
        }
        let mut offset = 0_u64;
        let input_buffer = buffer(
            BufferKind::InputVertices,
            &mut offset,
            input_size,
            input.vertex_stride,
            Access::Read,
        )?;
        let vertex_buffer = buffer(
            BufferKind::VertexRecords,
            &mut offset,
            vertex_records,
            input.vertex_stride,
            Access::Write,
        )?;
        let primitive_buffer = buffer(
            BufferKind::PrimitiveRecords,
            &mut offset,
            primitive_records,
            input.primitive_stride,
            Access::Write,
        )?;
        let count_buffer = buffer(
            BufferKind::OutputCounts,
            &mut offset,
            counts,
            4,
            Access::Write,
        )?;
        let prefix_buffer = buffer(
            BufferKind::PrefixSums,
            &mut offset,
            prefix,
            4,
            Access::ReadWrite,
        )?;
        let output_vertex_buffer = buffer(
            BufferKind::OutputVertices,
            &mut offset,
            output_vertices,
            input.vertex_stride,
            Access::Write,
        )?;
        let output_index_buffer = buffer(
            BufferKind::OutputIndices,
            &mut offset,
            output_indices,
            4,
            Access::Write,
        )?;
        let indirect_buffer = buffer(
            BufferKind::IndirectArguments,
            &mut offset,
            indirect,
            4,
            Access::Write,
        )?;
        let effects = geometry.side_effects;
        let stages = vec![
            Stage {
                kind: StageKind::VertexPrepass,
                reads: vec![BufferKind::InputVertices],
                writes: vec![BufferKind::VertexRecords, BufferKind::PrimitiveRecords],
                side_effects: effects,
            },
            Stage {
                kind: StageKind::OutputCount,
                reads: vec![BufferKind::VertexRecords, BufferKind::PrimitiveRecords],
                writes: vec![BufferKind::OutputCounts],
                side_effects: effects,
            },
            Stage {
                kind: StageKind::PrefixSum,
                reads: vec![BufferKind::OutputCounts],
                writes: vec![BufferKind::PrefixSums],
                side_effects: GeometrySideEffects::default(),
            },
            Stage {
                kind: StageKind::GeometryExpansion,
                reads: vec![BufferKind::VertexRecords, BufferKind::PrefixSums],
                writes: vec![BufferKind::OutputVertices, BufferKind::OutputIndices],
                side_effects: effects,
            },
            Stage {
                kind: StageKind::IndirectDraw,
                reads: vec![BufferKind::PrefixSums, BufferKind::IndirectArguments],
                writes: vec![BufferKind::IndirectArguments],
                side_effects: GeometrySideEffects::default(),
            },
        ];
        Ok(Self {
            context,
            stages,
            buffers: vec![
                input_buffer,
                vertex_buffer,
                primitive_buffer,
                count_buffer,
                prefix_buffer,
                output_vertex_buffer,
                output_index_buffer,
                indirect_buffer,
            ],
            primitive_count: input.primitive_count,
            max_vertices: vertices,
            max_primitives: primitives,
            allow_concurrent_compute,
        })
    }

    pub fn draw_arguments(&self, vertices: u64, primitives: u64) -> Option<DrawArguments> {
        if vertices == 0 || primitives == 0 || vertices > u64::from(u32::MAX) {
            return None;
        }
        let index_count = primitives.checked_mul(3)?.checked_mul(1)?;
        if index_count > u64::from(u32::MAX) {
            return None;
        }
        Some(DrawArguments {
            index_count: u32::try_from(index_count).ok()?,
            instance_count: 1,
            first_index: 0,
            base_vertex: 0,
            base_instance: 0,
        })
    }
}

fn checked_mul(left: u64, right: u64) -> Result<u64, Error> {
    left.checked_mul(right).ok_or(Error::Overflow)
}

fn buffer(
    kind: BufferKind,
    offset: &mut u64,
    size: u64,
    stride: u32,
    access: Access,
) -> Result<Buffer, Error> {
    let current = *offset;
    *offset = (*offset).checked_add(size).ok_or(Error::Overflow)?;
    Ok(Buffer {
        kind,
        offset: current,
        size,
        stride,
        access,
    })
}
