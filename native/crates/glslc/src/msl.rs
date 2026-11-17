use crate::{
    CompileError, CompileErrors, Location, Options, Stage,
    depth::{fix_pack_fragment_depth, fix_pack_vertex_depth},
    parse::{Ast, Declaration, Expression, Qualifier, Statement, TypeSpecifier},
    transform::{
        ComputeGeometryPlan, IndirectDrawLayout, OutputOffsetKind, PrefixSumKind,
        SideEffectOrdering, TessellationDomain, TessellationPlan, validate_atomic_operations,
    },
};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Eq, PartialEq)]
pub struct GeneratedMsl {
    pub text: String,
}

pub fn generate(
    module: &Ast,
    stage: Stage,
    options: &Options,
    geometry: Option<&ComputeGeometryPlan>,
) -> Result<GeneratedMsl, CompileErrors> {
    generate_with_tessellation(module, stage, options, geometry, None)
}

pub fn generate_with_tessellation(
    module: &Ast,
    stage: Stage,
    options: &Options,
    geometry: Option<&ComputeGeometryPlan>,
    tessellation: Option<&TessellationPlan>,
) -> Result<GeneratedMsl, CompileErrors> {
    if let Some(plan) = geometry {
        validate_compute_plan(plan)?;
    }
    validate_tessellation_stage(stage, tessellation)?;
    validate_atomic_operations(module)?;
    let mut emitter = Emitter {
        text: String::from("#include <metal_stdlib>\nusing namespace metal;\n\n"),
        next_buffer: 0,
        next_texture: 0,
        next_sampler: 0,
        stage,
        sampler_types: BTreeMap::new(),
        function_names: BTreeSet::new(),
        capture_names: Vec::new(),
    };
    for declaration in &module.declarations {
        if let (Some(name), TypeSpecifier::Sampler(kind)) =
            (declaration.name.as_deref(), &declaration.type_specifier)
        {
            emitter
                .sampler_types
                .insert(String::from(name), String::from(kind));
        }
        if declaration.body.is_some()
            && declaration.name.as_deref() != Some("main")
            && let Some(name) = declaration.name.as_deref()
        {
            emitter.function_names.insert(String::from(name));
        }
    }
    emitter.capture_names = capture_names(module);
    emitter.text.push_str(ATOMIC_HELPERS);
    if let Some(plan) = geometry {
        emitter.compute_geometry_helpers(plan);
    }
    if let Some(plan) = tessellation {
        emitter.tessellation_helpers(plan);
    }
    for declaration in &module.declarations {
        if matches!(declaration.type_specifier, TypeSpecifier::Interface(_, _)) {
            emitter.interface(declaration);
        }
    }
    for declaration in &module.declarations {
        if declaration.body.is_none()
            && declaration.name.is_some()
            && !matches!(declaration.type_specifier, TypeSpecifier::Interface(_, _))
            && !is_interface_value(declaration)
        {
            emitter.global(declaration);
        }
    }
    if stage == Stage::Compute {
        emitter.compute(module);
    } else if !matches!(
        stage,
        Stage::TessellationControl | Stage::TessellationEvaluation
    ) {
        emitter.graphics(module);
    }
    let _ = options;
    let text = match stage {
        Stage::Vertex => fix_pack_vertex_depth(&emitter.text),
        Stage::Fragment => fix_pack_fragment_depth(&emitter.text),
        _ => emitter.text,
    };
    Ok(GeneratedMsl { text })
}

fn validate_tessellation_stage(
    stage: Stage,
    plan: Option<&TessellationPlan>,
) -> Result<(), CompileErrors> {
    if !matches!(
        stage,
        Stage::TessellationControl | Stage::TessellationEvaluation
    ) {
        if plan.is_some() {
            return Err(CompileErrors::one(CompileError::unsupported_at(
                "tessellation lowering plan is only valid for a tessellation stage",
                Some(Location::new("<tessellation>", 1, 1)),
            )));
        }
        return Ok(());
    }
    let Some(plan) = plan else {
        return Err(CompileErrors::one(CompileError::unsupported_at(
            "tessellation stage requires a validated compute lowering plan",
            Some(Location::new("<tessellation>", 1, 1)),
        )));
    };
    if plan.control.patch.input_count == 0 || plan.control.patch.input_count > 32 {
        return Err(CompileErrors::one(CompileError::unsupported_at(
            "tessellation patch control-point count is outside the compute helper limit",
            Some(Location::new(
                plan.location.file.as_str(),
                plan.location.line,
                plan.location.column,
            )),
        )));
    }
    if plan.control.side_effects.image_writes
        || plan.control.side_effects.storage_writes
        || plan.control.side_effects.atomics
        || plan.control.atomics_required
    {
        return Err(CompileErrors::one(CompileError::unsupported_at(
            "tessellation side effects and atomics are outside the compute helper contract",
            Some(Location::new(
                plan.control.location.file.as_str(),
                plan.control.location.line,
                plan.control.location.column,
            )),
        )));
    }
    let subdivision_present = match plan.contract.domain {
        TessellationDomain::Triangles => true,
        TessellationDomain::Quads => plan.quad.is_some(),
        TessellationDomain::Isolines => plan.isoline.is_some(),
    };
    if !subdivision_present {
        return Err(CompileErrors::one(CompileError::unsupported_at(
            "tessellation topology has no validated subdivision plan",
            Some(Location::new(
                plan.location.file.as_str(),
                plan.location.line,
                plan.location.column,
            )),
        )));
    }
    validate_tessellation_limits(plan)
}

fn validate_tessellation_limits(plan: &TessellationPlan) -> Result<(), CompileErrors> {
    let location = Some(Location::new(
        plan.location.file.as_str(),
        plan.location.line,
        plan.location.column,
    ));
    let workgroup_product = plan.workgroup_size[0]
        .checked_mul(plan.workgroup_size[1])
        .and_then(|value| value.checked_mul(plan.workgroup_size[2]));
    if plan.workgroup_size.contains(&0) || workgroup_product.is_none_or(|value| value > 1024) {
        return Err(CompileErrors::one(CompileError::unsupported_at(
            "tessellation workgroup size exceeds the Metal compute workgroup limit",
            location,
        )));
    }
    if plan.output_limit == 0 || plan.output_limit > 256 {
        return Err(CompileErrors::one(CompileError::unsupported_at(
            "tessellation evaluation output exceeds the compute subdivision limit of 256",
            location,
        )));
    }
    let (vertices, primitives, invocations) = match plan.contract.domain {
        TessellationDomain::Triangles => {
            let factor = u64::from(plan.contract.outer_factor_range.maximum);
            (
                (factor + 1) * (factor + 2) / 2,
                factor * factor,
                (factor + 1) * (factor + 2) / 2,
            )
        }
        TessellationDomain::Quads => match plan.quad.as_ref() {
            Some(value) => (
                value.coordinates.len() as u64,
                value.primitives.len() as u64,
                value.generated_invocation_ids.len() as u64,
            ),
            None => (0, 0, 0),
        },
        TessellationDomain::Isolines => match plan.isoline.as_ref() {
            Some(value) => (
                value.coordinates.len() as u64,
                value.primitives.len() as u64,
                value.generated_invocation_ids.len() as u64,
            ),
            None => (0, 0, 0),
        },
    };
    if vertices > 4096 || primitives > 8192 || invocations > 4096 {
        return Err(CompileErrors::one(CompileError::unsupported_at(
            "tessellation output exceeds the selected topology vertex, primitive, or invocation limit",
            location,
        )));
    }
    Ok(())
}

struct Emitter {
    text: String,
    next_buffer: u32,
    next_texture: u32,
    next_sampler: u32,
    stage: Stage,
    sampler_types: BTreeMap<String, String>,
    function_names: BTreeSet<String>,
    capture_names: Vec<String>,
}

impl Emitter {
    fn interface(&mut self, declaration: &Declaration) {
        let (name, members) = match &declaration.type_specifier {
            TypeSpecifier::Interface(name, members) => (name, members),
            _ => return,
        };
        self.text.push_str("struct ");
        self.text.push_str(name);
        self.text.push_str(" {\n");
        for member in members {
            self.member(member);
        }
        self.text.push_str("};\n");
    }

    fn member(&mut self, declaration: &Declaration) {
        self.text.push_str("    ");
        self.text.push_str(&self.ty(&declaration.type_specifier));
        self.text.push(' ');
        self.text
            .push_str(declaration.name.as_deref().unwrap_or("member"));
        self.array_suffix(declaration);
        self.interpolation(declaration);
        self.text.push_str(";\n");
    }

    fn vertex_input_member(&mut self, declaration: &Declaration) {
        self.text.push_str("    ");
        self.text.push_str(&self.ty(&declaration.type_specifier));
        self.text.push(' ');
        self.text
            .push_str(declaration.name.as_deref().unwrap_or("input"));
        self.array_suffix(declaration);
        self.text.push_str(" [[attribute(");
        self.text.push_str(
            &layout_value(declaration, "location")
                .unwrap_or(0)
                .to_string(),
        );
        self.text.push_str(")]];\n");
    }

    fn interpolation(&mut self, declaration: &Declaration) {
        for qualifier in &declaration.qualifiers {
            if let Qualifier::Interpolation(value) = qualifier {
                let attribute = match value.as_str() {
                    "flat" => "flat",
                    "noperspective" => "center_no_perspective",
                    "smooth" => "center_perspective",
                    _ => "",
                };
                if !attribute.is_empty() {
                    self.text.push_str(" [[");
                    self.text.push_str(attribute);
                    self.text.push_str("]] ");
                }
            }
        }
        if is_integer(&declaration.type_specifier)
            && declaration
                .qualifiers
                .iter()
                .any(|value| matches!(value, Qualifier::Interpolation(name) if name == "flat"))
            && !has_interpolation(declaration, "flat")
        {
            self.text.push_str(" [[flat]] ");
        }
    }

