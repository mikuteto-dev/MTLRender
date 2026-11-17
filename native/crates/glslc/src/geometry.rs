pub mod mesh;

use crate::{
    parse::{Ast, Declaration, Expression, Qualifier, Statement},
    reflect::{Reflection, ResourceKind},
};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum GeometryKind {
    PassThrough,
    Layered,
    BoundedMesh,
    Compute,
}

pub const MAX_RENDER_TARGET_LAYERS: u32 = 2048;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Default)]
pub struct GeometrySideEffects {
    pub image_writes: bool,
    pub ssbo_writes: bool,
    pub atomics: bool,
}

#[derive(Debug, Eq, PartialEq)]
pub struct GeometryPlan {
    pub kind: GeometryKind,
    pub reason: String,
    pub input_topology: String,
    pub output_topology: String,
    pub max_vertices: u32,
    pub emitted_vertices: u32,
    pub emitted_primitives: u32,
    pub writes_layer: bool,
    pub dynamic_control_flow: bool,
    pub side_effects: GeometrySideEffects,
    pub layer_mapping: Vec<u32>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum LayerExpression {
    Affine { scale: i64, offset: i64 },
}

struct Scan<'a> {
    reflection: &'a Reflection,
    emitted_vertices: u32,
    emitted_primitives: u32,
    pending_vertices: u32,
    output_topology: String,
    writes_layer: bool,
    layer_expression: Option<LayerExpression>,
    invalid_layer: bool,
    dynamic_control_flow: bool,
    unknown_call: bool,
    side_effects: GeometrySideEffects,
    invalid_pass_through: bool,
}

pub fn analyze(module: &Ast, reflection: &Reflection) -> GeometryPlan {
    let input = find_interface(module, "in");
    let output = find_interface(module, "out");
    let input_topology = input
        .and_then(layout_name)
        .unwrap_or_else(|| String::from("unknown"));
    let output_topology = output
        .and_then(layout_name)
        .unwrap_or_else(|| String::from("unknown"));
    let max_vertices = output
        .and_then(|declaration| layout_value(declaration, "max_vertices"))
        .unwrap_or(0);
    let mut scan = Scan {
        reflection,
        emitted_vertices: 0,
        emitted_primitives: 0,
        pending_vertices: 0,
        output_topology: String::from(output_topology.as_str()),
        writes_layer: false,
        layer_expression: None,
        invalid_layer: false,
        dynamic_control_flow: false,
        unknown_call: false,
        side_effects: GeometrySideEffects::default(),
        invalid_pass_through: false,
    };
    if let Some(main) = module
        .declarations
        .iter()
        .find(|declaration| declaration.name.as_deref() == Some("main"))
        .and_then(|declaration| declaration.body.as_deref())
    {
        scan_statement(main, &mut scan);
    }
    if scan.pending_vertices != 0 {
        scan.emitted_primitives = scan
            .emitted_primitives
            .saturating_add(primitive_count(&output_topology, scan.pending_vertices));
    }
    let bounded = max_vertices != 0 && scan.emitted_vertices <= max_vertices;
    let pass_through = bounded
        && !scan.dynamic_control_flow
        && !scan.unknown_call
        && !scan.writes_layer
        && scan.side_effects == GeometrySideEffects::default()
        && !scan.invalid_pass_through
        && topology_preserved(
            &input_topology,
            &output_topology,
            scan.emitted_vertices,
            scan.emitted_primitives,
        );
    let layered = bounded
        && scan.writes_layer
        && scan.layer_expression.is_some_and(|expression| {
            input.is_some_and(|declaration| layer_mapping(expression, declaration).is_some())
        })
        && !scan.invalid_layer
        && !scan.dynamic_control_flow
        && !scan.unknown_call
        && scan.side_effects == GeometrySideEffects::default()
        && topology_preserved(
            &input_topology,
            &output_topology,
            scan.emitted_vertices,
            scan.emitted_primitives,
        );
    let (kind, reason) = if pass_through {
        (
            GeometryKind::PassThrough,
            String::from("pass-through: primitive topology and vertex emission are unchanged"),
        )
    } else if layered {
        (
            GeometryKind::Layered,
            String::from("layered: input primitive is instanced with a validated layer mapping"),
        )
    } else if scan.dynamic_control_flow || scan.unknown_call || !bounded {
        (
            GeometryKind::Compute,
            String::from(
                "compute: geometry records preserve dynamic output, layer, and side effects",
            ),
        )
    } else {
        (
            GeometryKind::BoundedMesh,
            if scan.side_effects != GeometrySideEffects::default() {
                String::from(
                    "bounded mesh: statically bounded primitive expansion with ordered resource effects",
                )
            } else {
                String::from("bounded mesh: statically bounded primitive expansion")
            },
        )
    };
    let layer_mapping = if kind == GeometryKind::Layered {
        match scan.layer_expression {
            Some(expression) => input
                .and_then(|declaration| layer_mapping(expression, declaration))
                .unwrap_or_default(),
            None => Vec::new(),
        }
    } else {
        Vec::new()
    };
    GeometryPlan {
        kind,
        reason,
        input_topology,
        output_topology,
        max_vertices,
        emitted_vertices: scan.emitted_vertices,
        emitted_primitives: scan.emitted_primitives,
        writes_layer: scan.writes_layer,
        dynamic_control_flow: scan.dynamic_control_flow || scan.unknown_call,
        side_effects: scan.side_effects,
        layer_mapping,
    }
}

