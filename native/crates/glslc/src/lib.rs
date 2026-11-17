pub mod builtins;
mod depth;
pub mod geometry;
pub mod geometry_compute;
pub mod geometry_count;
pub mod geometry_gs;
pub mod geometry_gs_effects;
pub mod geometry_gs_stage;
pub mod geometry_gs_topology;
pub mod geometry_records;
pub mod geometry_vs;
pub mod lex;
pub mod msl;
pub mod parse;
pub mod preprocess;
pub mod reflect;
pub mod sema;
pub mod tessellation;
pub mod transform;

#[cfg(test)]
mod geometry_compute_tests;
#[cfg(test)]
mod geometry_fixture_tests;
#[cfg(test)]
mod geometry_gs_conformance;
#[cfg(test)]
mod geometry_gs_effects_tests;
#[cfg(test)]
mod geometry_tests;

use std::fmt::{Display, Formatter};

use crate::geometry_gs_effects::{analyze_geometry_side_effects, collect_geometry_accesses};

pub const API_VERSION: u32 = 1;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub enum Stage {
    Vertex = 0,
    Fragment = 1,
    Compute = 2,
    Geometry = 3,
    TessellationControl = 4,
    TessellationEvaluation = 5,
}

impl Stage {
    pub fn from_u32(value: u32) -> Option<Self> {
        match value {
            0 => Some(Self::Vertex),
            1 => Some(Self::Fragment),
            2 => Some(Self::Compute),
            3 => Some(Self::Geometry),
            4 => Some(Self::TessellationControl),
            5 => Some(Self::TessellationEvaluation),
            _ => None,
        }
    }
}

#[derive(Debug, Eq, PartialEq)]
pub struct Source {
    path: String,
    text: String,
}

impl Source {
    pub fn new(path: impl Into<String>, text: impl Into<String>) -> Self {
        Self {
            path: path.into(),
            text: text.into(),
        }
    }

    pub fn path(&self) -> &str {
        &self.path
    }

    pub fn text(&self) -> &str {
        &self.text
    }
}

#[derive(Debug, Eq, PartialEq)]
pub struct Define {
    name: String,
    value: Option<String>,
}

impl Define {
    pub fn new(name: impl Into<String>, value: Option<&str>) -> Self {
        Self {
            name: name.into(),
            value: value.map(String::from),
        }
    }

    pub fn flag(name: impl Into<String>) -> Self {
        Self::new(name, None)
    }

    pub fn name(&self) -> &str {
        &self.name
    }

    pub fn value(&self) -> Option<&str> {
        self.value.as_deref()
    }
}

#[derive(Debug, Eq, PartialEq)]
pub struct Options {
    pub msl_major: u16,
    pub msl_minor: u16,
    pub max_include_depth: u32,
    pub max_source_bytes: u64,
    pub max_macro_expansion_depth: u32,
    pub max_macro_expanded_bytes: u64,
    pub max_parser_recursion_depth: u32,
    pub lenient_iris_compatibility: bool,
}

impl Default for Options {
    fn default() -> Self {
        Self {
            msl_major: 3,
            msl_minor: 2,
            max_include_depth: 64,
            max_source_bytes: 16 * 1024 * 1024,
            max_macro_expansion_depth: 128,
            max_macro_expanded_bytes: 64 * 1024 * 1024,
            max_parser_recursion_depth: 256,
            lenient_iris_compatibility: true,
        }
    }
}

#[derive(Debug, Eq, PartialEq)]
pub struct Location {
    pub file: String,
    pub line: u32,
    pub column: u32,
}

impl Location {
    pub fn new(file: impl Into<String>, line: u32, column: u32) -> Self {
        Self {
            file: file.into(),
            line,
            column,
        }
    }
}

#[derive(Debug, Eq, PartialEq)]
pub enum ErrorKind {
    Input,
    Preprocess,
    Lex,
    Parse,
    Semantic,
    Transform,
    Msl,
    Unsupported,
}

#[derive(Debug, Eq, PartialEq)]
pub struct CompileError {
    pub kind: ErrorKind,
    pub message: String,
    pub location: Option<Location>,
}

