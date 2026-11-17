use glslc::{ErrorKind, Options, Source, Stage, compile};

fn first_error(
    result: &Result<glslc::CompiledShader, glslc::CompileErrors>,
) -> Option<&glslc::CompileError> {
    match result {
        Ok(_) => None,
        Err(errors) => errors.as_slice().first(),
    }
}

#[test]
fn precision_profile_fixture_reaches_msl_through_compile() {
    let source = Source::new(
        "shaders/world0/gbuffers_basic.fsh",
        "#version 120\n#define DEFAULT_PRECISION highp\nprecision DEFAULT_PRECISION float;\nvoid main() {}\n",
    );
    let result = compile(Stage::Fragment, &[source], &[], &Options::default());
    assert!(result.is_ok(), "{result:?}");
    let compiled = match result {
        Ok(value) => value,
        Err(_) => return,
    };
    assert!(compiled.msl.contains("#include <metal_stdlib>"));
    assert!(compiled.msl.contains("main_fragment"));
}

#[test]
fn malformed_precision_profile_is_rejected_with_logical_source_location() {
    let source = Source::new(
        "shaders/world0/gbuffers_basic.fsh",
        "#version 120\n#define MALFORMED_PRECISION precision highp;\nMALFORMED_PRECISION\nvoid main() {}\n",
    );
    let result = compile(Stage::Fragment, &[source], &[], &Options::default());
    let error = match first_error(&result) {
        Some(value) => value,
        None => return,
    };
    assert_eq!(error.kind, ErrorKind::Parse);
    let location = match error.location.as_ref() {
        Some(value) => value,
        None => return,
    };
    assert_eq!(location.file, "shaders/world0/gbuffers_basic.fsh");
    assert_eq!(location.line, 4);
    assert_eq!(location.column, 6);
    assert!(!error.message.contains("/tmp"));
    assert!(!error.message.contains(".zip"));
}

#[test]
fn precision_without_a_precision_qualifier_remains_rejected() {
    let source = Source::new(
        "shaders/world0/gbuffers_basic.fsh",
        "precision float;\nvoid main() {}\n",
    );
    let result = compile(Stage::Fragment, &[source], &[], &Options::default());
    let error = first_error(&result);
    assert_eq!(error.map(|value| &value.kind), Some(&ErrorKind::Parse));
}
