use crate::{
    CompileError, CompileErrors, ErrorKind, Location, Stage,
    parse::{Ast, Declaration, Expression, Qualifier},
    transform::{
        FactorRange, InvocationIdentity, PatchControlPoints, PatchOrder, PatchPrimitiveOrder,
        SideEffects, TessellationContract, TessellationDomain, TessellationFactors,
        TessellationInput, TessellationSpacing,
    },
};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Topology {
    Triangles,
    Quads,
    Isolines,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Spacing {
    Equal,
    FractionalEven,
    FractionalOdd,
}

#[derive(Debug, Eq, PartialEq)]
pub struct TessellationDescriptor {
    pub topology: Option<Topology>,
    pub spacing: Option<Spacing>,
    pub patch_vertices: u32,
    pub control_inputs: Vec<String>,
    pub control_outputs: Vec<String>,
    pub evaluation_inputs: Vec<String>,
    pub evaluation_outputs: Vec<String>,
    pub uses_invocation_id: bool,
    pub uses_primitive_id: bool,
    pub uses_tess_coord: bool,
    pub uses_tess_levels: bool,
}
#[derive(Debug, Eq, PartialEq)]
pub struct Metadata {
    pub descriptor: TessellationDescriptor,
    pub input: TessellationInput,
}

fn location(declaration: &Declaration) -> Location {
    Location::new(
        &declaration.span.source_path,
        declaration.span.line,
        declaration.span.column,
    )
}
fn error(declaration: &Declaration, message: &str) -> CompileErrors {
    CompileErrors::one(CompileError::new(
        ErrorKind::Semantic,
        message,
        Some(location(declaration)),
    ))
}
fn has_layout(declaration: &Declaration, name: &str) -> bool {
    declaration.qualifiers.iter().any(|qualifier| matches!(qualifier, Qualifier::Layout(values) if values.iter().any(|(key, _)| key == name)))
}
fn layout<'a>(declaration: &'a Declaration, name: &str) -> Option<&'a Expression> {
    declaration
        .qualifiers
        .iter()
        .find_map(|qualifier| match qualifier {
            Qualifier::Layout(values) => values
                .iter()
                .find_map(|(key, value)| (key == name).then_some(value.as_ref()))
                .flatten(),
            _ => None,
        })
}
fn integer(expression: &Expression) -> Option<u32> {
    match expression {
        Expression::Literal(value) => value.parse::<u32>().ok(),
        _ => None,
    }
}
fn interface_names(ast: &Ast, storage: &str) -> Vec<String> {
    ast.declarations
        .iter()
        .filter_map(|declaration| {
            declaration
                .qualifiers
                .iter()
                .any(|qualifier| matches!(qualifier, Qualifier::Storage(value) if value == storage))
                .then(|| declaration.name.as_deref().map(String::from))
                .flatten()
        })
        .collect()
}
fn walk_expression(
    expression: &Expression,
    names: &mut [bool; 4],
    factors: &mut Vec<(String, Option<u32>)>,
) {
    match expression {
        Expression::Name(name) => {
            names[0] |= name == "gl_InvocationID";
            names[1] |= name == "gl_PrimitiveID";
            names[2] |= name == "gl_TessCoord";
            names[3] |= name == "gl_TessLevelOuter" || name == "gl_TessLevelInner";
        }
        Expression::Index(base, index) => {
            if let Expression::Name(name) = base.as_ref()
                && (name == "gl_TessLevelOuter" || name == "gl_TessLevelInner")
            {
                factors.push((String::from(name), integer(index)));
            }
            walk_expression(base, names, factors);
            walk_expression(index, names, factors);
        }
        Expression::Field(base, _) | Expression::Unary(_, base) => {
            walk_expression(base, names, factors)
        }
        Expression::Binary(_, left, right) => {
            walk_expression(left, names, factors);
            walk_expression(right, names, factors);
        }
        Expression::Conditional(condition, yes, no) => {
            walk_expression(condition, names, factors);
            walk_expression(yes, names, factors);
            walk_expression(no, names, factors);
        }
        Expression::Call(function, arguments) => {
            walk_expression(function, names, factors);
            for argument in arguments {
                walk_expression(argument, names, factors);
            }
        }
        Expression::Constructor(_, arguments) => {
            for argument in arguments {
                walk_expression(argument, names, factors);
            }
        }
        Expression::Literal(_) => {}
    }
}
fn walk_statement(
    statement: &crate::parse::Statement,
    names: &mut [bool; 4],
    factors: &mut Vec<(String, Option<u32>)>,
) {
    use crate::parse::Statement;
    match statement {
        Statement::Compound(_, statements) | Statement::Switch(_, _, statements) => {
            for item in statements {
                walk_statement(item, names, factors);
            }
        }
        Statement::Declaration(declaration) => walk_declaration(declaration, names, factors),
        Statement::Expression(_, expression) | Statement::Jump(_, _, expression) => {
            if let Some(expression) = expression {
                walk_expression(expression, names, factors);
            }
        }
        Statement::If(_, condition, yes, no) => {
            walk_expression(condition, names, factors);
            walk_statement(yes, names, factors);
            if let Some(no) = no {
                walk_statement(no, names, factors);
            }
        }
        Statement::Label(_, expression, statement) => {
            if let Some(expression) = expression {
                walk_expression(expression, names, factors);
            }
            walk_statement(statement, names, factors);
        }
        Statement::Loop(_, init, condition, increment, body) => {
            if let Some(init) = init {
                walk_statement(init, names, factors);
            }
            if let Some(condition) = condition {
                walk_expression(condition, names, factors);
            }
            if let Some(increment) = increment {
                walk_expression(increment, names, factors);
            }
            walk_statement(body, names, factors);
        }
        Statement::Empty(_) | Statement::Error(_) => {}
    }
}
fn walk_declaration(
    declaration: &Declaration,
    names: &mut [bool; 4],
    factors: &mut Vec<(String, Option<u32>)>,
) {
    if let Some(initializer) = &declaration.initializer {
        walk_expression(initializer, names, factors);
    }
    if let Some(body) = &declaration.body {
        walk_statement(body, names, factors);
    }
    for member in &declaration.members {
        walk_declaration(member, names, factors);
    }
}
fn parse_layout(
    declaration: &Declaration,
    stage: Stage,
) -> Result<(Option<Topology>, Option<Spacing>, u32), CompileErrors> {
    let topology_values = [
        ("triangles", Topology::Triangles),
        ("quads", Topology::Quads),
        ("isolines", Topology::Isolines),
    ]
    .iter()
    .filter_map(|(name, value)| has_layout(declaration, name).then_some(*value))
    .collect::<Vec<_>>();
    if topology_values.len() > 1 {
        return Err(error(
            declaration,
            "tessellation layout declares contradictory topologies",
        ));
    }
    let spacing_values = [
        has_layout(declaration, "equal_spacing"),
        has_layout(declaration, "fractional_even_spacing"),
        has_layout(declaration, "fractional_odd_spacing"),
    ]
    .into_iter()
    .filter(|value| *value)
    .count();
    if spacing_values > 1 {
        return Err(error(
            declaration,
            "tessellation layout declares contradictory spacing qualifiers",
        ));
    }
    if has_layout(declaration, "cw") && has_layout(declaration, "ccw") {
        return Err(error(
            declaration,
            "tessellation layout declares contradictory winding qualifiers",
        ));
    }
    let topology = topology_values.into_iter().next();
    let spacing = if has_layout(declaration, "fractional_even_spacing") {
        Some(Spacing::FractionalEven)
    } else if has_layout(declaration, "fractional_odd_spacing") {
        Some(Spacing::FractionalOdd)
    } else if has_layout(declaration, "equal_spacing") {
        Some(Spacing::Equal)
    } else {
        None
    };
    if has_layout(declaration, "point_mode") {
        return Err(error(declaration, "tessellation point mode is unsupported"));
    }
    if stage == Stage::TessellationEvaluation && topology.is_none() {
        return Err(error(
            declaration,
            "tessellation evaluation layout must declare triangles, quads, or isolines",
        ));
    }
    if stage == Stage::TessellationControl && layout(declaration, "vertices").is_none() {
        return Err(error(
            declaration,
            "tessellation control layout must declare patch vertices",
        ));
    }
    if stage == Stage::TessellationControl && topology.is_some() {
        return Err(error(
            declaration,
            "tessellation control layout cannot declare an evaluation topology",
        ));
    }
    let vertices = layout(declaration, "vertices")
        .and_then(integer)
        .unwrap_or(match topology {
            Some(Topology::Triangles) => 3,
            Some(Topology::Quads) => 4,
            Some(Topology::Isolines) => 2,
            None => 3,
        });
    if layout(declaration, "vertices").is_some()
        && layout(declaration, "vertices").and_then(integer).is_none()
    {
        return Err(error(
            declaration,
            "tessellation vertices layout must be a constant integer",
        ));
    }
    if stage == Stage::TessellationEvaluation
        && layout(declaration, "vertices").is_some()
        && topology.is_some_and(|value| match value {
            Topology::Triangles => vertices != 3,
            Topology::Quads => vertices != 4,
            Topology::Isolines => vertices != 2,
        })
    {
        return Err(error(
            declaration,
            "tessellation patch size does not match the selected topology",
        ));
    }
    Ok((topology, spacing, vertices))
}
fn make_input(
    declaration: &Declaration,
    topology: Topology,
    spacing: Spacing,
    vertices: u32,
    names: &[bool; 4],
    factors: &[(String, Option<u32>)],
) -> Result<TessellationInput, CompileErrors> {
    let domain = match topology {
        Topology::Triangles => TessellationDomain::Triangles,
        Topology::Quads => TessellationDomain::Quads,
        Topology::Isolines => TessellationDomain::Isolines,
    };
    let limit = if domain == TessellationDomain::Isolines {
        2
    } else {
        3
    };
    for (name, index) in factors {
        let Some(index) = index else {
            return Err(error(
                declaration,
                "tessellation factor array index must be a constant integer",
            ));
        };
        let max = if name == "gl_TessLevelOuter" {
            limit
        } else if domain == TessellationDomain::Isolines {
            return Err(error(
                declaration,
                "isolines cannot use inner tessellation factors",
            ));
        } else {
            2
        };
        if *index >= max {
            return Err(error(
                declaration,
                "tessellation factor array index is outside the selected topology",
            ));
        }
    }
    let inner_factor_range = (domain != TessellationDomain::Isolines).then_some(FactorRange {
        minimum: 1,
        maximum: 4,
    });
    Ok(TessellationInput {
        contract: TessellationContract {
            domain,
            spacing: match spacing {
                Spacing::Equal => TessellationSpacing::Equal,
                Spacing::FractionalEven => TessellationSpacing::FractionalEven,
                Spacing::FractionalOdd => TessellationSpacing::FractionalOdd,
            },
            patch_order: PatchOrder::CounterClockwise,
            primitive_order: PatchPrimitiveOrder::SourcePatchThenPrimitive,
            patch: PatchControlPoints {
                input_count: vertices,
                output_count: vertices,
                input_order: (0..vertices).collect(),
                output_order: (0..vertices).collect(),
            },
            outer_factor_range: FactorRange {
                minimum: 1,
                maximum: 4,
            },
            inner_factor_range,
            tcs_invocation: InvocationIdentity::TcsInvocation,
            generated_invocation: InvocationIdentity::GeneratedTessellator,
            domain_coordinates: 2,
            tes_outputs: vec![0],
            side_effects: SideEffects::default(),
            location: location(declaration),
        },
        factors: if factors.is_empty() {
            TessellationFactors::Dynamic
        } else {
            TessellationFactors::Constant
        },
        uses_invocation_id: names[0],
        location: location(declaration),
    })
}