impl CompileError {
    pub fn new(kind: ErrorKind, message: impl Into<String>, location: Option<Location>) -> Self {
        Self {
            kind,
            message: message.into(),
            location,
        }
    }

    pub fn input(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Input, message, None)
    }

    pub fn unsupported(stage: Stage, location: Option<Location>) -> Self {
        Self::new(
            ErrorKind::Unsupported,
            format!("GLSL {:?} compilation stages are not implemented", stage),
            location,
        )
    }

    pub fn unsupported_at(message: impl Into<String>, location: Option<Location>) -> Self {
        Self::new(ErrorKind::Unsupported, message, location)
    }
}

impl Display for CompileError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        if let Some(location) = &self.location {
            write!(
                formatter,
                "{}:{}:{}: {}",
                location.file, location.line, location.column, self.message
            )
        } else {
            formatter.write_str(&self.message)
        }
    }
}

#[derive(Debug, Eq, PartialEq)]
pub struct CompileErrors {
    errors: Vec<CompileError>,
}

impl CompileErrors {
    pub fn one(error: CompileError) -> Self {
        Self {
            errors: vec![error],
        }
    }

    pub(crate) fn from_vec(errors: Vec<CompileError>) -> Self {
        Self { errors }
    }

    pub fn as_slice(&self) -> &[CompileError] {
        &self.errors
    }
}

impl Display for CompileErrors {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        for (index, error) in self.errors.iter().enumerate() {
            if index != 0 {
                formatter.write_str("\n")?;
            }
            error.fmt(formatter)?;
        }
        Ok(())
    }
}

impl std::error::Error for CompileErrors {}

#[derive(Debug, Eq, PartialEq)]
pub struct CompiledShader {
    pub msl: String,
    pub reflection: reflect::Reflection,
}

