use crate::geometry::GeometryPlan;
use crate::geometry_gs_stage::GeometryGsStage;
use crate::geometry_gs_topology::{TopologyMetadata, stable_output_range, topology_metadata};
use crate::geometry_records::{GeometryRecordLayout, PrimitiveBoundary};
use crate::transform::PrimitiveTopology;
use std::fmt::{Display, Formatter};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum GeometryGsAccessMode {
    Read,
    Write,
    ReadWrite,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum GeometryGsResourceKind {
    Image,
    Ssbo,
    Atomic,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct GeometryGsAccess {
    pub resource: GeometryGsResourceKind,
    pub binding: u32,
    pub mode: GeometryGsAccessMode,
    pub operation: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct GeometryGsContext {
    pub pack: String,
    pub dimension: String,
    pub program: String,
    pub source_stage: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct GeometryGsVaryingIdentity {
    pub name: String,
    pub location: u32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum GeometryGsPrimitiveId {
    Invocation,
    Preserved,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum GeometryGsLayer {
    Unwritten,
    Constant(i32),
    Invocation,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct GeometryGsRange {
    pub offset: u64,
    pub count: u64,
    pub byte_offset: u64,
    pub byte_size: u64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct GeometryGsCompaction {
    pub input_primitives: u64,
    pub output_vertices: u64,
    pub output_primitives: u64,
    pub prefix_offset: u64,
    pub prefix_complete: bool,
    pub output_vertex_range: GeometryGsRange,
    pub output_primitive_range: GeometryGsRange,
}

#[derive(Debug, Eq, PartialEq)]
pub struct GeometryGsInput {
    pub context: GeometryGsContext,
    pub plan: GeometryPlan,
    pub input_primitives: u64,
    pub invocation_index: u64,
    pub prefix_offset: u64,
    pub prefix_complete: bool,
    pub primitive_boundary: PrimitiveBoundary,
    pub varying: GeometryGsVaryingIdentity,
    pub varying_stride: u32,
    pub primitive_id: GeometryGsPrimitiveId,
    pub layer: GeometryGsLayer,
    pub output_stride: u32,
    pub record_layout: GeometryRecordLayout,
    pub accesses: Vec<GeometryGsAccess>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct GeometryGsMetadata {
    pub topology: TopologyMetadata,
    pub invocation_is_one_input_primitive: bool,
    pub input_topology: PrimitiveTopology,
    pub output_topology: PrimitiveTopology,
    pub primitive_boundary: PrimitiveBoundary,
    pub varying: GeometryGsVaryingIdentity,
    pub primitive_id: GeometryGsPrimitiveId,
    pub layer: GeometryGsLayer,
    pub output_stride: u32,
    pub compaction: GeometryGsCompaction,
    pub accesses: Vec<GeometryGsAccess>,
}

#[derive(Debug, Eq, PartialEq)]
pub struct GeometryGsOutput {
    pub metadata: GeometryGsMetadata,
    pub record_layout: GeometryRecordLayout,
    pub stage: GeometryGsStage,
}

#[derive(Debug, Eq, PartialEq)]
pub enum GeometryGsError {
    EmptyContext {
        context: GeometryGsContext,
    },
    Unsupported {
        context: GeometryGsContext,
        operation: String,
    },
    Invalid {
        context: GeometryGsContext,
        operation: String,
    },
    Overflow {
        context: GeometryGsContext,
        operation: String,
    },
}

impl Display for GeometryGsError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::EmptyContext { .. } => formatter.write_str("geometry GS context is incomplete"),
            Self::Unsupported { context, operation } => write!(
                formatter,
                "{}:{}:{}:{}: geometry GS operation is unsupported: {}",
                context.pack, context.dimension, context.program, context.source_stage, operation
            ),
            Self::Invalid { context, operation } => write!(
                formatter,
                "{}:{}:{}:{}: invalid geometry GS contract: {}",
                context.pack, context.dimension, context.program, context.source_stage, operation
            ),
            Self::Overflow { context, operation } => write!(
                formatter,
                "{}:{}:{}:{}: geometry GS contract overflow: {}",
                context.pack, context.dimension, context.program, context.source_stage, operation
            ),
        }
    }
}

pub fn validate_geometry_gs(input: &GeometryGsInput) -> Result<(), GeometryGsError> {
    let context = &input.context;
    if context.pack.is_empty()
        || context.dimension.is_empty()
        || context.program.is_empty()
        || context.source_stage.is_empty()
    {
        return Err(GeometryGsError::EmptyContext {
            context: copy_context(context),
        });
    }
    if input.plan.kind != crate::geometry::GeometryKind::Compute
        && !(input.plan.kind == crate::geometry::GeometryKind::BoundedMesh
            && input.plan.side_effects != crate::geometry::GeometrySideEffects::default())
    {
        return Err(unsupported(input, "geometry plan is not compute emulation"));
    }
    if topology(&input.plan.input_topology).is_none() {
        return Err(unsupported(input, "input topology is not supported"));
    }
    if topology(&input.plan.output_topology).is_none() {
        return Err(unsupported(input, "output topology is not supported"));
    }
    if input.input_primitives == 0 {
        return Err(invalid(input, "input primitive count is zero"));
    }
    if !input.prefix_complete {
        return Err(unsupported(
            input,
            "prefix completion is required before record writes",
        ));
    }
    if input.varying_stride != 0 && input.varying.name.is_empty() {
        return Err(invalid(input, "varying payload identity is empty"));
    }
    if input.output_stride == 0
        || u64::from(input.output_stride) != input.record_layout.gs_vertices.stride
    {
        return Err(invalid(
            input,
            "output stride does not match the record layout",
        ));
    }
    if input.record_layout.prefix_offsets.stride != 4 {
        return Err(invalid(input, "prefix offset stride is not four bytes"));
    }
    if input.record_layout.gs_primitives.stride != 32 {
        return Err(invalid(input, "primitive record stride is not stable"));
    }
    let expected_vertices = u64::from(input.plan.emitted_vertices);
    let expected_primitives = u64::from(input.plan.emitted_primitives);
    let input_topology = topology(&input.plan.input_topology)
        .ok_or_else(|| unsupported(input, "input topology is not supported"))?;
    let output_topology = topology(&input.plan.output_topology)
        .ok_or_else(|| unsupported(input, "output topology is not supported"))?;
    let _topology = topology_metadata(
        input_topology,
        output_topology,
        &input.record_layout,
        input.varying_stride,
    )
    .map_err(|error| invalid(input, &topology_error_message(error)))?;
    if input.plan.max_vertices < input.plan.emitted_vertices {
        return Err(invalid(input, "emitted vertices exceed the declared bound"));
    }
    let Some(output_vertices) = input.input_primitives.checked_mul(expected_vertices) else {
        return Err(overflow(input, "output vertex range"));
    };
    let Some(output_primitives) = input.input_primitives.checked_mul(expected_primitives) else {
        return Err(overflow(input, "output primitive range"));
    };
    let Some(vertex_end) = input.prefix_offset.checked_add(expected_vertices) else {
        return Err(overflow(input, "vertex prefix range"));
    };
    let Some(primitive_end) = input.prefix_offset.checked_add(expected_primitives) else {
        return Err(overflow(input, "primitive prefix range"));
    };
    if vertex_end > output_vertices || primitive_end > output_primitives {
        return Err(invalid(
            input,
            "exclusive prefix offset is outside the output range",
        ));
    }
    if input.invocation_index >= input.input_primitives {
        return Err(invalid(
            input,
            "invocation identity is outside the input primitive range",
        ));
    }
    let vertex_bytes = output_vertices
        .checked_mul(input.record_layout.gs_vertices.stride)
        .ok_or_else(|| overflow(input, "vertex record byte range"))?;
    if input.record_layout.gs_vertices.size < vertex_bytes {
        return Err(invalid(input, "vertex record range exceeds its allocation"));
    }
    let primitive_bytes = output_primitives
        .checked_mul(input.record_layout.gs_primitives.stride)
        .ok_or_else(|| overflow(input, "primitive record byte range"))?;
    if input.record_layout.gs_primitives.size < primitive_bytes {
        return Err(invalid(
            input,
            "primitive record range exceeds its allocation",
        ));
    }
    if input.plan.writes_layer && matches!(input.layer, GeometryGsLayer::Unwritten) {
        return Err(invalid(input, "plan writes a layer without layer metadata"));
    }
    if !input.plan.writes_layer && !matches!(input.layer, GeometryGsLayer::Unwritten) {
        return Err(invalid(
            input,
            "layer metadata is present but the plan does not write it",
        ));
    }
    for access in &input.accesses {
        if access.operation.is_empty() {
            return Err(invalid(input, "resource access operation is empty"));
        }
        if matches!(access.resource, GeometryGsResourceKind::Atomic)
            && matches!(access.mode, GeometryGsAccessMode::Write)
        {
            return Err(unsupported(input, "atomic write-only access"));
        }
        if matches!(access.resource, GeometryGsResourceKind::Image)
            && input.plan.side_effects.image_writes
            && matches!(access.mode, GeometryGsAccessMode::Read)
        {
            return Err(invalid(
                input,
                "image write plan has read-only image metadata",
            ));
        }
    }
    let has_image = input
        .accesses
        .iter()
        .any(|access| access.resource == GeometryGsResourceKind::Image);
    let has_ssbo = input
        .accesses
        .iter()
        .any(|access| access.resource == GeometryGsResourceKind::Ssbo);
    let has_atomic = input
        .accesses
        .iter()
        .any(|access| access.resource == GeometryGsResourceKind::Atomic);
    if has_image != input.plan.side_effects.image_writes
        || has_ssbo != input.plan.side_effects.ssbo_writes
        || has_atomic != input.plan.side_effects.atomics
    {
        return Err(invalid(
            input,
            "resource access metadata does not match geometry side effects",
        ));
    }
    Ok(())
}

pub fn build_geometry_output_metadata(
    input: &GeometryGsInput,
) -> Result<GeometryGsMetadata, GeometryGsError> {
    let input_topology = topology(&input.plan.input_topology)
        .ok_or_else(|| unsupported(input, "input topology is not supported"))?;
    let output_topology = topology(&input.plan.output_topology)
        .ok_or_else(|| unsupported(input, "output topology is not supported"))?;
    let varying_stride = u32::try_from(
        input
            .record_layout
            .gs_vertices
            .stride
            .checked_sub(32)
            .ok_or_else(|| invalid(input, "vertex record stride is smaller than its header"))?,
    )
    .map_err(|_| overflow(input, "varying stride"))?;
    if varying_stride != input.varying_stride {
        return Err(invalid(
            input,
            "varying stride does not match the record layout",
        ));
    }
    let topology = topology_metadata(
        input_topology,
        output_topology,
        &input.record_layout,
        input.varying_stride,
    )
    .map_err(|error| invalid(input, &topology_error_message(error)))?;
    let compaction = build_compaction_metadata_with_topology(input, topology)?;
    Ok(GeometryGsMetadata {
        topology,
        invocation_is_one_input_primitive: true,
        input_topology,
        output_topology,
        primitive_boundary: input.primitive_boundary,
        varying: GeometryGsVaryingIdentity {
            name: String::from(input.varying.name.as_str()),
            location: input.varying.location,
        },
        primitive_id: input.primitive_id,
        layer: input.layer,
        output_stride: input.output_stride,
        compaction,
        accesses: input.accesses.iter().map(copy_access).collect(),
    })
}

pub fn build_compaction_metadata(
    input: &GeometryGsInput,
) -> Result<GeometryGsCompaction, GeometryGsError> {
    let input_topology = topology(&input.plan.input_topology)
        .ok_or_else(|| unsupported(input, "input topology is not supported"))?;
    let output_topology = topology(&input.plan.output_topology)
        .ok_or_else(|| unsupported(input, "output topology is not supported"))?;
    let metadata = topology_metadata(
        input_topology,
        output_topology,
        &input.record_layout,
        input.varying_stride,
    )
    .map_err(|error| invalid(input, &topology_error_message(error)))?;
    build_compaction_metadata_with_topology(input, metadata)
}

fn build_compaction_metadata_with_topology(
    input: &GeometryGsInput,
    topology: TopologyMetadata,
) -> Result<GeometryGsCompaction, GeometryGsError> {
    let output_vertices = input
        .input_primitives
        .checked_mul(u64::from(input.plan.emitted_vertices))
        .ok_or_else(|| overflow(input, "output vertex range"))?;
    let output_primitives = input
        .input_primitives
        .checked_mul(u64::from(input.plan.emitted_primitives))
        .ok_or_else(|| overflow(input, "output primitive range"))?;
    let ranges = stable_output_range(
        topology,
        input.prefix_offset,
        output_primitives,
        output_vertices,
        input.invocation_index,
        u64::from(input.plan.emitted_vertices),
        u64::from(input.plan.emitted_primitives),
    )
    .map_err(|error| invalid(input, &topology_error_message(error)))?;
    Ok(GeometryGsCompaction {
        input_primitives: input.input_primitives,
        output_vertices,
        output_primitives,
        prefix_offset: input.prefix_offset,
        prefix_complete: input.prefix_complete,
        output_vertex_range: GeometryGsRange {
            offset: ranges.0.offset,
            count: ranges.0.count,
            byte_offset: ranges.0.byte_offset,
            byte_size: ranges.0.byte_size,
        },
        output_primitive_range: GeometryGsRange {
            offset: ranges.1.offset,
            count: ranges.1.count,
            byte_offset: ranges.1.byte_offset,
            byte_size: ranges.1.byte_size,
        },
    })
}

pub fn validate_geometry_candidate(candidate: &GeometryGsOutput) -> Result<(), GeometryGsError> {
    let metadata = &candidate.metadata;
    if !metadata.compaction.prefix_complete {
        return Err(GeometryGsError::Invalid {
            context: GeometryGsContext {
                pack: String::from("geometry"),
                dimension: String::from("unknown"),
                program: String::from("unknown"),
                source_stage: String::from("geometry"),
            },
            operation: String::from("candidate writes require completed prefix metadata"),
        });
    }
    if candidate.stage.metadata.entry_point != crate::geometry_gs_stage::ENTRY_POINT {
        return Err(GeometryGsError::Invalid {
            context: GeometryGsContext {
                pack: String::from("geometry"),
                dimension: String::from("unknown"),
                program: String::from("unknown"),
                source_stage: String::from("geometry"),
            },
            operation: String::from(
                "candidate entry point is not the geometry compute entry point",
            ),
        });
    }
    let Ok(record_stride) = u32::try_from(candidate.record_layout.gs_vertices.stride) else {
        return Err(GeometryGsError::Overflow {
            context: candidate_context(),
            operation: String::from("candidate output stride"),
        });
    };
    if candidate.stage.metadata.output_stride != record_stride {
        return Err(GeometryGsError::Invalid {
            context: GeometryGsContext {
                pack: String::from("geometry"),
                dimension: String::from("unknown"),
                program: String::from("unknown"),
                source_stage: String::from("geometry"),
            },
            operation: String::from("candidate output stride does not match records"),
        });
    }
    if metadata.topology.vertex_stride != candidate.record_layout.gs_vertices.stride
        || metadata.topology.primitive_stride != candidate.record_layout.gs_primitives.stride
        || metadata.topology.varying_stride
            != u32::try_from(
                candidate
                    .record_layout
                    .gs_vertices
                    .stride
                    .saturating_sub(32),
            )
            .unwrap_or(u32::MAX)
    {
        return Err(GeometryGsError::Invalid {
            context: candidate_context(),
            operation: String::from("candidate topology metadata does not match records"),
        });
    }
    let vertex_range = &metadata.compaction.output_vertex_range;
    let primitive_range = &metadata.compaction.output_primitive_range;
    let vertex_bytes = vertex_range
        .count
        .checked_mul(candidate.record_layout.gs_vertices.stride)
        .ok_or_else(|| GeometryGsError::Overflow {
            context: candidate_context(),
            operation: String::from("candidate vertex byte range"),
        })?;
    let primitive_bytes = primitive_range
        .count
        .checked_mul(candidate.record_layout.gs_primitives.stride)
        .ok_or_else(|| GeometryGsError::Overflow {
            context: candidate_context(),
            operation: String::from("candidate primitive byte range"),
        })?;
    if vertex_range.byte_size != vertex_bytes
        || primitive_range.byte_size != primitive_bytes
        || vertex_range.byte_offset
            != vertex_range
                .offset
                .checked_mul(candidate.record_layout.gs_vertices.stride)
                .ok_or_else(|| GeometryGsError::Overflow {
                    context: candidate_context(),
                    operation: String::from("candidate vertex byte offset"),
                })?
        || primitive_range.byte_offset
            != primitive_range
                .offset
                .checked_mul(candidate.record_layout.gs_primitives.stride)
                .ok_or_else(|| GeometryGsError::Overflow {
                    context: candidate_context(),
                    operation: String::from("candidate primitive byte offset"),
                })?
    {
        return Err(GeometryGsError::Invalid {
            context: candidate_context(),
            operation: String::from("candidate byte ranges do not match records"),
        });
    }
    if (metadata.compaction.output_vertices == 0 || metadata.compaction.output_primitives == 0)
        && (vertex_range.count != 0
            || primitive_range.count != 0
            || candidate.stage.msl.contains("output_vertices[prefix_begin"))
    {
        return Err(GeometryGsError::Invalid {
            context: candidate_context(),
            operation: String::from("zero-output candidate contains output records"),
        });
    }
    Ok(())
}

pub fn lower_geometry_gs(input: GeometryGsInput) -> Result<GeometryGsOutput, GeometryGsError> {
    validate_geometry_gs(&input)?;
    let metadata = build_geometry_output_metadata(&input)?;
    let stage =
        crate::geometry_gs_stage::lower_geometry_invocation(&metadata, &input.record_layout);
    let output = GeometryGsOutput {
        metadata,
        record_layout: input.record_layout,
        stage,
    };
    validate_geometry_candidate(&output)?;
    Ok(output)
}

fn invalid(input: &GeometryGsInput, operation: &str) -> GeometryGsError {
    GeometryGsError::Invalid {
        context: copy_context(&input.context),
        operation: String::from(operation),
    }
}

fn unsupported(input: &GeometryGsInput, operation: &str) -> GeometryGsError {
    GeometryGsError::Unsupported {
        context: copy_context(&input.context),
        operation: String::from(operation),
    }
}

fn overflow(input: &GeometryGsInput, operation: &str) -> GeometryGsError {
    GeometryGsError::Overflow {
        context: copy_context(&input.context),
        operation: String::from(operation),
    }
}

fn candidate_context() -> GeometryGsContext {
    GeometryGsContext {
        pack: String::from("geometry"),
        dimension: String::from("unknown"),
        program: String::from("unknown"),
        source_stage: String::from("geometry"),
    }
}

fn copy_access(access: &GeometryGsAccess) -> GeometryGsAccess {
    GeometryGsAccess {
        resource: access.resource,
        binding: access.binding,
        mode: access.mode,
        operation: String::from(access.operation.as_str()),
    }
}

fn copy_context(context: &GeometryGsContext) -> GeometryGsContext {
    GeometryGsContext {
        pack: String::from(context.pack.as_str()),
        dimension: String::from(context.dimension.as_str()),
        program: String::from(context.program.as_str()),
        source_stage: String::from(context.source_stage.as_str()),
    }
}

fn topology_error_message(error: crate::geometry_gs_topology::TopologyError) -> String {
    match error {
        crate::geometry_gs_topology::TopologyError::Unsupported(value) => String::from(value),
        crate::geometry_gs_topology::TopologyError::Invalid(value) => String::from(value),
        crate::geometry_gs_topology::TopologyError::Overflow(value) => String::from(value),
    }
}

fn topology(value: &str) -> Option<PrimitiveTopology> {
    match value {
        "points" => Some(PrimitiveTopology::Points),
        "lines" => Some(PrimitiveTopology::Lines),
        "line_strip" => Some(PrimitiveTopology::LineStrip),
        "triangles" => Some(PrimitiveTopology::Triangles),
        "triangle_strip" => Some(PrimitiveTopology::TriangleStrip),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::geometry::{GeometryKind, GeometrySideEffects};
    use crate::geometry_records::GeometryLayoutInput;

    fn input(
        kind: GeometryKind,
        input_primitives: u64,
        vertices: u32,
        primitives: u32,
    ) -> Option<GeometryGsInput> {
        let record_layout = GeometryRecordLayout::new(GeometryLayoutInput {
            input_primitives,
            output_vertices: input_primitives.checked_mul(u64::from(vertices))?,
            output_primitives: input_primitives.checked_mul(u64::from(primitives))?,
            varying_stride: 16,
            device_buffer_limit: 4096,
        })
        .ok()?;
        Some(GeometryGsInput {
            context: GeometryGsContext {
                pack: String::from("pack"),
                dimension: String::from("world"),
                program: String::from("program"),
                source_stage: String::from("geometry"),
            },
            plan: GeometryPlan {
                kind,
                reason: String::from("test"),
                input_topology: String::from("triangles"),
                output_topology: String::from("triangles"),
                max_vertices: vertices,
                emitted_vertices: vertices,
                emitted_primitives: primitives,
                writes_layer: false,
                dynamic_control_flow: false,
                side_effects: GeometrySideEffects::default(),
                layer_mapping: Vec::new(),
            },
            input_primitives,
            invocation_index: 0,
            prefix_offset: 0,
            prefix_complete: true,
            primitive_boundary: PrimitiveBoundary::None,
            varying: GeometryGsVaryingIdentity {
                name: String::from("payload"),
                location: 0,
            },
            varying_stride: 16,
            primitive_id: GeometryGsPrimitiveId::Invocation,
            layer: GeometryGsLayer::Unwritten,
            output_stride: u32::try_from(record_layout.gs_vertices.stride).ok()?,
            record_layout,
            accesses: Vec::new(),
        })
    }

    #[test]
    fn valid_contract_exposes_one_invocation_and_ranges() {
        let Some(contract) = input(GeometryKind::Compute, 1, 3, 1) else {
            return;
        };
        assert!(validate_geometry_gs(&contract).is_ok());
        let output = lower_geometry_gs(contract).ok();
        assert_eq!(
            output.map(|value| value.metadata.compaction.output_vertices),
            Some(3)
        );
    }

    #[test]
    fn supported_topologies_and_metadata_are_retained() {
        for name in ["points", "lines", "triangles"] {
            let Some(mut contract) = input(GeometryKind::Compute, 2, 3, 1) else {
                return;
            };
            contract.plan.input_topology = String::from(name);
            contract.plan.output_topology = String::from(name);
            contract.plan.writes_layer = true;
            contract.layer = GeometryGsLayer::Constant(2);
            contract.primitive_id = GeometryGsPrimitiveId::Preserved;
            let output = lower_geometry_gs(contract).ok();
            assert!(output.is_some());
        }
    }

    #[test]
    fn malformed_plan_is_rejected_with_context() {
        let Some(contract) = input(GeometryKind::BoundedMesh, 1, 3, 1) else {
            return;
        };
        let error = validate_geometry_gs(&contract).err();
        assert!(matches!(error, Some(GeometryGsError::Unsupported { .. })));
        let message = match error {
            Some(value) => value.to_string(),
            None => String::new(),
        };
        assert!(message.contains("pack"));
    }

    #[test]
    fn incomplete_prefix_is_rejected_before_lowering() {
        let Some(mut contract) = input(GeometryKind::Compute, 1, 3, 1) else {
            return;
        };
        contract.prefix_complete = false;
        assert!(matches!(
            lower_geometry_gs(contract),
            Err(GeometryGsError::Unsupported { .. })
        ));
    }

    #[test]
    fn zero_output_is_an_empty_range() {
        let Some(contract) = input(GeometryKind::Compute, 1, 0, 0) else {
            return;
        };
        let output = lower_geometry_gs(contract).ok();
        assert_eq!(
            output
                .as_ref()
                .map(|value| value.metadata.compaction.output_vertices),
            Some(0)
        );
        assert_eq!(
            output
                .as_ref()
                .map(|value| value.metadata.compaction.output_primitives),
            Some(0)
        );
    }

    #[test]
    fn side_effect_accesses_are_representable() {
        let Some(mut contract) = input(GeometryKind::Compute, 1, 3, 1) else {
            return;
        };
        contract.plan.side_effects = GeometrySideEffects {
            image_writes: true,
            ssbo_writes: true,
            atomics: true,
        };
        contract.accesses = vec![
            GeometryGsAccess {
                resource: GeometryGsResourceKind::Image,
                binding: 1,
                mode: GeometryGsAccessMode::ReadWrite,
                operation: String::from("imageStore"),
            },
            GeometryGsAccess {
                resource: GeometryGsResourceKind::Ssbo,
                binding: 2,
                mode: GeometryGsAccessMode::Write,
                operation: String::from("bufferStore"),
            },
            GeometryGsAccess {
                resource: GeometryGsResourceKind::Atomic,
                binding: 3,
                mode: GeometryGsAccessMode::ReadWrite,
                operation: String::from("atomicAdd"),
            },
        ];
        assert!(validate_geometry_gs(&contract).is_ok());
    }
}
