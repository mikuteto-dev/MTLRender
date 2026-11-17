use glslc::{ErrorKind, Location, Options, Stage, msl, reflect, transform};

fn geometry(vertices: u32, primitives: u32) -> transform::GeometryInput {
    transform::GeometryInput {
        input_topology: transform::PrimitiveTopology::Triangles,
        output_topology: transform::PrimitiveTopology::TriangleStrip,
        max_vertices: vertices,
        emitted_vertices: vertices,
        emitted_primitives: primitives,
        writes_layer: false,
        uses_invocation_id: false,
        side_effects: transform::SideEffects::default(),
        compute_compatible: false,
        location: Location::new("shader.gsh", 7, 11),
    }
}

fn select(input: transform::StageEmulationInput) -> Option<transform::StageEmulationPlan> {
    transform::select_lowering(input).ok()
}

fn compute_geometry() -> transform::GeometryInput {
    let mut input = geometry(257, 513);
    input.compute_compatible = true;
    input
}

fn error_for(input: transform::StageEmulationInput) -> glslc::CompileError {
    let result = transform::select_lowering(input);
    let errors = match result {
        Ok(_) => return glslc::CompileError::input("expected geometry lowering to be rejected"),
        Err(errors) => errors,
    };
    match errors.as_slice().first() {
        Some(error) => glslc::CompileError::new(
            match &error.kind {
                ErrorKind::Input => ErrorKind::Input,
                ErrorKind::Preprocess => ErrorKind::Preprocess,
                ErrorKind::Lex => ErrorKind::Lex,
                ErrorKind::Parse => ErrorKind::Parse,
                ErrorKind::Semantic => ErrorKind::Semantic,
                ErrorKind::Transform => ErrorKind::Transform,
                ErrorKind::Msl => ErrorKind::Msl,
                ErrorKind::Unsupported => ErrorKind::Unsupported,
            },
            error.message.as_str(),
            error
                .location
                .as_ref()
                .map(|location| Location::new(&location.file, location.line, location.column)),
        ),
        None => glslc::CompileError::input("expected a geometry lowering diagnostic"),
    }
}

fn compile_metal(name: &str, text: &str) {
    let path = std::env::temp_dir().join(format!("mtlr-glslc-{name}.metal"));
    let output = path.with_extension("air");
    let written = std::fs::write(&path, text.as_bytes());
    assert!(written.is_ok(), "{written:?}");
    let status = std::process::Command::new("xcrun")
        .args(["-sdk", "macosx", "metal", "-std=metal3.2", "-c"])
        .arg(&path)
        .arg("-o")
        .arg(&output)
        .status();
    let _ = std::fs::remove_file(&path);
    let _ = std::fs::remove_file(&output);
    assert!(
        status.as_ref().is_ok_and(|value| value.success()),
        "{status:?}"
    );
}

#[test]
fn geometry_selection_is_generic_and_ordered() {
    let plan = select(transform::StageEmulationInput {
        stage: Stage::Geometry,
        geometry: Some(geometry(12, 11)),
        tessellation: None,
    });
    assert_eq!(
        plan.as_ref().map(|value| value.family),
        Some(transform::LoweringFamily::MeshCompatible)
    );
    assert_eq!(
        plan.as_ref().map(|value| value.mesh_limits.max_vertices),
        Some(256)
    );
    assert_eq!(
        plan.as_ref().map(|value| value.mesh_limits.max_primitives),
        Some(512)
    );
    assert_eq!(
        plan.as_ref().map(|value| value.helper_entries.as_slice()),
        Some([transform::HelperEntryPoint::GeometryMesh].as_slice())
    );
}

#[test]
fn mesh_vertex_limit_is_rejected_at_the_construct_location() {
    let error = error_for(transform::StageEmulationInput {
        stage: Stage::Geometry,
        geometry: Some(geometry(257, 1)),
        tessellation: None,
    });
    assert_eq!(error.kind, ErrorKind::Unsupported);
    assert_eq!(
        error.message,
        "geometry output exceeds the mesh vertex limit of 256"
    );
    assert_eq!(error.location, Some(Location::new("shader.gsh", 7, 11)));
}

#[test]
fn compute_fallback_is_selected_only_when_declared_compatible() {
    let mut input = geometry(257, 513);
    input.compute_compatible = true;
    let plan = select(transform::StageEmulationInput {
        stage: Stage::Geometry,
        geometry: Some(input),
        tessellation: None,
    });
    assert_eq!(
        plan.as_ref().map(|value| value.family),
        Some(transform::LoweringFamily::ComputeEmulation)
    );
    assert_eq!(
        plan.as_ref().map(|value| value.helper_entries.as_slice()),
        Some([transform::HelperEntryPoint::ComputeEmulation].as_slice())
    );
}

