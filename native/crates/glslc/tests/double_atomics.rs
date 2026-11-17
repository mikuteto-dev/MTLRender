use glslc::{ErrorKind, Options, Source, Stage, compile};
use std::sync::atomic::{AtomicUsize, Ordering};

static NEXT_TEMP: AtomicUsize = AtomicUsize::new(0);

fn compile_metal(source: &str) -> bool {
    let serial = NEXT_TEMP.fetch_add(1, Ordering::Relaxed);
    let path = std::env::temp_dir().join(format!(
        "mtlr-glslc-double-atomics-{}-{}.metal",
        std::process::id(),
        serial
    ));
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
fn ordinary_float_and_32_bit_integer_atomics_compile_as_metal() {
    let source = Source::new(
        "atomics.glsl",
        "buffer float value; buffer int signed_value; buffer uint unsigned_value; void main() { atomicAdd(value, 1.0); atomicAdd(signed_value, 1); atomicExchange(unsigned_value, 2u); }",
    );
    let result = compile(Stage::Compute, &[source], &[], &Options::default());
    assert!(result.is_ok(), "{result:?}");
    let compiled = match result {
        Ok(value) => value,
        Err(_) => return,
    };
    assert!(compile_metal(&compiled.msl), "{}", compiled.msl);
}

#[test]
fn double_and_64_bit_integer_atomics_are_rejected() {
    for text in [
        "buffer double value; void main() { atomicAdd(value, 1.0); }",
        "buffer int64_t value; void main() { atomicAdd(value, int64_t(1)); }",
        "buffer uint64_t value; void main() { atomicExchange(value, uint64_t(1)); }",
    ] {
        let source = Source::new("unsupported-atomics.glsl", text);
        let result = compile(Stage::Compute, &[source], &[], &Options::default());
        assert!(result.is_err(), "unsupported atomic operation compiled");
        let errors = match result {
            Ok(_) => continue,
            Err(value) => value,
        };
        assert!(errors.as_slice().iter().any(|error| {
            error.kind == ErrorKind::Unsupported
                && error.message.contains("64-bit")
                && error
                    .location
                    .as_ref()
                    .is_some_and(|location| location.file == "unsupported-atomics.glsl")
        }));
    }
}

#[test]
fn double_and_64_bit_arithmetic_lower_to_metal_scalar_types() {
    let source = Source::new(
        "numeric.glsl",
        "void main() { double d = double(1.0); int64_t i = int64_t(2); uint64_t u = uint64_t(3); d += double(i); i += int64_t(u); }",
    );
    let result = compile(Stage::Compute, &[source], &[], &Options::default());
    assert!(result.is_ok(), "{result:?}");
    let compiled = match result {
        Ok(value) => value,
        Err(_) => return,
    };
    assert!(compiled.msl.contains("float d"));
    assert!(compiled.msl.contains("long i"));
    assert!(compiled.msl.contains("ulong u"));
    assert!(compile_metal(&compiled.msl), "{}", compiled.msl);
}
