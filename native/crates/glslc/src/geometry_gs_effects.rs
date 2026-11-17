use crate::geometry::{GeometryKind, GeometryPlan};
use crate::geometry_gs::{
    GeometryGsAccess, GeometryGsAccessMode, GeometryGsContext, GeometryGsError,
    GeometryGsResourceKind,
};
use crate::geometry_records::{SideEffectAccess, SideEffectAccessDescriptor, SideEffectKind};
use crate::parse::{Ast, Expression, Statement};
use crate::reflect::{Reflection, ResourceKind};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum GeometryGsOrdering {
    Relaxed,
    AcquireRelease,
    Sequential,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum GeometryGsEffect {
    Access {
        descriptor: SideEffectAccessDescriptor,
        operation: String,
        width: u8,
        ordering: GeometryGsOrdering,
    },
    Barrier {
        operation: String,
        ordering: GeometryGsOrdering,
    },
    EmitVertex,
    EndPrimitive,
    OutputWrite,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct GeometryGsEmission {
    pub invocation_count: u64,
    pub source_order: u32,
    pub text: String,
}

pub fn collect_geometry_accesses(
    module: &Ast,
    reflection: &Reflection,
    context: &GeometryGsContext,
) -> Result<Vec<GeometryGsAccess>, GeometryGsError> {
    let effects = collect_geometry_effects(module, reflection, context)?;
    let mut accesses = Vec::new();
    for effect in effects {
        let GeometryGsEffect::Access {
            descriptor,
            operation,
            ..
        } = effect
        else {
            continue;
        };
        accesses.push(GeometryGsAccess {
            resource: match descriptor.kind {
                SideEffectKind::Image => GeometryGsResourceKind::Image,
                SideEffectKind::Ssbo => GeometryGsResourceKind::Ssbo,
                SideEffectKind::Atomic => GeometryGsResourceKind::Atomic,
            },
            binding: descriptor.binding,
            mode: match descriptor.access {
                SideEffectAccess::Read => GeometryGsAccessMode::Read,
                SideEffectAccess::Write => GeometryGsAccessMode::Write,
                SideEffectAccess::ReadWrite => GeometryGsAccessMode::ReadWrite,
            },
            operation,
        });
    }
    Ok(accesses)
}

pub fn analyze_geometry_side_effects(
    module: &Ast,
    reflection: &Reflection,
    plan: &GeometryPlan,
    context: &GeometryGsContext,
) -> Result<Vec<GeometryGsEffect>, GeometryGsError> {
    let effects = collect_geometry_effects(module, reflection, context)?;
    validate_side_effect_order(&effects, plan)?;
    Ok(effects)
}

pub fn collect_geometry_effects(
    module: &Ast,
    reflection: &Reflection,
    context: &GeometryGsContext,
) -> Result<Vec<GeometryGsEffect>, GeometryGsError> {
    let mut effects = Vec::new();
    for declaration in &module.declarations {
        if let Some(body) = declaration.body.as_deref() {
            collect_statement(body, reflection, context, &mut effects)?;
        }
    }
    Ok(effects)
}

pub fn emit_geometry_side_effect(
    effect: &GeometryGsEffect,
    invocation_count: u64,
    source_order: u32,
) -> Result<GeometryGsEmission, GeometryGsError> {
    if invocation_count == 0 {
        return Err(GeometryGsError::Invalid {
            context: GeometryGsContext {
                pack: String::from("geometry"),
                dimension: String::from("unknown"),
                program: String::from("unknown"),
                source_stage: String::from("geometry"),
            },
            operation: String::from("side effect invocation count is zero"),
        });
    }
    let text = match effect {
        GeometryGsEffect::Access {
            descriptor,
            operation,
            width,
            ordering,
        } => {
            if *width != 4 {
                return Err(GeometryGsError::Unsupported {
                    context: geometry_context(),
                    operation: format!("{operation} uses unsupported atomic width {width}"),
                });
            }
            let order = ordering_text(*ordering);
            format!(
                "mtlr_gs_effect_{operation}(resource_{}, {}, {}, {}, {order});",
                descriptor.binding, descriptor.byte_offset, descriptor.byte_size, invocation_count,
            )
        }
        GeometryGsEffect::Barrier {
            operation,
            ordering,
        } => format!("mtlr_gs_barrier_{operation}({});", ordering_text(*ordering)),
        GeometryGsEffect::EmitVertex => String::from("mtlr_gs_emit_vertex();"),
        GeometryGsEffect::EndPrimitive => String::from("mtlr_gs_end_primitive();"),
        GeometryGsEffect::OutputWrite => String::from("mtlr_gs_output_write();"),
    };
    Ok(GeometryGsEmission {
        invocation_count,
        source_order,
        text,
    })
}

pub fn emit_geometry_barrier(
    operation: &str,
    ordering: GeometryGsOrdering,
    source_order: u32,
) -> Result<GeometryGsEmission, GeometryGsError> {
    let effect = GeometryGsEffect::Barrier {
        operation: String::from(operation),
        ordering,
    };
    emit_geometry_side_effect(&effect, 1, source_order)
}

pub fn validate_side_effect_order(
    effects: &[GeometryGsEffect],
    plan: &GeometryPlan,
) -> Result<(), GeometryGsError> {
    if plan.kind != GeometryKind::Compute
        && !(plan.kind == GeometryKind::BoundedMesh
            && plan.side_effects != crate::geometry::GeometrySideEffects::default())
    {
        return Err(GeometryGsError::Unsupported {
            context: geometry_context(),
            operation: String::from("side effects require compute geometry emulation"),
        });
    }
    let mut image = false;
    let mut ssbo = false;
    let mut atomic = false;
    for effect in effects {
        match effect {
            GeometryGsEffect::Access { descriptor, .. } => match descriptor.kind {
                SideEffectKind::Image => image = true,
                SideEffectKind::Ssbo => ssbo = true,
                SideEffectKind::Atomic => atomic = true,
            },
            GeometryGsEffect::Barrier { .. }
            | GeometryGsEffect::EmitVertex
            | GeometryGsEffect::EndPrimitive
            | GeometryGsEffect::OutputWrite => {}
        }
    }
    if image != plan.side_effects.image_writes
        || ssbo != plan.side_effects.ssbo_writes
        || atomic != plan.side_effects.atomics
    {
        return Err(GeometryGsError::Invalid {
            context: geometry_context(),
            operation: String::from(
                "geometry side-effect metadata does not match emitted accesses",
            ),
        });
    }
    Ok(())
}

pub fn unsupported_geometry_operation(
    context: &GeometryGsContext,
    operation: &str,
) -> GeometryGsError {
    GeometryGsError::Unsupported {
        context: GeometryGsContext {
            pack: String::from(context.pack.as_str()),
            dimension: String::from(context.dimension.as_str()),
            program: String::from(context.program.as_str()),
            source_stage: String::from(context.source_stage.as_str()),
        },
        operation: String::from(operation),
    }
}

fn collect_statement(
    statement: &Statement,
    reflection: &Reflection,
    context: &GeometryGsContext,
    effects: &mut Vec<GeometryGsEffect>,
) -> Result<(), GeometryGsError> {
    match statement {
        Statement::Compound(_, statements) => {
            for item in statements {
                collect_statement(item, reflection, context, effects)?;
            }
        }
        Statement::Expression(_, Some(expression)) => {
            collect_expression(expression, reflection, context, effects)?;
        }
        Statement::If(_, condition, yes, no) => {
            if !constant(condition) {
                return Err(unsupported_geometry_operation(
                    context,
                    "dynamic side-effect control flow",
                ));
            }
            collect_expression(condition, reflection, context, effects)?;
            collect_statement(yes, reflection, context, effects)?;
            if let Some(no) = no {
                collect_statement(no, reflection, context, effects)?;
            }
        }
        Statement::Switch(_, _, _) | Statement::Loop(_, _, _, _, _) => {
            return Err(unsupported_geometry_operation(
                context,
                "loop or switch around a geometry side effect",
            ));
        }
        Statement::Declaration(declaration) => {
            if let Some(initializer) = &declaration.initializer {
                collect_expression(initializer, reflection, context, effects)?;
            }
        }
        Statement::Label(_, _, statement) => {
            collect_statement(statement, reflection, context, effects)?
        }
        Statement::Jump(_, _, _)
        | Statement::Expression(_, None)
        | Statement::Empty(_)
        | Statement::Error(_) => {}
    }
    Ok(())
}

fn collect_expression(
    expression: &Expression,
    reflection: &Reflection,
    context: &GeometryGsContext,
    effects: &mut Vec<GeometryGsEffect>,
) -> Result<(), GeometryGsError> {
    match expression {
        Expression::Call(function, arguments) => {
            let name = match function.as_ref() {
                Expression::Name(name) => name.as_str(),
                _ => {
                    return Err(unsupported_geometry_operation(
                        context,
                        "indirect geometry operation",
                    ));
                }
            };
            for argument in arguments {
                collect_expression(argument, reflection, context, effects)?;
            }
            if name == "EmitVertex" {
                effects.push(GeometryGsEffect::EmitVertex);
            } else if name == "EndPrimitive" {
                effects.push(GeometryGsEffect::EndPrimitive);
            } else if name == "barrier" || name.starts_with("memoryBarrier") {
                effects.push(GeometryGsEffect::Barrier {
                    operation: String::from(name),
                    ordering: GeometryGsOrdering::Sequential,
                });
            } else if let Some(effect) = resource_call(name, arguments, reflection, context)? {
                effects.push(effect);
            } else if !constructor(name) {
                return Err(unsupported_geometry_operation(context, name));
            }
        }
        Expression::Binary(operator, left, right) => {
            collect_expression(left, reflection, context, effects)?;
            collect_expression(right, reflection, context, effects)?;
            if operator == "=" {
                let root = root_name(left);
                if let Some(resource) = resource(reflection, root.as_str()) {
                    effects.push(GeometryGsEffect::Access {
                        descriptor: descriptor(resource, SideEffectAccess::Write, 4),
                        operation: String::from("ssboStore"),
                        width: 4,
                        ordering: GeometryGsOrdering::Relaxed,
                    });
                }
            }
        }
        Expression::Unary(_, value) => collect_expression(value, reflection, context, effects)?,
        Expression::Conditional(_, _, _) => {
            return Err(unsupported_geometry_operation(
                context,
                "conditional side-effect expression",
            ));
        }
        Expression::Index(base, index) => {
            collect_expression(base, reflection, context, effects)?;
            collect_expression(index, reflection, context, effects)?;
        }
        Expression::Field(base, _) => collect_expression(base, reflection, context, effects)?,
        Expression::Constructor(_, values) => {
            for value in values {
                collect_expression(value, reflection, context, effects)?;
            }
        }
        Expression::Name(_) | Expression::Literal(_) => {}
    }
    Ok(())
}

fn resource_call(
    name: &str,
    arguments: &[Expression],
    reflection: &Reflection,
    context: &GeometryGsContext,
) -> Result<Option<GeometryGsEffect>, GeometryGsError> {
    let (resource_name, access, kind, width, ordering) = match name {
        "imageLoad" => (
            argument_name(arguments, 0),
            SideEffectAccess::Read,
            SideEffectKind::Image,
            4,
            GeometryGsOrdering::Relaxed,
        ),
        "imageStore" => (
            argument_name(arguments, 0),
            SideEffectAccess::Write,
            SideEffectKind::Image,
            4,
            GeometryGsOrdering::AcquireRelease,
        ),
        "imageAtomicAdd" | "imageAtomicExchange" => (
            argument_name(arguments, 0),
            SideEffectAccess::ReadWrite,
            SideEffectKind::Image,
            4,
            GeometryGsOrdering::Sequential,
        ),
        "atomicAdd" | "atomicExchange" => (
            argument_name(arguments, 0),
            SideEffectAccess::ReadWrite,
            SideEffectKind::Atomic,
            4,
            GeometryGsOrdering::Sequential,
        ),
        "atomicCounter" | "atomicCounterIncrement" | "atomicCounterDecrement" => (
            argument_name(arguments, 0),
            SideEffectAccess::ReadWrite,
            SideEffectKind::Atomic,
            4,
            GeometryGsOrdering::Sequential,
        ),
        _ => return Ok(None),
    };
    let Some(resource_name) = resource_name else {
        return Err(unsupported_geometry_operation(
            context,
            "dynamic geometry resource address",
        ));
    };
    let Some(resource) = resource(reflection, resource_name.as_str()) else {
        return Err(unsupported_geometry_operation(
            context,
            "unreflected geometry resource",
        ));
    };
    let descriptor = descriptor_with_kind(resource, kind, access, width);
    Ok(Some(GeometryGsEffect::Access {
        descriptor,
        operation: String::from(name),
        width,
        ordering,
    }))
}

fn descriptor(
    resource: &crate::reflect::Resource,
    access: SideEffectAccess,
    width: u8,
) -> SideEffectAccessDescriptor {
    descriptor_with_kind(
        resource,
        match resource.kind {
            ResourceKind::Image => SideEffectKind::Image,
            ResourceKind::StorageBuffer => SideEffectKind::Ssbo,
            ResourceKind::AtomicCounter => SideEffectKind::Atomic,
            _ => SideEffectKind::Ssbo,
        },
        access,
        width,
    )
}

fn descriptor_with_kind(
    resource: &crate::reflect::Resource,
    kind: SideEffectKind,
    access: SideEffectAccess,
    width: u8,
) -> SideEffectAccessDescriptor {
    SideEffectAccessDescriptor {
        kind,
        access,
        binding: resource.binding,
        byte_offset: 0,
        byte_size: u64::from(width),
    }
}

fn resource<'a>(reflection: &'a Reflection, name: &str) -> Option<&'a crate::reflect::Resource> {
    reflection
        .resources
        .iter()
        .find(|resource| resource.name == name)
}

