use crate::geometry::GeometryKind;
use crate::{ErrorKind, Options, Source, Stage, compile};

fn compile_fixture(name: &str) -> Result<crate::CompiledShader, crate::CompileErrors> {
    let source = match name {
        "pass" => include_str!(
            "../../../../mod/run/shaderpacks/geometry-fixtures/shaders/gbuffers_pass.gsh"
        ),
        "layered" => include_str!(
            "../../../../mod/run/shaderpacks/geometry-fixtures/shaders/gbuffers_layered.gsh"
        ),
        "bounded" => include_str!(
            "../../../../mod/run/shaderpacks/geometry-fixtures/shaders/gbuffers_basic.gsh"
        ),
        "over_budget" => include_str!(
            "../../../../mod/run/shaderpacks/geometry-fixtures/shaders/gbuffers_over_budget.gsh"
        ),
        "effects" => include_str!(
            "../../../../mod/run/shaderpacks/geometry-fixtures/shaders/gbuffers_entities.gsh"
        ),
        "zero" => include_str!(
            "../../../../mod/run/shaderpacks/geometry-fixtures/shaders/gbuffers_zero_clean.gsh"
        ),
        _ => {
            return Err(crate::CompileErrors::one(crate::CompileError::input(
                "unknown fixture",
            )));
        }
    };
    compile(
        Stage::Geometry,
        &[Source::new(name, source)],
        &[],
        &Options::default(),
    )
}

#[test]
fn routes_shared_glsl_fixtures_through_all_geometry_paths() {
    let cases = [
        ("pass", GeometryKind::PassThrough),
        ("layered", GeometryKind::Layered),
        ("bounded", GeometryKind::BoundedMesh),
        ("over_budget", GeometryKind::Compute),
        ("effects", GeometryKind::BoundedMesh),
        ("zero", GeometryKind::BoundedMesh),
    ];
    for (name, expected) in cases {
        let result = compile_fixture(name);
        assert!(result.is_ok(), "{name}: {result:?}");
        let Ok(compiled) = result else { continue };
        assert!(
            compiled.reflection.geometry_plan.is_some(),
            "{name}: geometry reflection is missing"
        );
        let Some(plan) = compiled.reflection.geometry_plan.as_ref() else {
            continue;
        };
        assert_eq!(plan.kind, expected, "{name}: {plan:?}");
        if expected == GeometryKind::Compute || name == "effects" {
            assert!(!compiled.msl.is_empty(), "{name}: lowering MSL is missing");
        } else {
            assert!(
                compiled.msl.is_empty(),
                "{name}: specialized lowering emitted compute MSL"
            );
        }
    }
}

#[test]
fn keeps_malformed_and_unsupported_geometry_diagnostics_isolated() {
    let malformed = compile(
        Stage::Geometry,
        &[Source::new(
            "gbuffers_malformed.gsh",
            include_str!(
                "../../../../mod/run/shaderpacks/geometry-fixtures/shaders/gbuffers_malformed.gsh"
            ),
        )],
        &[],
        &Options::default(),
    );
    assert!(malformed.is_err());
    let Err(errors) = malformed else { return };
    assert!(
        errors
            .as_slice()
            .iter()
            .any(|error| error.kind == ErrorKind::Parse)
    );
    assert!(errors.as_slice().iter().any(|error| {
        error
            .location
            .as_ref()
            .is_some_and(|location| location.file == "gbuffers_malformed.gsh")
    }));

    let unsupported = compile(
        Stage::Geometry,
        &[Source::new(
            "gbuffers_unsupported.gsh",
            include_str!(
                "../../../../mod/run/shaderpacks/geometry-fixtures/shaders/gbuffers_unsupported.gsh"
            ),
        )],
        &[],
        &Options::default(),
    );
    assert!(unsupported.is_err());
    let Err(errors) = unsupported else { return };
    assert!(
        errors
            .as_slice()
            .iter()
            .any(|error| error.kind == ErrorKind::Unsupported)
    );
}

#[test]
fn preserves_side_effect_kinds_and_exact_mesh_boundaries() {
    for (name, source, image, ssbo, atomic) in [
        (
            "gbuffers_beaconbeam.gsh",
            include_str!(
                "../../../../mod/run/shaderpacks/geometry-fixtures/shaders/gbuffers_beaconbeam.gsh"
            ),
            true,
            false,
            false,
        ),
        (
            "gbuffers_entities.gsh",
            include_str!(
                "../../../../mod/run/shaderpacks/geometry-fixtures/shaders/gbuffers_entities.gsh"
            ),
            true,
            true,
            false,
        ),
        (
            "gbuffers_atomic_add.gsh",
            include_str!(
                "../../../../mod/run/shaderpacks/geometry-fixtures/shaders/gbuffers_atomic_add.gsh"
            ),
            false,
            true,
            false,
        ),
    ] {
        let result = compile(
            Stage::Geometry,
            &[Source::new(name, source)],
            &[],
            &Options::default(),
        );
        if name == "gbuffers_atomic_add.gsh" {
            assert!(
                result.is_err(),
                "{name}: atomic fixture unexpectedly compiled"
            );
            continue;
        }
        assert!(result.is_ok(), "{name}: {result:?}");
        let Ok(compiled) = result else { continue };
        assert!(
            compiled.reflection.geometry_plan.is_some(),
            "{name}: geometry reflection is missing"
        );
        let Some(plan) = compiled.reflection.geometry_plan.as_ref() else {
            continue;
        };
        assert_eq!(plan.kind, GeometryKind::BoundedMesh, "{name}: {plan:?}");
        assert_eq!(plan.side_effects.image_writes, image, "{name}: image");
        assert_eq!(plan.side_effects.ssbo_writes, ssbo, "{name}: ssbo");
        assert_eq!(plan.side_effects.atomics, atomic, "{name}: atomic");
        assert_eq!(plan.emitted_vertices, 3, "{name}: vertices");
        assert_eq!(plan.emitted_primitives, 1, "{name}: primitives");
    }
}