    fn global(&mut self, declaration: &Declaration) {
        let Some(name) = declaration.name.as_deref() else {
            return;
        };
        if has_storage(declaration, "in")
            || has_storage(declaration, "out")
            || has_storage(declaration, "buffer")
            || has_storage(declaration, "uniform")
        {
            return;
        }
        if has_storage(declaration, "shared") {
            return;
        }
        if !has_storage(declaration, "const") {
            return;
        }
        self.text.push_str("constant ");
        self.text.push_str(&self.ty(&declaration.type_specifier));
        self.text.push(' ');
        self.text.push_str(name);
        self.array_suffix(declaration);
        if let Some(value) = &declaration.initializer {
            self.text.push_str(" = ");
            self.expression(value);
        }
        self.text.push_str(";\n");
    }

    fn compute_geometry_helpers(&mut self, plan: &ComputeGeometryPlan) {
        self.text
            .push_str("struct MtlrGeometryCounts { uint vertices; uint primitives; };\n");
        self.text.push_str("struct MtlrGeometryIndirectArguments { uint vertexCount; uint instanceCount; uint vertexStart; uint baseInstance; };\n");
        self.text.push_str("struct MtlrGeometryVertexRecord { float4 position; uint sourceInvocation; uint vertexOrdinal; uint layer; };\n");
        self.text.push_str("struct MtlrGeometryPrimitiveRecord { uint3 vertices; uint sourceInvocation; uint primitiveOrdinal; uint layer; };\n");
        self.text.push_str("inline MtlrGeometryCounts mtlr_geometry_counts(uint vertices, uint primitives) { return { vertices, primitives }; }\n");
        if plan.prefix_sums.contains(&PrefixSumKind::Group) {
            self.text.push_str("inline uint mtlr_geometry_group_prefix(const device uint *counts, device uint *prefix, uint index) { uint sum = 0; for (uint i = 0; i < index; ++i) { sum += counts[i]; } prefix[index] = sum; return sum; }\n");
        }
        if plan.prefix_sums.contains(&PrefixSumKind::Global) {
            self.text.push_str("inline uint mtlr_geometry_global_prefix(const device uint *group_totals, device uint *prefix, uint group_count, uint index) { uint sum = 0; for (uint i = 0; i < index && i < group_count; ++i) { sum += group_totals[i]; } prefix[index] = sum; return sum; }\n");
        }
        if plan.output_offsets == OutputOffsetKind::SourcePrimitiveOrder {
            self.text.push_str("inline uint mtlr_geometry_output_offset(uint global_prefix, uint invocation_ordinal) { return global_prefix + invocation_ordinal; }\n");
            self.text.push_str("inline uint mtlr_geometry_record_offset(uint global_prefix, uint invocation_ordinal, uint records_per_invocation) { return global_prefix + invocation_ordinal * records_per_invocation; }\n");
        }
        self.text.push_str("inline bool mtlr_geometry_capacity_ok(uint vertex_offset, uint primitive_offset, uint vertices, uint primitives, uint vertex_capacity, uint primitive_capacity) { return vertex_offset <= vertex_capacity && primitive_offset <= primitive_capacity && vertices <= vertex_capacity - vertex_offset && primitives <= primitive_capacity - primitive_offset; }\n");
        self.text.push_str(&format!("inline uint mtlr_geometry_input_index(uint invocation, uint vertex_index) {{ return {}; }}\n", input_index_expression(plan.input_topology)));
        self.text.push_str(&format!("inline uint3 mtlr_geometry_output_indices(uint vertex_offset, uint primitive_index) {{ return {}; }}\n", output_index_expression(plan.output_topology)));
        self.text.push_str("inline void mtlr_geometry_write_vertex(device MtlrGeometryVertexRecord *records, uint offset, uint source_invocation, uint vertex_ordinal, uint layer, float4 position) { records[offset] = { position, source_invocation, vertex_ordinal, layer }; }\n");
        self.text.push_str("inline void mtlr_geometry_write_primitive(device MtlrGeometryPrimitiveRecord *records, uint offset, uint source_invocation, uint primitive_ordinal, uint layer, uint3 vertices) { records[offset] = { vertices, source_invocation, primitive_ordinal, layer }; }\n");
        self.text.push_str("inline void mtlr_geometry_emit_primitive(device MtlrGeometryPrimitiveRecord *records, uint offset, uint source_invocation, uint primitive_ordinal, uint layer, uint vertex_offset) { mtlr_geometry_write_primitive(records, offset, source_invocation, primitive_ordinal, layer, mtlr_geometry_output_indices(vertex_offset, primitive_ordinal)); }\n");
        match plan.indirect_draw {
            IndirectDrawLayout::MetalPrimitives => {
                self.text.push_str("inline MtlrGeometryIndirectArguments mtlr_geometry_indirect(uint vertex_count, uint vertex_start) { return { vertex_count, 1u, vertex_start, 0u }; }\n");
            }
            IndirectDrawLayout::MetalIndexed => {
                self.text.push_str("inline MtlrGeometryIndirectArguments mtlr_geometry_indirect(uint index_count, uint index_start) { return { index_count, 1u, index_start, 0u }; }\n");
            }
        }
        self.text.push_str("inline void mtlr_geometry_producer_consumer_barrier() { threadgroup_barrier(mem_flags::mem_device | mem_flags::mem_threadgroup); }\n");
        if plan.storage_capabilities.storage_atomics || plan.storage_capabilities.storage_writes {
            self.text.push_str("inline void mtlr_geometry_side_effect_barrier() { atomic_thread_fence(mem_flags::mem_device, memory_order_seq_cst); }\n");
        }
        self.geometry_vertex_entry();
        self.geometry_invocation_entry(plan);
        self.geometry_indirect_entry();
    }

    fn geometry_vertex_entry(&mut self) {
        self.text.push_str("kernel void mtlr_geometry_vertex_stage(const device float4 *input_positions [[buffer(0)]], device MtlrGeometryVertexRecord *vertex_records [[buffer(1)]], constant uint &input_count [[buffer(2)]], constant uint &vertex_capacity [[buffer(3)]], uint3 gid [[thread_position_in_grid]]) { uint invocation = gid.x; if (invocation >= input_count || invocation >= vertex_capacity) return; mtlr_geometry_write_vertex(vertex_records, invocation, invocation, 0u, 0u, input_positions[invocation]); }\n");
    }

    fn geometry_invocation_entry(&mut self, plan: &ComputeGeometryPlan) {
        let side_effect_parameter = if plan.storage_capabilities.storage_writes
            || plan.storage_capabilities.storage_atomics
        {
            ", device uint *side_effect_storage [[buffer(8)]]"
        } else {
            ""
        };
        let atomic_parameter = if plan.storage_capabilities.storage_atomics {
            ", device atomic_uint *side_effect_atomic_storage [[buffer(9)]]"
        } else {
            ""
        };
        let serial = match plan.side_effect_ordering {
            SideEffectOrdering::SourceInvocationSerial => {
                "if (gid.x != 0u) return; for (uint invocation = 0u; invocation < invocation_count; ++invocation) "
            }
        };
        let invocation = match plan.side_effect_ordering {
            SideEffectOrdering::SourceInvocationSerial => "invocation",
        };
        let barrier = if plan.storage_capabilities.storage_writes
            || plan.storage_capabilities.storage_atomics
        {
            " mtlr_geometry_side_effect_barrier();"
        } else {
            ""
        };
        self.text.push_str(&format!("kernel void mtlr_geometry_stage(const device MtlrGeometryVertexRecord *vertex_records [[buffer(0)]], device MtlrGeometryVertexRecord *output_vertices [[buffer(1)]], device MtlrGeometryPrimitiveRecord *output_primitives [[buffer(2)]], const device uint *vertex_prefix_offsets [[buffer(3)]], const device uint *primitive_prefix_offsets [[buffer(4)]], constant uint &invocation_count [[buffer(5)]], constant uint &vertex_capacity [[buffer(6)]], constant uint &primitive_capacity [[buffer(7)]]{side_effect_parameter}{atomic_parameter}, uint3 gid [[thread_position_in_grid]]) {{ {serial}{{ uint vertex_offset = mtlr_geometry_record_offset(vertex_prefix_offsets[{invocation}], {invocation}, {}u); uint primitive_offset = mtlr_geometry_record_offset(primitive_prefix_offsets[{invocation}], {invocation}, {}u); if (!mtlr_geometry_capacity_ok(vertex_offset, primitive_offset, {}u, {}u, vertex_capacity, primitive_capacity)) return; for (uint vertex_index = 0; vertex_index < {}u; ++vertex_index) {{ uint source = mtlr_geometry_input_index({invocation}, vertex_index); mtlr_geometry_write_vertex(output_vertices, vertex_offset + vertex_index, {invocation}, vertex_index, vertex_records[source].layer, vertex_records[source].position); }} for (uint primitive_index = 0; primitive_index < {}u; ++primitive_index) {{ mtlr_geometry_emit_primitive(output_primitives, primitive_offset + primitive_index, {invocation}, primitive_index, vertex_records[mtlr_geometry_input_index({invocation}, 0u)].layer, vertex_offset); }}{barrier} }} }}\n", plan.produced_vertices, plan.produced_primitives, plan.produced_vertices, plan.produced_primitives, plan.produced_vertices, plan.produced_primitives));
    }

