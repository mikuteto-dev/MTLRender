use super::lower::{self, StageForm};
use crate::parse::{Ast, Declaration, Qualifier, TypeSpecifier};
use crate::transform::{TessellationDomain, TessellationPlan};
use crate::{CompileErrors, Options, Stage};

pub fn generate(
    module: &Ast,
    stage: Stage,
    options: &Options,
    plan: &TessellationPlan,
) -> Result<String, CompileErrors> {
    let lowered = lower::lower(stage, plan)?;
    let mut output = String::from("#include <metal_stdlib>\nusing namespace metal;\n\n");
    output.push_str("struct MtlrTessFactors { float outer[4]; float inner[2]; };\n");
    output.push_str("struct MtlrTessControlPoint { float4 position;");
    for declaration in interface_declarations(module, "in") {
        append_field(&mut output, declaration, false);
    }
    for declaration in interface_declarations(module, "out") {
        append_field(&mut output, declaration, false);
    }
    output.push_str(" };\n");
    output
        .push_str("struct MtlrTessVertex { float4 position; uint primitive_id; float2 tess_coord;");
    for declaration in interface_declarations(module, "in") {
        append_field(&mut output, declaration, false);
    }
    output.push_str(" };\nstruct MtlrTessVertexOut { float4 position [[position]]; uint primitive_id [[user(primitive_id)]]; float2 tess_coord [[user(tess_coord)]];");
    for declaration in interface_declarations(module, "out") {
        append_field(&mut output, declaration, true);
    }
    output.push_str(" };\n");
    match lowered.form {
        StageForm::ControlKernel => control(&mut output, module, &lowered, plan),
        StageForm::EvaluationVertex => evaluation(&mut output, &lowered, module),
    }
    let _ = options;
    Ok(output)
}

fn control(
    output: &mut String,
    module: &Ast,
    lowered: &lower::LoweredStage,
    plan: &TessellationPlan,
) {
    let factors = factor_writes(plan);
    let barrier = "threadgroup_barrier(mem_flags::mem_threadgroup | mem_flags::mem_device);";
    let _ = module;
    output.push_str(&format!(
        "kernel void mtlr_tessellation_control(const device MtlrTessControlPoint *patch_inputs [[buffer(0)]], device MtlrTessControlPoint *patch_outputs [[buffer(1)]], device MtlrTessFactors *patch_factors [[buffer(2)]], constant uint &invocation_count [[buffer(3)]], uint3 gid [[thread_position_in_grid]], uint3 tid [[thread_position_in_threadgroup]]) {{ uint invocation = gid.x; uint local_invocation = tid.x; uint patch = invocation / {}u; bool active = invocation < invocation_count; threadgroup MtlrTessControlPoint points[{}]; if (active) points[local_invocation] = patch_inputs[invocation]; {} if (active) patch_outputs[invocation] = points[local_invocation]; if (local_invocation == 0u && active) {{ {} }} }}\n",
        lowered.patch_vertices, lowered.patch_vertices, barrier, factors
    ));
}

fn factor_writes(plan: &TessellationPlan) -> String {
    let mut result = String::from(
        "patch_factors[patch].outer[0] = 1.0; patch_factors[patch].outer[1] = 1.0; patch_factors[patch].outer[2] = 1.0; patch_factors[patch].outer[3] = 1.0; patch_factors[patch].inner[0] = 1.0; patch_factors[patch].inner[1] = 1.0;",
    );
    for write in &plan.control.factor_writes {
        let field = match write.kind {
            crate::transform::TessellationFactorKind::Outer => "outer",
            crate::transform::TessellationFactorKind::Inner => "inner",
        };
        result.push_str(&format!(
            " patch_factors[patch].{}[{}] = 1.0;",
            field, write.component
        ));
    }
    result
}

fn evaluation(output: &mut String, lowered: &lower::LoweredStage, module: &Ast) {
    let domain = match lowered.domain {
        TessellationDomain::Triangles => "triangles",
        TessellationDomain::Quads => "quads",
        TessellationDomain::Isolines => "isolines",
    };
    output.push_str(&format!(
        "vertex MtlrTessVertexOut mtlr_tessellation_evaluation_{}(uint vertex_id [[vertex_id]], const device MtlrTessVertex *tessellated_vertices [[buffer(0)]], const device uint2 *patch_data [[buffer(1)]], constant uint &vertex_count [[buffer(2)]]) {{ MtlrTessVertex result = tessellated_vertices[vertex_id]; float2 gl_TessCoord = result.tess_coord; uint gl_PrimitiveID = result.primitive_id; if (vertex_id >= vertex_count) result.position = float4(0.0); MtlrTessVertexOut output; output.position = result.position; output.tess_coord = gl_TessCoord; output.primitive_id = gl_PrimitiveID; {} return output; }}\n",
        domain,
        interface_assignments(module)
    ));
}

fn append_field(output: &mut String, declaration: &Declaration, user_attribute: bool) {
    let Some(name) = declaration.name.as_deref() else {
        return;
    };
    output.push(' ');
    output.push_str(type_name(&declaration.type_specifier));
    output.push(' ');
    output.push_str(name);
    if user_attribute {
        output.push_str(" [[user(");
        output.push_str(name);
        output.push_str(")]");
    }
    output.push(';');
}

fn interface_assignments(module: &Ast) -> String {
    let mut assignments = String::new();
    for declaration in interface_declarations(module, "out") {
        if let Some(name) = declaration.name.as_deref() {
            assignments.push_str(" output.");
            assignments.push_str(name);
            assignments.push_str(" = result.");
            assignments.push_str(name);
            assignments.push(';');
        }
    }
    assignments
}

pub fn has_binding(module: &Ast, binding: u32) -> bool {
    module.declarations.iter().any(|declaration| {
        declaration.qualifiers.iter().any(|qualifier| match qualifier {
            Qualifier::Layout(values) => values.iter().any(|(name, value)| {
                name == "binding" && matches!(value, Some(crate::parse::Expression::Literal(number)) if number.parse::<u32>().ok() == Some(binding))
            }),
            _ => false,
        })
    })
}

pub fn type_name(specifier: &TypeSpecifier) -> &'static str {
    match specifier {
        TypeSpecifier::Float => "float",
        TypeSpecifier::Vec(2) => "float2",
        TypeSpecifier::Vec(3) => "float3",
        TypeSpecifier::Vec(4) => "float4",
        TypeSpecifier::Int => "int",
        TypeSpecifier::UInt => "uint",
        _ => "float4",
    }
}

pub fn interface_declarations<'a>(module: &'a Ast, storage: &str) -> Vec<&'a Declaration> {
    module
        .declarations
        .iter()
        .filter(|declaration| {
            declaration
                .qualifiers
                .iter()
                .any(|qualifier| matches!(qualifier, Qualifier::Storage(value) if value == storage))
        })
        .collect()
}
