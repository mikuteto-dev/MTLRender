use glslc::{ErrorKind, Options, Source, Stage, compile, reflect};

fn assert_metal_compiles(name: &str, source: &str) {
    let path = std::env::temp_dir().join(format!("mtlr-glslc-{name}.metal"));
    let output = std::env::temp_dir().join(format!("mtlr-glslc-{name}.air"));
    let write_result = std::fs::write(&path, source.as_bytes());
    assert!(write_result.is_ok(), "{write_result:?}");
    let status = std::process::Command::new("xcrun")
        .args(["-sdk", "macosx", "metal", "-std=metal3.2", "-c"])
        .arg(&path)
        .arg("-o")
        .arg(&output)
        .status();
    let _ = std::fs::remove_file(path);
    let _ = std::fs::remove_file(output);
    assert!(
        status.as_ref().is_ok_and(|value| value.success()),
        "{status:?}"
    );
}

#[test]
fn legacy_vertex_interface_metadata_matches_entry_point() {
    let source = Source::new(
        "profiles/legacy.vsh",
        "#version 120\nattribute vec3 position; varying flat ivec2 ids; void main() { gl_Position = ftransform(); ids = ivec2(gl_MultiTexCoord0.xy); }\n",
    );
    let result = compile(Stage::Vertex, &[source], &[], &Options::default());
    assert!(result.is_ok(), "{result:?}");
    let compiled = match result {
        Ok(value) => value,
        Err(_) => return,
    };
    assert!(compiled.msl.contains("[[attribute(0)]]"));
    assert!(
        compiled.msl.contains("[[attribute(1)]]"),
        "{}",
        compiled.msl
    );
    assert!(compiled.msl.contains("[[attribute(2)]]"));
    assert!(
        compiled
            .msl
            .contains("mtlr_model_view_projection*mtlr_vertex")
    );
    assert!(compiled.msl.contains("[[flat]]"));
    assert_eq!(
        compiled.reflection.vertex_inputs,
        vec![
            reflect::VertexInput {
                name: String::from("position"),
                ty: String::from("vec3"),
                location: 0,
            },
            reflect::VertexInput {
                name: String::from("mtlr_vertex"),
                ty: String::from("vec4"),
                location: 1,
            },
            reflect::VertexInput {
                name: String::from("mtlr_texcoord0"),
                ty: String::from("vec4"),
                location: 2,
            },
        ]
    );
    assert_eq!(
        compiled.reflection.interfaces,
        vec![
            reflect::InterfaceVariable {
                direction: reflect::InterfaceDirection::Input,
                name: String::from("position"),
                ty: String::from("vec3"),
                interpolation: None,
                location: None,
                array_length: None,
            },
            reflect::InterfaceVariable {
                direction: reflect::InterfaceDirection::Output,
                name: String::from("ids"),
                ty: String::from("ivec2"),
                interpolation: Some(String::from("flat")),
                location: None,
                array_length: None,
            },
            reflect::InterfaceVariable {
                direction: reflect::InterfaceDirection::Input,
                name: String::from("mtlr_vertex"),
                ty: String::from("vec4"),
                interpolation: None,
                location: Some(1),
                array_length: None,
            },
            reflect::InterfaceVariable {
                direction: reflect::InterfaceDirection::Input,
                name: String::from("mtlr_texcoord0"),
                ty: String::from("vec4"),
                interpolation: None,
                location: Some(2),
                array_length: None,
            },
        ]
    );
}

#[test]
fn vertex_fragment_interface_pair_preserves_matching_shape_and_metadata() {
    let vertex = Source::new(
        "profiles/pair.vsh",
        "layout(location = 3) in vec3 position; varying flat ivec2 ids; void main() { gl_Position = vec4(position, 1.0); ids = ivec2(1); }\n",
    );
    let fragment = Source::new(
        "profiles/pair.fsh",
        "varying flat ivec2 ids; layout(location = 1, colortex = 4) out vec4 color; void main() { color = vec4(1.0); }\n",
    );
    let vertex_result = compile(Stage::Vertex, &[vertex], &[], &Options::default());
    let fragment_result = compile(Stage::Fragment, &[fragment], &[], &Options::default());
    assert!(vertex_result.is_ok(), "{vertex_result:?}");
    assert!(fragment_result.is_ok(), "{fragment_result:?}");
    let vertex = match vertex_result {
        Ok(value) => value,
        Err(_) => return,
    };
    let fragment = match fragment_result {
        Ok(value) => value,
        Err(_) => return,
    };
    assert!(vertex.msl.contains("[[attribute(3)]]"));
    assert!(fragment.msl.contains("ids"));
    assert_metal_compiles("profile-pair-vertex", &vertex.msl);
    assert_metal_compiles("profile-pair-fragment", &fragment.msl);
    assert_eq!(
        vertex.reflection.vertex_inputs,
        vec![reflect::VertexInput {
            name: String::from("position"),
            ty: String::from("vec3"),
            location: 3,
        }]
    );
    assert_eq!(
        vertex.reflection.interfaces,
        vec![
            reflect::InterfaceVariable {
                direction: reflect::InterfaceDirection::Input,
                name: String::from("position"),
                ty: String::from("vec3"),
                interpolation: None,
                location: Some(3),
                array_length: None,
            },
            reflect::InterfaceVariable {
                direction: reflect::InterfaceDirection::Output,
                name: String::from("ids"),
                ty: String::from("ivec2"),
                interpolation: Some(String::from("flat")),
                location: None,
                array_length: None,
            },
        ]
    );
    assert_eq!(
        fragment.reflection.interfaces,
        vec![
            reflect::InterfaceVariable {
                direction: reflect::InterfaceDirection::Input,
                name: String::from("ids"),
                ty: String::from("ivec2"),
                interpolation: Some(String::from("flat")),
                location: None,
                array_length: None,
            },
            reflect::InterfaceVariable {
                direction: reflect::InterfaceDirection::Output,
                name: String::from("color"),
                ty: String::from("vec4"),
                interpolation: None,
                location: Some(1),
                array_length: None,
            },
        ]
    );
    assert_eq!(
        fragment.reflection.fragment_outputs,
        vec![reflect::FragmentOutput {
            name: String::from("color"),
            ty: String::from("vec4"),
            location: 1,
            colortex: 4,
        }]
    );
}

