use crate::geometry::{GeometryKind, GeometryPlan, GeometrySideEffects};
use crate::geometry_gs::{GeometryGsContext, GeometryGsError};
use crate::geometry_gs_effects::{
    GeometryGsEffect, GeometryGsOrdering, emit_geometry_barrier, emit_geometry_side_effect,
    unsupported_geometry_operation, validate_side_effect_order,
};
use crate::geometry_records::{SideEffectAccess, SideEffectAccessDescriptor, SideEffectKind};

fn context() -> GeometryGsContext {
    GeometryGsContext {
        pack: String::from("pack"),
        dimension: String::from("world"),
        program: String::from("program"),
        source_stage: String::from("geometry"),
    }
}

fn plan() -> GeometryPlan {
    GeometryPlan {
        kind: GeometryKind::Compute,
        reason: String::from("test"),
        input_topology: String::from("triangles"),
        output_topology: String::from("triangles"),
        max_vertices: 3,
        emitted_vertices: 3,
        emitted_primitives: 1,
        writes_layer: false,
        dynamic_control_flow: false,
        side_effects: GeometrySideEffects {
            image_writes: true,
            ssbo_writes: true,
            atomics: false,
        },
        layer_mapping: Vec::new(),
    }
}

fn access(kind: SideEffectKind, operation: &str) -> GeometryGsEffect {
    GeometryGsEffect::Access {
        descriptor: SideEffectAccessDescriptor {
            kind,
            access: SideEffectAccess::ReadWrite,
            binding: 4,
            byte_offset: 16,
            byte_size: 4,
        },
        operation: String::from(operation),
        width: 4,
        ordering: GeometryGsOrdering::Sequential,
    }
}

#[test]
fn emits_image_and_ssbo_access_metadata_without_merging_resources() {
    let image = access(SideEffectKind::Image, "imageStore");
    let ssbo = access(SideEffectKind::Ssbo, "ssboStore");
    let image_text = emit_geometry_side_effect(&image, 3, 2).ok();
    let ssbo_text = emit_geometry_side_effect(&ssbo, 3, 3).ok();
    assert!(
        image_text
            .as_ref()
            .is_some_and(|value| value.text.contains("resource_4"))
    );
    assert!(
        ssbo_text
            .as_ref()
            .is_some_and(|value| value.source_order == 3)
    );
}

#[test]
fn emits_integer_and_float_atomic_operations_with_width_and_order() {
    let integer = access(SideEffectKind::Atomic, "atomicAdd");
    let float = GeometryGsEffect::Access {
        descriptor: SideEffectAccessDescriptor {
            kind: SideEffectKind::Atomic,
            access: SideEffectAccess::ReadWrite,
            binding: 7,
            byte_offset: 0,
            byte_size: 4,
        },
        operation: String::from("atomicAddFloat"),
        width: 4,
        ordering: GeometryGsOrdering::AcquireRelease,
    };
    assert!(
        emit_geometry_side_effect(&integer, 1, 0)
            .ok()
            .is_some_and(|value| value.text.contains("memory_order_seq_cst"))
    );
    assert!(
        emit_geometry_side_effect(&float, 1, 1)
            .ok()
            .is_some_and(|value| value.text.contains("memory_order_acq_rel"))
    );
}

#[test]
fn preserves_barriers_and_source_order_around_geometry_outputs() {
    let effects = vec![
        access(SideEffectKind::Ssbo, "ssboStore"),
        GeometryGsEffect::Barrier {
            operation: String::from("memoryBarrierBuffer"),
            ordering: GeometryGsOrdering::Sequential,
        },
        GeometryGsEffect::EmitVertex,
        access(SideEffectKind::Image, "imageStore"),
        GeometryGsEffect::EndPrimitive,
    ];
    assert!(validate_side_effect_order(&effects, &plan()).is_ok());
    assert!(
        emit_geometry_barrier("memoryBarrierBuffer", GeometryGsOrdering::Sequential, 1)
            .ok()
            .is_some_and(|value| value.text.contains("memory_order_seq_cst"))
    );
}

#[test]
fn rejects_unsupported_width_with_a_contextual_diagnostic() {
    let effect = GeometryGsEffect::Access {
        descriptor: SideEffectAccessDescriptor {
            kind: SideEffectKind::Atomic,
            access: SideEffectAccess::ReadWrite,
            binding: 2,
            byte_offset: 0,
            byte_size: 16,
        },
        operation: String::from("atomicAdd"),
        width: 16,
        ordering: GeometryGsOrdering::Sequential,
    };
    let error = emit_geometry_side_effect(&effect, 1, 0).err();
    assert!(matches!(error, Some(GeometryGsError::Unsupported { .. })));
    let diagnostic =
        unsupported_geometry_operation(&context(), "dynamic image address").to_string();
    assert!(diagnostic.contains("pack:world:program:geometry"));
    assert!(diagnostic.contains("dynamic image address"));
}
