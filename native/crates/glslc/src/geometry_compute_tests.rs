use crate::geometry::{GeometryKind, GeometryPlan, GeometrySideEffects};
use crate::geometry_compute::{Context, Error, GeometryComputePlan, Input, Limits, StageKind};

fn geometry(effects: GeometrySideEffects) -> GeometryPlan {
    GeometryPlan {
        kind: GeometryKind::Compute,
        reason: String::from("fixture"),
        input_topology: String::from("triangles"),
        output_topology: String::from("triangle_strip"),
        max_vertices: 257,
        emitted_vertices: 257,
        emitted_primitives: 513,
        writes_layer: false,
        dynamic_control_flow: true,
        side_effects: effects,
        layer_mapping: Vec::new(),
    }
}

fn plan(limit: u64) -> Result<GeometryComputePlan, Error> {
    GeometryComputePlan::build(
        Context {
            pack: String::from("fixture"),
            dimension: String::from("overworld"),
            program: String::from("gbuffers"),
            stage: String::from("geometry"),
        },
        &geometry(GeometrySideEffects::default()),
        Input {
            primitive_count: 2,
            vertex_stride: 16,
            primitive_stride: 12,
        },
        Limits {
            max_buffer_size: limit,
        },
    )
}

#[test]
fn stage_order_and_layout_are_deterministic() {
    let result = plan(100_000);
    assert!(result.is_ok());
    let Ok(result) = result else { return };
    assert_eq!(
        result
            .stages
            .iter()
            .map(|stage| stage.kind)
            .collect::<Vec<_>>(),
        vec![
            StageKind::VertexPrepass,
            StageKind::OutputCount,
            StageKind::PrefixSum,
            StageKind::GeometryExpansion,
            StageKind::IndirectDraw,
        ]
    );
    let sizes = result
        .buffers
        .iter()
        .map(|buffer| buffer.size)
        .collect::<Vec<_>>();
    assert_eq!(sizes, vec![24, 8224, 12312, 8, 8, 8224, 12312, 20]);
    let offsets = result
        .buffers
        .iter()
        .map(|buffer| buffer.offset)
        .collect::<Vec<_>>();
    assert_eq!(
        offsets,
        vec![0, 24, 8248, 20560, 20568, 20576, 28800, 41112]
    );
}

#[test]
fn side_effects_are_present_on_shader_stages() {
    let effects = GeometrySideEffects {
        image_writes: true,
        ssbo_writes: true,
        atomics: true,
    };
    let result = GeometryComputePlan::build(
        Context {
            pack: String::from("fixture"),
            dimension: String::from("overworld"),
            program: String::from("p"),
            stage: String::from("g"),
        },
        &geometry(effects),
        Input {
            primitive_count: 1,
            vertex_stride: 4,
            primitive_stride: 4,
        },
        Limits {
            max_buffer_size: 100_000,
        },
    );
    assert!(result.is_ok());
    let Ok(result) = result else { return };
    assert_eq!(
        result.stages.first().map(|stage| stage.side_effects),
        Some(effects)
    );
    assert_eq!(
        result.stages.get(3).map(|stage| stage.side_effects),
        Some(effects)
    );
}

#[test]
fn rejects_overflow_and_device_limits_and_skips_empty_draws() {
    let overflow = GeometryComputePlan::build(
        Context {
            pack: String::from("p"),
            dimension: String::from("d"),
            program: String::from("g"),
            stage: String::from("s"),
        },
        &geometry(GeometrySideEffects::default()),
        Input {
            primitive_count: u64::MAX,
            vertex_stride: 4,
            primitive_stride: 4,
        },
        Limits {
            max_buffer_size: u64::MAX,
        },
    );
    assert_eq!(overflow, Err(Error::Overflow));
    assert_eq!(plan(1), Err(Error::DeviceLimit));
    let result = plan(100_000);
    assert!(result.is_ok());
    let Ok(result) = result else { return };
    assert!(result.draw_arguments(0, 1).is_none());
    assert!(result.draw_arguments(1, 0).is_none());
    assert_eq!(
        result.draw_arguments(3, 1).map(|args| args.index_count),
        Some(3)
    );
}