#[test]
fn mesh_primitive_limit_is_rejected_at_the_construct_location() {
    let error = error_for(transform::StageEmulationInput {
        stage: Stage::Geometry,
        geometry: Some(geometry(4, 513)),
        tessellation: None,
    });
    assert_eq!(error.kind, ErrorKind::Unsupported);
    assert_eq!(
        error.message,
        "geometry output exceeds the mesh primitive limit of 512"
    );
    assert_eq!(error.location, Some(Location::new("shader.gsh", 7, 11)));
}

#[test]
fn storage_side_effects_use_serial_source_order_and_storage_capabilities() {
    let mut input = geometry(4, 2);
    input.compute_compatible = true;
    input.side_effects.storage_writes = true;
    input.side_effects.atomics = true;
    let plan = transform::select_lowering(transform::StageEmulationInput {
        stage: Stage::Geometry,
        geometry: Some(input),
        tessellation: None,
    });
    let plan = match plan {
        Ok(value) => value,
        Err(_) => return,
    };
    let compute = match plan.compute_geometry {
        Some(value) => value,
        None => return,
    };
    assert_eq!(
        compute.side_effect_ordering,
        transform::SideEffectOrdering::SourceInvocationSerial
    );
    assert!(compute.storage_capabilities.storage_writes);
    assert!(compute.storage_capabilities.storage_atomics);
}

#[test]
fn generic_compute_emulation_preserves_order_and_reflection_resources() {
    let plan = match transform::select_lowering(transform::StageEmulationInput {
        stage: Stage::Geometry,
        geometry: Some(compute_geometry()),
        tessellation: None,
    }) {
        Ok(value) => value,
        Err(_) => return,
    };
    let compute = match plan.compute_geometry.as_ref() {
        Some(value) => value,
        None => return,
    };
    assert_eq!(plan.family, transform::LoweringFamily::ComputeEmulation);
    assert_eq!(
        compute.output_offsets,
        transform::OutputOffsetKind::SourcePrimitiveOrder
    );
    assert_eq!(
        compute.prefix_sums,
        [
            transform::PrefixSumKind::Group,
            transform::PrefixSumKind::Global
        ]
    );
    let generated = match msl::generate(
        &glslc::parse::Ast {
            declarations: Vec::new(),
        },
        Stage::Compute,
        &Options::default(),
        Some(compute),
    ) {
        Ok(value) => value,
        Err(_) => return,
    };
    assert!(
        generated
            .text
            .contains("global_prefix + invocation_ordinal")
    );
    assert!(
        generated
            .text
            .contains("vertex_offset + primitive_index + 2u")
    );
    assert!(
        generated
            .text
            .contains("for (uint invocation = 0u; invocation < invocation_count; ++invocation)")
    );
    compile_metal("geometry-compute", &generated.text);

    let base = match reflect::collect_ast(
        &glslc::parse::Ast {
            declarations: Vec::new(),
        },
        Stage::Compute,
    ) {
        Ok(value) => value,
        Err(_) => return,
    };
    let reflection = base.with_stage_emulation(&plan);
    let geometry = match reflection.geometry.as_ref() {
        Some(value) => value,
        None => return,
    };
    assert_eq!(geometry.input_topology, "triangles");
    assert_eq!(geometry.output_topology, "triangle_strip");
    assert_eq!(geometry.order, reflect::CapabilityState::Supported);
    assert_eq!(geometry.overflow, reflect::CapabilityState::Supported);
    assert_eq!(geometry.layer_output, reflect::CapabilityState::Unsupported);
    assert_eq!(
        geometry.invocation_id,
        reflect::CapabilityState::Unsupported
    );
    assert_eq!(geometry.produced_vertices, 257);
    assert_eq!(geometry.produced_primitives, 513);
    for helper in &reflection.helper_names {
        assert!(generated.text.contains(helper), "missing helper {helper}");
    }
    for resource in &reflection.resources {
        assert!(
            generated.text.contains(resource.name.as_str()),
            "missing resource {}",
            resource.name
        );
    }
}