pub fn compile(
    stage: Stage,
    sources: &[Source],
    defines: &[Define],
    options: &Options,
) -> Result<CompiledShader, CompileErrors> {
    let source = sources.first().ok_or_else(|| {
        CompileErrors::one(CompileError::input(
            "at least one GLSL source file is required",
        ))
    })?;
    let total_bytes = sources.iter().try_fold(0_u64, |total, source| {
        let bytes = u64::try_from(source.text().len()).ok()?;
        total.checked_add(bytes)
    });
    let Some(total_bytes) = total_bytes else {
        return Err(CompileErrors::one(CompileError::new(
            ErrorKind::Input,
            "GLSL source size cannot be represented",
            Some(Location::new(source.path(), 1, 1)),
        )));
    };
    if total_bytes > options.max_source_bytes {
        return Err(CompileErrors::one(CompileError::new(
            ErrorKind::Input,
            "GLSL sources exceed the configured byte limit",
            Some(Location::new(source.path(), 1, 1)),
        )));
    }
    let preprocessed = preprocess::run(sources, defines, options)?;
    let tokens = lex::run(&preprocessed)?;
    let ast = parse::run_with_limit(&tokens, stage, options.max_parser_recursion_depth)?;
    let typed = sema::run(&ast, stage)?;
    let tessellation = tessellation::analyze(&ast, stage)?;
    let selected = if tessellation.is_some() {
        tessellation::analyze(&ast, stage)?
            .map(|metadata| {
                transform::select_lowering(transform::StageEmulationInput {
                    stage,
                    geometry: None,
                    tessellation: Some(metadata.input),
                })
            })
            .transpose()?
    } else {
        None
    };
    let _ = transform::run(&typed, stage, options)?;
    let mut transformed_ast = ast;
    let targets = output_targets(source.text());
    let atomic_requirements = transform::validate_atomic_operations(&transformed_ast)?;
    transform::run_ast(&mut transformed_ast, stage, options, &targets)?;
    let mut reflection = reflect::collect_ast(&transformed_ast, stage)?;
    reflection.capabilities.atomics = atomic_requirements.add || atomic_requirements.exchange;
    if let Some(plan) = selected.as_ref() {
        reflection = reflection.with_stage_emulation(plan);
    }
    reflection.tessellation = tessellation.map(|metadata| metadata.descriptor);
    if matches!(
        stage,
        Stage::TessellationControl | Stage::TessellationEvaluation
    ) {
        reflection.add_tessellation_resources(stage);
    }
    if stage == Stage::Geometry {
        let plan = geometry::analyze(&transformed_ast, &reflection);
        reflection.set_geometry_plan(&plan);
        if matches!(
            plan.kind,
            geometry::GeometryKind::PassThrough | geometry::GeometryKind::Layered
        ) {
            return Ok(CompiledShader {
                msl: String::new(),
                reflection,
            });
        }
        if plan.kind == geometry::GeometryKind::BoundedMesh
            && plan.side_effects == geometry::GeometrySideEffects::default()
        {
            return Ok(CompiledShader {
                msl: String::new(),
                reflection,
            });
        }
        if plan.kind != geometry::GeometryKind::Compute
            && plan.side_effects == geometry::GeometrySideEffects::default()
        {
            return Err(CompileErrors::one(CompileError::new(
                ErrorKind::Transform,
                format!("geometry compute lowering rejected: {}", plan.reason),
                Some(Location::new(source.path(), 1, 1)),
            )));
        }
        let context = geometry_gs::GeometryGsContext {
            pack: String::from(source.path()),
            dimension: String::from("unknown"),
            program: String::from("geometry"),
            source_stage: String::from("geometry"),
        };
        let accesses = if plan.side_effects.image_writes
            || plan.side_effects.ssbo_writes
            || plan.side_effects.atomics
        {
            collect_geometry_accesses(&transformed_ast, &reflection, &context).map_err(|error| {
                CompileErrors::one(CompileError::new(
                    ErrorKind::Unsupported,
                    error.to_string(),
                    Some(Location::new(source.path(), 1, 1)),
                ))
            })?
        } else {
            Vec::new()
        };
        if plan.side_effects.image_writes
            || plan.side_effects.ssbo_writes
            || plan.side_effects.atomics
        {
            analyze_geometry_side_effects(&transformed_ast, &reflection, &plan, &context).map_err(
                |error| {
                    CompileErrors::one(CompileError::new(
                        ErrorKind::Unsupported,
                        error.to_string(),
                        Some(Location::new(source.path(), 1, 1)),
                    ))
                },
            )?;
        }
        let output_vertices = u64::from(plan.emitted_vertices);
        let output_primitives = u64::from(plan.emitted_primitives);
        let record_layout =
            geometry_records::GeometryRecordLayout::new(geometry_records::GeometryLayoutInput {
                input_primitives: 1,
                output_vertices,
                output_primitives,
                varying_stride: 0,
                device_buffer_limit: u64::MAX,
            })
            .map_err(|error| {
                CompileErrors::one(CompileError::new(
                    ErrorKind::Transform,
                    error.to_string(),
                    Some(Location::new(source.path(), 1, 1)),
                ))
            })?;
        let legacy = geometry_vs::lower(
            &plan,
            geometry_vs::VsInput {
                input_records: 1,
                input_vertex_stride: 16,
                varying_stride: 0,
                device_buffer_limit: u64::MAX,
            },
        )
        .ok()
        .map(|value| value.msl)
        .unwrap_or_default();
        let legacy_count = geometry_count::lower(
            match plan.input_topology.as_str() {
                "points" => transform::PrimitiveTopology::Points,
                "lines" | "line_strip" => transform::PrimitiveTopology::Lines,
                "triangles" | "triangle_strip" => transform::PrimitiveTopology::Triangles,
                _ => transform::PrimitiveTopology::Triangles,
            },
            geometry_count::CountInput {
                input_records: 1,
                input_vertex_stride: 32,
                output_stride: 16,
                device_buffer_limit: u64::MAX,
            },
            plan.side_effects,
        )
        .ok()
        .map(|value| value.msl)
        .unwrap_or_default();
        let input = geometry_gs::GeometryGsInput {
            context,
            plan,
            input_primitives: 1,
            invocation_index: 0,
            prefix_offset: 0,
            prefix_complete: true,
            primitive_boundary: geometry_records::PrimitiveBoundary::None,
            varying: geometry_gs::GeometryGsVaryingIdentity {
                name: String::from("geometry_varying"),
                location: 0,
            },
            varying_stride: 0,
            primitive_id: geometry_gs::GeometryGsPrimitiveId::Invocation,
            layer: if reflection
                .geometry_plan
                .as_ref()
                .is_some_and(|value| value.writes_layer)
            {
                geometry_gs::GeometryGsLayer::Invocation
            } else {
                geometry_gs::GeometryGsLayer::Unwritten
            },
            output_stride: u32::try_from(record_layout.gs_vertices.stride).map_err(|_| {
                CompileErrors::one(CompileError::new(
                    ErrorKind::Transform,
                    String::from("geometry output stride exceeds metadata range"),
                    Some(Location::new(source.path(), 1, 1)),
                ))
            })?,
            record_layout,
            accesses,
        };
        let candidate = geometry_gs::lower_geometry_gs(input).map_err(|error| {
            CompileErrors::one(CompileError::new(
                ErrorKind::Transform,
                error.to_string(),
                Some(Location::new(source.path(), 1, 1)),
            ))
        })?;
        return Ok(CompiledShader {
            msl: format!("{}\n{}\n{}", legacy, legacy_count, candidate.stage.msl),
            reflection,
        });
    }
    let generated = match selected
        .as_ref()
        .and_then(|plan| plan.tessellation.as_ref())
    {
        Some(plan)
            if matches!(
                stage,
                Stage::TessellationControl | Stage::TessellationEvaluation
            ) =>
        {
            tessellation::msl::generate(&transformed_ast, stage, options, plan)?
        }
        Some(plan) => {
            msl::generate_with_tessellation(&transformed_ast, stage, options, None, Some(plan))?
                .text
        }
        None => msl::generate(&transformed_ast, stage, options, None)?.text,
    };
    Ok(CompiledShader {
        msl: generated,
        reflection,
    })
}