    fn geometry_indirect_entry(&mut self) {
        self.text.push_str("kernel void mtlr_geometry_indirect_stage(const device uint *vertex_counts [[buffer(0)]], const device uint *vertex_offsets [[buffer(1)]], device MtlrGeometryIndirectArguments *arguments [[buffer(2)]], constant uint &draw_count [[buffer(3)]], uint3 gid [[thread_position_in_grid]]) { if (gid.x != 0u) return; uint vertices = 0u; for (uint i = 0; i < draw_count; ++i) vertices += vertex_counts[i]; arguments[0] = mtlr_geometry_indirect(vertices, vertex_offsets[0]); }\n");
    }

    fn tessellation_helpers(&mut self, plan: &TessellationPlan) {
        self.text.push_str("struct MtlrTessControlPoint { float4 value; };\nstruct MtlrTessFactors { float outer[4]; float inner[2]; };\nstruct MtlrTessEvaluationInput { uint patch; uint invocation; float2 domain; };\nstruct MtlrTessEvaluationOutput { float4 value; uint patch; uint invocation; float2 domain; };\nstruct MtlrTessPrimitive { uint3 vertices; uint patch; uint primitive; };\nstruct MtlrTessIndirectArguments { uint vertexCount; uint instanceCount; uint vertexStart; uint baseInstance; };\n");
        self.text.push_str("inline bool mtlr_tess_patch_index_ok(uint patch, uint patch_count) { return patch < patch_count; }\ninline bool mtlr_tess_range_ok(uint offset, uint count, uint capacity) { return offset <= capacity && count <= capacity - offset; }\ninline uint mtlr_tess_patch_offset(const device uint *offsets, uint patch, uint count, uint capacity) { return offsets[patch]; }\n");
        match plan.contract.domain {
            TessellationDomain::Triangles => self.triangle_helpers(plan),
            TessellationDomain::Quads => self.quad_helpers(plan),
            TessellationDomain::Isolines => self.isoline_helpers(plan),
        }
        if self.stage == Stage::TessellationControl {
            self.tessellation_control_entry(plan);
        } else {
            self.tessellation_evaluation_entry(plan);
        }
        self.text.push_str("inline void mtlr_tessellation_domain_arguments(const device MtlrTessEvaluationInput *invocations, uint invocation, thread float2 &domain, thread uint &patch, thread uint &identity) { MtlrTessEvaluationInput input = invocations[invocation]; domain = input.domain; patch = input.patch; identity = input.invocation; }\n");
    }

    fn triangle_helpers(&mut self, plan: &TessellationPlan) {
        let factor = plan.contract.outer_factor_range.maximum;
        let vertices = (factor + 1) * (factor + 2) / 2;
        let primitives = factor * factor;
        self.text.push_str("inline float2 mtlr_tess_triangle_coordinate(uint index, uint factor) { uint row = 0u; uint row_start = 0u; while (row < factor && index >= row_start + row + 1u) { row_start += row + 1u; row += 1u; } uint column = index - row_start; return float2(float(column) / float(factor), float(row - column) / float(factor)); }\n");
        self.text.push_str(&format!("inline float4 mtlr_tess_triangle_value(const device MtlrTessControlPoint *points, uint base, float2 d) {{ float a = 1.0 - d.x - d.y; return points[base].value * a + points[base + 1u].value * d.x + points[base + 2u].value * d.y; }}\ninline void mtlr_tess_triangle_emit(const device MtlrTessFactors *factors, const device MtlrTessControlPoint *points, device MtlrTessEvaluationOutput *vertices, device MtlrTessPrimitive *primitives, device MtlrTessIndirectArguments *arguments, const device uint *vertex_offsets, const device uint *primitive_offsets, uint patch, uint patch_count, uint vertex_capacity, uint primitive_capacity) {{ uint factor = uint(ceil(factors[patch].outer[0])); uint vertex_count = (factor + 1u) * (factor + 2u) / 2u; uint primitive_count = factor * factor; uint vertex_base = mtlr_tess_patch_offset(vertex_offsets, patch, vertex_count, vertex_capacity); uint primitive_base = mtlr_tess_patch_offset(primitive_offsets, patch, primitive_count, primitive_capacity); if (!mtlr_tess_range_ok(vertex_base, vertex_count, vertex_capacity) || !mtlr_tess_range_ok(primitive_base, primitive_count, primitive_capacity)) return; uint control_base = patch * 3u; for (uint i = 0u; i < {}u && i < vertex_count; ++i) {{ float2 d = mtlr_tess_triangle_coordinate(i, factor); vertices[vertex_base + i] = {{ mtlr_tess_triangle_value(points, control_base, d), patch, i, d }}; }} for (uint i = 0u; i < {}u && i < primitive_count; ++i) {{ uint row = 0u; while (row + 1u < factor && i >= (row + 1u) * (row + 1u)) row += 1u; uint local = i - row * row; uint column = local / 2u; uint row_start = row * (row + 1u) / 2u; uint next_start = (row + 1u) * (row + 2u) / 2u; uint a = row_start + column; uint b = a + 1u; uint c = next_start + column; if ((local & 1u) != 0u) {{ a = b; b = next_start + column + 1u; }} primitives[primitive_base + i] = {{ uint3(vertex_base + a, vertex_base + b, vertex_base + c), patch, i }}; }} arguments[patch] = {{ primitive_count * 3u, 1u, vertex_base, 0u }}; }}\n", vertices, primitives));
    }

    fn quad_helpers(&mut self, plan: &TessellationPlan) {
        let Some(quad) = plan.quad.as_ref() else {
            return;
        };
        let coordinates = quad
            .coordinates
            .iter()
            .map(|value| format!("uint2({}, {})", value.u, value.v))
            .collect::<Vec<_>>()
            .join(", ");
        let primitives = quad
            .primitives
            .iter()
            .map(|value| format!("uint3({}, {}, {})", value[0], value[1], value[2]))
            .collect::<Vec<_>>()
            .join(", ");
        let vertex_count = quad.coordinates.len();
        let primitive_count = quad.primitives.len();
        self.text.push_str(&format!("constant uint2 mtlr_tess_quad_coordinates[{}] = {{ {} }};\nconstant uint3 mtlr_tess_quad_primitives[{}] = {{ {} }};\n", vertex_count, coordinates, primitive_count, primitives));
        self.text.push_str(&format!("inline void mtlr_tess_quad_emit(const device MtlrTessControlPoint *points, device MtlrTessEvaluationOutput *vertices, device MtlrTessPrimitive *primitives, device MtlrTessIndirectArguments *arguments, const device uint *vertex_offsets, const device uint *primitive_offsets, uint patch, uint vertex_capacity, uint primitive_capacity) {{ uint vertex_base = mtlr_tess_patch_offset(vertex_offsets, patch, {}u, vertex_capacity); uint primitive_base = mtlr_tess_patch_offset(primitive_offsets, patch, {}u, primitive_capacity); if (!mtlr_tess_range_ok(vertex_base, {}u, vertex_capacity) || !mtlr_tess_range_ok(primitive_base, {}u, primitive_capacity)) return; for (uint i = 0u; i < {}u; ++i) {{ float2 d = float2(mtlr_tess_quad_coordinates[i]) / float2({}, {}); uint row = mtlr_tess_quad_coordinates[i].y * ({}u + 1u); uint column = mtlr_tess_quad_coordinates[i].x; float4 value = mix(mix(points[patch * 4u].value, points[patch * 4u + 1u].value, d.x), mix(points[patch * 4u + 3u].value, points[patch * 4u + 2u].value, d.x), d.y); vertices[vertex_base + i] = {{ value, patch, i, d }}; }} for (uint i = 0u; i < {}u; ++i) primitives[primitive_base + i] = {{ uint3(vertex_base + mtlr_tess_quad_primitives[i]), patch, i }}; arguments[patch] = {{ {}u * 3u, 1u, vertex_base, 0u }}; }}\n", vertex_count, primitive_count, vertex_count, primitive_count, vertex_count, quad.outer_factors[0], quad.outer_factors[1], quad.outer_factors[0], primitive_count, primitive_count));
    }

    fn isoline_helpers(&mut self, plan: &TessellationPlan) {
        let Some(isoline) = plan.isoline.as_ref() else {
            return;
        };
        let coordinates = isoline
            .coordinates
            .iter()
            .map(|value| format!("uint2({}, {})", value.u, value.v))
            .collect::<Vec<_>>()
            .join(", ");
        let primitives = isoline
            .primitives
            .iter()
            .map(|value| format!("uint2({}, {})", value[0], value[1]))
            .collect::<Vec<_>>()
            .join(", ");
        let vertex_count = isoline.coordinates.len();
        let primitive_count = isoline.primitives.len();
        self.text.push_str(&format!("constant uint2 mtlr_tess_isoline_coordinates[{}] = {{ {} }};\nconstant uint2 mtlr_tess_isoline_primitives[{}] = {{ {} }};\n", vertex_count, coordinates, primitive_count, primitives));
        self.text.push_str(&format!("inline void mtlr_tess_isoline_emit(const device MtlrTessControlPoint *points, device MtlrTessEvaluationOutput *vertices, device MtlrTessPrimitive *primitives, device MtlrTessIndirectArguments *arguments, const device uint *vertex_offsets, const device uint *primitive_offsets, uint patch, uint vertex_capacity, uint primitive_capacity) {{ uint vertex_base = mtlr_tess_patch_offset(vertex_offsets, patch, {}u, vertex_capacity); uint primitive_base = mtlr_tess_patch_offset(primitive_offsets, patch, {}u, primitive_capacity); if (!mtlr_tess_range_ok(vertex_base, {}u, vertex_capacity) || !mtlr_tess_range_ok(primitive_base, {}u, primitive_capacity)) return; for (uint i = 0u; i < {}u; ++i) {{ float2 d = float2(mtlr_tess_isoline_coordinates[i]) / float2({}, {}); vertices[vertex_base + i] = {{ mix(points[patch * 2u].value, points[patch * 2u + 1u].value, d.x), patch, i, d }}; }} for (uint i = 0u; i < {}u; ++i) primitives[primitive_base + i] = {{ uint3(vertex_base + mtlr_tess_isoline_primitives[i].x, vertex_base + mtlr_tess_isoline_primitives[i].y, vertex_base + mtlr_tess_isoline_primitives[i].y), patch, i }}; arguments[patch] = {{ {}u * 2u, 1u, vertex_base, 0u }}; }}\n", vertex_count, primitive_count, vertex_count, primitive_count, vertex_count, isoline.outer_factors[0], isoline.outer_factors[1], primitive_count, primitive_count));
    }