fn argument_name(arguments: &[Expression], index: usize) -> Option<String> {
    arguments.get(index).and_then(|value| match value {
        Expression::Name(name) => Some(String::from(name)),
        Expression::Index(base, _) | Expression::Field(base, _) => Some(root_name(base)),
        _ => None,
    })
}

fn root_name(expression: &Expression) -> String {
    match expression {
        Expression::Name(name) => String::from(name),
        Expression::Index(base, _) | Expression::Field(base, _) => root_name(base),
        _ => String::new(),
    }
}

fn constant(expression: &Expression) -> bool {
    match expression {
        Expression::Literal(_) => true,
        Expression::Unary(_, value) => constant(value),
        Expression::Binary(_, left, right) => constant(left) && constant(right),
        _ => false,
    }
}

fn constructor(name: &str) -> bool {
    name == "vec2"
        || name == "vec3"
        || name == "vec4"
        || name == "ivec2"
        || name == "ivec3"
        || name == "ivec4"
        || name == "uvec2"
        || name == "uvec3"
        || name == "uvec4"
        || name == "float"
        || name == "int"
        || name == "uint"
}

fn ordering_text(ordering: GeometryGsOrdering) -> &'static str {
    match ordering {
        GeometryGsOrdering::Relaxed => "memory_order_relaxed",
        GeometryGsOrdering::AcquireRelease => "memory_order_acq_rel",
        GeometryGsOrdering::Sequential => "memory_order_seq_cst",
    }
}

fn geometry_context() -> GeometryGsContext {
    GeometryGsContext {
        pack: String::from("geometry"),
        dimension: String::from("unknown"),
        program: String::from("unknown"),
        source_stage: String::from("geometry"),
    }
}