#[test]
fn legacy_fragment_targets_from_comments_share_generated_locations() {
    let metadata = format!("{}{} RENDERTARGETS: 0, 12, 3 {}{}\n", "/", "*", "*", "/");
    let source = Source::new(
        "profiles/targets.fsh",
        format!(
            "{metadata}void main() {{ gl_FragData[0] = vec4(1.0); gl_FragData[1] = vec4(0.5); gl_FragData[2] = vec4(0.25); }}\n"
        ),
    );
    let result = compile(Stage::Fragment, &[source], &[], &Options::default());
    assert!(result.is_ok(), "{result:?}");
    let compiled = match result {
        Ok(value) => value,
        Err(_) => return,
    };
    assert!(compiled.msl.contains("[[color(0)]]"));
    assert!(compiled.msl.contains("[[color(1)]]"));
    assert!(compiled.msl.contains("[[color(2)]]"));
    assert_eq!(
        compiled.reflection.fragment_outputs,
        vec![
            reflect::FragmentOutput {
                name: String::from("mtlr_frag_data_0"),
                ty: String::from("vec4"),
                location: 0,
                colortex: 0,
            },
            reflect::FragmentOutput {
                name: String::from("mtlr_frag_data_1"),
                ty: String::from("vec4"),
                location: 1,
                colortex: 12,
            },
            reflect::FragmentOutput {
                name: String::from("mtlr_frag_data_2"),
                ty: String::from("vec4"),
                location: 2,
                colortex: 3,
            },
        ]
    );
}

#[test]
fn helper_captures_uniforms_in_graphics_entry_scope() {
    let source = Source::new(
        "profiles/helper_capture.vsh",
        "uniform float scale; float to_clip(float value) { return scale * value; } void main() { gl_Position = vec4(to_clip(1.0)); }\n",
    );
    let result = compile(Stage::Vertex, &[source], &[], &Options::default());
    assert!(result.is_ok(), "{result:?}");
    let compiled = match result {
        Ok(value) => value,
        Err(_) => return,
    };
    assert!(compiled.msl.contains("constant float& scale"));
    assert!(compiled.msl.contains("to_clip(1.0, scale)"));
    assert_metal_compiles("profile-helper-capture", &compiled.msl);
}

#[test]
fn malformed_interface_reports_parse_boundary_at_source_location() {
    let source = Source::new(
        "profiles/malformed.fsh",
        "varying vec2 uv[2; void main() {}\n",
    );
    let result = compile(Stage::Fragment, &[source], &[], &Options::default());
    let errors = match result {
        Ok(_) => return,
        Err(errors) => errors,
    };
    let error = match errors.as_slice().first() {
        Some(error) => error,
        None => return,
    };
    assert_eq!(error.kind, ErrorKind::Parse);
    assert_eq!(
        error.location,
        Some(glslc::Location::new("profiles/malformed.fsh", 1, 1))
    );
}

#[test]
fn fragment_interface_contract_preserves_location_and_colortex() {
    let source = Source::new(
        "profiles/fragment.fsh",
        "layout(location = 2, colortex = 7) out vec4 color; varying noperspective vec2 uv; void main() { color = vec4(uv, 0.0, 1.0); }\n",
    );
    let result = compile(Stage::Fragment, &[source], &[], &Options::default());
    assert!(result.is_ok(), "{result:?}");
    let compiled = match result {
        Ok(value) => value,
        Err(_) => return,
    };
    assert!(compiled.msl.contains("[[color(2)]]"));
    assert_eq!(
        compiled.reflection.fragment_outputs,
        vec![reflect::FragmentOutput {
            name: String::from("color"),
            ty: String::from("vec4"),
            location: 2,
            colortex: 7,
        }]
    );
    assert_eq!(
        compiled.reflection.interfaces,
        vec![
            reflect::InterfaceVariable {
                direction: reflect::InterfaceDirection::Output,
                name: String::from("color"),
                ty: String::from("vec4"),
                interpolation: None,
                location: Some(2),
                array_length: None,
            },
            reflect::InterfaceVariable {
                direction: reflect::InterfaceDirection::Input,
                name: String::from("uv"),
                ty: String::from("vec2"),
                interpolation: Some(String::from("noperspective")),
                location: None,
                array_length: None,
            },
        ]
    );
}