pub struct MeshCompileContext<'a> {
    pub pack: &'a str,
    pub dimension: &'a str,
    pub program: &'a str,
}

pub fn compile_mesh(
    vertex_sources: &[Source],
    geometry_sources: &[Source],
    fragment_sources: &[Source],
    defines: &[Define],
    options: &Options,
    context: MeshCompileContext<'_>,
) -> Result<CompiledShader, CompileErrors> {
    let vertex = compile(Stage::Vertex, vertex_sources, defines, options)?;
    let geometry = compile(Stage::Geometry, geometry_sources, defines, options)?;
    let fragment = compile(Stage::Fragment, fragment_sources, defines, options)?;
    let Some(plan) = geometry.reflection.geometry_plan.as_ref() else {
        return Err(CompileErrors::one(CompileError::new(
            ErrorKind::Transform,
            "mesh lowering requires geometry analysis",
            None,
        )));
    };
    if plan.kind != geometry::GeometryKind::BoundedMesh {
        return Err(CompileErrors::one(CompileError::new(
            ErrorKind::Transform,
            format!("mesh lowering rejected: {}", plan.reason),
            None,
        )));
    }
    let effects = geometry::mesh::MeshSideEffects {
        image_writes: plan.side_effects.image_writes,
        ssbo_writes: plan.side_effects.ssbo_writes,
        atomics: plan.side_effects.atomics,
        barriers: false,
        ordering_sensitive: plan.dynamic_control_flow,
    };
    let input = geometry::mesh::MeshLoweringInput {
        pack: context.pack,
        dimension: context.dimension,
        program: context.program,
        vertex: Some(&vertex.reflection),
        geometry: Some(&geometry.reflection),
        fragment: Some(&fragment.reflection),
        geometry_plan: Some(plan),
        payload: geometry::mesh::ObjectPayloadRecord {
            offset: 0,
            size: 16,
            alignment: 16,
        },
        output_vertex: geometry::mesh::OutputRecord {
            offset: 0,
            size: 16,
            stride: 16,
        },
        output_primitive: geometry::mesh::OutputRecord {
            offset: 0,
            size: 12,
            stride: 12,
        },
        side_effects: effects,
    };
    let lowered =
        geometry::mesh::lowering::lower(input, [1, 1, 1], [256, 1, 1]).map_err(|error| {
            CompileErrors::one(CompileError::new(ErrorKind::Transform, error.detail, None))
        })?;
    let mut reflection = fragment.reflection;
    reflection.attach_mesh_metadata(lowered.metadata);
    let mut msl = lowered.msl;
    msl.push('\n');
    msl.push_str(fragment.msl.as_str());
    Ok(CompiledShader { msl, reflection })
}

