use super::{MeshLoweringError, MeshLoweringInput, MeshPipelineMetadata};
use super::{msl, object};

#[derive(Debug, Eq, PartialEq)]
pub struct MeshLowering {
    pub msl: String,
    pub metadata: MeshPipelineMetadata,
}

pub fn lower(
    input: MeshLoweringInput<'_>,
    object_threadgroups: [u32; 3],
    mesh_threadgroups: [u32; 3],
) -> Result<MeshLowering, MeshLoweringError> {
    let object = object::lower(input, object_threadgroups, mesh_threadgroups)?;
    let mesh = msl::generate(&object.metadata, mesh_threadgroups)?;
    let mut generated = object.msl;
    generated.push('\n');
    generated.push_str(mesh.as_str());
    Ok(MeshLowering {
        msl: generated,
        metadata: object.metadata,
    })
}

pub fn metadata(input: MeshLoweringInput<'_>) -> Result<MeshPipelineMetadata, MeshLoweringError> {
    input.build()
}
