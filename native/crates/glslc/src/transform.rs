use crate::{
    CompileError, CompileErrors, Location, Options, Stage,
    lex::Span,
    parse::{Ast, Declaration, Expression, Qualifier, Statement, TypeSpecifier},
    sema::TypedModule,
};
use std::collections::BTreeMap;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PrimitiveTopology {
    Points,
    Lines,
    LineStrip,
    Triangles,
    TriangleStrip,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Default)]
pub struct SideEffects {
    pub image_writes: bool,
    pub storage_writes: bool,
    pub atomics: bool,
}

impl SideEffects {
    fn is_empty(self) -> bool {
        !self.image_writes && !self.storage_writes && !self.atomics
    }
}

#[derive(Debug, Eq, PartialEq)]
pub struct GeometryInput {
    pub input_topology: PrimitiveTopology,
    pub output_topology: PrimitiveTopology,
    pub max_vertices: u32,
    pub emitted_vertices: u32,
    pub emitted_primitives: u32,
    pub writes_layer: bool,
    pub uses_invocation_id: bool,
    pub side_effects: SideEffects,
    pub compute_compatible: bool,
    pub location: Location,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TessellationDomain {
    Triangles,
    Quads,
    Isolines,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TessellationSpacing {
    Equal,
    FractionalOdd,
    FractionalEven,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TriangleFactorEdge {
    OppositeControlPoint0,
    OppositeControlPoint1,
    OppositeControlPoint2,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TriangleCoordinateOrder {
    BarycentricRows,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TrianglePrimitiveOrder {
    CounterClockwiseRows,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TriangleInvocationOrder {
    CoordinateOrder,
}

#[derive(Debug, Eq, PartialEq)]
pub struct TriangleSubdivision {
    pub factor_edges: [TriangleFactorEdge; 3],
    pub spacing: TessellationSpacing,
    pub coordinate_order: TriangleCoordinateOrder,
    pub primitive_order: TrianglePrimitiveOrder,
    pub invocation_order: TriangleInvocationOrder,
    pub tes_output_order: Vec<u32>,
    pub maximum_vertices: u32,
    pub maximum_primitives: u32,
    pub maximum_invocations: u32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuadFactorEdge {
    U0,
    V0,
    U1,
    V1,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuadCoordinateOrder {
    UvRows,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuadPrimitiveOrder {
    CounterClockwiseRows,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuadInvocationOrder {
    CoordinateOrder,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct QuadCoordinate {
    pub u: u32,
    pub v: u32,
}

#[derive(Debug, Eq, PartialEq)]
pub struct QuadSubdivision {
    pub factor_edges: [QuadFactorEdge; 4],
    pub outer_factors: [u32; 4],
    pub inner_factors: [u32; 2],
    pub spacing: TessellationSpacing,
    pub coordinate_order: QuadCoordinateOrder,
    pub coordinates: Vec<QuadCoordinate>,
    pub primitives: Vec<[u32; 3]>,
    pub primitive_order: QuadPrimitiveOrder,
    pub invocation_order: QuadInvocationOrder,
    pub generated_invocation_ids: Vec<u32>,
    pub tes_output_order: Vec<u32>,
    pub maximum_vertices: u32,
    pub maximum_primitives: u32,
    pub maximum_invocations: u32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum IsolineFactorEdge {
    LineCount,
    SegmentCount,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum IsolineCoordinateOrder {
    SegmentRows,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum IsolinePrimitiveOrder {
    LineRows,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum IsolineInvocationOrder {
    CoordinateOrder,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct IsolineCoordinate {
    pub u: u32,
    pub v: u32,
}

#[derive(Debug, Eq, PartialEq)]
pub struct IsolineSubdivision {
    pub factor_edges: [IsolineFactorEdge; 2],
    pub outer_factors: [u32; 2],
    pub spacing: TessellationSpacing,
    pub coordinate_order: IsolineCoordinateOrder,
    pub coordinates: Vec<IsolineCoordinate>,
    pub primitives: Vec<[u32; 2]>,
    pub primitive_order: IsolinePrimitiveOrder,
    pub invocation_order: IsolineInvocationOrder,
    pub generated_invocation_ids: Vec<u32>,
    pub tes_output_order: Vec<u32>,
    pub maximum_vertices: u32,
    pub maximum_primitives: u32,
    pub maximum_invocations: u32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PatchOrder {
    CounterClockwise,
    Clockwise,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PatchPrimitiveOrder {
    SourcePatchThenPrimitive,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum InvocationIdentity {
    TcsInvocation,
    GeneratedTessellator,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TessellationFactors {
    Constant,
    Dynamic,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct FactorRange {
    pub minimum: u32,
    pub maximum: u32,
}

#[derive(Debug, Eq, PartialEq)]
pub struct PatchControlPoints {
    pub input_count: u32,
    pub output_count: u32,
    pub input_order: Vec<u32>,
    pub output_order: Vec<u32>,
}

#[derive(Debug, Eq, PartialEq)]
pub struct TessellationContract {
    pub domain: TessellationDomain,
    pub spacing: TessellationSpacing,
    pub patch_order: PatchOrder,
    pub primitive_order: PatchPrimitiveOrder,
    pub patch: PatchControlPoints,
    pub outer_factor_range: FactorRange,
    pub inner_factor_range: Option<FactorRange>,
    pub tcs_invocation: InvocationIdentity,
    pub generated_invocation: InvocationIdentity,
    pub domain_coordinates: u32,
    pub tes_outputs: Vec<u32>,
    pub side_effects: SideEffects,
    pub location: Location,
}

#[derive(Debug, Eq, PartialEq)]
pub struct TessellationInput {
    pub contract: TessellationContract,
    pub factors: TessellationFactors,
    pub uses_invocation_id: bool,
    pub location: Location,
}

#[derive(Debug, Eq, PartialEq)]
pub struct StageEmulationInput {
    pub stage: Stage,
    pub geometry: Option<GeometryInput>,
    pub tessellation: Option<TessellationInput>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum LoweringFamily {
    DedicatedPattern,
    MeshCompatible,
    ComputeEmulation,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Default)]
pub struct StageCapabilities {
    pub layer_output: bool,
    pub invocation_id: bool,
    pub side_effects: bool,
    pub atomics: bool,
    pub tessellation: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum HelperEntryPoint {
    GeometryMesh,
    TessellationControl,
    TessellationEvaluation,
    ComputeEmulation,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum GeometryHelperKind {
    Vertex,
    Geometry,
    Count,
    Prefix,
    Offset,
    Emission,
    Indirect,
    Barrier,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct GeometryHelper {
    pub name: &'static str,
    pub kind: GeometryHelperKind,
    pub entry_point: bool,
    pub local_size: [u32; 3],
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum GeometryResourceKind {
    InputPositions,
    VertexRecords,
    PrimitiveRecords,
    VertexPrefixOffsets,
    PrimitivePrefixOffsets,
    InvocationCount,
    VertexCapacity,
    PrimitiveCapacity,
    SideEffectStorage,
    SideEffectAtomicStorage,
    VertexCounts,
    VertexOffsets,
    IndirectArguments,
    DrawCount,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct GeometryResource {
    pub name: &'static str,
    pub kind: GeometryResourceKind,
    pub binding: u32,
    pub writable: bool,
}

pub const GEOMETRY_HELPERS: &[GeometryHelper] = &[
    GeometryHelper {
        name: "mtlr_geometry_vertex_stage",
        kind: GeometryHelperKind::Vertex,
        entry_point: true,
        local_size: [1, 1, 1],
    },
    GeometryHelper {
        name: "mtlr_geometry_stage",
        kind: GeometryHelperKind::Geometry,
        entry_point: true,
        local_size: [1, 1, 1],
    },
    GeometryHelper {
        name: "mtlr_geometry_indirect_stage",
        kind: GeometryHelperKind::Indirect,
        entry_point: true,
        local_size: [1, 1, 1],
    },
    GeometryHelper {
        name: "mtlr_geometry_counts",
        kind: GeometryHelperKind::Count,
        entry_point: false,
        local_size: [1, 1, 1],
    },
    GeometryHelper {
        name: "mtlr_geometry_group_prefix",
        kind: GeometryHelperKind::Prefix,
        entry_point: false,
        local_size: [1, 1, 1],
    },
    GeometryHelper {
        name: "mtlr_geometry_global_prefix",
        kind: GeometryHelperKind::Prefix,
        entry_point: false,
        local_size: [1, 1, 1],
    },
    GeometryHelper {
        name: "mtlr_geometry_output_offset",
        kind: GeometryHelperKind::Offset,
        entry_point: false,
        local_size: [1, 1, 1],
    },
    GeometryHelper {
        name: "mtlr_geometry_record_offset",
        kind: GeometryHelperKind::Offset,
        entry_point: false,
        local_size: [1, 1, 1],
    },
    GeometryHelper {
        name: "mtlr_geometry_capacity_ok",
        kind: GeometryHelperKind::Barrier,
        entry_point: false,
        local_size: [1, 1, 1],
    },
    GeometryHelper {
        name: "mtlr_geometry_input_index",
        kind: GeometryHelperKind::Offset,
        entry_point: false,
        local_size: [1, 1, 1],
    },
    GeometryHelper {
        name: "mtlr_geometry_output_indices",
        kind: GeometryHelperKind::Offset,
        entry_point: false,
        local_size: [1, 1, 1],
    },
    GeometryHelper {
        name: "mtlr_geometry_write_vertex",
        kind: GeometryHelperKind::Emission,
        entry_point: false,
        local_size: [1, 1, 1],
    },
    GeometryHelper {
        name: "mtlr_geometry_write_primitive",
        kind: GeometryHelperKind::Emission,
        entry_point: false,
        local_size: [1, 1, 1],
    },
    GeometryHelper {
        name: "mtlr_geometry_emit_primitive",
        kind: GeometryHelperKind::Emission,
        entry_point: false,
        local_size: [1, 1, 1],
    },
    GeometryHelper {
        name: "mtlr_geometry_indirect",
        kind: GeometryHelperKind::Indirect,
        entry_point: false,
        local_size: [1, 1, 1],
    },
    GeometryHelper {
        name: "mtlr_geometry_producer_consumer_barrier",
        kind: GeometryHelperKind::Barrier,
        entry_point: false,
        local_size: [1, 1, 1],
    },
    GeometryHelper {
        name: "mtlr_geometry_side_effect_barrier",
        kind: GeometryHelperKind::Barrier,
        entry_point: false,
        local_size: [1, 1, 1],
    },
];

pub const GEOMETRY_RESOURCES: &[GeometryResource] = &[
    GeometryResource {
        name: "input_positions",
        kind: GeometryResourceKind::InputPositions,
        binding: 0,
        writable: false,
    },
    GeometryResource {
        name: "vertex_records",
        kind: GeometryResourceKind::VertexRecords,
        binding: 1,
        writable: true,
    },
    GeometryResource {
        name: "input_count",
        kind: GeometryResourceKind::InvocationCount,
        binding: 2,
        writable: false,
    },
    GeometryResource {
        name: "vertex_capacity",
        kind: GeometryResourceKind::VertexCapacity,
        binding: 3,
        writable: false,
    },
    GeometryResource {
        name: "vertex_records",
        kind: GeometryResourceKind::VertexRecords,
        binding: 0,
        writable: false,
    },
    GeometryResource {
        name: "output_vertices",
        kind: GeometryResourceKind::VertexRecords,
        binding: 1,
        writable: true,
    },
    GeometryResource {
        name: "output_primitives",
        kind: GeometryResourceKind::PrimitiveRecords,
        binding: 2,
        writable: true,
    },
    GeometryResource {
        name: "vertex_prefix_offsets",
        kind: GeometryResourceKind::VertexPrefixOffsets,
        binding: 3,
        writable: false,
    },
    GeometryResource {
        name: "primitive_prefix_offsets",
        kind: GeometryResourceKind::PrimitivePrefixOffsets,
        binding: 4,
        writable: false,
    },
    GeometryResource {
        name: "invocation_count",
        kind: GeometryResourceKind::InvocationCount,
        binding: 5,
        writable: false,
    },
    GeometryResource {
        name: "vertex_capacity",
        kind: GeometryResourceKind::VertexCapacity,
        binding: 6,
        writable: false,
    },
    GeometryResource {
        name: "primitive_capacity",
        kind: GeometryResourceKind::PrimitiveCapacity,
        binding: 7,
        writable: false,
    },
    GeometryResource {
        name: "side_effect_storage",
        kind: GeometryResourceKind::SideEffectStorage,
        binding: 8,
        writable: true,
    },
    GeometryResource {
        name: "side_effect_atomic_storage",
        kind: GeometryResourceKind::SideEffectAtomicStorage,
        binding: 9,
        writable: true,
    },
    GeometryResource {
        name: "vertex_counts",
        kind: GeometryResourceKind::VertexCounts,
        binding: 0,
        writable: false,
    },
    GeometryResource {
        name: "vertex_offsets",
        kind: GeometryResourceKind::VertexOffsets,
        binding: 1,
        writable: false,
    },
    GeometryResource {
        name: "arguments",
        kind: GeometryResourceKind::IndirectArguments,
        binding: 2,
        writable: true,
    },
    GeometryResource {
        name: "draw_count",
        kind: GeometryResourceKind::DrawCount,
        binding: 3,
        writable: false,
    },
];

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct MeshLimits {
    pub max_vertices: u32,
    pub max_primitives: u32,
}

impl Default for MeshLimits {
    fn default() -> Self {
        Self {
            max_vertices: 256,
            max_primitives: 512,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CountProduction {
    VertexAndPrimitive,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PrefixSumKind {
    Group,
    Global,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum OutputOffsetKind {
    SourcePrimitiveOrder,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SideEffectOrdering {
    SourceInvocationSerial,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Default)]
pub struct StorageCapabilities {
    pub storage_writes: bool,
    pub storage_atomics: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AtomicOperation {
    Add,
    Exchange,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Default)]
pub struct AtomicRequirements {
    pub add: bool,
    pub exchange: bool,
    pub device_storage: bool,
}

impl AtomicRequirements {
    fn record(&mut self, operation: AtomicOperation) {
        self.device_storage = true;
        match operation {
            AtomicOperation::Add => self.add = true,
            AtomicOperation::Exchange => self.exchange = true,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum IndirectDrawLayout {
    MetalPrimitives,
    MetalIndexed,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct OutputCapacity {
    pub vertices: u32,
    pub primitives: u32,
}

#[derive(Debug, Eq, PartialEq)]
pub struct ComputeGeometryPlan {
    pub input_topology: PrimitiveTopology,
    pub output_topology: PrimitiveTopology,
    pub count_production: CountProduction,
    pub prefix_sums: [PrefixSumKind; 2],
    pub output_offsets: OutputOffsetKind,
    pub produced_vertices: u32,
    pub produced_primitives: u32,
    pub output_capacity: OutputCapacity,
    pub indirect_draw: IndirectDrawLayout,
    pub side_effects: SideEffects,
    pub side_effect_ordering: SideEffectOrdering,
    pub storage_capabilities: StorageCapabilities,
    pub location: Location,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TessellationFactorType {
    Float,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TessellationFactorKind {
    Outer,
    Inner,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct TessellationFactorWrite {
    pub kind: TessellationFactorKind,
    pub component: u32,
    pub invocation: u32,
    pub value_type: TessellationFactorType,
}

#[derive(Debug, Eq, PartialEq)]
pub struct TessellationControlPlan {
    pub patch: PatchControlPoints,
    pub input_storage: &'static str,
    pub output_storage: &'static str,
    pub invocation_identity: InvocationIdentity,
    pub factor_writes: Vec<TessellationFactorWrite>,
    pub factor_type: TessellationFactorType,
    pub patch_order: PatchPrimitiveOrder,
    pub barrier_required: bool,
    pub side_effects: SideEffects,
    pub atomics_required: bool,
    pub location: Location,
}

#[derive(Debug, Eq, PartialEq)]
pub struct TessellationPlan {
    pub contract: TessellationContract,
    pub control: TessellationControlPlan,
    pub triangle: TriangleSubdivision,
    pub quad: Option<QuadSubdivision>,
    pub isoline: Option<IsolineSubdivision>,
    pub workgroup_size: [u32; 3],
    pub output_limit: u32,
    pub location: Location,
}

#[derive(Debug, Eq, PartialEq)]
pub struct StageEmulationPlan {
    pub family: LoweringFamily,
    pub capabilities: StageCapabilities,
    pub helper_entries: Vec<HelperEntryPoint>,
    pub mesh_limits: MeshLimits,
    pub compute_geometry: Option<ComputeGeometryPlan>,
    pub tessellation: Option<TessellationPlan>,
}

fn copy_location(location: &Location) -> Location {
    Location::new(&location.file, location.line, location.column)
}

fn unsupported(message: &str, location: &Location) -> CompileErrors {
    CompileErrors::one(CompileError::unsupported_at(
        message,
        Some(copy_location(location)),
    ))
}

fn triangle_effective_factor(spacing: TessellationSpacing, factor: u32) -> Option<u32> {
    match spacing {
        TessellationSpacing::Equal => Some(factor),
        TessellationSpacing::FractionalEven => Some(if factor.is_multiple_of(2) {
            factor
        } else {
            factor.checked_add(1)?
        }),
        TessellationSpacing::FractionalOdd => Some(if !factor.is_multiple_of(2) {
            factor
        } else {
            factor.checked_add(1)?
        }),
    }
}

fn triangle_limits_fit(contract: &TessellationContract) -> bool {
    let mut maximum = contract.outer_factor_range.maximum;
    if let Some(inner) = contract.inner_factor_range
        && inner.maximum > maximum
    {
        maximum = inner.maximum;
    }
    let Some(factor) = triangle_effective_factor(contract.spacing, maximum) else {
        return false;
    };
    let Some(edge_product) = factor.checked_mul(factor) else {
        return false;
    };
    let Some(next_factor) = factor.checked_add(1) else {
        return false;
    };
    let Some(row_count) = next_factor.checked_add(1) else {
        return false;
    };
    let Some(vertex_product) = next_factor.checked_mul(row_count) else {
        return false;
    };
    let vertices = vertex_product / 2;
    edge_product <= 8192 && vertices <= 4096
}

fn triangle_subdivision(contract: &TessellationContract) -> TriangleSubdivision {
    let mut tes_output_order = Vec::with_capacity(contract.tes_outputs.len());
    for output in &contract.tes_outputs {
        tes_output_order.push(*output);
    }
    TriangleSubdivision {
        factor_edges: [
            TriangleFactorEdge::OppositeControlPoint0,
            TriangleFactorEdge::OppositeControlPoint1,
            TriangleFactorEdge::OppositeControlPoint2,
        ],
        spacing: contract.spacing,
        coordinate_order: TriangleCoordinateOrder::BarycentricRows,
        primitive_order: TrianglePrimitiveOrder::CounterClockwiseRows,
        invocation_order: TriangleInvocationOrder::CoordinateOrder,
        tes_output_order,
        maximum_vertices: 4096,
        maximum_primitives: 8192,
        maximum_invocations: 4096,
    }
}

fn quad_subdivision(contract: &TessellationContract) -> Option<QuadSubdivision> {
    let outer = contract.outer_factor_range.maximum;
    let inner = contract.inner_factor_range?.maximum;
    let u = triangle_effective_factor(contract.spacing, outer.max(inner));
    let v = triangle_effective_factor(contract.spacing, outer.max(inner));
    let u = u?;
    let v = v?;
    let vertex_count = u.checked_add(1)?.checked_mul(v.checked_add(1)?)?;
    let primitive_count = u.checked_mul(v)?.checked_mul(2)?;
    let mut coordinates = Vec::new();
    for row in 0..=v {
        for column in 0..=u {
            coordinates.push(QuadCoordinate { u: column, v: row });
        }
    }
    let primitive_capacity = usize::try_from(primitive_count).ok()?;
    let mut primitives = Vec::with_capacity(primitive_capacity);
    for row in 0..v {
        for column in 0..u {
            let base = row.checked_mul(u.checked_add(1)?)?.checked_add(column)?;
            let right = base.checked_add(1)?;
            let next = base.checked_add(u.checked_add(1)?)?;
            let diagonal = next.checked_add(1)?;
            primitives.push([base, right, diagonal]);
            primitives.push([base, diagonal, next]);
        }
    }
    let mut generated_invocation_ids = Vec::with_capacity(coordinates.len());
    for invocation in 0..vertex_count {
        generated_invocation_ids.push(invocation);
    }
    let mut tes_output_order = Vec::with_capacity(contract.tes_outputs.len());
    for output in &contract.tes_outputs {
        tes_output_order.push(*output);
    }
    Some(QuadSubdivision {
        factor_edges: [
            QuadFactorEdge::U0,
            QuadFactorEdge::V0,
            QuadFactorEdge::U1,
            QuadFactorEdge::V1,
        ],
        outer_factors: [outer, outer, outer, outer],
        inner_factors: [inner, inner],
        spacing: contract.spacing,
        coordinate_order: QuadCoordinateOrder::UvRows,
        coordinates,
        primitives,
        primitive_order: QuadPrimitiveOrder::CounterClockwiseRows,
        invocation_order: QuadInvocationOrder::CoordinateOrder,
        generated_invocation_ids,
        tes_output_order,
        maximum_vertices: 4096,
        maximum_primitives: 8192,
        maximum_invocations: 4096,
    })
}

fn quad_limits_fit(contract: &TessellationContract) -> bool {
    let Some(subdivision) = quad_subdivision(contract) else {
        return false;
    };
    let Some(vertices) = u32::try_from(subdivision.coordinates.len()).ok() else {
        return false;
    };
    let Some(primitives) = u32::try_from(subdivision.primitives.len()).ok() else {
        return false;
    };
    vertices <= subdivision.maximum_vertices
        && primitives <= subdivision.maximum_primitives
        && vertices <= subdivision.maximum_invocations
}

fn isoline_subdivision(contract: &TessellationContract) -> Option<IsolineSubdivision> {
    let factor = triangle_effective_factor(contract.spacing, contract.outer_factor_range.maximum)?;
    let line_count = factor;
    let segment_count = factor;
    let row_width = segment_count.checked_add(1)?;
    let vertex_count = line_count.checked_mul(row_width)?;
    let primitive_count = line_count.checked_mul(segment_count)?;
    let coordinate_capacity = usize::try_from(vertex_count).ok()?;
    let mut coordinates = Vec::with_capacity(coordinate_capacity);
    for line in 0..line_count {
        for segment in 0..=segment_count {
            coordinates.push(IsolineCoordinate {
                u: segment,
                v: line,
            });
        }
    }
    let primitive_capacity = usize::try_from(primitive_count).ok()?;
    let mut primitives = Vec::with_capacity(primitive_capacity);
    for line in 0..line_count {
        let base = line.checked_mul(row_width)?;
        for segment in 0..segment_count {
            let first = base.checked_add(segment)?;
            primitives.push([first, first.checked_add(1)?]);
        }
    }
    let mut generated_invocation_ids = Vec::with_capacity(coordinate_capacity);
    for invocation in 0..vertex_count {
        generated_invocation_ids.push(invocation);
    }
    let mut tes_output_order = Vec::with_capacity(contract.tes_outputs.len());
    for output in &contract.tes_outputs {
        tes_output_order.push(*output);
    }
    Some(IsolineSubdivision {
        factor_edges: [
            IsolineFactorEdge::LineCount,
            IsolineFactorEdge::SegmentCount,
        ],
        outer_factors: [factor, factor],
        spacing: contract.spacing,
        coordinate_order: IsolineCoordinateOrder::SegmentRows,
        coordinates,
        primitives,
        primitive_order: IsolinePrimitiveOrder::LineRows,
        invocation_order: IsolineInvocationOrder::CoordinateOrder,
        generated_invocation_ids,
        tes_output_order,
        maximum_vertices: 4096,
        maximum_primitives: 8192,
        maximum_invocations: 4096,
    })
}

fn isoline_limits_fit(contract: &TessellationContract) -> bool {
    let Some(subdivision) = isoline_subdivision(contract) else {
        return false;
    };
    let Some(vertices) = u32::try_from(subdivision.coordinates.len()).ok() else {
        return false;
    };
    let Some(primitives) = u32::try_from(subdivision.primitives.len()).ok() else {
        return false;
    };
    vertices <= subdivision.maximum_vertices
        && primitives <= subdivision.maximum_primitives
        && vertices <= subdivision.maximum_invocations
}

fn tessellation_control_plan(
    contract: &TessellationContract,
    location: &Location,
) -> TessellationControlPlan {
    let mut input_order = Vec::with_capacity(contract.patch.input_order.len());
    for value in &contract.patch.input_order {
        input_order.push(*value);
    }
    let mut output_order = Vec::with_capacity(contract.patch.output_order.len());
    for value in &contract.patch.output_order {
        output_order.push(*value);
    }
    let patch = PatchControlPoints {
        input_count: contract.patch.input_count,
        output_count: contract.patch.output_count,
        input_order,
        output_order,
    };
    let mut factor_writes = Vec::new();
    let outer_components = if contract.domain == TessellationDomain::Isolines {
        2
    } else {
        3
    };
    for component in 0..outer_components {
        factor_writes.push(TessellationFactorWrite {
            kind: TessellationFactorKind::Outer,
            component,
            invocation: 0,
            value_type: TessellationFactorType::Float,
        });
    }
    if contract.inner_factor_range.is_some() {
        factor_writes.push(TessellationFactorWrite {
            kind: TessellationFactorKind::Inner,
            component: 0,
            invocation: 0,
            value_type: TessellationFactorType::Float,
        });
    }
    TessellationControlPlan {
        patch,
        input_storage: "gl_in",
        output_storage: "gl_out",
        invocation_identity: contract.tcs_invocation,
        factor_writes,
        factor_type: TessellationFactorType::Float,
        patch_order: contract.primitive_order,
        barrier_required: true,
        side_effects: contract.side_effects,
        atomics_required: contract.side_effects.atomics,
        location: copy_location(location),
    }
}

pub fn select_lowering(input: StageEmulationInput) -> Result<StageEmulationPlan, CompileErrors> {
    let limits = MeshLimits::default();
    if let Some(geometry) = input.geometry {
        if geometry.writes_layer {
            return Err(unsupported(
                "geometry layer output is outside the order-preserving compute plan",
                &geometry.location,
            ));
        }
        if geometry.uses_invocation_id {
            return Err(unsupported(
                "geometry invocation identity is outside the order-preserving compute plan",
                &geometry.location,
            ));
        }
        if geometry.side_effects.image_writes {
            return Err(unsupported(
                "geometry image writes cannot be represented by the ordered storage-resource plan",
                &geometry.location,
            ));
        }
        if !geometry.side_effects.is_empty() && !geometry.compute_compatible {
            return Err(unsupported(
                "geometry side effects require an ordered compute emulation plan",
                &geometry.location,
            ));
        }
        if geometry.emitted_vertices > limits.max_vertices
            || geometry.emitted_primitives > limits.max_primitives
            || !geometry.side_effects.is_empty()
        {
            if geometry.compute_compatible {
                return Ok(StageEmulationPlan {
                    family: LoweringFamily::ComputeEmulation,
                    capabilities: StageCapabilities::default(),
                    helper_entries: vec![HelperEntryPoint::ComputeEmulation],
                    mesh_limits: limits,
                    compute_geometry: Some(ComputeGeometryPlan {
                        input_topology: geometry.input_topology,
                        output_topology: geometry.output_topology,
                        count_production: CountProduction::VertexAndPrimitive,
                        prefix_sums: [PrefixSumKind::Group, PrefixSumKind::Global],
                        output_offsets: OutputOffsetKind::SourcePrimitiveOrder,
                        produced_vertices: geometry.emitted_vertices,
                        produced_primitives: geometry.emitted_primitives,
                        output_capacity: OutputCapacity {
                            vertices: geometry.max_vertices,
                            primitives: geometry.emitted_primitives,
                        },
                        indirect_draw: IndirectDrawLayout::MetalPrimitives,
                        side_effects: geometry.side_effects,
                        side_effect_ordering: SideEffectOrdering::SourceInvocationSerial,
                        storage_capabilities: StorageCapabilities {
                            storage_writes: geometry.side_effects.storage_writes,
                            storage_atomics: geometry.side_effects.atomics,
                        },
                        location: copy_location(&geometry.location),
                    }),
                    tessellation: None,
                });
            }
            if geometry.emitted_vertices > limits.max_vertices {
                return Err(unsupported(
                    "geometry output exceeds the mesh vertex limit of 256",
                    &geometry.location,
                ));
            }
            return Err(unsupported(
                "geometry output exceeds the mesh primitive limit of 512",
                &geometry.location,
            ));
        }
        let family = if geometry.input_topology == geometry.output_topology
            && geometry.emitted_vertices <= geometry.max_vertices
        {
            LoweringFamily::DedicatedPattern
        } else {
            LoweringFamily::MeshCompatible
        };
        return Ok(StageEmulationPlan {
            family,
            capabilities: StageCapabilities::default(),
            helper_entries: vec![HelperEntryPoint::GeometryMesh],
            mesh_limits: limits,
            compute_geometry: None,
            tessellation: None,
        });
    }
    if let Some(tessellation) = input.tessellation {
        let contract = tessellation.contract;
        if tessellation.uses_invocation_id
            && contract.tcs_invocation != InvocationIdentity::TcsInvocation
        {
            return Err(unsupported(
                "tessellation control invocation identity must remain distinct from generated tessellator identity",
                &tessellation.location,
            ));
        }
        if !matches!(
            contract.domain,
            TessellationDomain::Triangles
                | TessellationDomain::Quads
                | TessellationDomain::Isolines
        ) {
            return Err(unsupported(
                "tessellation domain is unsupported without a selected lowering bucket",
                &contract.location,
            ));
        }
        if contract.tcs_invocation == contract.generated_invocation {
            return Err(unsupported(
                "tessellation control and generated invocation identities must be distinct",
                &contract.location,
            ));
        }
        if contract.side_effects.image_writes
            || contract.side_effects.storage_writes
            || contract.side_effects.atomics
        {
            return Err(unsupported(
                "tessellation side effects and atomics are unsupported before compute subdivision",
                &contract.location,
            ));
        }
        if contract.patch.input_count != contract.patch.output_count
            || contract.patch.input_order != contract.patch.output_order
        {
            return Err(unsupported(
                "tessellation patch input and output ordering must be identical",
                &contract.location,
            ));
        }
        let ordered_patch = contract
            .patch
            .input_order
            .iter()
            .copied()
            .enumerate()
            .all(|(index, value)| u32::try_from(index).ok() == Some(value));
        if !ordered_patch {
            return Err(unsupported(
                "tessellation patch control points must preserve source invocation order",
                &contract.location,
            ));
        }
        let expected_patch = match contract.domain {
            TessellationDomain::Triangles => Some(3),
            TessellationDomain::Quads => Some(4),
            TessellationDomain::Isolines => None,
        };
        if expected_patch.is_some_and(|value| contract.patch.input_count != value) {
            return Err(unsupported(
                "tessellation patch size does not match the selected domain",
                &contract.location,
            ));
        }
        if contract.patch.input_count == 0 || contract.patch.input_count > 32 {
            return Err(unsupported(
                "tessellation patch size must be between 1 and 32 control points",
                &contract.location,
            ));
        }
        if contract.outer_factor_range.minimum == 0
            || contract.outer_factor_range.minimum > contract.outer_factor_range.maximum
            || contract.outer_factor_range.maximum > 64
        {
            return Err(unsupported(
                "tessellation outer factors must be in the range 1..=64",
                &contract.location,
            ));
        }
        if let Some(range) = contract.inner_factor_range
            && (range.minimum == 0 || range.minimum > range.maximum || range.maximum > 64)
        {
            return Err(unsupported(
                "tessellation inner factors must be in the range 1..=64",
                &contract.location,
            ));
        }
        if contract.domain == TessellationDomain::Isolines && contract.inner_factor_range.is_some()
        {
            return Err(unsupported(
                "isoline tessellation does not accept inner tessellation factors",
                &contract.location,
            ));
        }
        if contract.domain_coordinates != 2 || contract.tes_outputs.is_empty() {
            return Err(unsupported(
                "tessellation evaluation requires two domain coordinates and an ordered output",
                &contract.location,
            ));
        }
        if contract.domain == TessellationDomain::Triangles && !triangle_limits_fit(&contract) {
            return Err(unsupported(
                "triangle tessellation exceeds the generated vertex, primitive, or invocation limit",
                &contract.location,
            ));
        }
        if contract.domain == TessellationDomain::Quads && !quad_limits_fit(&contract) {
            return Err(unsupported(
                "quad tessellation exceeds the generated vertex, primitive, or invocation limit",
                &contract.location,
            ));
        }
        if contract.domain == TessellationDomain::Isolines && !isoline_limits_fit(&contract) {
            return Err(unsupported(
                "isoline tessellation exceeds the generated vertex, primitive, or invocation limit",
                &contract.location,
            ));
        }
        if contract.tes_outputs.len() > 256 {
            return Err(unsupported(
                "tessellation evaluation output exceeds the compute subdivision limit of 256",
                &contract.location,
            ));
        }
        return Ok(StageEmulationPlan {
            family: if tessellation.factors == TessellationFactors::Dynamic {
                LoweringFamily::MeshCompatible
            } else {
                LoweringFamily::DedicatedPattern
            },
            capabilities: StageCapabilities {
                tessellation: true,
                ..StageCapabilities::default()
            },
            helper_entries: Vec::new(),
            mesh_limits: limits,
            compute_geometry: None,
            tessellation: Some(TessellationPlan {
                triangle: triangle_subdivision(&contract),
                quad: if contract.domain == TessellationDomain::Quads {
                    quad_subdivision(&contract)
                } else {
                    None
                },
                isoline: if contract.domain == TessellationDomain::Isolines {
                    isoline_subdivision(&contract)
                } else {
                    None
                },
                control: tessellation_control_plan(&contract, &tessellation.location),
                contract,
                workgroup_size: [32, 1, 1],
                output_limit: 256,
                location: copy_location(&tessellation.location),
            }),
        });
    }
    Err(CompileErrors::one(CompileError::unsupported(
        input.stage,
        None,
    )))
}

#[derive(Debug, Eq, PartialEq)]
pub struct TransformedModule<'a> {
    pub declarations: Vec<TransformedDeclaration<'a>>,
    pub tessellation: Option<TessellationContract>,
    pub lowering_family: Option<LoweringFamily>,
}

#[derive(Debug, Eq, PartialEq)]
pub struct TransformedDeclaration<'a> {
    pub source: &'a crate::sema::TypedDeclaration,
}

pub fn run<'a>(
    module: &'a TypedModule,
    _stage: Stage,
    _options: &Options,
) -> Result<TransformedModule<'a>, CompileErrors> {
    Ok(TransformedModule {
        declarations: module
            .declarations
            .iter()
            .map(|declaration| TransformedDeclaration {
                source: declaration,
            })
            .collect(),
        tessellation: None,
        lowering_family: None,
    })
}

pub fn validate_atomic_operations(module: &Ast) -> Result<AtomicRequirements, CompileErrors> {
    let mut targets = BTreeMap::new();
    for declaration in &module.declarations {
        collect_atomic_targets(declaration, &mut targets);
    }
    let mut requirements = AtomicRequirements::default();
    for declaration in &module.declarations {
        validate_declaration_atomics(declaration, &targets, &mut requirements)?;
    }
    Ok(requirements)
}

#[derive(Clone, Copy)]
enum AtomicTarget {
    DeviceInt,
    DeviceUInt,
    DeviceFloat,
    Other,
}

fn collect_atomic_targets(declaration: &Declaration, targets: &mut BTreeMap<String, AtomicTarget>) {
    if let Some(name) = declaration.name.as_deref() {
        let target = if has_storage(declaration, "buffer") {
            match &declaration.type_specifier {
                TypeSpecifier::Int => AtomicTarget::DeviceInt,
                TypeSpecifier::UInt => AtomicTarget::DeviceUInt,
                TypeSpecifier::Float => AtomicTarget::DeviceFloat,
                _ => AtomicTarget::Other,
            }
        } else {
            AtomicTarget::Other
        };
        targets.insert(String::from(name), target);
    }
    for member in &declaration.members {
        collect_atomic_targets(member, targets);
    }
    if let Some(body) = declaration.body.as_deref() {
        collect_statement_targets(body, targets);
    }
}

fn collect_statement_targets(statement: &Statement, targets: &mut BTreeMap<String, AtomicTarget>) {
    match statement {
        Statement::Compound(_, statements) => {
            for item in statements {
                collect_statement_targets(item, targets);
            }
        }
        Statement::Declaration(declaration) => collect_atomic_targets(declaration, targets),
        Statement::If(_, _, yes, no) => {
            collect_statement_targets(yes, targets);
            if let Some(no) = no {
                collect_statement_targets(no, targets);
            }
        }
        Statement::Switch(_, _, statements) => {
            for item in statements {
                collect_statement_targets(item, targets);
            }
        }
        Statement::Label(_, _, statement) => collect_statement_targets(statement, targets),
        Statement::Loop(_, init, _, _, body) => {
            if let Some(init) = init {
                collect_statement_targets(init, targets);
            }
            collect_statement_targets(body, targets);
        }
        Statement::Expression(_, _)
        | Statement::Jump(_, _, _)
        | Statement::Empty(_)
        | Statement::Error(_) => {}
    }
}

fn validate_declaration_atomics(
    declaration: &Declaration,
    targets: &BTreeMap<String, AtomicTarget>,
    requirements: &mut AtomicRequirements,
) -> Result<(), CompileErrors> {
    if let Some(initializer) = &declaration.initializer {
        validate_expression_atomics(
            initializer,
            targets,
            requirements,
            &declaration.span.source_path,
            declaration.span.line,
            declaration.span.column,
        )?;
    }
    if let Some(body) = declaration.body.as_deref() {
        validate_statement_atomics(body, targets, requirements)?;
    }
    for member in &declaration.members {
        validate_declaration_atomics(member, targets, requirements)?;
    }
    Ok(())
}

fn validate_statement_atomics(
    statement: &Statement,
    targets: &BTreeMap<String, AtomicTarget>,
    requirements: &mut AtomicRequirements,
) -> Result<(), CompileErrors> {
    let (span, expressions): (&Span, Vec<&Expression>) = match statement {
        Statement::Expression(span, expression) => (span, expression.iter().collect()),
        Statement::If(span, condition, _, _) => (span, vec![condition]),
        Statement::Switch(span, expression, _) => (span, vec![expression]),
        Statement::Label(span, expression, _) => (span, expression.iter().collect()),
        Statement::Loop(span, _, condition, increment, _) => {
            let mut values = Vec::new();
            if let Some(condition) = condition {
                values.push(condition);
            }
            if let Some(increment) = increment {
                values.push(increment);
            }
            (span, values)
        }
        Statement::Jump(span, _, expression) => (span, expression.iter().collect()),
        Statement::Compound(span, _) | Statement::Empty(span) | Statement::Error(span) => {
            (span, Vec::new())
        }
        Statement::Declaration(declaration) => {
            return validate_declaration_atomics(declaration, targets, requirements);
        }
    };
    for expression in expressions {
        validate_expression_atomics(
            expression,
            targets,
            requirements,
            &span.source_path,
            span.line,
            span.column,
        )?;
    }
    match statement {
        Statement::Compound(_, statements) | Statement::Switch(_, _, statements) => {
            for item in statements {
                validate_statement_atomics(item, targets, requirements)?;
            }
        }
        Statement::If(_, _, yes, no) => {
            validate_statement_atomics(yes, targets, requirements)?;
            if let Some(no) = no {
                validate_statement_atomics(no, targets, requirements)?;
            }
        }
        Statement::Label(_, _, statement) => {
            validate_statement_atomics(statement, targets, requirements)?
        }
        Statement::Loop(_, init, _, _, body) => {
            if let Some(init) = init {
                validate_statement_atomics(init, targets, requirements)?;
            }
            validate_statement_atomics(body, targets, requirements)?;
        }
        Statement::Expression(_, _)
        | Statement::Declaration(_)
        | Statement::Jump(_, _, _)
        | Statement::Empty(_)
        | Statement::Error(_) => {}
    }
    Ok(())
}

fn validate_expression_atomics(
    expression: &Expression,
    targets: &BTreeMap<String, AtomicTarget>,
    requirements: &mut AtomicRequirements,
    file: &str,
    line: u32,
    column: u32,
) -> Result<(), CompileErrors> {
    if let Expression::Call(function, arguments) = expression
        && let Expression::Name(name) = function.as_ref()
    {
        let operation = match name.as_str() {
            "atomicAdd" => Some(AtomicOperation::Add),
            "atomicExchange" => Some(AtomicOperation::Exchange),
            _ => None,
        };
        if operation.is_none()
            && matches!(
                name.as_str(),
                "atomicCounter" | "atomicCounterIncrement" | "atomicCounterDecrement"
            )
        {
            let message = format!("{name} is unsupported for Metal device atomics");
            return Err(CompileErrors::one(CompileError::unsupported_at(
                message,
                Some(Location::new(file, line, column)),
            )));
        }
        let Some(operation) = operation else {
            return validate_call_arguments_atomics(
                function,
                arguments,
                targets,
                requirements,
                file,
                line,
                column,
            );
        };
        let target_name = arguments.first().and_then(expression_root_name);
        let target = target_name.and_then(|value| targets.get(value));
        let supported = matches!(
            target,
            Some(AtomicTarget::DeviceInt | AtomicTarget::DeviceUInt | AtomicTarget::DeviceFloat)
        );
        if !supported {
            let message = format!(
                "{name} requires a device storage atomic target of int, uint, or float type"
            );
            return Err(CompileErrors::one(CompileError::unsupported_at(
                message,
                Some(Location::new(file, line, column)),
            )));
        }
        requirements.record(operation);
    }
    match expression {
        Expression::Unary(_, value) => {
            validate_expression_atomics(value, targets, requirements, file, line, column)?
        }
        Expression::Binary(_, left, right) => {
            validate_expression_atomics(left, targets, requirements, file, line, column)?;
            validate_expression_atomics(right, targets, requirements, file, line, column)?;
        }
        Expression::Conditional(condition, yes, no) => {
            validate_expression_atomics(condition, targets, requirements, file, line, column)?;
            validate_expression_atomics(yes, targets, requirements, file, line, column)?;
            validate_expression_atomics(no, targets, requirements, file, line, column)?;
        }
        Expression::Call(function, arguments) => {
            validate_expression_atomics(function, targets, requirements, file, line, column)?;
            for argument in arguments {
                validate_expression_atomics(argument, targets, requirements, file, line, column)?;
            }
        }
        Expression::Index(base, index) => {
            validate_expression_atomics(base, targets, requirements, file, line, column)?;
            validate_expression_atomics(index, targets, requirements, file, line, column)?;
        }
        Expression::Field(base, _) => {
            validate_expression_atomics(base, targets, requirements, file, line, column)?
        }
        Expression::Constructor(_, arguments) => {
            for argument in arguments {
                validate_expression_atomics(argument, targets, requirements, file, line, column)?;
            }
        }
        Expression::Literal(_) | Expression::Name(_) => {}
    }
    Ok(())
}

fn validate_call_arguments_atomics(
    function: &Expression,
    arguments: &[Expression],
    targets: &BTreeMap<String, AtomicTarget>,
    requirements: &mut AtomicRequirements,
    file: &str,
    line: u32,
    column: u32,
) -> Result<(), CompileErrors> {
    validate_expression_atomics(function, targets, requirements, file, line, column)?;
    for argument in arguments {
        validate_expression_atomics(argument, targets, requirements, file, line, column)?;
    }
    Ok(())
}

fn expression_root_name(expression: &Expression) -> Option<&str> {
    match expression {
        Expression::Name(name) => Some(name.as_str()),
        Expression::Index(base, _) | Expression::Field(base, _) => expression_root_name(base),
        _ => None,
    }
}

pub fn run_ast(
    module: &mut Ast,
    stage: Stage,
    _options: &Options,
    output_targets: &[u32],
) -> Result<(), CompileErrors> {
    validate_atomic_operations(module)?;
    if stage == Stage::Vertex {
        materialize_legacy_vertex_inputs(module);
    }
    for declaration in &mut module.declarations {
        declaration_qualifiers(declaration, stage);
        declaration_expressions(declaration);
    }
    if stage == Stage::Fragment {
        fragment_outputs(module, output_targets);
    }
    Ok(())
}

fn declaration_qualifiers(declaration: &mut Declaration, stage: Stage) {
    for qualifier in &mut declaration.qualifiers {
        if let Qualifier::Storage(storage) = qualifier {
            if storage == "attribute" {
                *storage = String::from("in");
            } else if storage == "varying" {
                *storage = if stage == Stage::Vertex {
                    String::from("out")
                } else {
                    String::from("in")
                };
            }
        }
    }
    for member in &mut declaration.members {
        declaration_qualifiers(member, stage);
    }
    if let Some(body) = declaration.body.as_mut() {
        statement_qualifiers(body, stage);
    }
}

fn statement_qualifiers(statement: &mut Statement, stage: Stage) {
    match statement {
        Statement::Compound(_, statements) => {
            for item in statements {
                statement_qualifiers(item, stage);
            }
        }
        Statement::Declaration(declaration) => declaration_qualifiers(declaration, stage),
        Statement::If(_, _, yes, no) => {
            statement_qualifiers(yes, stage);
            if let Some(no) = no {
                statement_qualifiers(no, stage);
            }
        }
        Statement::Switch(_, _, statements) => {
            for item in statements {
                statement_qualifiers(item, stage);
            }
        }
        Statement::Label(_, _, statement) => statement_qualifiers(statement, stage),
        Statement::Loop(_, init, _, _, body) => {
            if let Some(init) = init {
                statement_qualifiers(init, stage);
            }
            statement_qualifiers(body, stage);
        }
        Statement::Expression(_, _)
        | Statement::Jump(_, _, _)
        | Statement::Empty(_)
        | Statement::Error(_) => {}
    }
}

fn declaration_expressions(declaration: &mut Declaration) {
    for expression in &mut declaration.array {
        expression_names(expression);
    }
    if let Some(initializer) = declaration.initializer.as_mut() {
        expression_names(initializer);
    }
    for parameter in &mut declaration.parameters {
        for expression in &mut parameter.array {
            expression_names(expression);
        }
    }
    for member in &mut declaration.members {
        declaration_expressions(member);
    }
    if let Some(body) = declaration.body.as_mut() {
        statement_expressions(body);
    }
}

fn statement_expressions(statement: &mut Statement) {
    match statement {
        Statement::Compound(_, statements) => {
            for item in statements {
                statement_expressions(item);
            }
        }
        Statement::Declaration(declaration) => declaration_expressions(declaration),
        Statement::Expression(_, expression) => {
            if let Some(expression) = expression {
                expression_names(expression);
            }
        }
        Statement::If(_, condition, yes, no) => {
            expression_names(condition);
            statement_expressions(yes);
            if let Some(no) = no {
                statement_expressions(no);
            }
        }
        Statement::Switch(_, expression, statements) => {
            expression_names(expression);
            for item in statements {
                statement_expressions(item);
            }
        }
        Statement::Label(_, expression, statement) => {
            if let Some(expression) = expression {
                expression_names(expression);
            }
            statement_expressions(statement);
        }
        Statement::Loop(_, init, condition, increment, body) => {
            if let Some(init) = init {
                statement_expressions(init);
            }
            if let Some(condition) = condition {
                expression_names(condition);
            }
            if let Some(increment) = increment {
                expression_names(increment);
            }
            statement_expressions(body);
        }
        Statement::Jump(_, _, expression) => {
            if let Some(expression) = expression {
                expression_names(expression);
            }
        }
        Statement::Empty(_) | Statement::Error(_) => {}
    }
}

fn expression_names(expression: &mut Expression) {
    if let Expression::Call(function, arguments) = expression
        && arguments.is_empty()
        && matches!(function.as_ref(), Expression::Name(name) if name == "ftransform")
    {
        *expression = Expression::Binary(
            String::from("*"),
            Box::new(Expression::Name(String::from("mtlr_model_view_projection"))),
            Box::new(Expression::Name(String::from("mtlr_vertex"))),
        );
        return;
    }
    match expression {
        Expression::Name(name) => *name = map_name(name),
        Expression::Unary(_, value) => expression_names(value),
        Expression::Binary(_, left, right) => {
            expression_names(left);
            expression_names(right);
        }
        Expression::Conditional(condition, yes, no) => {
            expression_names(condition);
            expression_names(yes);
            expression_names(no);
        }
        Expression::Call(function, arguments) => {
            expression_names(function);
            for argument in arguments {
                expression_names(argument);
            }
        }
        Expression::Index(value, index) => {
            expression_names(value);
            expression_names(index);
        }
        Expression::Field(value, _) => expression_names(value),
        Expression::Constructor(_, arguments) => {
            for argument in arguments {
                expression_names(argument);
            }
        }
        Expression::Literal(_) => {}
    }
}

fn materialize_legacy_vertex_inputs(module: &mut Ast) {
    let names = [
        ("gl_Vertex", "mtlr_vertex", TypeSpecifier::Vec(4)),
        ("gl_Color", "mtlr_color", TypeSpecifier::Vec(4)),
        ("gl_Normal", "mtlr_normal", TypeSpecifier::Vec(3)),
        ("gl_MultiTexCoord0", "mtlr_texcoord0", TypeSpecifier::Vec(4)),
        ("gl_MultiTexCoord1", "mtlr_texcoord1", TypeSpecifier::Vec(4)),
        ("gl_MultiTexCoord2", "mtlr_texcoord2", TypeSpecifier::Vec(4)),
        ("gl_MultiTexCoord3", "mtlr_texcoord3", TypeSpecifier::Vec(4)),
    ];
    let used_locations = module
        .declarations
        .iter()
        .filter(|declaration| {
            has_storage(declaration, "in") || has_storage(declaration, "attribute")
        })
        .count() as u32;
    let explicit_location = module
        .declarations
        .iter()
        .filter(|declaration| {
            has_storage(declaration, "in") || has_storage(declaration, "attribute")
        })
        .filter_map(|declaration| layout_value(declaration, "location"))
        .max()
        .map_or(0, |value| value.saturating_add(1));
    let mut next_location = used_locations.max(explicit_location);
    let Some(source_span) = source_span(module) else {
        return;
    };
    for (source_name, name, type_specifier) in names {
        let used = ast_uses_name(module, source_name)
            || (source_name == "gl_Vertex" && ast_uses_name(module, "ftransform"));
        if !used || declaration_named(module, name) {
            continue;
        }
        module.declarations.push(Declaration {
            span: Span {
                start: source_span.start,
                end: source_span.end,
                line: source_span.line,
                column: source_span.column,
                source_index: source_span.source_index,
                source_path: String::from(source_span.source_path.as_str()),
            },
            qualifiers: vec![
                Qualifier::Storage(String::from("in")),
                Qualifier::Layout(vec![(
                    String::from("location"),
                    Some(Expression::Literal(next_location.to_string())),
                )]),
            ],
            type_specifier,
            name: Some(String::from(name)),
            array: Vec::new(),
            initializer: None,
            body: None,
            parameters: Vec::new(),
            members: Vec::new(),
        });
        next_location = next_location.saturating_add(1);
    }
    if ast_uses_name(module, "ftransform")
        && !declaration_named(module, "mtlr_model_view_projection")
    {
        module.declarations.push(Declaration {
            span: Span {
                start: source_span.start,
                end: source_span.end,
                line: source_span.line,
                column: source_span.column,
                source_index: source_span.source_index,
                source_path: String::from(source_span.source_path.as_str()),
            },
            qualifiers: vec![Qualifier::Storage(String::from("uniform"))],
            type_specifier: TypeSpecifier::Mat(4, 4),
            name: Some(String::from("mtlr_model_view_projection")),
            array: Vec::new(),
            initializer: None,
            body: None,
            parameters: Vec::new(),
            members: Vec::new(),
        });
    }
}

fn has_storage(declaration: &Declaration, name: &str) -> bool {
    declaration
        .qualifiers
        .iter()
        .any(|qualifier| matches!(qualifier, Qualifier::Storage(value) if value == name))
}

fn layout_value(declaration: &Declaration, name: &str) -> Option<u32> {
    declaration.qualifiers.iter().find_map(|qualifier| {
        if let Qualifier::Layout(values) = qualifier {
            values.iter().find_map(|(key, value)| {
                if key == name {
                    match value {
                        Some(Expression::Literal(number)) => number.parse().ok(),
                        _ => None,
                    }
                } else {
                    None
                }
            })
        } else {
            None
        }
    })
}

fn source_span(module: &Ast) -> Option<Span> {
    module.declarations.first().map(|declaration| Span {
        start: declaration.span.start,
        end: declaration.span.end,
        line: declaration.span.line,
        column: declaration.span.column,
        source_index: declaration.span.source_index,
        source_path: String::from(declaration.span.source_path.as_str()),
    })
}

fn declaration_named(module: &Ast, name: &str) -> bool {
    module
        .declarations
        .iter()
        .any(|declaration| declaration.name.as_deref() == Some(name))
}

fn ast_uses_name(module: &Ast, name: &str) -> bool {
    module
        .declarations
        .iter()
        .any(|declaration| declaration_uses_name(declaration, name))
}

fn map_name(name: &str) -> String {
    match name {
        "gl_Vertex" => String::from("mtlr_vertex"),
        "gl_Color" => String::from("mtlr_color"),
        "gl_Normal" => String::from("mtlr_normal"),
        "gl_Position" => String::from("mtlr_position"),
        "gl_VertexID" => String::from("mtlr_vertex_id"),
        "gl_InstanceID" => String::from("mtlr_instance_id"),
        "gl_FragCoord" => String::from("mtlr_frag_coord"),
        "gl_FragDepth" => String::from("mtlr_frag_depth"),
        "gl_MultiTexCoord0" => String::from("mtlr_texcoord0"),
        "gl_MultiTexCoord1" => String::from("mtlr_texcoord1"),
        "gl_MultiTexCoord2" => String::from("mtlr_texcoord2"),
        "gl_MultiTexCoord3" => String::from("mtlr_texcoord3"),
        "gl_ModelViewMatrix" => String::from("mtlr_model_view"),
        "gl_ModelViewMatrixInverse" => String::from("mtlr_model_view_inverse"),
        "gl_ProjectionMatrix" => String::from("mtlr_projection"),
        "gl_ProjectionMatrixInverse" => String::from("mtlr_projection_inverse"),
        "gl_ModelViewProjectionMatrix" => String::from("mtlr_model_view_projection"),
        "gl_NormalMatrix" => String::from("mtlr_normal_matrix"),
        "gl_TextureMatrix" => String::from("mtlr_texture_matrix"),
        "gl_Fog" => String::from("mtlr_fog"),
        "gl_FogFragCoord" => String::from("mtlr_fog_coord"),
        "gl_FrontColor" => String::from("mtlr_front_color"),
        "gl_TexCoord" => String::from("mtlr_texcoord"),
        "mc_Entity" => String::from("mtlr_entity"),
        "mc_midTexCoord" => String::from("mtlr_mid_texcoord"),
        "at_tangent" => String::from("mtlr_tangent"),
        "at_midBlock" => String::from("mtlr_mid_block"),
        "gl_FragColor" => String::from("mtlr_frag_color"),
        "gl_FragData" => String::from("mtlr_frag_data"),
        "ftransform" => String::from("mtlr_ftransform"),
        _ => String::from(name),
    }
}

fn fragment_outputs(module: &mut Ast, targets: &[u32]) {
    let mut color = false;
    let mut data = false;
    let mut depth = false;
    for declaration in &module.declarations {
        color |= declaration_uses_name(declaration, "mtlr_frag_color");
        data |= declaration_uses_name(declaration, "mtlr_frag_data");
        depth |= declaration_uses_name(declaration, "mtlr_frag_depth");
    }
    if color {
        module.declarations.push(output("mtlr_frag_color", 0, 0));
    }
    if depth {
        module.declarations.push(output_depth("mtlr_frag_depth"));
    }
    if data {
        let values = if targets.is_empty() {
            vec![0]
        } else {
            targets.to_vec()
        };
        for (location, colortex) in values.iter().enumerate() {
            let location = u32::try_from(location).unwrap_or(0);
            let name = format!("mtlr_frag_data_{location}");
            if replace_indexed_output(module, location, &name) {
                module.declarations.push(output(&name, location, *colortex));
            }
        }
    }
}

fn output_depth(name: &str) -> Declaration {
    Declaration {
        span: Span {
            start: 0,
            end: 0,
            line: 1,
            column: 1,
            source_index: 0,
            source_path: String::from("<generated>"),
        },
        qualifiers: vec![Qualifier::Storage(String::from("out"))],
        type_specifier: TypeSpecifier::Float,
        name: Some(String::from(name)),
        array: Vec::new(),
        initializer: None,
        body: None,
        parameters: Vec::new(),
        members: Vec::new(),
    }
}

fn declaration_uses_name(declaration: &Declaration, target: &str) -> bool {
    declaration
        .initializer
        .as_ref()
        .is_some_and(|value| expression_uses_name(value, target))
        || declaration
            .body
            .as_ref()
            .is_some_and(|body| statement_uses_name(body, target))
        || declaration
            .members
            .iter()
            .any(|member| declaration_uses_name(member, target))
}

fn statement_uses_name(statement: &Statement, target: &str) -> bool {
    match statement {
        Statement::Compound(_, values) | Statement::Switch(_, _, values) => values
            .iter()
            .any(|value| statement_uses_name(value, target)),
        Statement::Declaration(value) => declaration_uses_name(value, target),
        Statement::Expression(_, value) => value
            .as_ref()
            .is_some_and(|value| expression_uses_name(value, target)),
        Statement::If(_, condition, yes, no) => {
            expression_uses_name(condition, target)
                || statement_uses_name(yes, target)
                || no
                    .as_ref()
                    .is_some_and(|value| statement_uses_name(value, target))
        }
        Statement::Label(_, value, body) => {
            value
                .as_ref()
                .is_some_and(|value| expression_uses_name(value, target))
                || statement_uses_name(body, target)
        }
        Statement::Loop(_, init, condition, increment, body) => {
            init.as_ref()
                .is_some_and(|value| statement_uses_name(value, target))
                || condition
                    .as_ref()
                    .is_some_and(|value| expression_uses_name(value, target))
                || increment
                    .as_ref()
                    .is_some_and(|value| expression_uses_name(value, target))
                || statement_uses_name(body, target)
        }
        Statement::Jump(_, _, value) => value
            .as_ref()
            .is_some_and(|value| expression_uses_name(value, target)),
        Statement::Empty(_) | Statement::Error(_) => false,
    }
}

fn expression_uses_name(expression: &Expression, target: &str) -> bool {
    match expression {
        Expression::Name(name) => name == target,
        Expression::Unary(_, value) => expression_uses_name(value, target),
        Expression::Binary(_, left, right) => {
            expression_uses_name(left, target) || expression_uses_name(right, target)
        }
        Expression::Conditional(condition, yes, no) => {
            expression_uses_name(condition, target)
                || expression_uses_name(yes, target)
                || expression_uses_name(no, target)
        }
        Expression::Call(function, arguments) => {
            expression_uses_name(function, target)
                || arguments
                    .iter()
                    .any(|value| expression_uses_name(value, target))
        }
        Expression::Index(value, index) => {
            expression_uses_name(value, target) || expression_uses_name(index, target)
        }
        Expression::Field(value, _) => expression_uses_name(value, target),
        Expression::Constructor(_, arguments) => arguments
            .iter()
            .any(|value| expression_uses_name(value, target)),
        Expression::Literal(_) => false,
    }
}

fn replace_indexed_output(module: &mut Ast, index: u32, name: &str) -> bool {
    let mut replaced = false;
    for declaration in &mut module.declarations {
        if let Some(body) = declaration.body.as_mut() {
            replaced |= replace_statement(body, index, name);
        }
    }
    replaced
}

fn replace_statement(statement: &mut Statement, index: u32, name: &str) -> bool {
    match statement {
        Statement::Compound(_, values) | Statement::Switch(_, _, values) => {
            let mut replaced = false;
            for value in values {
                replaced |= replace_statement(value, index, name);
            }
            replaced
        }
        Statement::Declaration(value) => value
            .body
            .as_mut()
            .is_some_and(|body| replace_statement(body, index, name)),
        Statement::Expression(_, value) => value
            .as_mut()
            .is_some_and(|value| replace_expression(value, index, name)),
        Statement::If(_, condition, yes, no) => {
            let mut replaced = replace_expression(condition, index, name);
            replaced |= replace_statement(yes, index, name);
            if let Some(no) = no {
                replaced |= replace_statement(no, index, name);
            }
            replaced
        }
        Statement::Label(_, value, body) => {
            let mut replaced = value
                .as_mut()
                .is_some_and(|value| replace_expression(value, index, name));
            replaced |= replace_statement(body, index, name);
            replaced
        }
        Statement::Loop(_, init, condition, increment, body) => {
            let mut replaced = init
                .as_mut()
                .is_some_and(|init| replace_statement(init, index, name));
            replaced |= condition
                .as_mut()
                .is_some_and(|value| replace_expression(value, index, name));
            replaced |= increment
                .as_mut()
                .is_some_and(|value| replace_expression(value, index, name));
            replaced |= replace_statement(body, index, name);
            replaced
        }
        Statement::Jump(_, _, value) => value
            .as_mut()
            .is_some_and(|value| replace_expression(value, index, name)),
        Statement::Empty(_) | Statement::Error(_) => false,
    }
}

fn replace_expression(expression: &mut Expression, index: u32, name: &str) -> bool {
    if let Expression::Index(value, subscript) = expression
        && let (Expression::Name(value), Expression::Literal(subscript)) =
            (value.as_ref(), subscript.as_ref())
        && value == "mtlr_frag_data"
        && subscript.parse::<u32>().ok() == Some(index)
    {
        *expression = Expression::Name(String::from(name));
        return true;
    }
    match expression {
        Expression::Unary(_, value) => replace_expression(value, index, name),
        Expression::Binary(_, left, right) => {
            let mut replaced = replace_expression(left, index, name);
            replaced |= replace_expression(right, index, name);
            replaced
        }
        Expression::Conditional(condition, yes, no) => {
            let mut replaced = replace_expression(condition, index, name);
            replaced |= replace_expression(yes, index, name);
            replaced |= replace_expression(no, index, name);
            replaced
        }
        Expression::Call(function, arguments) => {
            let mut replaced = replace_expression(function, index, name);
            for argument in arguments {
                replaced |= replace_expression(argument, index, name);
            }
            replaced
        }
        Expression::Index(value, subscript) => {
            let mut replaced = replace_expression(value, index, name);
            replaced |= replace_expression(subscript, index, name);
            replaced
        }
        Expression::Field(value, _) => replace_expression(value, index, name),
        Expression::Constructor(_, arguments) => {
            let mut replaced = false;
            for argument in arguments {
                replaced |= replace_expression(argument, index, name);
            }
            replaced
        }
        Expression::Name(_) | Expression::Literal(_) => false,
    }
}

fn output(name: &str, location: u32, colortex: u32) -> Declaration {
    Declaration {
        span: crate::lex::Span {
            start: 0,
            end: 0,
            line: 1,
            column: 1,
            source_index: 0,
            source_path: String::from("<generated>"),
        },
        qualifiers: vec![
            Qualifier::Storage(String::from("out")),
            Qualifier::Layout(vec![
                (
                    String::from("location"),
                    Some(Expression::Literal(location.to_string())),
                ),
                (
                    String::from("colortex"),
                    Some(Expression::Literal(colortex.to_string())),
                ),
            ]),
        ],
        type_specifier: TypeSpecifier::Vec(4),
        name: Some(String::from(name)),
        array: Vec::new(),
        initializer: None,
        body: None,
        parameters: Vec::new(),
        members: Vec::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::run_ast;
    use crate::{Options, Source, Stage, lex, parse, preprocess};

    fn transformed(stage: Stage, source: &str, targets: &[u32]) -> crate::parse::Ast {
        let prepared = match preprocess::run(
            &[Source::new("test.glsl", source)],
            &[],
            &Options::default(),
        ) {
            Ok(value) => value,
            Err(_) => {
                return crate::parse::Ast {
                    declarations: Vec::new(),
                };
            }
        };
        let tokens = match lex::run(&prepared) {
            Ok(value) => value,
            Err(_) => {
                return crate::parse::Ast {
                    declarations: Vec::new(),
                };
            }
        };
        let mut ast = match parse::run(&tokens, stage) {
            Ok(value) => value,
            Err(_) => {
                return crate::parse::Ast {
                    declarations: Vec::new(),
                };
            }
        };
        let result = run_ast(&mut ast, stage, &Options::default(), targets);
        assert!(result.is_ok());
        ast
    }

    #[test]
    fn legacy_interfaces_follow_the_stage_direction() {
        let vertex = transformed(
            Stage::Vertex,
            "attribute vec3 position; varying vec2 uv; void main() {}",
            &[],
        );
        assert!(vertex.declarations.iter().any(|value| {
            value.name.as_deref() == Some("position")
                && value.qualifiers.iter().any(|item| {
                    matches!(
                        item,
                        crate::parse::Qualifier::Storage(storage) if storage == "in"
                    )
                })
        }));
        assert!(vertex.declarations.iter().any(|value| {
            value.name.as_deref() == Some("uv")
                && value.qualifiers.iter().any(|item| {
                    matches!(
                        item,
                        crate::parse::Qualifier::Storage(storage) if storage == "out"
                    )
                })
        }));
        let fragment = transformed(Stage::Fragment, "varying vec2 uv; void main() {}", &[]);
        assert!(fragment.declarations.iter().any(|value| {
            value.name.as_deref() == Some("uv")
                && value.qualifiers.iter().any(|item| {
                    matches!(
                        item,
                        crate::parse::Qualifier::Storage(storage) if storage == "in"
                    )
                })
        }));
    }

    #[test]
    fn fragment_outputs_map_to_explicit_locations() {
        let fragment = transformed(
            Stage::Fragment,
            "void main() { gl_FragData[0] = vec4(1.0); gl_FragData[1] = vec4(0.5); }",
            &[1, 3],
        );
        assert!(
            fragment
                .declarations
                .iter()
                .any(|value| value.name.as_deref() == Some("mtlr_frag_data_0"))
        );
        assert!(
            fragment
                .declarations
                .iter()
                .any(|value| value.name.as_deref() == Some("mtlr_frag_data_1"))
        );
        let body = fragment
            .declarations
            .iter()
            .find(|value| value.name.as_deref() == Some("main"))
            .and_then(|value| value.body.as_ref());
        assert!(body.is_some());
        let text = format!("{body:?}");
        assert!(text.contains("mtlr_frag_data_0"));
        assert!(text.contains("mtlr_frag_data_1"));
    }
}