    fn tessellation_control_entry(&mut self, plan: &TessellationPlan) {
        let patch_size = plan.control.patch.input_count;
        let mut factors = String::new();
        for write in &plan.control.factor_writes {
            let field = match write.kind {
                crate::transform::TessellationFactorKind::Outer => "outer",
                crate::transform::TessellationFactorKind::Inner => "inner",
            };
            factors.push_str(&format!(
                " tess_factors[patch].{field}[{}] = 1.0;",
                write.component
            ));
        }
        let barrier = if plan.control.barrier_required {
            " threadgroup_barrier(mem_flags::mem_device | mem_flags::mem_threadgroup);"
        } else {
            ""
        };
        self.text.push_str(&format!("kernel void mtlr_tessellation_control(const device MtlrTessControlPoint *gl_in [[buffer(0)]], device MtlrTessControlPoint *gl_out [[buffer(1)]], device MtlrTessFactors *tess_factors [[buffer(2)]], constant uint &invocation_count [[buffer(3)]], uint3 gid [[thread_position_in_grid]], uint3 tid [[thread_position_in_threadgroup]]) {{ uint invocation = gid.x; uint patch = invocation / {}u; uint local_invocation = invocation % {}u; bool active = invocation < invocation_count; threadgroup MtlrTessControlPoint patch_points[{}]; if (active) patch_points[local_invocation] = gl_in[invocation]; threadgroup_barrier(mem_flags::mem_threadgroup); if (active) gl_out[invocation] = patch_points[local_invocation]; if (local_invocation == 0u && active) {{{} }}{} }}\n", patch_size, patch_size, patch_size, factors, barrier));
    }

    fn tessellation_evaluation_entry(&mut self, plan: &TessellationPlan) {
        let family = match plan.contract.domain {
            TessellationDomain::Triangles => "triangle",
            TessellationDomain::Quads => "quad",
            TessellationDomain::Isolines => "isoline",
        };
        let call = match plan.contract.domain {
            TessellationDomain::Triangles => {
                "mtlr_tess_triangle_emit(tess_factors, control_points, outputs, primitives, arguments, vertex_offsets, primitive_offsets, patch, patch_count, vertex_capacity, primitive_capacity)"
            }
            TessellationDomain::Quads => {
                "mtlr_tess_quad_emit(control_points, outputs, primitives, arguments, vertex_offsets, primitive_offsets, patch, vertex_capacity, primitive_capacity)"
            }
            TessellationDomain::Isolines => {
                "mtlr_tess_isoline_emit(control_points, outputs, primitives, arguments, vertex_offsets, primitive_offsets, patch, vertex_capacity, primitive_capacity)"
            }
        };
        self.text.push_str(&format!("kernel void mtlr_tessellation_evaluation_{family}(const device MtlrTessControlPoint *control_points [[buffer(0)]], const device MtlrTessFactors *tess_factors [[buffer(1)]], device MtlrTessEvaluationOutput *outputs [[buffer(2)]], device MtlrTessPrimitive *primitives [[buffer(3)]], device MtlrTessIndirectArguments *arguments [[buffer(4)]], const device uint *vertex_offsets [[buffer(5)]], const device uint *primitive_offsets [[buffer(6)]], constant uint &patch_count [[buffer(7)]], constant uint &vertex_capacity [[buffer(8)]], constant uint &primitive_capacity [[buffer(9)]], uint3 gid [[thread_position_in_grid]]) {{ uint patch = gid.x; if (!mtlr_tess_patch_index_ok(patch, patch_count)) return; {call}; }}\n"));
    }

    fn compute(&mut self, module: &Ast) {
        if let Some(main) = main_declaration(module) {
            self.text.push_str("kernel void mtlrender_compute_main(uint3 gl_GlobalInvocationID [[thread_position_in_grid]], uint3 gl_LocalInvocationID [[thread_position_in_threadgroup]], uint3 gl_WorkGroupID [[threadgroup_position_in_grid]], uint3 gl_NumWorkGroups [[threadgroups_per_grid]], uint3 gl_WorkGroupSize [[threads_per_threadgroup]]");
            self.resource_parameters(module);
            self.shared_parameters(module);
            self.text.push_str(") ");
            self.body(main.body.as_deref());
        } else {
            self.text
                .push_str("kernel void mtlrender_compute_main() {}\n");
        }
    }

    fn graphics(&mut self, module: &Ast) {
        for declaration in &module.declarations {
            if declaration.body.is_some() && declaration.name.as_deref() != Some("main") {
                self.function(module, declaration);
            }
        }
        let Some(main) = main_declaration(module) else {
            return;
        };
        if self.stage == Stage::Vertex {
            self.vertex_entry(module, main);
        } else {
            self.fragment_entry(module, main);
        }
    }

    fn vertex_entry(&mut self, module: &Ast, main: &Declaration) {
        let inputs = stage_values(module, "in");
        if !inputs.is_empty() {
            self.text.push_str("struct MtlrVertexIn {\n");
            for declaration in &inputs {
                self.vertex_input_member(declaration);
            }
            self.text.push_str("};\n");
        }
        self.text
            .push_str("struct MtlrVertexOut {\n    float4 mtlr_position [[position]];\n");
        for declaration in stage_values(module, "out") {
            self.member(declaration);
        }
        self.text.push_str("};\nvertex MtlrVertexOut main_vertex(");
        if !inputs.is_empty() {
            self.text.push_str("MtlrVertexIn mtlr_in [[stage_in]], uint mtlr_vertex_id [[vertex_id]], uint mtlr_instance_id [[instance_id]]");
        } else {
            self.text.push_str(
                "uint mtlr_vertex_id [[vertex_id]], uint mtlr_instance_id [[instance_id]]",
            );
        }
        self.resource_parameters_inline(module);
        self.text.push_str(
            ") {\n    MtlrVertexOut mtlr_out{};\n    float4 mtlr_position = float4(0.0);\n",
        );
        for declaration in &inputs {
            self.local_from_input(declaration);
        }
        for declaration in stage_values(module, "out") {
            self.local(declaration);
        }
        self.body_statements(main.body.as_deref());
        self.text
            .push_str("    mtlr_out.mtlr_position = mtlr_position;\n");
        for declaration in stage_values(module, "out") {
            if let Some(name) = declaration.name.as_deref() {
                self.text.push_str("    mtlr_out.");
                self.text.push_str(name);
                self.text.push_str(" = ");
                self.text.push_str(name);
                self.text.push_str(";\n");
            }
        }
        self.text.push_str("    return mtlr_out;\n}\n");
    }

    fn fragment_entry(&mut self, module: &Ast, main: &Declaration) {
        let outputs = stage_values(module, "out")
            .into_iter()
            .filter(|declaration| declaration.name.as_deref() != Some("mtlr_frag_depth"))
            .collect::<Vec<_>>();
        let has_depth = declaration_named(module, "mtlr_frag_depth");
        self.text.push_str("struct MtlrFragmentIn {\n");
        for declaration in stage_values(module, "in") {
            self.member(declaration);
        }
        self.text.push_str("};\nstruct MtlrFragmentOut {\n");
        for declaration in &outputs {
            self.text.push_str("    ");
            self.text.push_str(&self.ty(&declaration.type_specifier));
            self.text.push(' ');
            self.text
                .push_str(declaration.name.as_deref().unwrap_or("color"));
            self.array_suffix(declaration);
            self.text.push_str(" [[color(");
            self.text.push_str(
                &layout_value(declaration, "location")
                    .unwrap_or(0)
                    .to_string(),
            );
            self.text.push_str(")]];\n");
        }
        if has_depth {
            self.text.push_str("    float mtlr_frag_depth [[depth(");
            self.text.push_str(depth_attribute(module));
            self.text.push_str(")]];\n");
        }
        if outputs.is_empty() && !has_depth {
            self.text
                .push_str("    float4 mtlr_default [[color(0)]];\n");
        }
        self.text.push_str("};\nfragment MtlrFragmentOut main_fragment(MtlrFragmentIn mtlr_in [[stage_in]], float4 mtlr_frag_coord [[position]]");
        self.resource_parameters_inline(module);
        self.text.push_str(") {\n    MtlrFragmentOut mtlr_out{};\n");
        for declaration in stage_values(module, "in") {
            self.local_from_input(declaration);
        }
        for declaration in &outputs {
            self.local(declaration);
        }
        if declaration_named(module, "mtlr_frag_color") || outputs.is_empty() {
            self.text
                .push_str("    float4 mtlr_frag_color = float4(0.0);\n");
        }
        if has_depth {
            self.text.push_str("    float mtlr_frag_depth = 0.0;\n");
        }
        self.body_statements(main.body.as_deref());
        for declaration in &outputs {
            if let Some(name) = declaration.name.as_deref() {
                self.text.push_str("    mtlr_out.");
                self.text.push_str(name);
                self.text.push_str(" = ");
                self.text.push_str(name);
                self.text.push_str(";\n");
            }
        }
        if has_depth {
            self.text
                .push_str("    mtlr_out.mtlr_frag_depth = mtlr_frag_depth;\n");
        } else if outputs.is_empty() {
            self.text
                .push_str("    mtlr_out.mtlr_default = mtlr_frag_color;\n");
        }
        self.text.push_str("    return mtlr_out;\n}\n");
    }

