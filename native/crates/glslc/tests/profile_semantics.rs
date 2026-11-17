use glslc::{ErrorKind, Options, Source, Stage, compile};

fn first_error(
    result: &Result<glslc::CompiledShader, glslc::CompileErrors>,
) -> Option<&glslc::CompileError> {
    match result {
        Ok(_) => None,
        Err(errors) => errors.as_slice().first(),
    }
}

fn compile_metal(source: &str) -> bool {
    let path = std::env::temp_dir().join("mtlr-glslc-profile-semantics.metal");
    let output = path.with_extension("air");
    if std::fs::write(&path, source.as_bytes()).is_err() {
        return false;
    }
    let result = std::process::Command::new("xcrun")
        .args(["-sdk", "macosx", "metal", "-std=metal3.2", "-c"])
        .arg(&path)
        .arg("-o")
        .arg(&output)
        .output();
    let _ = std::fs::remove_file(&path);
    let _ = std::fs::remove_file(&output);
    result.is_ok_and(|value| value.status.success())
}

#[test]
fn vector_scalar_arithmetic_and_builtin_overload_reach_metal() {
    let source = Source::new(
        "shaders/program/semantic_boundary.fsh",
        "#version 330 core\nlayout(location = 0) out vec4 color;\nvoid main() {\n    vec3 value = vec3(1.0);\n    value *= 2.0;\n    color = vec4(normalize(value), 1.0);\n}\n",
    );
    let result = compile(Stage::Fragment, &[source], &[], &Options::default());
    assert!(result.is_ok(), "{result:?}");
    let compiled = match result {
        Ok(value) => value,
        Err(_) => return,
    };
    assert!(compiled.msl.contains("normalize"));
    assert!(compiled.msl.contains("main_fragment"));
    assert_eq!(compiled.reflection.fragment_outputs.len(), 1);
    let output = match compiled.reflection.fragment_outputs.first() {
        Some(value) => value,
        None => return,
    };
    assert_eq!(output.name, "color");
    assert_eq!(output.ty, "vec4");
    assert_eq!(output.location, 0);
    assert!(compile_metal(&compiled.msl), "{}", compiled.msl);
}

#[test]
fn boolean_vector_arithmetic_is_rejected_at_the_logical_source() {
    let source = Source::new(
        "shaders/program/semantic_boundary.fsh",
        "#version 330 core\nlayout(location = 0) out vec4 color;\nvoid main() {\n    vec3 value = vec3(1.0);\n    value += bvec3(true);\n    color = vec4(value, 1.0);\n}\n",
    );
    let result = compile(Stage::Fragment, &[source], &[], &Options::default());
    let error = match first_error(&result) {
        Some(value) => value,
        None => return,
    };
    assert_eq!(error.kind, ErrorKind::Semantic);
    let location = match error.location.as_ref() {
        Some(value) => value,
        None => return,
    };
    assert_eq!(location.file, "shaders/program/semantic_boundary.fsh");
    assert_eq!(location.line, 3);
    assert_eq!(location.column, 1);
}
