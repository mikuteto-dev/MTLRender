use super::msl::generate;
use super::{
    MeshFunctionNames, MeshPipelineMetadata, MeshSideEffects, ObjectPayloadRecord, OutputRecord,
    PrimitiveTopology,
};
use crate::reflect::{InterfaceDirection, InterfaceVariable, Resource, ResourceKind};

fn metadata(topology: PrimitiveTopology, vertices: u32, primitives: u32) -> MeshPipelineMetadata {
    MeshPipelineMetadata {
        names: MeshFunctionNames {
            object: String::from("mtlr_object_fixture"),
            mesh: String::from("mtlr_mesh_fixture"),
            fragment: String::from("mtlr_fragment_fixture"),
        },
        input_topology: topology,
        output_topology: topology,
        payload: ObjectPayloadRecord {
            offset: 0,
            size: 16,
            alignment: 16,
        },
        output_vertex: OutputRecord {
            offset: 0,
            size: 16,
            stride: 16,
        },
        output_primitive: OutputRecord {
            offset: 0,
            size: 12,
            stride: 12,
        },
        output_locations: Vec::new(),
        resources: Vec::new(),
        varyings: vec![InterfaceVariable {
            direction: InterfaceDirection::Output,
            name: String::from("color"),
            ty: String::from("vec4"),
            interpolation: Some(String::from("smooth")),
            location: Some(0),
            array_length: None,
        }],
        output_vertices: vertices,
        output_primitives: primitives,
        side_effects: MeshSideEffects::default(),
    }
}

#[test]
fn emits_bounded_triangle_strip_and_reflected_varying() {
    let result = generate(&metadata(PrimitiveTopology::TriangleStrip, 4, 2), [4, 1, 1]);
    assert!(result.is_ok());
    let Ok(text) = result else { return };
    assert!(text.contains("mesh<mtlr_mesh_fixtureVertex, void, 256, 512, topology::triangle>"));
    assert!(text.contains("set_primitive_count(2u)"));
    assert!(text.contains("color [[user(locn0)]]"));
    assert!(text.contains("[[mesh]] void mtlr_mesh_fixture"));
}

#[test]
fn emits_point_and_line_primitive_types() {
    let points = generate(&metadata(PrimitiveTopology::Points, 2, 2), [2, 1, 1]);
    let lines = generate(&metadata(PrimitiveTopology::Lines, 4, 2), [2, 1, 1]);
    assert!(points.is_ok());
    assert!(lines.is_ok());
    assert!(points.ok().is_some_and(|text| {
        text.contains("mesh<mtlr_mesh_fixtureVertex, void, 256, 512, topology::point>")
    }));
    assert!(lines.ok().is_some_and(|text| {
        text.contains("mesh<mtlr_mesh_fixtureVertex, void, 256, 512, topology::line>")
    }));
}

#[test]
fn lowers_image_and_ssbo_effects_at_reflected_bindings_once_per_mesh_invocation() {
    let mut value = metadata(PrimitiveTopology::Triangles, 3, 1);
    value.resources = vec![
        Resource {
            name: String::from("target"),
            kind: ResourceKind::Image,
            dimension: String::from("2D"),
            format: String::from("rgba32f"),
            binding: 5,
            address_space: String::from("constant"),
            writable: true,
            helper: None,
            helper_version: 0,
        },
        Resource {
            name: String::from("data"),
            kind: ResourceKind::StorageBuffer,
            dimension: String::from("buffer"),
            format: String::from("raw"),
            binding: 7,
            address_space: String::from("device"),
            writable: true,
            helper: None,
            helper_version: 0,
        },
    ];
    value.side_effects.image_writes = true;
    value.side_effects.ssbo_writes = true;
    let result = generate(&value, [1, 1, 1]);
    assert!(result.is_ok());
    let Ok(text) = result else { return };
    assert!(text.contains("[[texture(5)]]"));
    assert!(text.contains("[[buffer(7)]]"));
    assert_eq!(text.matches("mtlr_image_5.write").count(), 1);
    assert_eq!(text.matches("mtlr_ssbo_7[payload.primitive_id]").count(), 2);
}

#[test]
fn lowers_integer_atomic_and_rejects_unrepresented_atomic_resources() {
    let mut value = metadata(PrimitiveTopology::Triangles, 3, 1);
    value.resources = vec![Resource {
        name: String::from("counter"),
        kind: ResourceKind::AtomicCounter,
        dimension: String::from("buffer"),
        format: String::from("r32ui"),
        binding: 9,
        address_space: String::from("device"),
        writable: false,
        helper: None,
        helper_version: 0,
    }];
    value.side_effects.atomics = true;
    let result = generate(&value, [1, 1, 1]);
    assert!(result.is_ok());
    let Ok(text) = result else { return };
    assert!(text.contains("atomic_fetch_add_explicit"));
    assert!(text.contains("memory_order_seq_cst"));
    value.resources.clear();
    let error = generate(&value, [1, 1, 1]).err();
    assert!(error.is_some_and(|item| {
        item.reason == super::MeshLoweringErrorReason::ComputeCandidate
            && item.detail.contains("atomic")
    }));
}

#[test]
fn rejects_barriers_and_order_sensitive_control_flow_for_compute() {
    let mut value = metadata(PrimitiveTopology::Triangles, 3, 1);
    value.side_effects.image_writes = true;
    value.side_effects.barriers = true;
    let error = generate(&value, [1, 1, 1]).err();
    assert!(error.is_some_and(|item| {
        item.reason == super::MeshLoweringErrorReason::ComputeCandidate
            && item.detail.contains("ordering")
    }));
    value.side_effects.barriers = false;
    value.side_effects.ordering_sensitive = true;
    assert!(generate(&value, [1, 1, 1]).is_err());
}

#[test]
fn rejects_side_effects_and_over_budget_metadata() {
    let mut effects = metadata(PrimitiveTopology::Triangles, 3, 1);
    effects.side_effects.atomics = true;
    assert!(generate(&effects, [1, 1, 1]).is_err());
    assert!(generate(&metadata(PrimitiveTopology::Triangles, 257, 1), [1, 1, 1]).is_err());
    assert!(generate(&metadata(PrimitiveTopology::Triangles, 3, 513), [1, 1, 1]).is_err());
}