    fn local(&mut self, declaration: &Declaration) {
        self.text.push_str("    ");
        self.text.push_str(&self.ty(&declaration.type_specifier));
        self.text.push(' ');
        self.text
            .push_str(declaration.name.as_deref().unwrap_or("value"));
        self.array_suffix(declaration);
        self.text.push_str("{};\n");
    }

    fn local_from_input(&mut self, declaration: &Declaration) {
        let Some(name) = declaration.name.as_deref() else {
            return;
        };
        self.text.push_str("    ");
        self.text.push_str(&self.ty(&declaration.type_specifier));
        self.text.push(' ');
        self.text.push_str(name);
        self.array_suffix(declaration);
        self.text.push_str(" = mtlr_in.");
        self.text.push_str(name);
        self.text.push_str(";\n");
    }

    fn resource_parameters_inline(&mut self, module: &Ast) {
        for declaration in &module.declarations {
            if has_storage(declaration, "uniform") && is_resource(&declaration.type_specifier) {
                self.parameter_resource(declaration);
            } else if has_storage(declaration, "buffer")
                || (has_storage(declaration, "uniform")
                    && matches!(declaration.type_specifier, TypeSpecifier::Interface(_, _)))
            {
                self.parameter_buffer(declaration);
            } else if has_storage(declaration, "uniform")
                && !matches!(declaration.type_specifier, TypeSpecifier::Interface(_, _))
            {
                self.text.push_str(", constant ");
                self.text.push_str(&self.ty(&declaration.type_specifier));
                self.text.push_str("& ");
                self.text
                    .push_str(declaration.name.as_deref().unwrap_or("uniform_value"));
                self.binding(declaration);
            }
        }
    }

    fn resource_parameters(&mut self, module: &Ast) {
        self.resource_parameters_inline(module);
    }

    fn shared_parameters(&mut self, module: &Ast) {
        let mut index = 0_u32;
        for declaration in &module.declarations {
            if !has_storage(declaration, "shared") {
                continue;
            }
            self.text.push_str(", threadgroup ");
            self.text.push_str(&self.ty(&declaration.type_specifier));
            self.text.push_str("* ");
            self.text
                .push_str(declaration.name.as_deref().unwrap_or("shared_memory"));
            self.text.push_str(&format!(" [[threadgroup({index})]]"));
            index = index.saturating_add(1);
        }
    }

    fn parameter_resource(&mut self, declaration: &Declaration) {
        let name = declaration.name.as_deref().unwrap_or("resource");
        let binding = layout_value(declaration, "binding").unwrap_or(self.next_texture);
        match &declaration.type_specifier {
            TypeSpecifier::Sampler(kind) if kind.starts_with("sampler") => {
                self.text.push_str(", ");
                self.text.push_str(texture_type(kind));
                self.text.push(' ');
                self.text.push_str(name);
                self.text.push_str(&format!(" [[texture({binding})]]"));
                self.text.push_str(&format!(
                    ", sampler mtlr_{}_sampler [[sampler({binding})]]",
                    name
                ));
                self.next_texture = self.next_texture.max(binding.saturating_add(1));
                self.next_sampler = self.next_sampler.max(binding.saturating_add(1));
            }
            _ => {
                self.text.push_str(", ");
                self.resource_type(&declaration.type_specifier, declaration);
                self.text.push(' ');
                self.text.push_str(name);
                self.binding(declaration);
            }
        }
    }

    fn parameter_buffer(&mut self, declaration: &Declaration) {
        if has_storage(declaration, "buffer") {
            self.text.push_str(", device ");
            self.text.push_str(&self.ty(&declaration.type_specifier));
            self.text.push_str("* ");
        } else {
            self.text.push_str(", constant ");
            self.text.push_str(&self.ty(&declaration.type_specifier));
            self.text.push_str("& ");
        }
        self.text
            .push_str(declaration.name.as_deref().unwrap_or("buffer"));
        self.binding(declaration);
    }

    fn resource_type(&mut self, spec: &TypeSpecifier, declaration: &Declaration) {
        self.text.push_str(match spec {
            TypeSpecifier::Image(name) => image_type_with_access(name, declaration),
            TypeSpecifier::Sampler(name) if name.starts_with("sampler") => texture_type(name),
            TypeSpecifier::Sampler(_) => "sampler",
            _ => "constant uint",
        });
    }

    fn binding(&mut self, declaration: &Declaration) {
        let binding = layout_value(declaration, "binding").unwrap_or(self.next_buffer);
        if matches!(declaration.type_specifier, TypeSpecifier::Image(_)) {
            self.text.push_str(&format!(" [[texture({binding})]]"));
            self.next_texture = binding.saturating_add(1);
        } else if matches!(declaration.type_specifier, TypeSpecifier::Sampler(_)) {
            self.text.push_str(&format!(" [[sampler({binding})]]"));
            self.next_sampler = binding.saturating_add(1);
        } else {
            self.text.push_str(&format!(" [[buffer({binding})]]"));
            self.next_buffer = binding.saturating_add(1);
        }
    }

    fn function(&mut self, module: &Ast, declaration: &Declaration) {
        self.text.push_str(&self.ty(&declaration.type_specifier));
        self.text.push(' ');
        self.text
            .push_str(declaration.name.as_deref().unwrap_or("function"));
        self.text.push('(');
        for (index, parameter) in declaration.parameters.iter().enumerate() {
            if index > 0 {
                self.text.push_str(", ");
            }
            self.text.push_str(&self.ty(&parameter.type_specifier));
            self.text.push(' ');
            self.text
                .push_str(parameter.name.as_deref().unwrap_or("value"));
        }
        for (index, capture) in capture_declarations(module, &self.capture_names)
            .iter()
            .enumerate()
        {
            if !declaration.parameters.is_empty() || index > 0 {
                self.text.push_str(", ");
            }
            self.capture_parameter(capture);
        }
        self.text.push_str(") ");
        self.body(declaration.body.as_deref());
    }

    fn capture_parameter(&mut self, declaration: &Declaration) {
        if has_storage(declaration, "uniform") && is_resource(&declaration.type_specifier) {
            self.text
                .push_str(texture_type_for_capture(&declaration.type_specifier));
            self.text.push(' ');
            self.text
                .push_str(declaration.name.as_deref().unwrap_or("resource"));
            if let TypeSpecifier::Sampler(kind) = &declaration.type_specifier
                && kind.starts_with("sampler")
            {
                self.text.push_str(", sampler ");
                self.text.push_str("mtlr_");
                self.text
                    .push_str(declaration.name.as_deref().unwrap_or("resource"));
                self.text.push_str("_sampler");
            }
        } else if has_storage(declaration, "buffer") {
            self.text.push_str("device ");
            self.text.push_str(&self.ty(&declaration.type_specifier));
            self.text.push_str("* ");
            self.text
                .push_str(declaration.name.as_deref().unwrap_or("buffer"));
        } else if has_storage(declaration, "uniform")
            && matches!(declaration.type_specifier, TypeSpecifier::Interface(_, _))
        {
            self.text.push_str("constant ");
            self.text.push_str(&self.ty(&declaration.type_specifier));
            self.text.push_str("& ");
            self.text
                .push_str(declaration.name.as_deref().unwrap_or("buffer"));
        } else {
            if has_storage(declaration, "uniform") {
                self.text.push_str("constant ");
                self.text.push_str(&self.ty(&declaration.type_specifier));
                self.text.push_str("& ");
            } else {
                self.text.push_str(&self.ty(&declaration.type_specifier));
                self.text.push(' ');
            }
            self.text
                .push_str(declaration.name.as_deref().unwrap_or("value"));
            self.array_suffix(declaration);
        }
    }

    fn body(&mut self, statement: Option<&Statement>) {
        self.text.push_str("{\n");
        self.body_statements(statement);
        self.text.push_str("}\n");
    }

    fn body_statements(&mut self, statement: Option<&Statement>) {
        if let Some(Statement::Compound(_, statements)) = statement {
            for item in statements {
                self.statement(item);
            }
        }
    }