#[test]
fn layer_writes_are_rejected_at_the_construct_location() {
    let mut input = geometry(4, 2);
    input.writes_layer = true;
    let error = error_for(transform::StageEmulationInput {
        stage: Stage::Geometry,
        geometry: Some(input),
        tessellation: None,
    });
    assert_eq!(error.kind, ErrorKind::Unsupported);
    assert_eq!(
        error.message,
        "geometry layer output is outside the order-preserving compute plan"
    );
    assert_eq!(error.location, Some(Location::new("shader.gsh", 7, 11)));
}

#[test]
fn invocation_ids_are_rejected_at_the_construct_location() {
    let mut input = geometry(4, 2);
    input.uses_invocation_id = true;
    let error = error_for(transform::StageEmulationInput {
        stage: Stage::Geometry,
        geometry: Some(input),
        tessellation: None,
    });
    assert_eq!(error.kind, ErrorKind::Unsupported);
    assert_eq!(
        error.message,
        "geometry invocation identity is outside the order-preserving compute plan"
    );
    assert_eq!(error.location, Some(Location::new("shader.gsh", 7, 11)));
}

#[test]
fn image_side_effects_are_rejected_at_the_construct_location() {
    let mut input = geometry(4, 2);
    input.side_effects.image_writes = true;
    let error = error_for(transform::StageEmulationInput {
        stage: Stage::Geometry,
        geometry: Some(input),
        tessellation: None,
    });
    assert_eq!(error.kind, ErrorKind::Unsupported);
    assert_eq!(
        error.message,
        "geometry image writes cannot be represented by the ordered storage-resource plan"
    );
    assert_eq!(error.location, Some(Location::new("shader.gsh", 7, 11)));
}

#[test]
fn tessellation_helpers_compile_for_each_topology() {
    for domain in [
        transform::TessellationDomain::Triangles,
        transform::TessellationDomain::Quads,
        transform::TessellationDomain::Isolines,
    ] {
        let mut input = tessellation_contract();
        input.contract.domain = domain;
        if domain == transform::TessellationDomain::Quads {
            input.contract.patch.input_count = 4;
            input.contract.patch.output_count = 4;
            input.contract.patch.input_order = vec![0, 1, 2, 3];
            input.contract.patch.output_order = vec![0, 1, 2, 3];
        }
        if domain == transform::TessellationDomain::Isolines {
            input.contract.patch.input_count = 2;
            input.contract.patch.output_count = 2;
            input.contract.patch.input_order = vec![0, 1];
            input.contract.patch.output_order = vec![0, 1];
            input.contract.inner_factor_range = None;
        }
        let plan = match transform::select_lowering(transform::StageEmulationInput {
            stage: Stage::TessellationEvaluation,
            geometry: None,
            tessellation: Some(input),
        }) {
            Ok(value) => value,
            Err(errors) => {
                assert!(errors.as_slice().iter().any(|_| true), "{errors}");
                return;
            }
        };
        for stage in [Stage::TessellationControl, Stage::TessellationEvaluation] {
            let generated = match msl::generate_with_tessellation(
                &glslc::parse::Ast {
                    declarations: Vec::new(),
                },
                stage,
                &Options::default(),
                None,
                plan.tessellation.as_ref(),
            ) {
                Ok(value) => value,
                Err(errors) => {
                    assert!(errors.as_slice().iter().any(|_| true), "{errors}");
                    return;
                }
            };
            if stage == Stage::TessellationControl {
                assert!(generated.text.contains("mtlr_tessellation_control"));
                assert!(generated.text.contains("gl_in [[buffer(0)]]"));
            } else {
                assert!(generated.text.contains("mtlr_tessellation_evaluation_"));
                assert!(generated.text.contains("domain"));
            }
            compile_metal("tessellation-helper", &generated.text);
        }
    }
}

fn tessellation_contract() -> transform::TessellationInput {
    transform::TessellationInput {
        contract: transform::TessellationContract {
            domain: transform::TessellationDomain::Triangles,
            spacing: transform::TessellationSpacing::FractionalOdd,
            patch_order: transform::PatchOrder::CounterClockwise,
            primitive_order: transform::PatchPrimitiveOrder::SourcePatchThenPrimitive,
            patch: transform::PatchControlPoints {
                input_count: 3,
                output_count: 3,
                input_order: vec![0, 1, 2],
                output_order: vec![0, 1, 2],
            },
            outer_factor_range: transform::FactorRange {
                minimum: 1,
                maximum: 64,
            },
            inner_factor_range: Some(transform::FactorRange {
                minimum: 1,
                maximum: 64,
            }),
            tcs_invocation: transform::InvocationIdentity::TcsInvocation,
            generated_invocation: transform::InvocationIdentity::GeneratedTessellator,
            domain_coordinates: 2,
            tes_outputs: vec![0],
            side_effects: transform::SideEffects::default(),
            location: Location::new("shader.tes", 19, 5),
        },
        factors: transform::TessellationFactors::Dynamic,
        uses_invocation_id: true,
        location: Location::new("shader.tcs", 8, 9),
    }
}

