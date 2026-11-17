use super::topology::analyze_events;
use super::{EmissionEvent, MeshSideEffects, PrimitiveBoundary, PrimitiveTopology, validate};
use crate::geometry::{GeometryKind, GeometryPlan, GeometrySideEffects};

fn plan(input: &str, output: &str, vertices: u32, primitives: u32) -> GeometryPlan {
    GeometryPlan {
        kind: GeometryKind::BoundedMesh,
        reason: String::from("fixture"),
        input_topology: String::from(input),
        output_topology: String::from(output),
        max_vertices: vertices,
        emitted_vertices: vertices,
        emitted_primitives: primitives,
        writes_layer: false,
        dynamic_control_flow: false,
        side_effects: GeometrySideEffects::default(),
        layer_mapping: Vec::new(),
    }
}

#[test]
fn preserves_point_line_and_triangle_topology() {
    for (input, output, vertices, primitives, expected) in [
        ("points", "points", 1, 1, PrimitiveTopology::Points),
        ("lines", "line_strip", 2, 1, PrimitiveTopology::LineStrip),
        (
            "triangles",
            "triangle_strip",
            3,
            1,
            PrimitiveTopology::TriangleStrip,
        ),
    ] {
        let result = validate(
            &plan(input, output, vertices, primitives),
            MeshSideEffects::default(),
        );
        assert!(result.is_ok());
        let Ok(result) = result else { continue };
        assert_eq!(result.topology.output, expected);
        assert_eq!(result.topology.boundary, PrimitiveBoundary::EndPrimitive);
    }
}

#[test]
fn tracks_end_and_restart_boundaries() {
    let events = [
        EmissionEvent::Vertex,
        EmissionEvent::Vertex,
        EmissionEvent::Vertex,
        EmissionEvent::EndPrimitive,
        EmissionEvent::Vertex,
        EmissionEvent::Vertex,
        EmissionEvent::Vertex,
        EmissionEvent::Restart,
    ];
    let result = analyze_events(
        PrimitiveTopology::Triangles,
        PrimitiveTopology::TriangleStrip,
        &events,
    );
    assert!(result.is_ok());
    let Ok(result) = result else { return };
    assert_eq!(result.primitives, 2);
    assert_eq!(result.boundary, PrimitiveBoundary::Restart);
    assert_eq!(result.restart_count, 1);
}

#[test]
fn accepts_zero_output_without_a_draw_primitive() {
    let result = validate(
        &plan("triangles", "triangle_strip", 0, 0),
        MeshSideEffects::default(),
    );
    assert!(result.is_ok());
    let Ok(result) = result else { return };
    assert_eq!(result.topology.boundary, PrimitiveBoundary::ImplicitEnd);
}

#[test]
fn accepts_exact_budget_boundaries() {
    let result = validate(
        &plan("triangles", "triangle_strip", 256, 512),
        MeshSideEffects::default(),
    );
    assert!(result.is_ok());
}

#[test]
fn rejects_both_budget_boundaries_above_the_limit() {
    let vertices = plan("triangles", "triangle_strip", 257, 1);
    let primitives = plan("triangles", "triangle_strip", 3, 513);
    assert_eq!(
        validate(&vertices, MeshSideEffects::default())
            .err()
            .map(|error| error.reason),
        Some(super::MeshLoweringErrorReason::ComputeCandidate)
    );
    assert_eq!(
        validate(&primitives, MeshSideEffects::default())
            .err()
            .map(|error| error.reason),
        Some(super::MeshLoweringErrorReason::ComputeCandidate)
    );
}

#[test]
fn retains_side_effect_flags_when_budget_is_valid() {
    let result = validate(
        &plan("triangles", "triangle_strip", 3, 1),
        MeshSideEffects {
            image_writes: true,
            ssbo_writes: true,
            atomics: true,
            barriers: true,
            ordering_sensitive: true,
        },
    );
    assert!(result.is_ok());
    let Ok(result) = result else { return };
    assert!(result.side_effects.any());
    assert!(result.side_effects.barriers);
    assert!(result.side_effects.ordering_sensitive);
}

#[test]
fn rejects_unknown_and_dynamic_output_as_compute() {
    let unknown = plan("unknown", "triangle_strip", 3, 1);
    let mut dynamic = plan("triangles", "triangle_strip", 3, 1);
    dynamic.dynamic_control_flow = true;
    assert_eq!(
        validate(&unknown, MeshSideEffects::default())
            .err()
            .map(|error| error.reason),
        Some(super::MeshLoweringErrorReason::UnsupportedOrdering)
    );
    assert_eq!(
        validate(&dynamic, MeshSideEffects::default())
            .err()
            .map(|error| error.reason),
        Some(super::MeshLoweringErrorReason::ComputeCandidate)
    );
}