    fn statement(&mut self, statement: &Statement) {
        match statement {
            Statement::Compound(_, _) => self.body(Some(statement)),
            Statement::Declaration(declaration) => {
                self.text.push_str("    ");
                self.text.push_str(&self.ty(&declaration.type_specifier));
                self.text.push(' ');
                self.text
                    .push_str(declaration.name.as_deref().unwrap_or("value"));
                self.array_suffix(declaration);
                if let Some(value) = &declaration.initializer {
                    self.text.push_str(" = ");
                    self.expression(value);
                }
                self.text.push_str(";\n");
            }
            Statement::Expression(_, value) => {
                self.text.push_str("    ");
                if let Some(value) = value {
                    self.expression(value);
                }
                self.text.push_str(";\n");
            }
            Statement::Jump(_, kind, value) => {
                self.text.push_str("    ");
                if kind == "discard" {
                    self.text.push_str("discard_fragment()");
                } else {
                    self.text
                        .push_str(if kind == "return" { "return" } else { kind });
                    if let Some(value) = value {
                        self.text.push(' ');
                        self.expression(value);
                    }
                }
                self.text.push_str(";\n");
            }
            Statement::If(_, condition, yes, no) => {
                self.text.push_str("    if (");
                self.expression(condition);
                self.text.push_str(") ");
                self.statement(yes);
                if let Some(no) = no {
                    self.text.push_str(" else ");
                    self.statement(no);
                }
            }
            Statement::Loop(_, _, condition, _, body) => {
                self.text.push_str("    while (");
                if let Some(condition) = condition {
                    self.expression(condition);
                } else {
                    self.text.push_str("true");
                }
                self.text.push_str(") ");
                self.statement(body);
            }
            Statement::Switch(_, _, _)
            | Statement::Label(_, _, _)
            | Statement::Empty(_)
            | Statement::Error(_) => self.text.push_str("    ;\n"),
        }
    }

    fn expression(&mut self, expression: &Expression) {
        match expression {
            Expression::Literal(value) => self.text.push_str(value),
            Expression::Name(value) => self.text.push_str(match value.as_str() {
                "barrier"
                | "memoryBarrier"
                | "memoryBarrierShared"
                | "memoryBarrierBuffer"
                | "memoryBarrierImage" => "threadgroup_barrier",
                "dFdx" => "dfdx",
                "dFdy" => "dfdy",
                _ => value,
            }),
            Expression::Unary(op, value) => {
                self.text.push_str(op);
                self.expression(value);
            }
            Expression::Binary(op, left, right) => {
                self.text.push('(');
                self.expression(left);
                self.text.push_str(op);
                self.expression(right);
                self.text.push(')');
            }
            Expression::Conditional(condition, yes, no) => {
                self.text.push('(');
                self.expression(condition);
                self.text.push_str(" ? ");
                self.expression(yes);
                self.text.push_str(" : ");
                self.expression(no);
                self.text.push(')');
            }
            Expression::Call(name, args) => {
                if let Expression::Name(value) = name.as_ref()
                    && is_texture_call(value)
                {
                    self.texture_call(value, args);
                    return;
                }
                if let Expression::Name(value) = name.as_ref()
                    && matches!(value.as_str(), "imageLoad" | "imageStore")
                {
                    self.image_call(value, args);
                    return;
                }
                if let Expression::Name(value) = name.as_ref()
                    && (value == "barrier" || value.starts_with("memoryBarrier"))
                {
                    self.barrier(value);
                    return;
                }
                if let Expression::Name(value) = name.as_ref()
                    && matches!(value.as_str(), "atomicAdd" | "atomicExchange")
                {
                    self.text.push_str(if value == "atomicAdd" {
                        "mtlr_atomic_add"
                    } else {
                        "mtlr_atomic_exchange"
                    });
                } else if let Expression::Name(value) = name.as_ref()
                    && is_type_name(value)
                {
                    self.text.push_str(&named_type(value));
                } else {
                    self.expression(name);
                }
                self.text.push('(');
                for (index, arg) in args.iter().enumerate() {
                    if index > 0 {
                        self.text.push_str(", ");
                    }
                    self.expression(arg);
                }
                if let Expression::Name(value) = name.as_ref()
                    && self.function_names.contains(value)
                {
                    for (index, capture) in self.capture_names.iter().enumerate() {
                        if index > 0 || !args.is_empty() {
                            self.text.push_str(", ");
                        }
                        self.text.push_str(capture);
                        if self.sampler_types.contains_key(capture) {
                            self.text.push_str(", mtlr_");
                            self.text.push_str(capture);
                            self.text.push_str("_sampler");
                        }
                    }
                }
                self.text.push(')');
            }
            Expression::Index(base, index) => {
                self.expression(base);
                self.text.push('[');
                self.expression(index);
                self.text.push(']');
            }
            Expression::Field(base, field) => {
                self.expression(base);
                self.text.push('.');
                self.text.push_str(field);
            }
            Expression::Constructor(spec, args) => {
                self.text.push_str(&self.ty(spec));
                self.text.push('(');
                for (index, arg) in args.iter().enumerate() {
                    if index > 0 {
                        self.text.push_str(", ");
                    }
                    self.expression(arg);
                }
                self.text.push(')');
            }
        }
    }

    fn barrier(&mut self, name: &str) {
        let flags = match name {
            "memoryBarrierBuffer" | "memoryBarrierImage" => "mem_flags::mem_device",
            "memoryBarrierShared" => "mem_flags::mem_threadgroup",
            _ => "mem_flags::mem_device | mem_flags::mem_threadgroup",
        };
        self.text.push_str("threadgroup_barrier(");
        self.text.push_str(flags);
        self.text.push(')');
    }

    fn image_call(&mut self, name: &str, args: &[Expression]) {
        let Some(image) = args.first() else {
            return;
        };
        self.expression(image);
        if name == "imageLoad" {
            self.text.push_str(".read(uint2(");
            if let Some(value) = args.get(1) {
                self.expression(value);
            }
            self.text.push_str("))");
        } else {
            self.text.push_str(".write(");
            if let Some(value) = args.get(2) {
                self.expression(value);
            }
            self.text.push_str(", uint2(");
            if let Some(value) = args.get(1) {
                self.expression(value);
            }
            self.text.push(')');
            self.text.push(')');
        }
    }

    fn texture_call(&mut self, name: &str, args: &[Expression]) {
        let Some(first) = args.first() else {
            self.text.push_str(name);
            self.text.push_str("()");
            return;
        };
        let sampler_name = match first {
            Expression::Name(value) => value.as_str(),
            _ => "",
        };
        let (shadow, cube, array) = self.sampler_types.get(sampler_name).map_or(
            (name.starts_with("shadow"), false, false),
            |kind| {
                (
                    kind.contains("Shadow") || name.starts_with("shadow"),
                    kind.contains("Cube"),
                    kind.contains("Array"),
                )
            },
        );
        let base = if shadow { "sample_compare" } else { "sample" };
        if name == "texelFetch" {
            self.expression(first);
            self.text.push_str(".read(uint2(");
            if let Some(value) = args.get(1) {
                self.expression(value);
            }
            self.text.push(')');
            if let Some(value) = args.get(2) {
                self.text.push_str(", uint(");
                self.expression(value);
                self.text.push(')');
            }
            self.text.push(')');
            return;
        }
        if name == "textureQueryLod" {
            self.expression(first);
            self.text.push_str(".query_lod(mtlr_");
            self.text.push_str(sampler_name);
            self.text.push_str("_sampler, ");
            if let Some(value) = args.get(1) {
                self.expression(value);
            }
            self.text.push(')');
            return;
        }
        if name == "textureSize" {
            self.expression(first);
            self.text.push_str(".get_width(");
            if let Some(value) = args.get(1) {
                self.expression(value);
            }
            self.text.push(')');
            return;
        }
        if name.starts_with("textureGather") {
            self.expression(first);
            self.text.push_str(".gather(mtlr_");
            self.text.push_str(sampler_name);
            self.text.push_str("_sampler, ");
            if let Some(value) = args.get(1) {
                self.expression(value);
            }
            if let Some(value) = args.get(2) {
                self.text.push_str(", ");
                self.expression(value);
            }
            self.text.push(')');
            return;
        }
        let coordinate = args.get(1);
        self.expression(first);
        self.text.push('.');
        self.text.push_str(base);
        self.text.push_str("(mtlr_");
        self.text.push_str(sampler_name);
        self.text.push_str("_sampler, ");
        if name.contains("Proj") {
            if let Some(value) = coordinate {
                self.text.push('(');
                self.expression(value);
                self.text.push_str(" / ");
                self.expression_projection_denominator(value);
                self.text.push(')');
            }
        } else if shadow {
            if let Some(value) = coordinate {
                self.expression_shadow_coordinate(value, cube, array);
            }
        } else if let Some(value) = coordinate {
            if name.contains("Offset") {
                self.text.push('(');
                self.expression(value);
                self.text.push_str(" + float2(");
                if let Some(offset) = args.last() {
                    self.expression(offset);
                }
                self.text.push(')');
                self.text.push(')');
            } else {
                self.expression(value);
            }
        } else {
            self.text.push_str("float2(0.0)");
        }
        if shadow && let Some(value) = coordinate {
            self.text.push_str(", ");
            self.expression_shadow_compare(value, cube, array);
        }
        if name.contains("Lod") || name.ends_with("Lod") {
            if let Some(value) = args.get(2) {
                self.text.push_str(", level(");
                self.expression(value);
                self.text.push(')');
            }
        } else if name.contains("Grad")
            && let (Some(dx), Some(dy)) = (args.get(2), args.get(3))
        {
            self.text.push_str(", gradient2d(");
            self.expression(dx);
            self.text.push_str(", ");
            self.expression(dy);
            self.text.push(')');
        }
        self.text.push(')');
    }

    fn expression_projection_denominator(&mut self, value: &Expression) {
        self.expression(value);
        self.text.push_str(".z");
    }

    fn expression_shadow_coordinate(&mut self, value: &Expression, cube: bool, array: bool) {
        self.expression(value);
        if cube || array {
            self.text.push_str(".xyz");
        } else {
            self.text.push_str(".xy");
        }
    }

    fn expression_shadow_compare(&mut self, value: &Expression, cube: bool, array: bool) {
        self.expression(value);
        if cube || array {
            self.text.push_str(".w");
        } else {
            self.text.push_str(".z");
        }
    }