pub fn analyze(ast: &Ast, stage: Stage) -> Result<Option<Metadata>, CompileErrors> {
    if !matches!(
        stage,
        Stage::TessellationControl | Stage::TessellationEvaluation
    ) {
        return Ok(None);
    }
    let Some(declaration) = ast.declarations.iter().find(|item| {
        item.qualifiers
            .iter()
            .any(|qualifier| matches!(qualifier, Qualifier::Layout(_)))
    }) else {
        let location = ast.declarations.first().map(location);
        return Err(CompileErrors::one(CompileError::new(
            ErrorKind::Semantic,
            "tessellation layout is missing",
            location,
        )));
    };
    let (topology, spacing, vertices) = parse_layout(declaration, stage)?;
    let mut names = [false; 4];
    let mut factors = Vec::new();
    for item in &ast.declarations {
        walk_declaration(item, &mut names, &mut factors);
    }
    let topology = topology.unwrap_or(Topology::Triangles);
    let spacing = spacing.unwrap_or(Spacing::Equal);
    let input = make_input(declaration, topology, spacing, vertices, &names, &factors)?;
    let descriptor = TessellationDescriptor {
        topology: (stage == Stage::TessellationEvaluation).then_some(topology),
        spacing: (stage == Stage::TessellationEvaluation).then_some(spacing),
        patch_vertices: vertices,
        control_inputs: if stage == Stage::TessellationControl {
            vec![String::from("gl_in")]
        } else {
            Vec::new()
        },
        control_outputs: if stage == Stage::TessellationControl {
            vec![String::from("gl_out")]
        } else {
            Vec::new()
        },
        evaluation_inputs: if stage == Stage::TessellationEvaluation {
            interface_names(ast, "in")
        } else {
            Vec::new()
        },
        evaluation_outputs: if stage == Stage::TessellationEvaluation {
            let mut values = vec![String::from("gl_Position")];
            values.extend(interface_names(ast, "out"));
            values
        } else {
            Vec::new()
        },
        uses_invocation_id: names[0],
        uses_primitive_id: names[1],
        uses_tess_coord: names[2],
        uses_tess_levels: names[3],
    };
    Ok(Some(Metadata { descriptor, input }))
}

