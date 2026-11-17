use crate::{
    geometry::GeometryPlan,
    reflect::{InterfaceDirection, InterfaceVariable, Reflection, Resource},
};

pub const MAX_OUTPUT_VERTICES: u32 = 256;
pub const MAX_OUTPUT_PRIMITIVES: u32 = 512;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PrimitiveTopology {
    Points,
    Lines,
    LineStrip,
    Triangles,
    TriangleStrip,
}

#[derive(Debug, Eq, PartialEq)]
pub enum MeshLoweringErrorReason {
    MissingVertexStage,
    MissingGeometryStage,
    MissingFragmentStage,
    ComputeCandidate,
    InvalidTopology,
    UnsupportedOrdering,
    InconsistentInterfaces,
    DuplicateEntryName,
    InvalidLayout,
    LayoutOverflow,
    ObjectStageRejected,
    InvalidDispatch,
}

#[derive(Debug, Eq, PartialEq)]
pub struct MeshLoweringError {
    pub reason: MeshLoweringErrorReason,
    pub detail: String,
}

impl MeshLoweringError {
    pub(crate) fn new(reason: MeshLoweringErrorReason, detail: impl Into<String>) -> Self {
        Self {
            reason,
            detail: detail.into(),
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MeshFunctionNames {
    pub object: String,
    pub mesh: String,
    pub fragment: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ObjectPayloadRecord {
    pub offset: u32,
    pub size: u32,
    pub alignment: u32,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OutputRecord {
    pub offset: u32,
    pub size: u32,
    pub stride: u32,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OutputLocation {
    pub name: String,
    pub location: u32,
    pub ty: String,
    pub interpolation: Option<String>,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct MeshSideEffects {
    pub image_writes: bool,
    pub ssbo_writes: bool,
    pub atomics: bool,
    pub barriers: bool,
    pub ordering_sensitive: bool,
}

impl MeshSideEffects {
    pub fn any(self) -> bool {
        self.image_writes
            || self.ssbo_writes
            || self.atomics
            || self.barriers
            || self.ordering_sensitive
    }
}

#[derive(Debug)]
pub struct MeshLoweringInput<'a> {
    pub pack: &'a str,
    pub dimension: &'a str,
    pub program: &'a str,
    pub vertex: Option<&'a Reflection>,
    pub geometry: Option<&'a Reflection>,
    pub fragment: Option<&'a Reflection>,
    pub geometry_plan: Option<&'a GeometryPlan>,
    pub payload: ObjectPayloadRecord,
    pub output_vertex: OutputRecord,
    pub output_primitive: OutputRecord,
    pub side_effects: MeshSideEffects,
}

#[derive(Debug, Eq, PartialEq)]
pub struct MeshPipelineMetadata {
    pub names: MeshFunctionNames,
    pub input_topology: PrimitiveTopology,
    pub output_topology: PrimitiveTopology,
    pub payload: ObjectPayloadRecord,
    pub output_vertex: OutputRecord,
    pub output_primitive: OutputRecord,
    pub output_locations: Vec<OutputLocation>,
    pub resources: Vec<Resource>,
    pub varyings: Vec<InterfaceVariable>,
    pub output_vertices: u32,
    pub output_primitives: u32,
    pub side_effects: MeshSideEffects,
}

impl<'a> MeshLoweringInput<'a> {
    pub fn build(self) -> Result<MeshPipelineMetadata, MeshLoweringError> {
        let vertex = self.vertex.ok_or_else(|| {
            MeshLoweringError::new(
                MeshLoweringErrorReason::MissingVertexStage,
                "mesh candidate requires a vertex stage",
            )
        })?;
        if vertex.stage() != crate::Stage::Vertex {
            return Err(MeshLoweringError::new(
                MeshLoweringErrorReason::MissingVertexStage,
                "mesh candidate vertex reflection has the wrong stage",
            ));
        }
        let geometry = self.geometry.ok_or_else(|| {
            MeshLoweringError::new(
                MeshLoweringErrorReason::MissingGeometryStage,
                "mesh candidate requires a geometry stage",
            )
        })?;
        if geometry.stage() != crate::Stage::Geometry {
            return Err(MeshLoweringError::new(
                MeshLoweringErrorReason::MissingGeometryStage,
                "mesh candidate geometry reflection has the wrong stage",
            ));
        }
        let fragment = self.fragment.ok_or_else(|| {
            MeshLoweringError::new(
                MeshLoweringErrorReason::MissingFragmentStage,
                "mesh candidate requires a fragment stage",
            )
        })?;
        if fragment.stage() != crate::Stage::Fragment {
            return Err(MeshLoweringError::new(
                MeshLoweringErrorReason::MissingFragmentStage,
                "mesh candidate fragment reflection has the wrong stage",
            ));
        }
        let plan = self.geometry_plan.ok_or_else(|| {
            MeshLoweringError::new(
                MeshLoweringErrorReason::MissingGeometryStage,
                "mesh candidate requires geometry analysis",
            )
        })?;
        let validation = super::validate::validate(plan, self.side_effects)?;
        let input_topology = validation.topology.input;
        let output_topology = validation.topology.output;
        validate_payload_layout(&self.payload)?;
        validate_output_layout(&self.output_vertex)?;
        validate_output_layout(&self.output_primitive)?;
        let varyings = matching_varyings(vertex, geometry, fragment)?;
        let output_locations = output_locations(fragment)?;
        let names = names(self.pack, self.dimension, self.program);
        if names.object == names.mesh
            || names.object == names.fragment
            || names.mesh == names.fragment
        {
            return Err(MeshLoweringError::new(
                MeshLoweringErrorReason::DuplicateEntryName,
                "mesh entry names are not distinct",
            ));
        }
        let resources = resources(vertex, geometry, fragment);
        Ok(MeshPipelineMetadata {
            names,
            input_topology,
            output_topology,
            payload: self.payload,
            output_vertex: self.output_vertex,
            output_primitive: self.output_primitive,
            output_locations,
            resources,
            varyings,
            output_vertices: plan.emitted_vertices,
            output_primitives: plan.emitted_primitives,
            side_effects: validation.side_effects,
        })
    }
}

impl Reflection {
    pub fn attach_mesh_metadata(&mut self, metadata: MeshPipelineMetadata) {
        self.mesh = Some(metadata);
    }

    pub fn mesh_metadata(&self) -> Option<&MeshPipelineMetadata> {
        self.mesh.as_ref()
    }
}

fn validate_payload_layout(record: &ObjectPayloadRecord) -> Result<(), MeshLoweringError> {
    if record.alignment == 0
        || !record.alignment.is_power_of_two()
        || !record.offset.is_multiple_of(record.alignment)
    {
        return Err(MeshLoweringError::new(
            MeshLoweringErrorReason::InvalidLayout,
            "payload alignment is invalid",
        ));
    }
    record.offset.checked_add(record.size).ok_or_else(|| {
        MeshLoweringError::new(
            MeshLoweringErrorReason::LayoutOverflow,
            "payload layout overflows",
        )
    })?;
    Ok(())
}

fn validate_output_layout(record: &OutputRecord) -> Result<(), MeshLoweringError> {
    if record.stride == 0 || record.offset.checked_add(record.size).is_none() {
        return Err(MeshLoweringError::new(
            MeshLoweringErrorReason::InvalidLayout,
            "mesh output layout is invalid",
        ));
    }
    Ok(())
}

fn matching_varyings(
    vertex: &Reflection,
    geometry: &Reflection,
    fragment: &Reflection,
) -> Result<Vec<InterfaceVariable>, MeshLoweringError> {
    let mut result = Vec::new();
    for output in vertex
        .interfaces
        .iter()
        .filter(|value| value.direction == InterfaceDirection::Output)
    {
        let fragment_input = fragment.interfaces.iter().find(|value| {
            value.direction == InterfaceDirection::Input && value.name == output.name
        });
        let geometry_input = geometry.interfaces.iter().find(|value| {
            value.direction == InterfaceDirection::Input && value.name == output.name
        });
        let Some(fragment_input) = fragment_input else {
            return Err(MeshLoweringError::new(
                MeshLoweringErrorReason::InconsistentInterfaces,
                "vertex output has no fragment input",
            ));
        };
        let Some(geometry_input) = geometry_input else {
            return Err(MeshLoweringError::new(
                MeshLoweringErrorReason::InconsistentInterfaces,
                "vertex output has no geometry input",
            ));
        };
        if output.ty != fragment_input.ty
            || output.ty != geometry_input.ty
            || output.interpolation != fragment_input.interpolation
            || output.location != fragment_input.location
            || output.location != geometry_input.location
            || output.array_length != fragment_input.array_length
            || output.array_length != geometry_input.array_length
        {
            return Err(MeshLoweringError::new(
                MeshLoweringErrorReason::InconsistentInterfaces,
                "stage varying interfaces disagree",
            ));
        }
        result.push(InterfaceVariable {
            direction: output.direction,
            name: String::from(output.name.as_str()),
            ty: String::from(output.ty.as_str()),
            interpolation: output.interpolation.as_deref().map(String::from),
            location: output.location,
            array_length: output.array_length,
        });
    }
    Ok(result)
}

fn output_locations(reflection: &Reflection) -> Result<Vec<OutputLocation>, MeshLoweringError> {
    let mut result = Vec::new();
    for output in &reflection.fragment_outputs {
        if result
            .iter()
            .any(|value: &OutputLocation| value.location == output.location)
        {
            return Err(MeshLoweringError::new(
                MeshLoweringErrorReason::InconsistentInterfaces,
                "fragment output locations are duplicated",
            ));
        }
        result.push(OutputLocation {
            name: String::from(output.name.as_str()),
            location: output.location,
            ty: String::from(output.ty.as_str()),
            interpolation: None,
        });
    }
    Ok(result)
}

fn resources(vertex: &Reflection, geometry: &Reflection, fragment: &Reflection) -> Vec<Resource> {
    let mut result = Vec::new();
    for source in [vertex, geometry, fragment] {
        for resource in &source.resources {
            if !result.iter().any(|value: &Resource| {
                value.binding == resource.binding && value.kind == resource.kind
            }) {
                result.push(Resource {
                    name: String::from(resource.name.as_str()),
                    kind: resource.kind,
                    dimension: String::from(resource.dimension.as_str()),
                    format: String::from(resource.format.as_str()),
                    binding: resource.binding,
                    address_space: String::from(resource.address_space.as_str()),
                    writable: resource.writable,
                    helper: resource.helper.as_deref().map(String::from),
                    helper_version: resource.helper_version,
                });
            }
        }
    }
    result.sort_by(|left, right| {
        left.binding
            .cmp(&right.binding)
            .then_with(|| left.name.cmp(&right.name))
    });
    result
}

fn names(pack: &str, dimension: &str, program: &str) -> MeshFunctionNames {
    let mut hash = 0xcbf29ce484222325_u64;
    for value in [pack, dimension, program] {
        for byte in value.as_bytes() {
            hash ^= u64::from(*byte);
            hash = hash.wrapping_mul(0x100000001b3);
        }
        hash ^= 0xff;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    MeshFunctionNames {
        object: format!("mtlr_object_{hash:016x}"),
        mesh: format!("mtlr_mesh_{hash:016x}"),
        fragment: format!("mtlr_fragment_{hash:016x}"),
    }
}
