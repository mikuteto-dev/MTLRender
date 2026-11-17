use crate::{
    CompileErrors, Stage,
    parse::{Ast, Declaration, Expression, Qualifier, TypeSpecifier},
    sema::TypedModule,
    tessellation::TessellationDescriptor,
    transform::{
        GEOMETRY_HELPERS, GEOMETRY_RESOURCES, GeometryResourceKind, HelperEntryPoint,
        LoweringFamily, PrimitiveTopology, StageCapabilities, StageEmulationPlan, TessellationPlan,
    },
};

pub const REFLECTION_MAGIC: &[u8; 8] = b"MTLRGLSL";
pub const REFLECTION_VERSION: u32 = 9;
pub const COMPUTE_SUBDIVISION_VERSION: u32 = 1;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ResourceKind {
    UniformBuffer,
    StorageBuffer,
    Texture,
    ShadowTexture,
    Sampler,
    Image,
    AtomicCounter,
}

#[derive(Debug, Eq, PartialEq)]
pub struct Resource {
    pub name: String,
    pub kind: ResourceKind,
    pub dimension: String,
    pub format: String,
    pub binding: u32,
    pub address_space: String,
    pub writable: bool,
    pub helper: Option<String>,
    pub helper_version: u32,
}

#[derive(Debug, Eq, PartialEq)]
pub struct UniformMember {
    pub name: String,
    pub ty: String,
    pub offset: u32,
    pub size: u32,
    pub array_stride: u32,
}

#[derive(Debug, Eq, PartialEq)]
pub struct VertexInput {
    pub name: String,
    pub ty: String,
    pub location: u32,
}

#[derive(Debug, Eq, PartialEq)]
pub struct FragmentOutput {
    pub name: String,
    pub ty: String,
    pub location: u32,
    pub colortex: u32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum InterfaceDirection {
    Input,
    Output,
}

#[derive(Debug, Eq, PartialEq)]
pub struct InterfaceVariable {
    pub direction: InterfaceDirection,
    pub name: String,
    pub ty: String,
    pub interpolation: Option<String>,
    pub location: Option<u32>,
    pub array_length: Option<u32>,
}

#[derive(Debug, Eq, PartialEq)]
pub struct ComputeLocalSize {
    pub x: u32,
    pub y: u32,
    pub z: u32,
    pub helper: Option<String>,
    pub helper_version: u32,
}

#[derive(Debug, Eq, PartialEq)]
pub struct ComputeSubdivision {
    pub version: u32,
    pub topology: String,
    pub spacing: String,
    pub domain_coordinates: u32,
    pub patch_vertices: u32,
    pub output_limit: u32,
    pub output_storage: String,
    pub indirect_draw: String,
    pub workgroup: ComputeLocalSize,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CapabilityState {
    Supported,
    Unsupported,
}

#[derive(Debug, Eq, PartialEq)]
pub struct GeometryReflection {
    pub kind: String,
    pub reason: String,
    pub input_topology: String,
    pub output_topology: String,
    pub order: CapabilityState,
    pub overflow: CapabilityState,
    pub layer_output: CapabilityState,
    pub invocation_id: CapabilityState,
    pub side_effects: CapabilityState,
    pub atomics: CapabilityState,
    pub indirect_draw: String,
    pub produced_vertices: u32,
    pub produced_primitives: u32,
    pub instance_count: u32,
    pub layer_mapping: Vec<u32>,
    pub image_writes: bool,
    pub ssbo_writes: bool,
    pub dynamic_control_flow: bool,
}

#[derive(Debug, Eq, PartialEq)]
pub struct Reflection {
    stage: Stage,
    pub uniform_members: Vec<UniformMember>,
    pub resources: Vec<Resource>,
    pub vertex_inputs: Vec<VertexInput>,
    pub fragment_outputs: Vec<FragmentOutput>,
    pub interfaces: Vec<InterfaceVariable>,
    pub compute_local_size: Option<ComputeLocalSize>,
    pub compute_subdivision: Option<ComputeSubdivision>,
    pub helper_local_sizes: Vec<ComputeLocalSize>,
    pub uses_argument_buffers: bool,
    pub lowering: Option<LoweringFamily>,
    pub capabilities: StageCapabilities,
    pub unsupported_capabilities: StageCapabilities,
    pub helper_entries: Vec<HelperEntryPoint>,
    pub helper_names: Vec<String>,
    pub geometry: Option<GeometryReflection>,
    pub geometry_plan: Option<crate::geometry::GeometryPlan>,
    pub mesh: Option<crate::geometry::mesh::MeshPipelineMetadata>,
    pub msl_standard: String,
    pub tessellation: Option<TessellationDescriptor>,
}

impl Reflection {
    pub fn stage(&self) -> Stage {
        self.stage
    }