#[test]
fn quad_tessellation_plan_preserves_grid_and_output_order() {
    let mut input = tessellation_contract();
    input.contract.domain = transform::TessellationDomain::Quads;
    input.contract.spacing = transform::TessellationSpacing::Equal;
    input.contract.patch.input_count = 4;
    input.contract.patch.output_count = 4;
    input.contract.patch.input_order = vec![0, 1, 2, 3];
    input.contract.patch.output_order = vec![0, 1, 2, 3];
    input.contract.outer_factor_range.maximum = 4;
    input.contract.inner_factor_range = Some(transform::FactorRange {
        minimum: 2,
        maximum: 3,
    });
    input.contract.tes_outputs = vec![4, 7];
    let plan = match transform::select_lowering(transform::StageEmulationInput {
        stage: Stage::TessellationEvaluation,
        geometry: None,
        tessellation: Some(input),
    }) {
        Ok(value) => value,
        Err(errors) => {
            assert!(errors.as_slice().iter().any(|_| true), "{errors}");
            return;
        }
    };
    let Some(tessellation) = plan.tessellation.as_ref() else {
        assert!(plan.tessellation.is_some());
        return;
    };
    let Some(quad) = tessellation.quad.as_ref() else {
        assert!(tessellation.quad.is_some());
        return;
    };
    assert_eq!(quad.outer_factors, [4, 4, 4, 4]);
    assert_eq!(quad.inner_factors, [3, 3]);
    assert_eq!(quad.coordinates.len(), 25);
    assert_eq!(
        quad.coordinates.first().copied(),
        Some(transform::QuadCoordinate { u: 0, v: 0 })
    );
    assert_eq!(
        quad.coordinates.get(24).copied(),
        Some(transform::QuadCoordinate { u: 4, v: 4 })
    );
    assert_eq!(quad.primitives.len(), 32);
    assert_eq!(quad.primitives.first().copied(), Some([0, 1, 6]));
    assert_eq!(quad.primitives.get(1).copied(), Some([0, 6, 5]));
    assert_eq!(quad.generated_invocation_ids.get(24).copied(), Some(24));
    assert_eq!(quad.tes_output_order, vec![4, 7]);
}

#[test]
fn isoline_tessellation_plan_preserves_line_coordinates_and_order() {
    let mut input = tessellation_contract();
    input.contract.domain = transform::TessellationDomain::Isolines;
    input.contract.spacing = transform::TessellationSpacing::Equal;
    input.contract.patch.input_count = 2;
    input.contract.patch.output_count = 2;
    input.contract.patch.input_order = vec![0, 1];
    input.contract.patch.output_order = vec![0, 1];
    input.contract.outer_factor_range.maximum = 3;
    input.contract.inner_factor_range = None;
    input.contract.tes_outputs = vec![2, 5];
    let plan = match transform::select_lowering(transform::StageEmulationInput {
        stage: Stage::TessellationEvaluation,
        geometry: None,
        tessellation: Some(input),
    }) {
        Ok(value) => value,
        Err(errors) => {
            assert!(errors.as_slice().iter().any(|_| true), "{errors}");
            return;
        }
    };
    let Some(tessellation) = plan.tessellation.as_ref() else {
        assert!(plan.tessellation.is_some());
        return;
    };
    let Some(isoline) = tessellation.isoline.as_ref() else {
        assert!(tessellation.isoline.is_some());
        return;
    };
    assert_eq!(isoline.outer_factors, [3, 3]);
    assert_eq!(isoline.coordinates.len(), 12);
    assert_eq!(
        isoline.coordinates.first().copied(),
        Some(transform::IsolineCoordinate { u: 0, v: 0 })
    );
    assert_eq!(
        isoline.coordinates.get(11).copied(),
        Some(transform::IsolineCoordinate { u: 3, v: 2 })
    );
    assert_eq!(isoline.primitives.len(), 9);
    assert_eq!(isoline.primitives.first().copied(), Some([0, 1]));
    assert_eq!(isoline.primitives.get(3).copied(), Some([4, 5]));
    assert_eq!(isoline.generated_invocation_ids.get(11).copied(), Some(11));
    assert_eq!(isoline.tes_output_order, vec![2, 5]);
    assert_eq!(tessellation.control.factor_writes.len(), 2);
}

