use glslc::{ErrorKind, Options, Source, Stage, compile, reflect};
use std::path::Path;
use std::process::Command;

fn fixture(path: &str) -> Source {
    let full = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures/free_variable_capture")
        .join(path);
    let text = match std::fs::read_to_string(&full) {
        Ok(value) => value,
        Err(error) => {
            assert_eq!(error.to_string(), String::new(), "{}", full.display());
            String::new()
        }
    };
    Source::new(format!("fixtures/free_variable_capture/{path}"), text)
}

fn metal_available() -> bool {
    Command::new("xcrun")
        .args(["-sdk", "macosx", "metal", "-std=metal3.2", "-c"])
        .output()
        .is_ok_and(|output| output.status.code().is_some())
}

fn assert_metal_compiles(path: &str, source: &str) {
    if !metal_available() {
        eprintln!("assumption: xcrun metal is unavailable; skipping {path}");
        return;
    }
    let base = std::env::temp_dir().join(format!(
        "mtlr-glslc-{}-{}",
        std::process::id(),
        path.replace('/', "_")
    ));
    let metal_path = base.with_extension("metal");
    let air_path = base.with_extension("air");
    let write_result = std::fs::write(&metal_path, source);
    assert!(
        write_result.is_ok(),
        "{path}: unable to write temporary MSL: {write_result:?}"
    );
    let result = Command::new("xcrun")
        .args(["-sdk", "macosx", "metal", "-std=metal3.2", "-c"])
        .arg(&metal_path)
        .arg("-o")
        .arg(&air_path)
        .output();
    let _ = std::fs::remove_file(&metal_path);
    let _ = std::fs::remove_file(&air_path);
    let output = match result {
        Ok(value) => value,
        Err(ref error) => {
            assert!(result.is_ok(), "{path}: xcrun failed: {error}");
            return;
        }
    };
    assert!(
        output.status.success(),
        "{path}: Metal rejected generated MSL\n{}",
        String::from_utf8_lossy(&output.stderr)
    );
}

fn compile_fixture(stage: Stage, path: &str) -> Option<glslc::CompiledShader> {
    let source = fixture(path);
    match compile(stage, &[source], &[], &Options::default()) {
        Ok(value) => Some(value),
        Err(errors) => {
            assert_eq!(
                errors.as_slice().len(),
                0,
                "fixtures/free_variable_capture/{path}: {errors}"
            );
            None
        }
    }
}

fn assert_location_and_kind(
    stage: Stage,
    path: &str,
    line: u32,
    column: u32,
    expected: ErrorKind,
    expected_count: usize,
) {
    let source = fixture(path);
    let errors = match compile(stage, &[source], &[], &Options::default()) {
        Ok(_) => {
            assert!(
                std::hint::black_box(false),
                "unsupported fixture unexpectedly compiled: {path}"
            );
            return;
        }
        Err(value) => value,
    };
    assert_eq!(errors.as_slice().len(), expected_count);
    let error = match errors.as_slice().first() {
        Some(value) => value,
        None => {
            assert_eq!(
                errors.as_slice().len(),
                expected_count,
                "unsupported fixture returned no diagnostic"
            );
            return;
        }
    };
    assert_eq!(error.kind, expected);
    let location = match error.location.as_ref() {
        Some(value) => value,
        None => {
            assert!(
                error.location.is_some(),
                "unsupported diagnostic has no location"
            );
            return;
        }
    };
    assert_eq!(
        location.file,
        format!("fixtures/free_variable_capture/{path}")
    );
    assert_eq!(location.line, line);
    assert_eq!(location.column, column);
}

#[test]
fn accepted_capture_forms_preserve_msl_and_reflection_contract() {
    let Some(vertex) = compile_fixture(Stage::Vertex, "vertex_uniform.glsl") else {
        return;
    };
    assert!(vertex.msl.contains("project_value("));
    assert!(
        vertex.msl.contains("constant float& transform"),
        "{}",
        vertex.msl
    );
    assert!(vertex.msl.contains("[[attribute(2)]]"), "{}", vertex.msl);
    let vertex_input = match vertex.reflection.vertex_inputs.first() {
        Some(value) => value,
        None => {
            assert_eq!(
                vertex.reflection.vertex_inputs.len(),
                1,
                "vertex input reflection was dropped"
            );
            return;
        }
    };
    assert_eq!(vertex_input.location, 2);
    assert_eq!(vertex.reflection.interfaces.len(), 2);
    assert_metal_compiles("vertex_uniform.glsl", &vertex.msl);

    let Some(legacy) = compile_fixture(Stage::Vertex, "vertex_legacy_input.glsl") else {
        return;
    };
    assert!(legacy.msl.contains("mtlr_normal"));
    assert_metal_compiles("vertex_legacy_input.glsl", &legacy.msl);

    let Some(block) = compile_fixture(Stage::Fragment, "fragment_uniform_block.glsl") else {
        return;
    };
    assert!(block.msl.contains("constant Scene& scene"), "{}", block.msl);
    assert!(block.msl.contains("[[buffer("));
    let block_resource = match block.reflection.resources.first() {
        Some(value) => value,
        None => {
            assert_eq!(
                block.reflection.resources.len(),
                1,
                "uniform block reflection was dropped"
            );
            return;
        }
    };
    assert_eq!(block_resource.kind, reflect::ResourceKind::UniformBuffer);
    assert_eq!(block.reflection.fragment_outputs.len(), 1);
    assert_metal_compiles("fragment_uniform_block.glsl", &block.msl);

    let Some(sampler) = compile_fixture(Stage::Fragment, "fragment_sampler.glsl") else {
        return;
    };
    assert!(
        sampler.msl.contains("texture2d<float> source_texture"),
        "{}",
        sampler.msl
    );
    assert!(
        sampler.msl.contains("sampler mtlr_source_texture_sampler"),
        "{}",
        sampler.msl
    );
    let sampler_resource = match sampler.reflection.resources.first() {
        Some(value) => value,
        None => {
            assert_eq!(
                sampler.reflection.resources.len(),
                1,
                "sampler reflection was dropped"
            );
            return;
        }
    };
    assert_eq!(sampler_resource.kind, reflect::ResourceKind::Texture);
    assert_metal_compiles("fragment_sampler.glsl", &sampler.msl);
}

#[test]
fn unsupported_stage_boundaries_keep_exact_diagnostics() {
    assert_location_and_kind(
        Stage::Geometry,
        "unsupported_geometry.glsl",
        2,
        6,
        ErrorKind::Parse,
        1,
    );
    assert_location_and_kind(
        Stage::TessellationControl,
        "unsupported_tessellation_control.glsl",
        1,
        1,
        ErrorKind::Unsupported,
        1,
    );
}