    pub fn add_tessellation_resources(&mut self, stage: Stage) {
        let entries: &[(&str, ResourceKind, u32)] = match stage {
            Stage::TessellationControl => &[
                ("tess_patch_inputs", ResourceKind::StorageBuffer, 0_u32),
                ("tess_patch_outputs", ResourceKind::StorageBuffer, 1_u32),
                ("tess_patch_factors", ResourceKind::StorageBuffer, 2_u32),
                ("tess_invocation_count", ResourceKind::UniformBuffer, 3_u32),
            ],
            Stage::TessellationEvaluation => &[
                ("tessellated_vertices", ResourceKind::StorageBuffer, 0_u32),
                ("tess_patch_data", ResourceKind::StorageBuffer, 1_u32),
                ("tess_vertex_count", ResourceKind::UniformBuffer, 2_u32),
            ],
            _ => return,
        };
        for &(name, kind, binding) in entries {
            self.resources.push(Resource {
                name: String::from(name),
                kind,
                dimension: String::from("buffer"),
                format: String::from("raw"),
                binding,
                address_space: String::from("device"),
                writable: matches!(stage, Stage::TessellationControl) && binding != 3,
                helper: Some(String::from("tessellation")),
                helper_version: 1,
            });
        }
    }

    pub fn set_geometry_plan(&mut self, plan: &crate::geometry::GeometryPlan) {
        self.geometry_plan = Some(crate::geometry::GeometryPlan {
            kind: plan.kind,
            reason: String::from(plan.reason.as_str()),
            input_topology: String::from(plan.input_topology.as_str()),
            output_topology: String::from(plan.output_topology.as_str()),
            max_vertices: plan.max_vertices,
            emitted_vertices: plan.emitted_vertices,
            emitted_primitives: plan.emitted_primitives,
            writes_layer: plan.writes_layer,
            dynamic_control_flow: plan.dynamic_control_flow,
            side_effects: plan.side_effects,
            layer_mapping: copy_layers(&plan.layer_mapping),
        });
        self.geometry = Some(GeometryReflection {
            kind: format!("{:?}", plan.kind),
            reason: String::from(plan.reason.as_str()),
            input_topology: String::from(plan.input_topology.as_str()),
            output_topology: String::from(plan.output_topology.as_str()),
            order: CapabilityState::Supported,
            overflow: if plan.max_vertices != 0 && plan.emitted_vertices <= plan.max_vertices {
                CapabilityState::Supported
            } else {
                CapabilityState::Unsupported
            },
            layer_output: if plan.writes_layer {
                CapabilityState::Supported
            } else {
                CapabilityState::Unsupported
            },
            invocation_id: CapabilityState::Unsupported,
            side_effects: if plan.side_effects.image_writes
                || plan.side_effects.ssbo_writes
                || plan.side_effects.atomics
            {
                CapabilityState::Supported
            } else {
                CapabilityState::Unsupported
            },
            atomics: if plan.side_effects.atomics {
                CapabilityState::Supported
            } else {
                CapabilityState::Unsupported
            },
            indirect_draw: String::from("not_lowered"),
            produced_vertices: plan.emitted_vertices,
            produced_primitives: plan.emitted_primitives,
            instance_count: u32::try_from(plan.layer_mapping.len()).unwrap_or_default(),
            layer_mapping: copy_layers(&plan.layer_mapping),
            image_writes: plan.side_effects.image_writes,
            ssbo_writes: plan.side_effects.ssbo_writes,
            dynamic_control_flow: plan.dynamic_control_flow,
        });
    }