#[test]
fn isoline_tessellation_rejects_generated_capacity_before_lowering() {
    let mut input = tessellation_contract();
    input.contract.domain = transform::TessellationDomain::Isolines;
    input.contract.patch.input_count = 2;
    input.contract.patch.output_count = 2;
    input.contract.patch.input_order = vec![0, 1];
    input.contract.patch.output_order = vec![0, 1];
    input.contract.outer_factor_range.maximum = 64;
    input.contract.inner_factor_range = None;
    let error = error_for(transform::StageEmulationInput {
        stage: Stage::TessellationEvaluation,
        geometry: None,
        tessellation: Some(input),
    });
    assert_eq!(error.kind, ErrorKind::Unsupported);
    assert_eq!(
        error.message,
        "isoline tessellation exceeds the generated vertex, primitive, or invocation limit"
    );
    assert_eq!(error.location, Some(Location::new("shader.tes", 19, 5)));
}

#[test]
fn quad_tessellation_rejects_non_quad_patch_at_tes_location() {
    let mut input = tessellation_contract();
    input.contract.domain = transform::TessellationDomain::Quads;
    input.contract.inner_factor_range = Some(transform::FactorRange {
        minimum: 1,
        maximum: 1,
    });
    let error = error_for(transform::StageEmulationInput {
        stage: Stage::TessellationEvaluation,
        geometry: None,
        tessellation: Some(input),
    });
    assert_eq!(error.kind, ErrorKind::Unsupported);
    assert_eq!(
        error.message,
        "tessellation patch size does not match the selected domain"
    );
    assert_eq!(error.location, Some(Location::new("shader.tes", 19, 5)));
}

#[test]
fn quad_tessellation_rejects_generated_capacity_before_lowering() {
    let mut input = tessellation_contract();
    input.contract.domain = transform::TessellationDomain::Quads;
    input.contract.patch.input_count = 4;
    input.contract.patch.output_count = 4;
    input.contract.patch.input_order = vec![0, 1, 2, 3];
    input.contract.patch.output_order = vec![0, 1, 2, 3];
    input.contract.inner_factor_range = Some(transform::FactorRange {
        minimum: 1,
        maximum: 64,
    });
    let error = error_for(transform::StageEmulationInput {
        stage: Stage::TessellationEvaluation,
        geometry: None,
        tessellation: Some(input),
    });
    assert_eq!(error.kind, ErrorKind::Unsupported);
    assert_eq!(
        error.message,
        "quad tessellation exceeds the generated vertex, primitive, or invocation limit"
    );
    assert_eq!(error.location, Some(Location::new("shader.tes", 19, 5)));
}

#[test]
fn tessellation_contract_selects_mesh_compatible_subdivision() {
    let plan = transform::select_lowering(transform::StageEmulationInput {
        stage: Stage::TessellationEvaluation,
        geometry: None,
        tessellation: Some(tessellation_contract()),
    });
    let plan = match plan {
        Ok(value) => value,
        Err(errors) => {
            assert!(errors.as_slice().iter().any(|_| true), "{errors}");
            return;
        }
    };
    assert_eq!(plan.family, transform::LoweringFamily::MeshCompatible);
    let Some(tessellation) = plan.tessellation.as_ref() else {
        assert!(plan.tessellation.is_some());
        return;
    };
    assert_eq!(tessellation.contract.patch.input_order, vec![0, 1, 2]);
    assert_eq!(tessellation.contract.patch.output_order, vec![0, 1, 2]);
    assert_eq!(
        tessellation.contract.tcs_invocation,
        transform::InvocationIdentity::TcsInvocation
    );
    assert_eq!(
        tessellation.contract.generated_invocation,
        transform::InvocationIdentity::GeneratedTessellator
    );
    assert_eq!(tessellation.control.input_storage, "gl_in");
    assert_eq!(tessellation.control.output_storage, "gl_out");
    assert!(tessellation.control.barrier_required);
    assert_eq!(tessellation.control.factor_writes.len(), 4);
    assert!(
        tessellation
            .control
            .factor_writes
            .iter()
            .all(|write| write.invocation == 0)
    );
    assert!(
        tessellation
            .control
            .factor_writes
            .iter()
            .all(|write| write.value_type == transform::TessellationFactorType::Float)
    );
}