fn find_interface<'a>(module: &'a Ast, storage: &str) -> Option<&'a Declaration> {
    module.declarations.iter().find(|declaration| {
        has_storage(declaration, storage)
            && declaration.name.is_some()
            && declaration
                .qualifiers
                .iter()
                .any(|qualifier| matches!(qualifier, Qualifier::Layout(_)))
    })
}

fn has_storage(declaration: &Declaration, expected: &str) -> bool {
    declaration
        .qualifiers
        .iter()
        .any(|qualifier| match qualifier {
            Qualifier::Storage(value) => value == expected,
            _ => false,
        })
}

fn layout_name(declaration: &Declaration) -> Option<String> {
    declaration.qualifiers.iter().find_map(|qualifier| {
        let Qualifier::Layout(entries) = qualifier else {
            return None;
        };
        entries.iter().find_map(|(name, value)| {
            if value.is_none()
                && matches!(
                    name.as_str(),
                    "points"
                        | "lines"
                        | "lines_adjacency"
                        | "line_strip"
                        | "line_strip_adjacency"
                        | "triangles"
                        | "triangles_adjacency"
                        | "triangle_strip"
                        | "triangle_strip_adjacency"
                )
            {
                Some(String::from(name))
            } else {
                None
            }
        })
    })
}

fn layout_value(declaration: &Declaration, name: &str) -> Option<u32> {
    declaration.qualifiers.iter().find_map(|qualifier| {
        let Qualifier::Layout(entries) = qualifier else {
            return None;
        };
        entries.iter().find_map(|(key, value)| {
            if key == name {
                value.as_ref().and_then(const_u32)
            } else {
                None
            }
        })
    })
}

fn const_u32(expression: &Expression) -> Option<u32> {
    let Expression::Literal(value) = expression else {
        return None;
    };
    value.parse().ok()
}

fn topology_preserved(input: &str, output: &str, vertices: u32, primitives: u32) -> bool {
    (input == output
        || (input == "triangles" && output == "triangle_strip")
        || (input == "lines" && output == "line_strip"))
        && primitives == 1
        && vertices == primitive_width(input)
}

fn primitive_width(topology: &str) -> u32 {
    match topology {
        "points" => 1,
        "lines" | "line_strip" => 2,
        "triangles" | "triangle_strip" => 3,
        _ => 0,
    }
}

fn primitive_count(topology: &str, vertices: u32) -> u32 {
    match topology {
        "points" => vertices,
        "lines" => vertices / 2,
        "line_strip" => vertices.saturating_sub(1),
        "triangles" => vertices / 3,
        "triangle_strip" => vertices.saturating_sub(2),
        _ => 0,
    }
}

