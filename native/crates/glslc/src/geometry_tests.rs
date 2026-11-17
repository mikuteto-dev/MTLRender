use crate::{
    Options, Source, Stage, compile,
    geometry::{GeometryKind, GeometryPlan},
};

fn compile_fixture(source: &str) -> GeometryPlan {
    let result = compile(
        Stage::Geometry,
        &[Source::new("fixture.gsh", source)],
        &[],
        &Options::default(),
    );
    assert!(result.is_ok());
    let Ok(compiled) = result else {
        return empty_plan();
    };
    let Some(plan) = compiled.reflection.geometry_plan else {
        return empty_plan();
    };
    plan
}

fn empty_plan() -> GeometryPlan {
    GeometryPlan {
        kind: GeometryKind::Compute,
        reason: String::from("missing"),
        input_topology: String::new(),
        output_topology: String::new(),
        max_vertices: 0,
        emitted_vertices: 0,
        emitted_primitives: 0,
        writes_layer: false,
        dynamic_control_flow: true,
        side_effects: crate::geometry::GeometrySideEffects::default(),
        layer_mapping: Vec::new(),
    }
}

#[test]
fn classifies_pass_through() {
    let result = compile_fixture(
        "layout(triangles) in vec4 inputData[]; layout(triangle_strip, max_vertices = 3) out vec4 outputData; void main() { EmitVertex(); EmitVertex(); EmitVertex(); EndPrimitive(); }",
    );
    assert_eq!(result.kind, GeometryKind::PassThrough);
    assert_eq!(
        result.reason,
        "pass-through: primitive topology and vertex emission are unchanged"
    );
}

#[test]
fn accepts_point_and_line_pass_through_topologies() {
    for (source, topology) in [
        (
            "layout(points) in vec4 inputData[]; layout(points, max_vertices = 1) out vec4 outputData; void main() { EmitVertex(); EndPrimitive(); }",
            "points",
        ),
        (
            "layout(lines) in vec4 inputData[]; layout(line_strip, max_vertices = 2) out vec4 outputData; void main() { EmitVertex(); EmitVertex(); EndPrimitive(); }",
            "line_strip",
        ),
    ] {
        let result = compile_fixture(source);
        assert_eq!(result.kind, GeometryKind::PassThrough);
        assert_eq!(result.output_topology, topology);
    }
}

#[test]
fn lowers_instance_derived_layer_to_mapping_metadata() {
    let result = compile(
        Stage::Geometry,
        &[Source::new(
            "fixture.gsh",
            "layout(triangles, invocations = 3) in vec4 inputData[]; layout(triangle_strip, max_vertices = 3) out vec4 outputData; void main() { gl_Layer = gl_InvocationID + 1; EmitVertex(); EmitVertex(); EmitVertex(); EndPrimitive(); }",
        )],
        &[],
        &Options::default(),
    );
    assert!(result.is_ok());
    let Ok(compiled) = result else { return };
    let Some(plan) = compiled.reflection.geometry_plan else {
        return;
    };
    assert_eq!(plan.kind, GeometryKind::Layered);
    assert_eq!(plan.layer_mapping, vec![1, 2, 3]);
    assert!(compiled.msl.is_empty());
}

#[test]
fn accepts_constant_layer_as_one_instance() {
    let result = compile_fixture(
        "layout(triangles) in vec4 inputData[]; layout(triangle_strip, max_vertices = 3) out vec4 outputData; void main() { gl_Layer = 2; EmitVertex(); EmitVertex(); EmitVertex(); EndPrimitive(); }",
    );
    assert_eq!(result.kind, GeometryKind::Layered);
    assert_eq!(result.layer_mapping, vec![2]);
}

#[test]
fn accepts_multiple_constant_layer_programs_without_extra_instances() {
    for (source, layer) in [
        ("gl_Layer = 0;", 0),
        ("gl_Layer = 7;", 7),
        ("gl_Layer = 2047;", 2047),
    ] {
        let result = compile_fixture(&format!(
            "layout(triangles) in vec4 inputData[]; layout(triangle_strip, max_vertices = 3) out vec4 outputData; void main() {{ {source} EmitVertex(); EmitVertex(); EmitVertex(); EndPrimitive(); }}"
        ));
        assert_eq!(result.kind, GeometryKind::Layered);
        assert_eq!(result.layer_mapping, vec![layer]);
    }
}

#[test]
fn rejects_out_of_range_layer_mapping() {
    let result = compile_fixture(
        "layout(triangles) in vec4 inputData[]; layout(triangle_strip, max_vertices = 3) out vec4 outputData; void main() { gl_Layer = 2048; EmitVertex(); EmitVertex(); EmitVertex(); EndPrimitive(); }",
    );
    assert_ne!(result.kind, GeometryKind::Layered);
    assert!(result.layer_mapping.is_empty());
}

#[test]
fn rejects_layered_lowering_when_side_effects_are_present() {
    let result = compile_fixture(
        "layout(triangles) in vec4 inputData[]; layout(triangle_strip, max_vertices = 3) out vec4 outputData; layout(rgba32f, binding = 1) uniform image2D target; void main() { gl_Layer = 1; imageStore(target, ivec2(0), vec4(1.0)); EmitVertex(); EmitVertex(); EmitVertex(); EndPrimitive(); }",
    );
    assert_ne!(result.kind, GeometryKind::Layered);
    assert!(result.side_effects.image_writes);
}

#[test]
fn rejects_multiple_primitives_during_lowering() {
    let result = compile(
        Stage::Geometry,
        &[Source::new(
            "fixture.gsh",
            "layout(triangles) in vec4 inputData[]; layout(triangle_strip, max_vertices = 6) out vec4 outputData; void main() { EmitVertex(); EmitVertex(); EmitVertex(); EndPrimitive(); EmitVertex(); EmitVertex(); EmitVertex(); EndPrimitive(); }",
        )],
        &[],
        &Options::default(),
    );
    assert!(result.is_ok());
    let Ok(compiled) = result else { return };
    let Some(plan) = compiled.reflection.geometry_plan else {
        return;
    };
    assert_eq!(plan.kind, GeometryKind::BoundedMesh);
}

#[test]
fn preserves_side_effect_resource_metadata_during_lowering() {
    let result = compile(
        Stage::Geometry,
        &[Source::new(
            "fixture.gsh",
            "layout(triangles) in vec4 inputData[]; layout(triangle_strip, max_vertices = 3) out vec4 outputData; layout(rgba32f, binding = 1) uniform image2D target; void main() { imageStore(target, ivec2(0), vec4(1.0)); EmitVertex(); EmitVertex(); EmitVertex(); EndPrimitive(); }",
        )],
        &[],
        &Options::default(),
    );
    assert!(result.is_ok());
    let Ok(compiled) = result else { return };
    assert!(compiled.msl.contains("side_effect_storage"));
}

#[test]
fn lowers_dynamic_flow_to_distinct_compute_entries() {
    let result = compile(
        Stage::Geometry,
        &[Source::new(
            "fixture.gsh",
            "layout(triangles) in vec4 inputData[]; layout(triangle_strip, max_vertices = 3) out vec4 outputData; uniform int value; void main() { if (value > 0) { EmitVertex(); } EndPrimitive(); }",
        )],
        &[],
        &Options::default(),
    );
    assert!(result.is_ok());
    let Ok(compiled) = result else { return };
    assert!(compiled.msl.contains("mtlr_geometry_vs_record_stage"));
    assert!(compiled.msl.contains("mtlr_geometry_count_stage"));
}