#[test]
fn tessellation_patch_order_is_rejected_before_lowering() {
    let mut input = tessellation_contract();
    input.contract.patch.input_order = vec![0, 2, 1];
    input.contract.patch.output_order = vec![0, 2, 1];
    let result = transform::select_lowering(transform::StageEmulationInput {
        stage: Stage::TessellationEvaluation,
        geometry: None,
        tessellation: Some(input),
    });
    let errors = match result {
        Ok(_) => return,
        Err(value) => value,
    };
    let Some(error) = errors.as_slice().first() else {
        assert!(!errors.as_slice().is_empty());
        return;
    };
    assert_eq!(error.kind, ErrorKind::Unsupported);
    assert_eq!(
        error.message,
        "tessellation patch control points must preserve source invocation order"
    );
    assert_eq!(error.location, Some(Location::new("shader.tes", 19, 5)));
}

#[test]
fn tessellation_reflection_describes_compute_subdivision_only() {
    let plan = match transform::select_lowering(transform::StageEmulationInput {
        stage: Stage::TessellationEvaluation,
        geometry: None,
        tessellation: Some(tessellation_contract()),
    }) {
        Ok(value) => value,
        Err(errors) => {
            assert!(errors.as_slice().iter().any(|_| true), "{errors}");
            return;
        }
    };
    let base = match reflect::collect_ast(
        &glslc::parse::Ast {
            declarations: Vec::new(),
        },
        Stage::TessellationEvaluation,
    ) {
        Ok(value) => value,
        Err(errors) => {
            assert!(errors.as_slice().iter().any(|_| true), "{errors}");
            return;
        }
    };
    let reflection = base.with_stage_emulation(&plan);
    let Some(subdivision) = reflection.compute_subdivision else {
        assert!(reflection.compute_subdivision.is_some());
        return;
    };
    assert_eq!(subdivision.version, reflect::COMPUTE_SUBDIVISION_VERSION);
    assert_eq!(subdivision.patch_vertices, 3);
    assert_eq!(subdivision.topology, "triangles");
    assert_eq!(subdivision.spacing, "fractional_odd");
    assert_eq!(subdivision.workgroup.x, 32);
    assert_eq!(subdivision.indirect_draw, "metal_primitives");
    assert_eq!(
        reflection.helper_names,
        vec![
            "mtlr_tess_patch_index_ok",
            "mtlr_tess_range_ok",
            "mtlr_tess_patch_offset",
            "mtlr_tess_triangle_coordinate",
            "mtlr_tess_triangle_value",
            "mtlr_tess_triangle_emit",
            "mtlr_tessellation_evaluation_triangle",
            "mtlr_tessellation_domain_arguments",
        ]
    );
    assert_eq!(
        reflection
            .resources
            .iter()
            .map(|resource| (resource.name.as_str(), resource.binding))
            .collect::<Vec<_>>(),
        vec![
            ("control_points", 0),
            ("tess_factors", 1),
            ("outputs", 2),
            ("primitives", 3),
            ("arguments", 4),
            ("vertex_offsets", 5),
            ("primitive_offsets", 6),
            ("patch_count", 7),
            ("vertex_capacity", 8),
            ("primitive_capacity", 9),
        ]
    );
}

#[test]
fn tessellation_side_effects_are_rejected_at_the_tes_location() {
    let mut input = tessellation_contract();
    input.contract.side_effects.storage_writes = true;
    let error = error_for(transform::StageEmulationInput {
        stage: Stage::TessellationEvaluation,
        geometry: None,
        tessellation: Some(input),
    });
    assert_eq!(error.kind, ErrorKind::Unsupported);
    assert_eq!(
        error.message,
        "tessellation side effects and atomics are unsupported before compute subdivision"
    );
    assert_eq!(error.location, Some(Location::new("shader.tes", 19, 5)));
}

#[test]
fn tessellation_atomics_are_rejected_at_the_tes_location() {
    let mut input = tessellation_contract();
    input.contract.side_effects.atomics = true;
    let error = error_for(transform::StageEmulationInput {
        stage: Stage::TessellationEvaluation,
        geometry: None,
        tessellation: Some(input),
    });
    assert_eq!(error.kind, ErrorKind::Unsupported);
    assert_eq!(
        error.message,
        "tessellation side effects and atomics are unsupported before compute subdivision"
    );
    assert_eq!(error.location, Some(Location::new("shader.tes", 19, 5)));
}

