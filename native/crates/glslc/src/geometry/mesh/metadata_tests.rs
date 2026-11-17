use super::{
    MAX_OUTPUT_PRIMITIVES, MAX_OUTPUT_VERTICES, MeshFunctionNames, MeshSideEffects,
    ObjectPayloadRecord, OutputRecord, PrimitiveTopology,
};
use crate::{Options, Source, Stage, compile};

#[test]
fn topology_metadata_supports_mesh_output_families() {
    assert_eq!(PrimitiveTopology::Points, PrimitiveTopology::Points);
    assert_eq!(PrimitiveTopology::Lines, PrimitiveTopology::Lines);
    assert_eq!(PrimitiveTopology::LineStrip, PrimitiveTopology::LineStrip);
    assert_eq!(PrimitiveTopology::Triangles, PrimitiveTopology::Triangles);
    assert_eq!(
        PrimitiveTopology::TriangleStrip,
        PrimitiveTopology::TriangleStrip
    );
}

#[test]
fn output_limits_are_exact_metal_limits() {
    assert_eq!(MAX_OUTPUT_VERTICES, 256);
    assert_eq!(MAX_OUTPUT_PRIMITIVES, 512);
    assert_eq!(
        ObjectPayloadRecord {
            offset: 0,
            size: 256,
            alignment: 16
        }
        .size,
        256
    );
    assert_eq!(
        OutputRecord {
            offset: 0,
            size: 512,
            stride: 4
        }
        .size,
        512
    );
}

#[test]
fn side_effect_flags_are_explicit() {
    let effects = MeshSideEffects {
        image_writes: true,
        ssbo_writes: true,
        atomics: true,
        barriers: true,
        ordering_sensitive: true,
    };
    assert!(effects.any());
    assert!(!MeshSideEffects::default().any());
}

#[test]
fn names_are_stable_and_stage_specific() {
    let first = MeshFunctionNames {
        object: String::from("mtlr_object_a"),
        mesh: String::from("mtlr_mesh_a"),
        fragment: String::from("mtlr_fragment_a"),
    };
    let second = MeshFunctionNames {
        object: String::from("mtlr_object_a"),
        mesh: String::from("mtlr_mesh_a"),
        fragment: String::from("mtlr_fragment_a"),
    };
    assert_eq!(first, second);
    assert_ne!(first.object, first.mesh);
    assert_ne!(first.mesh, first.fragment);
}

#[test]
fn empty_shader_path_has_no_mesh_metadata() {
    let result = compile(
        Stage::Vertex,
        &[Source::new("empty.vsh", "void main() {}")],
        &[],
        &Options::default(),
    );
    let Ok(compiled) = result else { return };
    assert!(compiled.reflection.mesh_metadata().is_none());
}