fn scan_statement(statement: &Statement, scan: &mut Scan<'_>) {
    match statement {
        Statement::Compound(_, statements) => {
            for statement in statements {
                scan_statement(statement, scan);
            }
        }
        Statement::Expression(_, Some(expression)) => scan_expression(expression, scan),
        Statement::Expression(_, None) => {}
        Statement::If(_, condition, yes, no) => {
            if !is_constant(condition) {
                scan.dynamic_control_flow = true;
            }
            scan_expression(condition, scan);
            scan_statement(yes, scan);
            if let Some(no) = no {
                scan_statement(no, scan);
            }
        }
        Statement::Switch(_, expression, statements) => {
            scan.dynamic_control_flow = true;
            scan_expression(expression, scan);
            for statement in statements {
                scan_statement(statement, scan);
            }
        }
        Statement::Loop(_, init, condition, step, body) => {
            scan.dynamic_control_flow = true;
            if let Some(init) = init {
                scan_statement(init, scan);
            }
            if let Some(condition) = condition {
                scan_expression(condition, scan);
            }
            if let Some(step) = step {
                scan_expression(step, scan);
            }
            scan_statement(body, scan);
        }
        Statement::Declaration(declaration) => {
            if let Some(initializer) = &declaration.initializer {
                scan_expression(initializer, scan);
            }
            for expression in &declaration.array {
                scan_expression(expression, scan);
            }
        }
        Statement::Label(_, _, statement) => scan_statement(statement, scan),
        Statement::Jump(_, _, expression) => {
            if let Some(expression) = expression {
                scan_expression(expression, scan)
            }
        }
        Statement::Empty(_) | Statement::Error(_) => {}
    }
}

fn scan_expression(expression: &Expression, scan: &mut Scan<'_>) {
    match expression {
        Expression::Call(function, arguments) => {
            if let Expression::Name(name) = function.as_ref() {
                match name.as_str() {
                    "EmitVertex" => {
                        scan.emitted_vertices = scan.emitted_vertices.saturating_add(1);
                        scan.pending_vertices = scan.pending_vertices.saturating_add(1);
                    }
                    "EndPrimitive" => {
                        scan.emitted_primitives = scan.emitted_primitives.saturating_add(
                            primitive_count(scan.output_topology.as_str(), scan.pending_vertices),
                        );
                        scan.pending_vertices = 0;
                    }
                    "imageStore" | "imageAtomicAdd" | "imageAtomicExchange" => {
                        scan.side_effects.image_writes = true
                    }
                    "atomicAdd"
                    | "atomicExchange"
                    | "atomicCounterIncrement"
                    | "atomicCounterDecrement" => scan.side_effects.atomics = true,
                    _ if scan.reflection.resources.iter().any(|resource| {
                        resource.kind == ResourceKind::StorageBuffer
                            && resource.name == root_name(function)
                    }) =>
                    {
                        scan.side_effects.ssbo_writes = true
                    }
                    _ if !is_constructor(name) => scan.unknown_call = true,
                    _ => {}
                }
            }
            scan_expression(function, scan);
            for argument in arguments {
                scan_expression(argument, scan);
            }
        }
        Expression::Binary(operator, left, right) => {
            if operator == "="
                && (root_name(left) == "data"
                    || scan.reflection.resources.iter().any(|resource| {
                        resource.kind == ResourceKind::StorageBuffer
                            && resource.name == root_name(left)
                    }))
            {
                scan.side_effects.ssbo_writes = true;
            }
            if operator == "=" && root_name(left) == "gl_Layer" {
                scan.writes_layer = true;
                let expression = affine_layer_expression(right);
                if let Some(previous) = scan.layer_expression {
                    if Some(previous) != expression {
                        scan.invalid_layer = true;
                    }
                } else if let Some(expression) = expression {
                    scan.layer_expression = Some(expression);
                } else {
                    scan.invalid_layer = true;
                }
            }
            if operator == "=" && root_name(left) != "gl_Layer" && !is_passthrough_value(right) {
                scan.invalid_pass_through = true;
            }
            scan_expression(left, scan);
            scan_expression(right, scan);
        }
        Expression::Unary(_, value) => scan_expression(value, scan),
        Expression::Conditional(condition, yes, no) => {
            scan.dynamic_control_flow = true;
            scan_expression(condition, scan);
            scan_expression(yes, scan);
            scan_expression(no, scan);
        }
        Expression::Index(base, index) => {
            scan_expression(base, scan);
            scan_expression(index, scan);
        }
        Expression::Field(base, _) => scan_expression(base, scan),
        Expression::Constructor(_, values) => {
            for value in values {
                scan_expression(value, scan)
            }
        }
        Expression::Literal(_) | Expression::Name(_) => {}
    }
}