    fn ty(&self, spec: &TypeSpecifier) -> String {
        match spec {
            TypeSpecifier::Void => String::from("void"),
            TypeSpecifier::Bool => String::from("bool"),
            TypeSpecifier::Int => String::from("int"),
            TypeSpecifier::UInt => String::from("uint"),
            TypeSpecifier::Int64 => String::from("long"),
            TypeSpecifier::UInt64 => String::from("ulong"),
            TypeSpecifier::Float => String::from("float"),
            TypeSpecifier::Double => String::from("float"),
            TypeSpecifier::Vec(n) | TypeSpecifier::DVec(n) => format!("float{n}"),
            TypeSpecifier::I64Vec(n) => format!("long{n}"),
            TypeSpecifier::U64Vec(n) => format!("ulong{n}"),
            TypeSpecifier::IVec(n) => format!("int{n}"),
            TypeSpecifier::UVec(n) => format!("uint{n}"),
            TypeSpecifier::BVec(n) => format!("bool{n}"),
            TypeSpecifier::Mat(c, r) | TypeSpecifier::DMat(c, r) => format!("float{c}x{r}"),
            TypeSpecifier::Sampler(name) => texture_type(name).to_string(),
            TypeSpecifier::Image(name) => image_type(name).to_string(),
            TypeSpecifier::Named(name) => named_type(name),
            TypeSpecifier::Struct(name, _) | TypeSpecifier::Interface(name, _) => {
                String::from(name)
            }
        }
    }

    fn array_suffix(&mut self, declaration: &Declaration) {
        for value in &declaration.array {
            self.text.push('[');
            self.expression(value);
            self.text.push(']');
        }
    }
}

fn input_index_expression(topology: crate::transform::PrimitiveTopology) -> &'static str {
    match topology {
        crate::transform::PrimitiveTopology::Points => "invocation",
        crate::transform::PrimitiveTopology::Lines => "invocation * 2u + vertex_index",
        crate::transform::PrimitiveTopology::LineStrip => "invocation + vertex_index",
        crate::transform::PrimitiveTopology::Triangles => "invocation * 3u + vertex_index",
        crate::transform::PrimitiveTopology::TriangleStrip => "invocation + vertex_index",
    }
}

fn output_index_expression(topology: crate::transform::PrimitiveTopology) -> &'static str {
    match topology {
        crate::transform::PrimitiveTopology::Points => {
            "uint3(vertex_offset + primitive_index, vertex_offset + primitive_index, vertex_offset + primitive_index)"
        }
        crate::transform::PrimitiveTopology::Lines
        | crate::transform::PrimitiveTopology::LineStrip => {
            "uint3(vertex_offset + primitive_index * 2u, vertex_offset + primitive_index * 2u + 1u, vertex_offset + primitive_index * 2u + 1u)"
        }
        crate::transform::PrimitiveTopology::Triangles => {
            "uint3(vertex_offset + primitive_index * 3u, vertex_offset + primitive_index * 3u + 1u, vertex_offset + primitive_index * 3u + 2u)"
        }
        crate::transform::PrimitiveTopology::TriangleStrip => {
            "uint3(vertex_offset + primitive_index, vertex_offset + primitive_index + 1u, vertex_offset + primitive_index + 2u)"
        }
    }
}

fn validate_compute_plan(plan: &ComputeGeometryPlan) -> Result<(), CompileErrors> {
    let location = || {
        Some(Location::new(
            plan.location.file.as_str(),
            plan.location.line,
            plan.location.column,
        ))
    };
    if plan.side_effects.image_writes {
        return Err(CompileErrors::one(CompileError::new(
            crate::ErrorKind::Unsupported,
            "geometry image writes cannot be represented by the ordered storage-resource plan",
            location(),
        )));
    }
    if plan.side_effects.storage_writes && !plan.storage_capabilities.storage_writes {
        return Err(CompileErrors::one(CompileError::new(
            crate::ErrorKind::Unsupported,
            "geometry storage writes require a writable storage binding",
            location(),
        )));
    }
    if plan.side_effects.atomics && !plan.storage_capabilities.storage_atomics {
        return Err(CompileErrors::one(CompileError::new(
            crate::ErrorKind::Unsupported,
            "geometry atomic operations require an atomic-capable storage binding",
            location(),
        )));
    }
    if plan.output_capacity.vertices == 0 || plan.output_capacity.primitives == 0 {
        return Err(CompileErrors::one(CompileError::new(
            crate::ErrorKind::Msl,
            "geometry output capacity must be non-zero",
            location(),
        )));
    }
    if plan.produced_vertices > plan.output_capacity.vertices
        || plan.produced_primitives > plan.output_capacity.primitives
    {
        return Err(CompileErrors::one(CompileError::new(
            crate::ErrorKind::Msl,
            "geometry output exceeds its declared capacity; output was not clamped",
            location(),
        )));
    }
    if plan.indirect_draw == IndirectDrawLayout::MetalIndexed {
        return Err(CompileErrors::one(CompileError::unsupported_at(
            "indexed geometry indirect layout is not available in the allocation-only path",
            location(),
        )));
    }
    Ok(())
}

const ATOMIC_HELPERS: &str = r#"
inline uint mtlr_atomic_add(device uint *value, uint operand) { atomic_thread_fence(mem_flags::mem_device, memory_order_seq_cst); uint result = atomic_fetch_add_explicit(reinterpret_cast<device atomic_uint *>(value), operand, memory_order_relaxed); atomic_thread_fence(mem_flags::mem_device, memory_order_seq_cst); return result; }
inline int mtlr_atomic_add(device int *value, int operand) { atomic_thread_fence(mem_flags::mem_device, memory_order_seq_cst); int result = atomic_fetch_add_explicit(reinterpret_cast<device atomic_int *>(value), operand, memory_order_relaxed); atomic_thread_fence(mem_flags::mem_device, memory_order_seq_cst); return result; }
inline uint mtlr_atomic_exchange(device uint *value, uint operand) { atomic_thread_fence(mem_flags::mem_device, memory_order_seq_cst); uint result = atomic_exchange_explicit(reinterpret_cast<device atomic_uint *>(value), operand, memory_order_relaxed); atomic_thread_fence(mem_flags::mem_device, memory_order_seq_cst); return result; }
inline int mtlr_atomic_exchange(device int *value, int operand) { atomic_thread_fence(mem_flags::mem_device, memory_order_seq_cst); int result = atomic_exchange_explicit(reinterpret_cast<device atomic_int *>(value), operand, memory_order_relaxed); atomic_thread_fence(mem_flags::mem_device, memory_order_seq_cst); return result; }
inline float mtlr_atomic_add(device float *value, float operand) {
    device atomic_uint *target = reinterpret_cast<device atomic_uint *>(value);
    atomic_thread_fence(mem_flags::mem_device, memory_order_seq_cst);
    uint old = atomic_load_explicit(target, memory_order_relaxed);
    while (true) {
        float current = as_type<float>(old);
        uint next = as_type<uint>(current + operand);
        if (atomic_compare_exchange_weak_explicit(target, &old, next, memory_order_relaxed, memory_order_relaxed)) {
            atomic_thread_fence(mem_flags::mem_device, memory_order_seq_cst);
            return current;
        }
    }
}
inline float mtlr_atomic_exchange(device float *value, float operand) {
    atomic_thread_fence(mem_flags::mem_device, memory_order_seq_cst);
    float result = as_type<float>(atomic_exchange_explicit(reinterpret_cast<device atomic_uint *>(value), as_type<uint>(operand), memory_order_relaxed));
    atomic_thread_fence(mem_flags::mem_device, memory_order_seq_cst);
    return result;
}
"#;

fn capture_names(module: &Ast) -> Vec<String> {
    let mut names = BTreeSet::new();
    for declaration in &module.declarations {
        if declaration.name.is_some()
            && (has_storage(declaration, "uniform")
                || has_storage(declaration, "buffer")
                || has_storage(declaration, "in")
                || has_storage(declaration, "out"))
            && let Some(name) = declaration.name.as_deref()
        {
            names.insert(String::from(name));
        }
    }
    names.into_iter().collect()
}

fn capture_declarations<'a>(module: &'a Ast, names: &[String]) -> Vec<&'a Declaration> {
    names
        .iter()
        .filter_map(|name| {
            module
                .declarations
                .iter()
                .find(|declaration| declaration.name.as_deref() == Some(name.as_str()))
        })
        .collect()
}

fn texture_type_for_capture(spec: &TypeSpecifier) -> &'static str {
    match spec {
        TypeSpecifier::Sampler(name) => texture_type(name),
        TypeSpecifier::Image(name) => image_type(name),
        _ => "constant uint",
    }
}

