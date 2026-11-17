use super::object::{MAX_PAYLOAD_BYTES, generate};
use super::{
    MeshFunctionNames, MeshPipelineMetadata, MeshSideEffects, ObjectPayloadRecord, OutputLocation,
    OutputRecord, PrimitiveTopology,
};
use crate::reflect::{InterfaceDirection, InterfaceVariable, Resource};

fn metadata() -> MeshPipelineMetadata {
    MeshPipelineMetadata {
        names: MeshFunctionNames {
            object: String::from("mtlr_object_fixture"),
            mesh: String::from("mtlr_mesh_fixture"),
            fragment: String::from("mtlr_fragment_fixture"),
        },
        input_topology: PrimitiveTopology::Triangles,
        output_topology: PrimitiveTopology::Triangles,
        payload: ObjectPayloadRecord {
            offset: 0,
            size: 32,
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
        output_locations: vec![OutputLocation {
            name: String::from("color"),
            location: 0,
            ty: String::from("vec4"),
            interpolation: Some(String::from("smooth")),
        }],
        resources: vec![Resource {
            name: String::from("Matrices"),
            kind: crate::reflect::ResourceKind::UniformBuffer,
            dimension: String::from("buffer"),
            format: String::from("raw"),
            binding: 2,
            address_space: String::from("constant"),
            writable: false,
            helper: None,
            helper_version: 0,
        }],
        varyings: vec![InterfaceVariable {
            direction: InterfaceDirection::Output,
            name: String::from("color"),
            ty: String::from("vec4"),
            interpolation: Some(String::from("smooth")),
            location: Some(0),
            array_length: None,
        }],
        output_vertices: 3,
        output_primitives: 1,
        side_effects: MeshSideEffects::default(),
    }
}

#[test]
fn generates_stable_object_entry_and_payload_order() {
    let result = generate(&metadata(), [2, 1, 1], [3, 1, 1]);
    assert!(result.is_ok());
    let text = match result {
        Ok(value) => value,
        Err(_) => return,
    };
    assert!(text.contains("[[object]] void mtlr_object_fixture"));
    assert!(text.contains("object_data MtlrObjectPayload& payload [[payload]]"));
    assert!(text.contains("uint source_vertex;\n    uint primitive_id;\n    uint instance_id;\n    uint base_instance;"));
    assert!(text.contains("set_threadgroups_per_grid(uint3(3u, 1u, 1u))"));
    assert!(!text.contains("set_vertex"));
    assert!(!text.contains("set_primitive"));
    assert!(!text.contains("[[vertex]]"));
    assert!(!text.contains("[[fragment]]"));
}

#[test]
fn preserves_shared_reflection_contract() {
    let value = metadata();
    assert_eq!(value.varyings.len(), 1);
    assert_eq!(value.resources.len(), 1);
    assert_eq!(
        value.output_locations.first().map(|item| item.location),
        Some(0)
    );
    assert_eq!(value.names.mesh, "mtlr_mesh_fixture");
    assert_eq!(value.names.fragment, "mtlr_fragment_fixture");
}

#[test]
fn rejects_side_effects_and_invalid_dispatch() {
    let mut value = metadata();
    value.side_effects.ssbo_writes = true;
    let result = generate(&value, [1, 1, 1], [1, 1, 1]);
    assert!(result.is_ok());
    value.side_effects = MeshSideEffects::default();
    assert!(generate(&value, [0, 1, 1], [1, 1, 1]).is_err());
    assert!(generate(&value, [1, 1, 1], [1025, 1, 1]).is_err());
}

#[test]
fn rejects_payloads_outside_metal_contract() {
    let mut value = metadata();
    value.payload.size = MAX_PAYLOAD_BYTES + 4;
    assert!(generate(&value, [1, 1, 1], [1, 1, 1]).is_err());
    value.payload.size = 18;
    assert!(generate(&value, [1, 1, 1], [1, 1, 1]).is_err());
}