fn output_targets(source: &str) -> Vec<u32> {
    for line in source.lines() {
        if let Some(value) = line.split_once("DRAWBUFFERS:").map(|(_, value)| value) {
            return value
                .chars()
                .filter_map(|character| character.to_digit(16))
                .collect();
        }
        if let Some(value) = line.split_once("RENDERTARGETS:").map(|(_, value)| value) {
            let mut result = Vec::new();
            for part in value.split(|character: char| !character.is_ascii_digit()) {
                if !part.is_empty()
                    && let Ok(number) = part.parse::<u32>()
                {
                    result.push(number);
                }
            }
            return result;
        }
    }
    Vec::new()
}

#[cfg(test)]
mod tests {
    use super::{Define, ErrorKind, Options, Source, Stage, compile, reflect};

    #[test]
    fn compile_returns_msl_for_a_minimal_vertex_stage() {
        let source = Source::new("shaders/world0/gbuffers_basic.vsh", "void main() {}\n");
        let result = compile(Stage::Vertex, &[source], &[], &Options::default());
        assert!(result.is_ok(), "{result:?}");
        let compiled = match result {
            Ok(value) => value,
            Err(_) => return,
        };
        assert!(compiled.msl.contains("#include <metal_stdlib>"));
        assert!(compiled.msl.contains("main_vertex"));
    }

    #[test]
    fn graphics_entries_preserve_interfaces_depth_and_discard() {
        let vertex = Source::new(
            "graphics.vsh",
            "layout(location=2) in vec3 position; varying flat ivec2 ids; void main() { gl_Position = vec4(position, 1.0); ids = ivec2(1); }",
        );
        let fragment = Source::new(
            "graphics.fsh",
            "varying flat ivec2 ids; layout(location=1) out vec4 color; void main() { if (ids.x > 0) discard; gl_FragDepth = 0.5; color = vec4(1.0); }",
        );
        let vertex_result = compile(Stage::Vertex, &[vertex], &[], &Options::default());
        let fragment_result = compile(Stage::Fragment, &[fragment], &[], &Options::default());
        assert!(vertex_result.is_ok(), "{vertex_result:?}");
        assert!(fragment_result.is_ok(), "{fragment_result:?}");
        let vertex_msl = vertex_result.map_or_else(|_| String::new(), |value| value.msl);
        let fragment_msl = fragment_result.map_or_else(|_| String::new(), |value| value.msl);
        assert!(vertex_msl.contains("[[stage_in]]"));
        assert!(vertex_msl.contains("[[attribute(2)]]"));
        assert!(vertex_msl.contains("[[position]]"));
        assert!(fragment_msl.contains("[[stage_in]]"));
        assert!(fragment_msl.contains("[[depth(any)]]"));
        assert!(fragment_msl.contains("discard_fragment()"));
        assert!(fragment_msl.contains("[[color(1)]]"));
        for (name, text) in [
            ("graphics-vertex", vertex_msl),
            ("graphics-fragment", fragment_msl),
        ] {
            let path = std::env::temp_dir().join(format!("mtlr-glslc-{name}.metal"));
            let output = std::env::temp_dir().join(format!("mtlr-glslc-{name}.air"));
            let write_result = std::fs::write(&path, text.as_bytes());
            assert!(write_result.is_ok(), "{write_result:?}");
            let status = std::process::Command::new("xcrun")
                .args(["-sdk", "macosx", "metal", "-std=metal3.2", "-c"])
                .arg(&path)
                .arg("-o")
                .arg(&output)
                .status();
            let success = status.as_ref().is_ok_and(|value| value.success());
            assert!(success, "{status:?}");
            let _ = std::fs::remove_file(path);
            let _ = std::fs::remove_file(output);
        }
    }