#[cfg(test)]
mod tests {
    use crate::{ErrorKind, Options, Source, Stage, compile};
    #[test]
    fn supported_topologies_and_spacing_are_reflected() {
        for (layout, topology, spacing) in [
            (
                "triangles, equal_spacing",
                super::Topology::Triangles,
                super::Spacing::Equal,
            ),
            (
                "quads, fractional_even_spacing",
                super::Topology::Quads,
                super::Spacing::FractionalEven,
            ),
            (
                "isolines, fractional_odd_spacing",
                super::Topology::Isolines,
                super::Spacing::FractionalOdd,
            ),
        ] {
            let source = format!(
                "layout({layout}) in; void main() {{ float x = gl_TessCoord.x; gl_PrimitiveID; }}"
            );
            let result = compile(
                Stage::TessellationEvaluation,
                &[Source::new("shader.tes", source)],
                &[],
                &Options::default(),
            );
            assert!(result.is_ok(), "{result:?}");
            let Ok(compiled) = result else { continue };
            let Some(descriptor) = compiled.reflection.tessellation else {
                continue;
            };
            assert_eq!(descriptor.topology, Some(topology));
            assert_eq!(descriptor.spacing, Some(spacing));
        }
    }
    #[test]
    fn control_stage_exposes_patch_builtins_and_factor_writes() {
        let source = "layout(vertices = 3) out; void main() { gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position; gl_TessLevelOuter[0] = 2.0; gl_TessLevelInner[0] = 2.0; }";
        let result = compile(
            Stage::TessellationControl,
            &[Source::new("shader.tcs", source)],
            &[],
            &Options::default(),
        );
        assert!(result.is_ok(), "{result:?}");
        let Ok(compiled) = result else { return };
        let Some(descriptor) = compiled.reflection.tessellation else {
            return;
        };
        assert_eq!(descriptor.patch_vertices, 3);
        assert!(descriptor.uses_invocation_id);
        assert!(descriptor.uses_tess_levels);
    }
    #[test]
    fn invalid_layouts_report_kind_and_context() {
        for (stage, source, message) in [
            (
                Stage::TessellationEvaluation,
                "void main() {}",
                "tessellation layout is missing",
            ),
            (
                Stage::TessellationEvaluation,
                "layout(point_mode) in; void main() {}",
                "tessellation point mode is unsupported",
            ),
            (
                Stage::TessellationEvaluation,
                "layout(triangles) in; void main() { gl_TessLevelOuter[3]; }",
                "tessellation factor array index is outside the selected topology",
            ),
            (
                Stage::TessellationEvaluation,
                "layout(isolines) in; void main() { gl_TessLevelInner[0]; }",
                "isolines cannot use inner tessellation factors",
            ),
            (
                Stage::TessellationEvaluation,
                "layout(vertices = 3, quads) in; void main() {}",
                "tessellation patch size does not match the selected topology",
            ),
        ] {
            let result = compile(
                stage,
                &[Source::new("packs/test.tes", source)],
                &[],
                &Options::default(),
            );
            let Err(errors) = result else { continue };
            assert_eq!(
                errors.as_slice().first().map(|value| &value.kind),
                Some(&ErrorKind::Semantic)
            );
            assert!(errors.to_string().contains(message));
            assert!(errors.to_string().contains("packs/test.tes:"));
        }
    }
}
