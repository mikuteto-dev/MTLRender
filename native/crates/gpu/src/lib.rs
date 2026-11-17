mod calibration;
mod command;
mod cull;
mod device;
mod encode;
mod eval;
mod expand;
mod format;
mod frames;
mod geometry_compute;
mod geometry_runtime;
mod gpu;
mod hazard;
mod lod;
#[cfg(test)]
mod oit;
mod pathtrace;
mod pipeline;
mod plan;
mod resources;
mod slab;
mod sort;
mod stats;
mod surface;
mod tessellate;
mod timing;

pub use command::{
    Command, Decoder, MESH_DISPATCH_OPCODE, MESH_DISPATCH_WORDS, MESH_MAX_PAYLOAD_BYTES,
    MESH_MAX_PRIMITIVES, MESH_MAX_THREADGROUP_DIMENSION, MESH_MAX_VERTICES, MeshDispatchRequest,
    valid_mesh_dispatch,
};

pub use calibration::{
    CalibrationDrawPath, CalibrationMode, CalibrationPayload, CalibrationProfile,
    CalibrationResolution, METAL_MAX_TEXTURE_DIMENSION,
};
pub use cull::{
    Culler, FeatureHistoryKey, FeatureProjection, FeatureVisibility, FeatureVisibilityProjection,
    feature_depth_occluded, feature_history_identity_changed, feature_hysteresis,
    feature_visibility, project_feature_aabb,
};
pub use device::{Device, SamplerDesc};
pub use eval::{ENERGY_SOURCE, EvalFrame};
pub use geometry_compute::{
    Diagnostics as GeometryDiagnostics, Dispatch as GeometryDispatch,
    Error as GeometryComputeError, IndirectArguments as GeometryIndirectArguments,
    Stage as GeometryStage, compact_indices, diagnostics as geometry_diagnostics,
    exclusive_prefix as geometry_exclusive_prefix, groups as geometry_groups,
    indirect_arguments as geometry_indirect_arguments,
};
pub use geometry_runtime::{
    AccessKind as GeometryAccessKind, AccessMode as GeometryAccessMode,
    AccessRecord as GeometryAccessRecord, AccessSet as GeometryAccessSet,
    CandidateGeneration as GeometryCandidateGeneration, CandidateState as GeometryCandidateState,
    DispatchStats as GeometryDispatchStats, GenerationId as GeometryGenerationId,
    GenerationLifecycle as GeometryGenerationLifecycle, GeometryRuntime,
    GeometryStage as GeometryRuntimeStage, GeometryStages as GeometryRuntimeStages,
    OutputState as GeometryOutputState, ResourceIdentity as GeometryResourceIdentity,
    RetirementIntent as GeometryRetirementIntent,
};
pub use gpu::{Caps, Gpu};
pub use mtl4::TextureShape;
pub use pipeline::ArchiveConfig;
pub use resources::Kind;
pub use stats::nanos;
pub use tessellate::{
    Counts, Domain, Factors, RequestError, Spacing, TessellateRequest, counts, encode_request,
    indices, normalize_factors,
};

pub fn phys_footprint_bytes() -> Option<u64> {
    mtl4::phys_footprint_bytes()
}
pub use surface::{PresentMode, Surface};