#[test]
fn tessellation_invocation_id_requires_distinct_generated_identity() {
    let mut input = tessellation_contract();
    input.contract.tcs_invocation = transform::InvocationIdentity::GeneratedTessellator;
    let error = error_for(transform::StageEmulationInput {
        stage: Stage::TessellationEvaluation,
        geometry: None,
        tessellation: Some(input),
    });
    assert_eq!(error.kind, ErrorKind::Unsupported);
    assert_eq!(
        error.message,
        "tessellation control invocation identity must remain distinct from generated tessellator identity"
    );
    assert_eq!(error.location, Some(Location::new("shader.tcs", 8, 9)));
}

#[test]
fn tessellation_factor_range_is_rejected_at_the_tes_location() {
    let mut input = tessellation_contract();
    input.contract.outer_factor_range.maximum = 65;
    let result = transform::select_lowering(transform::StageEmulationInput {
        stage: Stage::TessellationEvaluation,
        geometry: None,
        tessellation: Some(input),
    });
    let errors = match result {
        Ok(_) => return,
        Err(value) => value,
    };
    let Some(error) = errors.as_slice().first() else {
        assert!(!errors.as_slice().is_empty());
        return;
    };
    assert_eq!(error.kind, ErrorKind::Unsupported);
    assert_eq!(
        error.message,
        "tessellation outer factors must be in the range 1..=64"
    );
    assert_eq!(error.location, Some(Location::new("shader.tes", 19, 5)));
}

#[test]
fn tessellation_triangle_matrix_preserves_spacing_coordinates_and_output_order() {
    let mut input = tessellation_contract();
    input.contract.spacing = transform::TessellationSpacing::FractionalEven;
    input.contract.outer_factor_range = transform::FactorRange {
        minimum: 2,
        maximum: 4,
    };
    let plan_result = transform::select_lowering(transform::StageEmulationInput {
        stage: Stage::TessellationEvaluation,
        geometry: None,
        tessellation: Some(input),
    });
    assert!(plan_result.is_ok(), "expected triangle tessellation plan");
    let plan = match plan_result {
        Ok(value) => value,
        Err(_) => return,
    };
    assert!(plan.tessellation.is_some(), "missing tessellation plan");
    let Some(tessellation) = plan.tessellation.as_ref() else {
        return;
    };
    let triangle = &tessellation.triangle;
    assert_eq!(
        triangle.spacing,
        transform::TessellationSpacing::FractionalEven
    );
    assert_eq!(
        triangle.factor_edges,
        [
            transform::TriangleFactorEdge::OppositeControlPoint0,
            transform::TriangleFactorEdge::OppositeControlPoint1,
            transform::TriangleFactorEdge::OppositeControlPoint2,
        ]
    );
    assert_eq!(
        triangle.coordinate_order,
        transform::TriangleCoordinateOrder::BarycentricRows
    );
    assert_eq!(
        triangle.primitive_order,
        transform::TrianglePrimitiveOrder::CounterClockwiseRows
    );
    assert_eq!(
        triangle.invocation_order,
        transform::TriangleInvocationOrder::CoordinateOrder
    );
    assert_eq!(triangle.tes_output_order, vec![0]);
    assert_eq!(triangle.maximum_vertices, 4096);
    assert_eq!(triangle.maximum_primitives, 8192);
    assert_eq!(triangle.maximum_invocations, 4096);
}