    #[test]
    fn texture_resource_fixture_emits_matching_metal_bindings_and_compiles() {
        let source = Source::new(
            "textures.fsh",
            "layout(location=0) out vec4 color; layout(binding=4) uniform sampler2D tex; layout(binding=7) uniform sampler2DShadow shadow; layout(binding=9, rgba32f) uniform image2D image; void main() { vec2 uv = vec2(0.5); color = texture(tex, uv) + textureLod(tex, uv, 1.0) + textureGrad(tex, uv, dFdx(uv), dFdy(uv)) + textureOffset(tex, uv, ivec2(1)); color += vec4(texelFetch(tex, ivec2(1))); color += vec4(texture(shadow, vec3(uv, 0.5))); imageStore(image, ivec2(0), color); }",
        );
        let result = compile(Stage::Fragment, &[source], &[], &Options::default());
        assert!(result.is_ok(), "{result:?}");
        let compiled = match result {
            Ok(value) => value,
            Err(_) => return,
        };
        assert!(compiled.msl.contains("texture2d<float> tex [[texture(4)]]"));
        assert!(
            compiled
                .msl
                .contains("sampler mtlr_tex_sampler [[sampler(4)]]")
        );
        assert!(compiled.msl.contains("sample_compare"));
        let path = std::env::temp_dir().join("mtlr-glslc-texture-fixture.metal");
        let output = path.with_extension("air");
        let write_result = std::fs::write(&path, compiled.msl.as_bytes());
        assert!(write_result.is_ok(), "{write_result:?}");
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
    fn raw_output_directives_keep_drawbuffer_slots_and_render_targets() {
        assert_eq!(super::output_targets("DRAWBUFFERS:013"), vec![0, 1, 3]);
        assert_eq!(
            super::output_targets("RENDERTARGETS:0, 12, 3"),
            vec![0, 12, 3]
        );
    }

    #[test]
    fn source_and_defines_preserve_pack_owned_text() {
        let source = Source::new("pack.glsl", "#version 460 core\n");
        let define = Define::new("MC_VERSION", Some("26.2"));
        assert_eq!(source.path(), "pack.glsl");
        assert_eq!(source.text(), "#version 460 core\n");
        assert_eq!(define.name(), "MC_VERSION");
        assert_eq!(define.value(), Some("26.2"));
    }

    #[test]
    fn recursive_macro_is_rejected_at_the_call_site() {
        let source = Source::new("pack.glsl", "#define LOOP LOOP\nLOOP\n");
        let result = compile(Stage::Vertex, &[source], &[], &Options::default());
        let errors = match result {
            Ok(_) => return,
            Err(errors) => errors,
        };
        let error = match errors.as_slice().first() {
            Some(error) => error,
            None => return,
        };
        assert_eq!(error.kind, ErrorKind::Preprocess);
        assert_eq!(
            error.location.as_ref().map(|value| value.file.as_str()),
            Some("pack.glsl")
        );
        assert_eq!(error.location.as_ref().map(|value| value.line), Some(2));
    }

    #[test]
    fn nested_parentheses_are_bounded() {
        let source = Source::new("pack.glsl", "(".repeat(20));
        let options = Options {
            max_parser_recursion_depth: 8,
            ..Options::default()
        };
        let result = compile(Stage::Vertex, &[source], &[], &options);
        let errors = match result {
            Ok(_) => return,
            Err(errors) => errors,
        };
        assert_eq!(
            errors.as_slice().first().map(|value| &value.kind),
            Some(&ErrorKind::Parse)
        );
    }

    #[test]
    fn reflection_reports_bindings_layout_and_compute_size() {
        let source = Source::new(
            "reflection.glsl",
            "layout(local_size_x = 8, local_size_y = 4, local_size_z = 2) in; layout(binding = 3) uniform Camera { mat4 view; vec3 eye; float exposure; } camera; void main() {}",
        );
        let result = compile(Stage::Compute, &[source], &[], &Options::default());
        assert!(result.is_ok(), "{result:?}");
        let compiled = match result {
            Ok(value) => value,
            Err(_) => return,
        };
        assert_eq!(
            compiled.reflection.compute_local_size,
            Some(reflect::ComputeLocalSize {
                x: 8,
                y: 4,
                z: 2,
                helper: None,
                helper_version: 0,
            })
        );
        assert_eq!(compiled.reflection.resources.len(), 1);
        assert_eq!(
            compiled
                .reflection
                .resources
                .first()
                .map(|value| value.binding),
            Some(3)
        );
        assert_eq!(
            compiled
                .reflection
                .resources
                .get(1)
                .map(|value| value.binding),
            None
        );
        assert_eq!(
            compiled
                .reflection
                .uniform_members
                .first()
                .map(|value| value.offset),
            Some(0)
        );
        assert_eq!(
            compiled
                .reflection
                .uniform_members
                .get(1)
                .map(|value| value.offset),
            Some(64)
        );
        assert_eq!(
            compiled
                .reflection
                .uniform_members
                .get(2)
                .map(|value| value.offset),
            Some(80)
        );
        let encoded = reflect::encode(&compiled.reflection, Stage::Compute);
        assert_eq!(encoded.get(..8), Some(b"MTLRGLSL".as_slice()));
    }

    #[test]
    fn unterminated_comment_is_rejected() {
        let source = Source::new("pack.glsl", String::from("/") + "* unterminated");
        let result = compile(Stage::Fragment, &[source], &[], &Options::default());
        let errors = match result {
            Ok(_) => return,
            Err(errors) => errors,
        };
        assert_eq!(
            errors.as_slice().first().map(|value| &value.kind),
            Some(&ErrorKind::Lex)
        );
    }

    #[test]
    fn metal_lacks_double_but_double_and_64_bit_integer_dialects_lower() {
        let source = Source::new(
            "precision.glsl",
            "void main() { dvec3 position = dvec3(1.0); int64_t count = 1; uint64_t mask = 2; }",
        );
        let result = compile(Stage::Compute, &[source], &[], &Options::default());
        assert!(result.is_ok(), "{result:?}");
        let compiled = match result {
            Ok(value) => value,
            Err(_) => return,
        };
        assert!(compiled.msl.contains("float3 position"));
        assert!(compiled.msl.contains("long count"));
        assert!(compiled.msl.contains("ulong mask"));
        assert!(!compiled.msl.contains("double"));
    }

    #[test]
    fn compute_storage_resources_and_shared_memory_compile_as_metal() {
        let source = Source::new(
            "compute-storage.glsl",
            "layout(local_size_x = 8, local_size_y = 4) in; const ivec3 workGroups = ivec3(2, 2, 1); shared uint shared_values[64]; layout(rgba32f, binding = 2) writeonly uniform image2D target; layout(std430, binding = 3) buffer Data { float values[]; } data; void main() { uint index = gl_LocalInvocationID.x; shared_values[index] = 1u; barrier(); memoryBarrierBuffer(); imageStore(target, ivec2(gl_GlobalInvocationID.xy), vec4(float(shared_values[index]))); }",
        );
        let result = compile(Stage::Compute, &[source], &[], &Options::default());
        assert!(result.is_ok(), "{result:?}");
        let compiled = match result {
            Ok(value) => value,
            Err(_) => return,
        };
        assert!(
            compiled
                .msl
                .contains("constant int3 workGroups = int3(2, 2, 1);")
        );
        assert!(compiled.msl.contains("threadgroup uint* shared_values"));
        assert!(compiled.msl.contains("device Data* data"));
        assert_eq!(compiled.reflection.resources.len(), 2);
        assert_eq!(
            compiled
                .reflection
                .resources
                .first()
                .map(|value| value.binding),
            Some(2)
        );
        assert_eq!(
            compiled
                .reflection
                .resources
                .get(1)
                .map(|value| value.binding),
            Some(3)
        );
        assert!(
            compiled.msl.contains(
                "threadgroup_barrier(mem_flags::mem_device | mem_flags::mem_threadgroup)"
            )
        );
        assert!(
            compiled
                .msl
                .contains("threadgroup_barrier(mem_flags::mem_device)")
        );
        let path = std::env::temp_dir().join("mtlr-glslc-compute-storage-test.metal");
        let write_result = std::fs::write(&path, compiled.msl.as_bytes());
        assert!(write_result.is_ok(), "{write_result:?}");
        let output = std::process::Command::new("xcrun")
            .args(["-sdk", "macosx", "metal", "-std=metal3.2", "-c"])
            .arg(&path)
            .arg("-o")
            .arg(path.with_extension("air"))
            .output();
        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(path.with_extension("air"));
        assert!(output.is_ok(), "{output:?}");
        if let Ok(output) = output {
            assert!(
                output.status.success(),
                "{}\n{}",
                compiled.msl,
                String::from_utf8_lossy(&output.stderr)
            );
        }
    }

    #[test]
    fn float_and_integer_buffer_atomics_use_metal_cas_helpers() {
        let source = Source::new(
            "atomics.glsl",
            "buffer float value; buffer uint count; void main() { atomicAdd(value, 1.0); atomicAdd(count, 1u); }",
        );
        let result = compile(Stage::Compute, &[source], &[], &Options::default());
        assert!(result.is_ok(), "{result:?}");
        let compiled = match result {
            Ok(value) => value,
            Err(_) => return,
        };
        assert!(compiled.msl.contains("mtlr_atomic_add"));
        assert!(compiled.msl.contains("memory_order_seq_cst"));
        assert!(
            compiled
                .msl
                .contains("atomic_compare_exchange_weak_explicit")
        );
        assert!(compiled.reflection.capabilities.atomics);
        let path = std::env::temp_dir().join("mtlr-glslc-atomic-test.metal");
        let write_result = std::fs::write(&path, compiled.msl.as_bytes());
        assert!(write_result.is_ok(), "{write_result:?}");
        let output = std::process::Command::new("xcrun")
            .args(["-sdk", "macosx", "metal", "-std=metal3.2", "-c"])
            .arg(&path)
            .arg("-o")
            .arg(path.with_extension("air"))
            .output();
        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(path.with_extension("air"));
        assert!(output.is_ok(), "{output:?}");
        if let Ok(output) = output {
            assert!(
                output.status.success(),
                "{}",
                String::from_utf8_lossy(&output.stderr)
            );
        }
    }

    #[test]
    fn unsupported_atomic_scope_reports_the_call_location() {
        let source = Source::new(
            "atomic-scope.glsl",
            "shared uint value;\nvoid main() { atomicAdd(value, 1u); }\n",
        );
        let result = compile(Stage::Compute, &[source], &[], &Options::default());
        let errors = match result {
            Ok(_) => return,
            Err(errors) => errors,
        };
        let error = match errors.as_slice().first() {
            Some(error) => error,
            None => return,
        };
        assert_eq!(error.kind, ErrorKind::Unsupported);
        assert!(error.message.contains("atomicAdd"));
        assert_eq!(
            error.location,
            Some(super::Location::new("atomic-scope.glsl", 2, 13))
        );
    }

    #[test]
    fn source_limit_covers_the_complete_compile_unit() {
        let sources = [
            Source::new("main.glsl", "void main() {}\n"),
            Source::new("common.glsl", "const int value = 1;\n"),
        ];
        let options = Options {
            max_source_bytes: 20,
            ..Options::default()
        };
        let result = compile(Stage::Fragment, &sources, &[], &options);
        let errors = match result {
            Ok(_) => return,
            Err(errors) => errors,
        };
        let error = errors.as_slice().first();
        assert!(error.is_some());
        let error = match error {
            Some(error) => error,
            None => return,
        };
        assert_eq!(error.kind, ErrorKind::Input);
        assert_eq!(
            error
                .location
                .as_ref()
                .map(|location| location.file.as_str()),
            Some("main.glsl")
        );
    }
}