fn main_declaration(module: &Ast) -> Option<&Declaration> {
    module
        .declarations
        .iter()
        .find(|item| item.name.as_deref() == Some("main") && item.body.is_some())
}
fn stage_values<'a>(module: &'a Ast, storage: &str) -> Vec<&'a Declaration> {
    module
        .declarations
        .iter()
        .filter(|item| has_storage(item, storage))
        .collect()
}
fn declaration_named(module: &Ast, name: &str) -> bool {
    module
        .declarations
        .iter()
        .any(|item| item.name.as_deref() == Some(name))
}
fn is_interface_value(declaration: &Declaration) -> bool {
    has_storage(declaration, "in") || has_storage(declaration, "out")
}
fn has_storage(declaration: &Declaration, name: &str) -> bool {
    declaration
        .qualifiers
        .iter()
        .any(|item| matches!(item, Qualifier::Storage(value) if value == name))
}
fn has_interpolation(declaration: &Declaration, name: &str) -> bool {
    declaration
        .qualifiers
        .iter()
        .any(|item| matches!(item, Qualifier::Interpolation(value) if value == name))
}
fn is_integer(spec: &TypeSpecifier) -> bool {
    matches!(
        spec,
        TypeSpecifier::Int
            | TypeSpecifier::UInt
            | TypeSpecifier::IVec(_)
            | TypeSpecifier::UVec(_)
            | TypeSpecifier::I64Vec(_)
            | TypeSpecifier::U64Vec(_)
    )
}
fn layout_value(declaration: &Declaration, name: &str) -> Option<u32> {
    declaration.qualifiers.iter().find_map(|item| {
        if let Qualifier::Layout(values) = item {
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
fn is_resource(spec: &TypeSpecifier) -> bool {
    matches!(spec, TypeSpecifier::Sampler(_) | TypeSpecifier::Image(_))
}
fn is_type_name(name: &str) -> bool {
    matches!(
        name,
        "vec2"
            | "vec3"
            | "vec4"
            | "ivec2"
            | "ivec3"
            | "ivec4"
            | "uvec2"
            | "uvec3"
            | "uvec4"
            | "mat2"
            | "mat3"
            | "mat4"
            | "double"
            | "int64_t"
            | "uint64_t"
    )
}

fn named_type(name: &str) -> String {
    match name {
        "vec2" => String::from("float2"),
        "vec3" => String::from("float3"),
        "vec4" => String::from("float4"),
        "ivec2" => String::from("int2"),
        "ivec3" => String::from("int3"),
        "ivec4" => String::from("int4"),
        "uvec2" => String::from("uint2"),
        "uvec3" => String::from("uint3"),
        "uvec4" => String::from("uint4"),
        "mat2" => String::from("float2x2"),
        "mat3" => String::from("float3x3"),
        "mat4" => String::from("float4x4"),
        "double" => String::from("float"),
        "int64_t" => String::from("long"),
        "uint64_t" => String::from("ulong"),
        _ => String::from(name),
    }
}

fn depth_attribute(module: &Ast) -> &'static str {
    for declaration in &module.declarations {
        for qualifier in &declaration.qualifiers {
            if let Qualifier::Layout(values) = qualifier {
                for (name, _) in values {
                    match name.as_str() {
                        "depth_greater" => return "greater",
                        "depth_less" => return "less",
                        "depth_unchanged" => return "unchanged",
                        _ => {}
                    }
                }
            }
        }
    }
    "any"
}

fn is_texture_call(name: &str) -> bool {
    name.starts_with("texture") || name.starts_with("shadow") || name == "texelFetch"
}

fn texture_type(name: &str) -> &'static str {
    if name.contains("Shadow") {
        "depth2d<float>"
    } else if name.contains("3D") {
        "texture3d<float>"
    } else if name.contains("CubeArray") {
        "texturecube_array<float>"
    } else if name.contains("Cube") {
        "texturecube<float>"
    } else if name.contains("2DMSArray") {
        "texture2d_ms_array<float>"
    } else if name.contains("2DMS") {
        "texture2d_ms<float>"
    } else if name.contains("1DArray") {
        "texture1d_array<float>"
    } else if name.contains("2DArray") {
        "texture2d_array<float>"
    } else if name.contains("1D") {
        "texture1d<float>"
    } else {
        "texture2d<float>"
    }
}
fn image_type(name: &str) -> &'static str {
    image_type_for_access(name, "read_write")
}
fn image_type_with_access(name: &str, declaration: &Declaration) -> &'static str {
    let access = if has_qualifier(declaration, "readonly") {
        "read"
    } else if has_qualifier(declaration, "writeonly") {
        "write"
    } else {
        "read_write"
    };
    image_type_for_access(name, access)
}
fn image_type_for_access(name: &str, access: &str) -> &'static str {
    match (
        name.contains("3D"),
        name.starts_with("iimage"),
        name.starts_with("uimage"),
        access,
    ) {
        (true, true, _, "read") => "texture3d<int, access::read>",
        (true, true, _, "write") => "texture3d<int, access::write>",
        (true, true, _, _) => "texture3d<int, access::read_write>",
        (true, _, true, "read") => "texture3d<uint, access::read>",
        (true, _, true, "write") => "texture3d<uint, access::write>",
        (true, _, true, _) => "texture3d<uint, access::read_write>",
        (true, _, _, "read") => "texture3d<float, access::read>",
        (true, _, _, "write") => "texture3d<float, access::write>",
        (true, _, _, _) => "texture3d<float, access::read_write>",
        (_, true, _, "read") => "texture2d<int, access::read>",
        (_, true, _, "write") => "texture2d<int, access::write>",
        (_, true, _, _) => "texture2d<int, access::read_write>",
        (_, _, true, "read") => "texture2d<uint, access::read>",
        (_, _, true, "write") => "texture2d<uint, access::write>",
        (_, _, true, _) => "texture2d<uint, access::read_write>",
        (_, _, _, "read") => "texture2d<float, access::read>",
        (_, _, _, "write") => "texture2d<float, access::write>",
        (_, _, _, _) => "texture2d<float, access::read_write>",
    }
}
fn has_qualifier(declaration: &Declaration, value: &str) -> bool {
    declaration
        .qualifiers
        .iter()
        .any(|item| matches!(item, Qualifier::Memory(name) if name == value))
}

#[cfg(test)]
mod tests {
    use super::generate;
    use crate::parse::Ast;
    use crate::transform::{
        ComputeGeometryPlan, CountProduction, IndirectDrawLayout, OutputCapacity, OutputOffsetKind,
        PrefixSumKind, PrimitiveTopology, SideEffectOrdering, SideEffects, StorageCapabilities,
    };
    use crate::{Location, Options, Stage};

    fn plan(vertices: u32, capacity: u32) -> ComputeGeometryPlan {
        ComputeGeometryPlan {
            input_topology: PrimitiveTopology::Triangles,
            output_topology: PrimitiveTopology::TriangleStrip,
            count_production: CountProduction::VertexAndPrimitive,
            prefix_sums: [PrefixSumKind::Group, PrefixSumKind::Global],
            output_offsets: OutputOffsetKind::SourcePrimitiveOrder,
            produced_vertices: vertices,
            produced_primitives: vertices / 3,
            output_capacity: OutputCapacity {
                vertices: capacity,
                primitives: capacity / 3,
            },
            indirect_draw: IndirectDrawLayout::MetalPrimitives,
            side_effects: SideEffects::default(),
            side_effect_ordering: SideEffectOrdering::SourceInvocationSerial,
            storage_capabilities: StorageCapabilities::default(),
            location: Location::new("geometry.gsh", 4, 2),
        }
    }

    #[test]
    fn geometry_offsets_are_source_ordered_and_capacity_is_not_clamped() {
        let result = generate(
            &Ast {
                declarations: Vec::new(),
            },
            Stage::Compute,
            &Options::default(),
            Some(&plan(12, 12)),
        );
        assert!(result.is_ok(), "{result:?}");
        let text = match result {
            Ok(value) => value.text,
            Err(_) => return,
        };
        let prefix = text.find("global_prefix + invocation_ordinal");
        let capacity = text.find("vertices <= vertex_capacity - vertex_offset");
        assert!(prefix.is_some());
        assert!(capacity.is_some());
        assert!(text.contains("vertexCount"));
        assert!(!text.contains("min(vertices"));
        let path = std::env::temp_dir().join("mtlr-glslc-geometry-layout.metal");
        let output = path.with_extension("air");
        let written = std::fs::write(&path, text.as_bytes());
        assert!(written.is_ok(), "{written:?}");
        let status = std::process::Command::new("xcrun")
            .args(["-sdk", "macosx", "metal", "-std=metal3.2", "-c"])
            .arg(&path)
            .arg("-o")
            .arg(&output)
            .status();
        assert!(
            status.as_ref().is_ok_and(|value| value.success()),
            "{status:?}"
        );
        let _ = std::fs::remove_file(path);
        let _ = std::fs::remove_file(output);
    }

    #[test]
    fn geometry_side_effect_emission_is_serial_and_has_storage_binding() {
        let mut geometry = plan(12, 12);
        geometry.side_effects.storage_writes = true;
        geometry.side_effects.atomics = true;
        geometry.storage_capabilities.storage_writes = true;
        geometry.storage_capabilities.storage_atomics = true;
        let result = generate(
            &Ast {
                declarations: Vec::new(),
            },
            Stage::Compute,
            &Options::default(),
            Some(&geometry),
        );
        assert!(result.is_ok(), "{result:?}");
        let text = match result {
            Ok(value) => value.text,
            Err(_) => return,
        };
        assert!(text.contains("if (gid.x != 0u) return; for (uint invocation = 0u;"));
        assert!(text.contains("side_effect_storage [[buffer(8)]]"));
        assert!(text.contains("mtlr_geometry_side_effect_barrier();"));
    }

    #[test]
    fn geometry_capacity_overflow_is_rejected_before_emission() {
        let result = generate(
            &Ast {
                declarations: Vec::new(),
            },
            Stage::Compute,
            &Options::default(),
            Some(&plan(12, 9)),
        );
        let errors = match result {
            Ok(_) => return,
            Err(value) => value,
        };
        let error = match errors.as_slice().first() {
            Some(value) => value,
            None => return,
        };
        assert_eq!(error.kind, crate::ErrorKind::Msl);
        assert!(error.message.contains("not clamped"));
        assert_eq!(error.location, Some(Location::new("geometry.gsh", 4, 2)));
    }
}