    pub fn with_stage_emulation(mut self, plan: &StageEmulationPlan) -> Self {
        self.lowering = Some(plan.family);
        self.capabilities = plan.capabilities;
        self.helper_entries = Vec::with_capacity(plan.helper_entries.len());
        for entry in &plan.helper_entries {
            self.helper_entries.push(*entry);
        }
        self.unsupported_capabilities = StageCapabilities {
            layer_output: !plan.capabilities.layer_output,
            invocation_id: !plan.capabilities.invocation_id,
            side_effects: !plan.capabilities.side_effects,
            atomics: !plan.capabilities.atomics,
            tessellation: !plan.capabilities.tessellation,
        };
        if let Some(geometry) = &plan.compute_geometry {
            self.capabilities.side_effects = geometry.storage_capabilities.storage_writes;
            self.capabilities.atomics = geometry.storage_capabilities.storage_atomics;
            self.unsupported_capabilities.side_effects = !self.capabilities.side_effects;
            self.unsupported_capabilities.atomics = !self.capabilities.atomics;
            self.geometry = Some(GeometryReflection {
                kind: String::from("lowered"),
                reason: String::from("stage emulation lowering plan"),
                input_topology: topology_name(geometry.input_topology),
                output_topology: topology_name(geometry.output_topology),
                order: CapabilityState::Supported,
                overflow: CapabilityState::Supported,
                layer_output: CapabilityState::Unsupported,
                invocation_id: CapabilityState::Unsupported,
                side_effects: if geometry.storage_capabilities.storage_writes {
                    CapabilityState::Supported
                } else {
                    CapabilityState::Unsupported
                },
                atomics: if geometry.storage_capabilities.storage_atomics {
                    CapabilityState::Supported
                } else {
                    CapabilityState::Unsupported
                },
                indirect_draw: String::from("metal_primitives"),
                produced_vertices: geometry.produced_vertices,
                produced_primitives: geometry.produced_primitives,
                instance_count: 0,
                layer_mapping: Vec::new(),
                image_writes: geometry.side_effects.image_writes,
                ssbo_writes: geometry.side_effects.storage_writes,
                dynamic_control_flow: false,
            });
            for helper in GEOMETRY_HELPERS {
                if helper.name == "mtlr_geometry_side_effect_barrier"
                    && !geometry.storage_capabilities.storage_writes
                    && !geometry.storage_capabilities.storage_atomics
                {
                    continue;
                }
                self.helper_names.push(String::from(helper.name));
                if helper.entry_point {
                    self.helper_local_sizes.push(ComputeLocalSize {
                        x: helper.local_size[0],
                        y: helper.local_size[1],
                        z: helper.local_size[2],
                        helper: Some(String::from(helper.name)),
                        helper_version: 1,
                    });
                }
            }
            for resource in GEOMETRY_RESOURCES {
                if resource.kind == GeometryResourceKind::SideEffectStorage
                    && !geometry.storage_capabilities.storage_writes
                {
                    continue;
                }
                if resource.kind == GeometryResourceKind::SideEffectAtomicStorage
                    && !geometry.storage_capabilities.storage_atomics
                {
                    continue;
                }
                self.resources.push(Resource {
                    name: String::from(resource.name),
                    kind: if resource.kind == GeometryResourceKind::SideEffectAtomicStorage {
                        ResourceKind::AtomicCounter
                    } else {
                        ResourceKind::StorageBuffer
                    },
                    dimension: String::from("buffer"),
                    format: String::from("r32ui"),
                    binding: resource.binding,
                    address_space: String::from(if resource.writable {
                        "device"
                    } else {
                        "const_device"
                    }),
                    writable: resource.writable,
                    helper: None,
                    helper_version: 0,
                });
            }
        }
        if let Some(tessellation) = &plan.tessellation {
            self.compute_subdivision = Some(subdivision(tessellation, self.stage));
            add_tessellation_reflection(&mut self, tessellation);
        }
        self
    }
}

pub fn collect(_module: &TypedModule, stage: Stage) -> Result<Reflection, CompileErrors> {
    Ok(Reflection {
        stage,
        uniform_members: Vec::new(),
        resources: Vec::new(),
        vertex_inputs: Vec::new(),
        fragment_outputs: Vec::new(),
        interfaces: Vec::new(),
        compute_local_size: None,
        compute_subdivision: None,
        helper_local_sizes: Vec::new(),
        uses_argument_buffers: false,
        lowering: None,
        capabilities: StageCapabilities::default(),
        unsupported_capabilities: unsupported_capabilities(),
        helper_entries: Vec::new(),
        helper_names: Vec::new(),
        geometry: None,
        geometry_plan: None,
        mesh: None,
        msl_standard: String::from("metal3.2"),
        tessellation: None,
    })
}

pub fn collect_ast(module: &Ast, stage: Stage) -> Result<Reflection, CompileErrors> {
    let mut reflection = Reflection {
        stage,
        uniform_members: Vec::new(),
        resources: Vec::new(),
        vertex_inputs: Vec::new(),
        fragment_outputs: Vec::new(),
        interfaces: Vec::new(),
        compute_local_size: None,
        compute_subdivision: None,
        helper_local_sizes: Vec::new(),
        uses_argument_buffers: false,
        lowering: None,
        capabilities: StageCapabilities::default(),
        unsupported_capabilities: unsupported_capabilities(),
        helper_entries: Vec::new(),
        helper_names: Vec::new(),
        geometry: None,
        geometry_plan: None,
        mesh: None,
        msl_standard: String::from("metal3.2"),
        tessellation: None,
    };
    let mut next_binding = 0_u32;
    let mut next_location = 0_u32;
    for declaration in &module.declarations {
        if let Some(size) = local_size(declaration) {
            reflection.compute_local_size = Some(size);
        }
        if let TypeSpecifier::Interface(_, members) = &declaration.type_specifier
            && has_storage(declaration, "uniform")
        {
            let mut offset = 0_u32;
            for member in members {
                let layout = layout(member, false);
                let aligned = round_up(offset, layout.alignment);
                offset = layout_value(member, "offset").map_or(aligned, |value| aligned.max(value));
                let (size, stride) = member_size(member, layout);
                reflection.uniform_members.push(UniformMember {
                    name: member
                        .name
                        .as_deref()
                        .map_or_else(String::new, String::from),
                    ty: type_name(&member.type_specifier),
                    offset,
                    size,
                    array_stride: stride,
                });
                offset = offset.saturating_add(size);
            }
        }
        let Some(name) = declaration.name.as_deref() else {
            continue;
        };
        if let Some(kind) = resource_kind(declaration) {
            let storage = kind == ResourceKind::StorageBuffer;
            let writable = storage || kind == ResourceKind::Image;
            let binding = layout_value(declaration, "binding").unwrap_or(next_binding);
            next_binding = binding.saturating_add(1);
            reflection.resources.push(Resource {
                name: String::from(name),
                kind,
                dimension: dimension(&declaration.type_specifier),
                format: format(declaration),
                binding,
                address_space: String::from(if storage { "device" } else { "constant" }),
                writable,
                helper: None,
                helper_version: 0,
            });
            continue;
        }
        let direction = if has_storage(declaration, "in") {
            Some(InterfaceDirection::Input)
        } else if has_storage(declaration, "out") {
            Some(InterfaceDirection::Output)
        } else {
            None
        };
        if let Some(direction) = direction {
            reflection.interfaces.push(InterfaceVariable {
                direction,
                name: String::from(name),
                ty: type_name(&declaration.type_specifier),
                interpolation: interpolation(declaration),
                location: layout_value(declaration, "location"),
                array_length: array_length(declaration),
            });
        }
        if stage == Stage::Vertex && has_storage(declaration, "in") {
            let location = layout_value(declaration, "location").unwrap_or(next_location);
            next_location = location.saturating_add(1);
            reflection.vertex_inputs.push(VertexInput {
                name: String::from(name),
                ty: type_name(&declaration.type_specifier),
                location,
            });
        }
        if stage == Stage::Fragment
            && has_storage(declaration, "out")
            && declaration.name.as_deref() != Some("mtlr_frag_depth")
        {
            let location = layout_value(declaration, "location").unwrap_or(next_location);
            next_location = location.saturating_add(1);
            reflection.fragment_outputs.push(FragmentOutput {
                name: String::from(name),
                ty: type_name(&declaration.type_specifier),
                location,
                colortex: layout_value(declaration, "colortex")
                    .or_else(|| layout_value(declaration, "location"))
                    .or_else(|| {
                        name.strip_prefix("colortex")
                            .and_then(|value| value.parse().ok())
                    })
                    .unwrap_or(location),
            });
        }
    }
    Ok(reflection)
}

pub fn encode(reflection: &Reflection, stage: Stage) -> Vec<u8> {
    let mut output = Vec::new();
    output.extend_from_slice(REFLECTION_MAGIC);
    put_u32(&mut output, REFLECTION_VERSION);
    put_u32(&mut output, stage as u32);
    put_u32(&mut output, u32::from(reflection.uses_argument_buffers));
    put_u32(&mut output, reflection.resources.len() as u32);
    put_u32(&mut output, reflection.uniform_members.len() as u32);
    put_u32(&mut output, reflection.vertex_inputs.len() as u32);
    put_u32(&mut output, reflection.fragment_outputs.len() as u32);
    put_u32(&mut output, reflection.interfaces.len() as u32);
    put_u32(
        &mut output,
        u32::from(reflection.compute_local_size.is_some()),
    );
    let local = reflection.compute_local_size.as_ref();
    put_u32(&mut output, local.map_or(0, |value| value.x));
    put_u32(&mut output, local.map_or(0, |value| value.y));
    put_u32(&mut output, local.map_or(0, |value| value.z));
    put_u32(
        &mut output,
        u32::from(reflection.compute_subdivision.is_some()),
    );
    if let Some(subdivision) = &reflection.compute_subdivision {
        put_u32(&mut output, subdivision.version);
        put_string(&mut output, &subdivision.topology);
        put_string(&mut output, &subdivision.spacing);
        put_u32(&mut output, subdivision.domain_coordinates);
        put_u32(&mut output, subdivision.patch_vertices);
        put_u32(&mut output, subdivision.output_limit);
        put_string(&mut output, &subdivision.output_storage);
        put_string(&mut output, &subdivision.indirect_draw);
        put_u32(&mut output, subdivision.workgroup.x);
        put_u32(&mut output, subdivision.workgroup.y);
        put_u32(&mut output, subdivision.workgroup.z);
        put_u32(&mut output, subdivision.workgroup.helper_version);
    }
    put_u32(&mut output, reflection.lowering.map_or(0, lowering_code));
    put_u32(&mut output, capability_bits(reflection.capabilities));
    put_u32(&mut output, reflection.helper_entries.len() as u32);
    for entry in &reflection.helper_entries {
        put_u32(&mut output, helper_entry_code(entry));
    }
    put_u32(&mut output, reflection.helper_names.len() as u32);
    for helper in &reflection.helper_names {
        put_string(&mut output, helper);
    }
    put_u32(&mut output, reflection.helper_local_sizes.len() as u32);
    for local in &reflection.helper_local_sizes {
        put_u32(&mut output, local.x);
        put_u32(&mut output, local.y);
        put_u32(&mut output, local.z);
        put_u32(&mut output, local.helper_version);
        put_string(&mut output, local.helper.as_deref().unwrap_or(""));
    }
    put_u32(&mut output, u32::from(reflection.geometry.is_some()));
    if let Some(geometry) = &reflection.geometry {
        put_string(&mut output, &geometry.kind);
        put_string(&mut output, &geometry.reason);
        put_string(&mut output, &geometry.input_topology);
        put_string(&mut output, &geometry.output_topology);
        put_u32(&mut output, capability_state_code(geometry.order));
        put_u32(&mut output, capability_state_code(geometry.overflow));
        put_u32(&mut output, capability_state_code(geometry.layer_output));
        put_u32(&mut output, capability_state_code(geometry.invocation_id));
        put_u32(&mut output, capability_state_code(geometry.side_effects));
        put_u32(&mut output, capability_state_code(geometry.atomics));
        put_string(&mut output, &geometry.indirect_draw);
        put_u32(&mut output, geometry.produced_vertices);
        put_u32(&mut output, geometry.produced_primitives);
        put_u32(&mut output, geometry.instance_count);
        put_u32(&mut output, geometry.layer_mapping.len() as u32);
        for layer in &geometry.layer_mapping {
            put_u32(&mut output, *layer);
        }
        put_u32(&mut output, u32::from(geometry.image_writes));
        put_u32(&mut output, u32::from(geometry.ssbo_writes));
        put_u32(
            &mut output,
            u32::from(geometry.atomics == CapabilityState::Supported),
        );
        put_u32(&mut output, u32::from(geometry.dynamic_control_flow));
    }
    put_string(&mut output, &reflection.msl_standard);
    put_u32(&mut output, u32::from(reflection.tessellation.is_some()));
    if let Some(tessellation) = &reflection.tessellation {
        put_u32(
            &mut output,
            tessellation.topology.map_or(0, tessellation_topology_code),
        );
        put_u32(
            &mut output,
            tessellation.spacing.map_or(0, tessellation_spacing_code),
        );
        put_u32(&mut output, tessellation.patch_vertices);
        put_u32(&mut output, u32::from(tessellation.uses_invocation_id));
        put_u32(&mut output, u32::from(tessellation.uses_primitive_id));
        put_u32(&mut output, u32::from(tessellation.uses_tess_coord));
        put_u32(&mut output, u32::from(tessellation.uses_tess_levels));
        put_strings(&mut output, &tessellation.control_inputs);
        put_strings(&mut output, &tessellation.control_outputs);
        put_strings(&mut output, &tessellation.evaluation_inputs);
        put_strings(&mut output, &tessellation.evaluation_outputs);
    }
    for resource in &reflection.resources {
        put_u32(&mut output, resource_kind_code(&resource.kind));
        put_u32(&mut output, dimension_code(&resource.dimension));
        put_u32(&mut output, format_code(&resource.format));
        put_u32(&mut output, resource.binding);
        put_u32(&mut output, u32::from(resource.writable));
        put_string(&mut output, &resource.address_space);
        put_u32(&mut output, resource.helper_version);
        put_string(&mut output, resource.helper.as_deref().unwrap_or(""));
        put_string(&mut output, &resource.name);
    }
    for member in &reflection.uniform_members {
        put_u32(&mut output, type_code(&member.ty));
        put_u32(&mut output, member.offset);
        put_u32(&mut output, member.size);
        put_u32(&mut output, member.array_stride);
        put_string(&mut output, &member.name);
    }
    for input in &reflection.vertex_inputs {
        put_u32(&mut output, input.location);
        put_u32(&mut output, type_code(&input.ty));
        put_string(&mut output, &input.name);
    }
    for output_value in &reflection.fragment_outputs {
        put_u32(&mut output, output_value.location);
        put_u32(&mut output, output_value.colortex);
        put_u32(&mut output, type_code(&output_value.ty));
        put_string(&mut output, &output_value.name);
    }
    for interface in &reflection.interfaces {
        put_u32(
            &mut output,
            match interface.direction {
                InterfaceDirection::Input => 0,
                InterfaceDirection::Output => 1,
            },
        );
        put_u32(&mut output, interface.location.unwrap_or(u32::MAX));
        put_u32(&mut output, type_code(&interface.ty));
        put_u32(&mut output, interface.array_length.unwrap_or(u32::MAX));
        put_u32(
            &mut output,
            interpolation_code(interface.interpolation.as_deref()),
        );
        put_string(&mut output, &interface.name);
    }
    output
}

fn copy_layers(values: &[u32]) -> Vec<u32> {
    let mut result = Vec::with_capacity(values.len());
    for value in values {
        result.push(*value);
    }
    result
}

fn put_u32(output: &mut Vec<u8>, value: u32) {
    output.extend_from_slice(&value.to_le_bytes());
}
fn put_string(output: &mut Vec<u8>, value: &str) {
    put_u32(output, value.len() as u32);
    output.extend_from_slice(value.as_bytes());
}

fn put_strings(output: &mut Vec<u8>, values: &[String]) {
    put_u32(output, values.len() as u32);
    for value in values {
        put_string(output, value);
    }
}

fn tessellation_topology_code(value: crate::tessellation::Topology) -> u32 {
    match value {
        crate::tessellation::Topology::Triangles => 1,
        crate::tessellation::Topology::Quads => 2,
        crate::tessellation::Topology::Isolines => 3,
    }
}

fn tessellation_spacing_code(value: crate::tessellation::Spacing) -> u32 {
    match value {
        crate::tessellation::Spacing::Equal => 1,
        crate::tessellation::Spacing::FractionalEven => 2,
        crate::tessellation::Spacing::FractionalOdd => 3,
    }
}
fn lowering_code(value: LoweringFamily) -> u32 {
    match value {
        LoweringFamily::DedicatedPattern => 1,
        LoweringFamily::MeshCompatible => 2,
        LoweringFamily::ComputeEmulation => 3,
    }
}
fn capability_bits(value: StageCapabilities) -> u32 {
    u32::from(value.layer_output)
        | (u32::from(value.invocation_id) << 1)
        | (u32::from(value.side_effects) << 2)
        | (u32::from(value.atomics) << 3)
        | (u32::from(value.tessellation) << 4)
}
fn capability_state_code(value: CapabilityState) -> u32 {
    match value {
        CapabilityState::Supported => 1,
        CapabilityState::Unsupported => 0,
    }
}
fn helper_entry_code(value: &HelperEntryPoint) -> u32 {
    match value {
        HelperEntryPoint::GeometryMesh => 1,
        HelperEntryPoint::TessellationControl => 2,
        HelperEntryPoint::TessellationEvaluation => 3,
        HelperEntryPoint::ComputeEmulation => 4,
    }
}
fn resource_kind_code(kind: &ResourceKind) -> u32 {
    match kind {
        ResourceKind::UniformBuffer => 0,
        ResourceKind::StorageBuffer => 1,
        ResourceKind::Texture => 2,
        ResourceKind::ShadowTexture => 3,
        ResourceKind::Sampler => 4,
        ResourceKind::Image => 5,
        ResourceKind::AtomicCounter => 6,
    }
}
fn dimension_code(value: &str) -> u32 {
    match value {
        "2d" => 0,
        "3d" => 1,
        "cube" => 2,
        _ => 3,
    }
}
fn format_code(value: &str) -> u32 {
    match value {
        "r32f" => 1,
        "r32i" => 2,
        "r32ui" => 3,
        _ => 0,
    }
}
fn type_code(value: &str) -> u32 {
    match value {
        "void" => 0,
        "bool" => 1,
        "int" => 2,
        "uint" => 3,
        "float" => 4,
        "double" => 5,
        "vec2" => 6,
        "vec3" => 7,
        "vec4" => 8,
        "ivec2" => 9,
        "ivec3" => 10,
        "ivec4" => 11,
        "uvec2" => 12,
        "uvec3" => 13,
        "uvec4" => 14,
        "mat2" => 15,
        "mat3" => 16,
        "mat4" => 17,
        _ => 255,
    }
}

fn interpolation(declaration: &Declaration) -> Option<String> {
    declaration.qualifiers.iter().find_map(|qualifier| {
        if let Qualifier::Interpolation(value) = qualifier {
            Some(String::from(value))
        } else {
            None
        }
    })
}
fn interpolation_code(value: Option<&str>) -> u32 {
    match value {
        None => 0,
        Some("smooth") => 1,
        Some("flat") => 2,
        Some("noperspective") => 3,
        Some(_) => 4,
    }
}
fn array_length(declaration: &Declaration) -> Option<u32> {
    declaration.array.first().and_then(|expression| {
        if let Expression::Literal(value) = expression {
            value.parse().ok()
        } else {
            None
        }
    })
}
fn has_storage(declaration: &Declaration, value: &str) -> bool {
    declaration
        .qualifiers
        .iter()
        .any(|item| matches!(item, Qualifier::Storage(name) if name == value))
}
fn layout_value(declaration: &Declaration, name: &str) -> Option<u32> {
    declaration.qualifiers.iter().find_map(|item| {
        if let Qualifier::Layout(values) = item {
            values.iter().find_map(|(key, value)| {
                if key == name {
                    match value {
                        Some(Expression::Literal(text)) => text.parse().ok(),
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
fn local_size(declaration: &Declaration) -> Option<ComputeLocalSize> {
    Some(ComputeLocalSize {
        x: layout_value(declaration, "local_size_x")?,
        y: layout_value(declaration, "local_size_y").unwrap_or(1),
        z: layout_value(declaration, "local_size_z").unwrap_or(1),
        helper: None,
        helper_version: 0,
    })
}
fn resource_kind(declaration: &Declaration) -> Option<ResourceKind> {
    match &declaration.type_specifier {
        TypeSpecifier::Image(_) => Some(ResourceKind::Image),
        TypeSpecifier::Sampler(name) if name == "sampler" => Some(ResourceKind::Sampler),
        TypeSpecifier::Sampler(name)
            if name.starts_with("sampler")
                || name.starts_with("isampler")
                || name.starts_with("usampler") =>
        {
            Some(if name.contains("Shadow") {
                ResourceKind::ShadowTexture
            } else {
                ResourceKind::Texture
            })
        }
        TypeSpecifier::Sampler(name) if name == "atomic_uint" => Some(ResourceKind::AtomicCounter),
        TypeSpecifier::Interface(_, _) if has_storage(declaration, "buffer") => {
            Some(ResourceKind::StorageBuffer)
        }
        TypeSpecifier::Interface(_, _) if has_storage(declaration, "uniform") => {
            Some(ResourceKind::UniformBuffer)
        }
        _ => None,
    }
}
fn dimension(specifier: &TypeSpecifier) -> String {
    match specifier {
        TypeSpecifier::Sampler(name) | TypeSpecifier::Image(name) => {
            if name.contains("3D") {
                String::from("3d")
            } else if name.contains("Cube") {
                String::from("cube")
            } else if name.contains("1D") {
                String::from("1d")
            } else if name.contains("Buffer") {
                String::from("buffer")
            } else if name.contains("MS") {
                String::from("2d_ms")
            } else {
                String::from("2d")
            }
        }
        _ => String::from("buffer"),
    }
}
fn format(declaration: &Declaration) -> String {
    let TypeSpecifier::Image(name) = &declaration.type_specifier else {
        return String::new();
    };
    let explicit = declaration.qualifiers.iter().find_map(|item| {
        if let Qualifier::Layout(values) = item {
            values.iter().find_map(|(key, value)| {
                if value.is_none() && key.contains("32") {
                    Some(key.as_str())
                } else {
                    None
                }
            })
        } else {
            None
        }
    });
    if let Some(value) = explicit {
        return String::from(value);
    }
    if name.starts_with("uimage") {
        String::from("r32ui")
    } else if name.starts_with("iimage") {
        String::from("r32i")
    } else {
        String::from("r32f")
    }
}
fn type_name(specifier: &TypeSpecifier) -> String {
    match specifier {
        TypeSpecifier::Bool => String::from("bool"),
        TypeSpecifier::Int => String::from("int"),
        TypeSpecifier::UInt => String::from("uint"),
        TypeSpecifier::Float => String::from("float"),
        TypeSpecifier::Double => String::from("double"),
        TypeSpecifier::Vec(n) => format!("vec{n}"),
        TypeSpecifier::IVec(n) => format!("ivec{n}"),
        TypeSpecifier::UVec(n) => format!("uvec{n}"),
        TypeSpecifier::Mat(c, r) if c == r => format!("mat{c}"),
        TypeSpecifier::Mat(c, r) => format!("mat{c}x{r}"),
        TypeSpecifier::DVec(n) => format!("dvec{n}"),
        TypeSpecifier::DMat(c, r) => format!("dmat{c}x{r}"),
        TypeSpecifier::Named(name)
        | TypeSpecifier::Struct(name, _)
        | TypeSpecifier::Interface(name, _) => String::from(name),
        _ => String::from("unknown"),
    }
}
struct Layout {
    alignment: u32,
    size: u32,
}
fn layout(declaration: &Declaration, array: bool) -> Layout {
    let base = type_layout(&declaration.type_specifier);
    if array || !declaration.array.is_empty() {
        Layout {
            alignment: round_up(base.alignment, 16),
            size: round_up(base.size, 16),
        }
    } else {
        base
    }
}
fn type_layout(specifier: &TypeSpecifier) -> Layout {
    match specifier {
        TypeSpecifier::Bool | TypeSpecifier::Int | TypeSpecifier::UInt | TypeSpecifier::Float => {
            Layout {
                alignment: 4,
                size: 4,
            }
        }
        TypeSpecifier::Vec(2) | TypeSpecifier::IVec(2) | TypeSpecifier::UVec(2) => Layout {
            alignment: 8,
            size: 8,
        },
        TypeSpecifier::Vec(_) | TypeSpecifier::IVec(_) | TypeSpecifier::UVec(_) => Layout {
            alignment: 16,
            size: 16,
        },
        TypeSpecifier::Mat(columns, _) | TypeSpecifier::DMat(columns, _) => Layout {
            alignment: 16,
            size: u32::from(*columns).saturating_mul(16),
        },
        TypeSpecifier::Struct(_, members) | TypeSpecifier::Interface(_, members) => {
            let mut offset = 0;
            for member in members {
                let value = layout(member, false);
                offset = round_up(offset, value.alignment).saturating_add(value.size);
            }
            Layout {
                alignment: 16,
                size: round_up(offset, 16),
            }
        }
        _ => Layout {
            alignment: 4,
            size: 4,
        },
    }
}
fn member_size(declaration: &Declaration, layout: Layout) -> (u32, u32) {
    if declaration.array.is_empty() {
        (layout.size, 0)
    } else {
        let count = declaration
            .array
            .first()
            .and_then(expression_u32)
            .unwrap_or(1);
        let stride = round_up(layout.size, 16);
        (stride.saturating_mul(count), stride)
    }
}
fn expression_u32(expression: &Expression) -> Option<u32> {
    match expression {
        Expression::Literal(value) => value.parse().ok(),
        _ => None,
    }
}
fn unsupported_capabilities() -> StageCapabilities {
    StageCapabilities {
        layer_output: true,
        invocation_id: true,
        side_effects: true,
        atomics: true,
        tessellation: true,
    }
}

fn subdivision(plan: &TessellationPlan, stage: Stage) -> ComputeSubdivision {
    let helper = match stage {
        Stage::TessellationControl => "mtlr_tessellation_control",
        Stage::TessellationEvaluation => match plan.contract.domain {
            crate::transform::TessellationDomain::Triangles => {
                "mtlr_tessellation_evaluation_triangle"
            }
            crate::transform::TessellationDomain::Quads => "mtlr_tessellation_evaluation_quad",
            crate::transform::TessellationDomain::Isolines => {
                "mtlr_tessellation_evaluation_isoline"
            }
        },
        _ => "",
    };
    ComputeSubdivision {
        version: COMPUTE_SUBDIVISION_VERSION,
        topology: tessellation_domain_name(plan.contract.domain),
        spacing: tessellation_spacing_name(plan.contract.spacing),
        domain_coordinates: plan.contract.domain_coordinates,
        patch_vertices: plan.contract.patch.input_count,
        output_limit: plan.output_limit,
        output_storage: String::from("device"),
        indirect_draw: String::from("metal_primitives"),
        workgroup: ComputeLocalSize {
            x: plan.workgroup_size[0],
            y: plan.workgroup_size[1],
            z: plan.workgroup_size[2],
            helper: Some(String::from(helper)),
            helper_version: COMPUTE_SUBDIVISION_VERSION,
        },
    }
}

fn add_tessellation_reflection(reflection: &mut Reflection, plan: &TessellationPlan) {
    let entry = match reflection.stage {
        Stage::TessellationControl => {
            reflection
                .helper_entries
                .push(HelperEntryPoint::TessellationControl);
            "mtlr_tessellation_control"
        }
        Stage::TessellationEvaluation => {
            reflection
                .helper_entries
                .push(HelperEntryPoint::TessellationEvaluation);
            match plan.contract.domain {
                crate::transform::TessellationDomain::Triangles => {
                    "mtlr_tessellation_evaluation_triangle"
                }
                crate::transform::TessellationDomain::Quads => "mtlr_tessellation_evaluation_quad",
                crate::transform::TessellationDomain::Isolines => {
                    "mtlr_tessellation_evaluation_isoline"
                }
            }
        }
        _ => "",
    };
    reflection.helper_local_sizes.push(ComputeLocalSize {
        x: plan.workgroup_size[0],
        y: plan.workgroup_size[1],
        z: plan.workgroup_size[2],
        helper: Some(String::from(entry)),
        helper_version: COMPUTE_SUBDIVISION_VERSION,
    });
    let domain_helper = match plan.contract.domain {
        crate::transform::TessellationDomain::Triangles => "mtlr_tess_triangle_emit",
        crate::transform::TessellationDomain::Quads => "mtlr_tess_quad_emit",
        crate::transform::TessellationDomain::Isolines => "mtlr_tess_isoline_emit",
    };
    reflection.helper_names.extend([
        String::from("mtlr_tess_patch_index_ok"),
        String::from("mtlr_tess_range_ok"),
        String::from("mtlr_tess_patch_offset"),
    ]);
    match plan.contract.domain {
        crate::transform::TessellationDomain::Triangles => reflection.helper_names.extend([
            String::from("mtlr_tess_triangle_coordinate"),
            String::from("mtlr_tess_triangle_value"),
            String::from(domain_helper),
        ]),
        crate::transform::TessellationDomain::Quads
        | crate::transform::TessellationDomain::Isolines => {
            reflection.helper_names.push(String::from(domain_helper));
        }
    }
    reflection.helper_names.push(String::from(entry));
    reflection
        .helper_names
        .push(String::from("mtlr_tessellation_domain_arguments"));
    let resources = match reflection.stage {
        Stage::TessellationControl => [
            (
                "gl_in",
                ResourceKind::StorageBuffer,
                0,
                false,
                "const_device",
            ),
            ("gl_out", ResourceKind::StorageBuffer, 1, true, "device"),
            (
                "tess_factors",
                ResourceKind::StorageBuffer,
                2,
                true,
                "device",
            ),
            (
                "invocation_count",
                ResourceKind::UniformBuffer,
                3,
                false,
                "constant",
            ),
        ]
        .as_slice(),
        Stage::TessellationEvaluation => [
            (
                "control_points",
                ResourceKind::StorageBuffer,
                0,
                false,
                "const_device",
            ),
            (
                "tess_factors",
                ResourceKind::StorageBuffer,
                1,
                false,
                "const_device",
            ),
            ("outputs", ResourceKind::StorageBuffer, 2, true, "device"),
            ("primitives", ResourceKind::StorageBuffer, 3, true, "device"),
            ("arguments", ResourceKind::StorageBuffer, 4, true, "device"),
            (
                "vertex_offsets",
                ResourceKind::StorageBuffer,
                5,
                false,
                "const_device",
            ),
            (
                "primitive_offsets",
                ResourceKind::StorageBuffer,
                6,
                false,
                "const_device",
            ),
            (
                "patch_count",
                ResourceKind::UniformBuffer,
                7,
                false,
                "constant",
            ),
            (
                "vertex_capacity",
                ResourceKind::UniformBuffer,
                8,
                false,
                "constant",
            ),
            (
                "primitive_capacity",
                ResourceKind::UniformBuffer,
                9,
                false,
                "constant",
            ),
        ]
        .as_slice(),
        _ => [].as_slice(),
    };
    for (name, kind, binding, writable, address_space) in resources {
        reflection.resources.push(Resource {
            name: String::from(*name),
            kind: *kind,
            dimension: String::from("buffer"),
            format: String::new(),
            binding: *binding,
            address_space: String::from(*address_space),
            writable: *writable,
            helper: Some(String::from(match reflection.stage {
                Stage::TessellationControl => "mtlr_tessellation_control",
                Stage::TessellationEvaluation => match plan.contract.domain {
                    crate::transform::TessellationDomain::Triangles => {
                        "mtlr_tessellation_evaluation_triangle"
                    }
                    crate::transform::TessellationDomain::Quads => {
                        "mtlr_tessellation_evaluation_quad"
                    }
                    crate::transform::TessellationDomain::Isolines => {
                        "mtlr_tessellation_evaluation_isoline"
                    }
                },
                _ => "",
            })),
            helper_version: COMPUTE_SUBDIVISION_VERSION,
        });
    }
}

fn tessellation_domain_name(value: crate::transform::TessellationDomain) -> String {
    String::from(match value {
        crate::transform::TessellationDomain::Triangles => "triangles",
        crate::transform::TessellationDomain::Quads => "quads",
        crate::transform::TessellationDomain::Isolines => "isolines",
    })
}

fn tessellation_spacing_name(value: crate::transform::TessellationSpacing) -> String {
    String::from(match value {
        crate::transform::TessellationSpacing::Equal => "equal",
        crate::transform::TessellationSpacing::FractionalOdd => "fractional_odd",
        crate::transform::TessellationSpacing::FractionalEven => "fractional_even",
    })
}

fn topology_name(value: PrimitiveTopology) -> String {
    String::from(match value {
        PrimitiveTopology::Points => "points",
        PrimitiveTopology::Lines => "lines",
        PrimitiveTopology::LineStrip => "line_strip",
        PrimitiveTopology::Triangles => "triangles",
        PrimitiveTopology::TriangleStrip => "triangle_strip",
    })
}

fn round_up(value: u32, alignment: u32) -> u32 {
    if alignment == 0 {
        value
    } else {
        value
            .saturating_add(alignment - 1)
            .checked_div(alignment)
            .map_or(u32::MAX, |quotient| quotient.saturating_mul(alignment))
    }
}
