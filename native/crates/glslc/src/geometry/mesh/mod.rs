#[path = "mesh.rs"]
pub mod lowering;
mod metadata;
pub mod msl;
pub mod object;
pub mod topology;
pub mod validate;

pub use metadata::{
    MAX_OUTPUT_PRIMITIVES, MAX_OUTPUT_VERTICES, MeshFunctionNames, MeshLoweringError,
    MeshLoweringErrorReason, MeshLoweringInput, MeshPipelineMetadata, MeshSideEffects,
    ObjectPayloadRecord, OutputLocation, OutputRecord, PrimitiveTopology,
};
pub use topology::{EmissionEvent, PrimitiveBoundary, TopologyAnalysis};
pub use validate::{ValidationResult, validate};

#[cfg(test)]
mod mesh_tests;
#[cfg(test)]
mod metadata_tests;
#[cfg(test)]
mod object_tests;
#[cfg(test)]
mod validate_tests;