#[test]
fn tessellation_msl_rejects_workgroup_and_output_limits_exactly() {
    let selected_result = transform::select_lowering(transform::StageEmulationInput {
        stage: Stage::TessellationEvaluation,
        geometry: None,
        tessellation: Some(tessellation_contract()),
    });
    assert!(selected_result.is_ok(), "expected tessellation plan");
    let selected = match selected_result {
        Ok(value) => value,
        Err(_) => return,
    };
    assert!(selected.tessellation.is_some(), "missing tessellation plan");
    let Some(mut workgroup_plan) = selected.tessellation else {
        return;
    };
    workgroup_plan.workgroup_size = [1025, 1, 1];
    let workgroup_result = msl::generate_with_tessellation(
        &glslc::parse::Ast {
            declarations: Vec::new(),
        },
        Stage::TessellationEvaluation,
        &Options::default(),
        None,
        Some(&workgroup_plan),
    );
    assert!(workgroup_result.is_err());
    let workgroup_errors = match workgroup_result {
        Ok(_) => return,
        Err(value) => value,
    };
    assert!(
        !workgroup_errors.as_slice().is_empty(),
        "missing workgroup diagnostic"
    );
    let Some(workgroup_error) = workgroup_errors.as_slice().first() else {
        return;
    };
    assert_eq!(workgroup_error.kind, ErrorKind::Unsupported);
    assert_eq!(
        workgroup_error.message,
        "tessellation workgroup size exceeds the Metal compute workgroup limit"
    );
    assert_eq!(
        workgroup_error.location,
        Some(Location::new("shader.tcs", 8, 9))
    );

    workgroup_plan.workgroup_size = [32, 1, 1];
    workgroup_plan.output_limit = 257;
    let output_result = msl::generate_with_tessellation(
        &glslc::parse::Ast {
            declarations: Vec::new(),
        },
        Stage::TessellationEvaluation,
        &Options::default(),
        None,
        Some(&workgroup_plan),
    );
    assert!(output_result.is_err());
    let output_errors = match output_result {
        Ok(_) => return,
        Err(value) => value,
    };
    assert!(
        !output_errors.as_slice().is_empty(),
        "missing output diagnostic"
    );
    let Some(output_error) = output_errors.as_slice().first() else {
        return;
    };
    assert_eq!(output_error.kind, ErrorKind::Unsupported);
    assert_eq!(
        output_error.message,
        "tessellation evaluation output exceeds the compute subdivision limit of 256"
    );
    assert_eq!(
        output_error.location,
        Some(Location::new("shader.tcs", 8, 9))
    );
}

#[test]
fn tessellation_reflection_and_msl_agree_for_control_and_evaluation_entries() {
    for stage in [Stage::TessellationControl, Stage::TessellationEvaluation] {
        let plan_result = transform::select_lowering(transform::StageEmulationInput {
            stage,
            geometry: None,
            tessellation: Some(tessellation_contract()),
        });
        assert!(plan_result.is_ok(), "expected tessellation plan");
        let plan = match plan_result {
            Ok(value) => value,
            Err(_) => return,
        };
        assert!(plan.tessellation.is_some(), "missing tessellation plan");
        let Some(tessellation) = plan.tessellation.as_ref() else {
            return;
        };
        let generated_result = msl::generate_with_tessellation(
            &glslc::parse::Ast {
                declarations: Vec::new(),
            },
            stage,
            &Options::default(),
            None,
            Some(tessellation),
        );
        assert!(
            generated_result.is_ok(),
            "expected generated tessellation MSL"
        );
        let generated = match generated_result {
            Ok(value) => value,
            Err(_) => return,
        };
        let base_result = reflect::collect_ast(
            &glslc::parse::Ast {
                declarations: Vec::new(),
            },
            stage,
        );
        assert!(base_result.is_ok(), "expected tessellation reflection");
        let base = match base_result {
            Ok(value) => value,
            Err(_) => return,
        };
        let reflection = base.with_stage_emulation(&plan);
        for helper in &reflection.helper_names {
            assert!(generated.text.contains(helper), "missing helper {helper}");
        }
        for resource in &reflection.resources {
            assert!(
                generated.text.contains(resource.name.as_str()),
                "missing resource {}",
                resource.name
            );
        }
        compile_metal("tessellation-reflection", &generated.text);
    }
}

#[test]
fn storage_side_effects_and_atomics_are_supported_in_ordered_compute() {
    let mut input = compute_geometry();
    input.side_effects.storage_writes = true;
    input.side_effects.atomics = true;
    let plan = match transform::select_lowering(transform::StageEmulationInput {
        stage: Stage::Geometry,
        geometry: Some(input),
        tessellation: None,
    }) {
        Ok(value) => value,
        Err(_) => return,
    };
    let compute = match plan.compute_geometry.as_ref() {
        Some(value) => value,
        None => return,
    };
    assert_eq!(
        compute.side_effect_ordering,
        transform::SideEffectOrdering::SourceInvocationSerial
    );
    assert!(compute.storage_capabilities.storage_writes);
    assert!(compute.storage_capabilities.storage_atomics);
    let generated = match msl::generate(
        &glslc::parse::Ast {
            declarations: Vec::new(),
        },
        Stage::Compute,
        &Options::default(),
        Some(compute),
    ) {
        Ok(value) => value,
        Err(_) => return,
    };
    assert!(generated.text.contains("side_effect_storage [[buffer(8)]]"));
    assert!(
        generated
            .text
            .contains("side_effect_atomic_storage [[buffer(9)]]")
    );
    assert!(
        generated
            .text
            .contains("mtlr_geometry_side_effect_barrier();")
    );
    compile_metal("geometry-compute-side-effects", &generated.text);
}