fn root_name(expression: &Expression) -> String {
    match expression {
        Expression::Name(name) => String::from(name),
        Expression::Field(base, _) | Expression::Index(base, _) => root_name(base),
        _ => String::new(),
    }
}

fn is_passthrough_value(expression: &Expression) -> bool {
    match expression {
        Expression::Name(name) => name == "gl_Position" || name == "gl_in",
        Expression::Field(base, _) | Expression::Index(base, _) => is_passthrough_value(base),
        Expression::Constructor(_, values) => values.iter().all(is_passthrough_value),
        Expression::Literal(_) => false,
        Expression::Binary(_, left, right) => {
            is_passthrough_value(left) || is_passthrough_value(right)
        }
        Expression::Unary(_, value) => is_passthrough_value(value),
        Expression::Conditional(condition, yes, no) => {
            is_passthrough_value(condition) || is_passthrough_value(yes) || is_passthrough_value(no)
        }
        Expression::Call(_, _) => false,
    }
}

fn affine_layer_expression(expression: &Expression) -> Option<LayerExpression> {
    let (scale, offset) = affine(expression)?;
    Some(LayerExpression::Affine { scale, offset })
}

fn affine(expression: &Expression) -> Option<(i64, i64)> {
    match expression {
        Expression::Literal(value) => value.parse().ok().map(|value| (0, value)),
        Expression::Name(name) if name == "gl_InvocationID" => Some((1, 0)),
        Expression::Unary(operator, value) if operator == "+" => affine(value),
        Expression::Unary(operator, value) if operator == "-" => {
            let (scale, offset) = affine(value)?;
            Some((scale.checked_neg()?, offset.checked_neg()?))
        }
        Expression::Binary(operator, left, right) if operator == "+" || operator == "-" => {
            let (left_scale, left_offset) = affine(left)?;
            let (right_scale, right_offset) = affine(right)?;
            if operator == "+" {
                Some((
                    left_scale.checked_add(right_scale)?,
                    left_offset.checked_add(right_offset)?,
                ))
            } else {
                Some((
                    left_scale.checked_sub(right_scale)?,
                    left_offset.checked_sub(right_offset)?,
                ))
            }
        }
        Expression::Binary(operator, left, right) if operator == "*" => {
            let (left_scale, left_offset) = affine(left)?;
            let (right_scale, right_offset) = affine(right)?;
            if left_scale == 0 {
                Some((
                    right_scale.checked_mul(left_offset)?,
                    right_offset.checked_mul(left_offset)?,
                ))
            } else if right_scale == 0 {
                Some((
                    left_scale.checked_mul(right_offset)?,
                    left_offset.checked_mul(right_offset)?,
                ))
            } else {
                None
            }
        }
        _ => None,
    }
}

fn layer_mapping(expression: LayerExpression, input: &Declaration) -> Option<Vec<u32>> {
    let LayerExpression::Affine { scale, offset } = expression;
    if scale < 0 || offset < 0 || scale >= i64::from(MAX_RENDER_TARGET_LAYERS) {
        return None;
    }
    let count = if scale == 0 {
        1
    } else {
        layout_value(input, "invocations").unwrap_or(1)
    };
    if count == 0 || count > MAX_RENDER_TARGET_LAYERS {
        return None;
    }
    let mut mapping = Vec::new();
    for index in 0..count {
        let layer = offset.checked_add(scale.checked_mul(i64::from(index))?)?;
        let layer = u32::try_from(layer).ok()?;
        if layer >= MAX_RENDER_TARGET_LAYERS {
            return None;
        }
        mapping.push(layer);
    }
    Some(mapping)
}

fn is_constant(expression: &Expression) -> bool {
    match expression {
        Expression::Literal(_) => true,
        Expression::Unary(_, value) => is_constant(value),
        Expression::Binary(_, left, right) => is_constant(left) && is_constant(right),
        Expression::Conditional(condition, yes, no) => {
            is_constant(condition) && is_constant(yes) && is_constant(no)
        }
        _ => false,
    }
}

fn is_constructor(name: &str) -> bool {
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
            | "float"
            | "int"
            | "uint"
            | "bool"
    )
}
