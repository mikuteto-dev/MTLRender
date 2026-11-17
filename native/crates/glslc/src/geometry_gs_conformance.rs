use crate::geometry::{GeometryKind, GeometryPlan, GeometrySideEffects};
use crate::geometry_gs::{
    GeometryGsAccess, GeometryGsAccessMode, GeometryGsContext, GeometryGsError, GeometryGsInput,
    GeometryGsLayer, GeometryGsPrimitiveId, GeometryGsResourceKind, GeometryGsVaryingIdentity,
    lower_geometry_gs, validate_geometry_candidate, validate_geometry_gs,
};
use crate::geometry_gs_effects::{
    GeometryGsEffect, GeometryGsOrdering, emit_geometry_barrier, emit_geometry_side_effect,
    unsupported_geometry_operation, validate_side_effect_order,
};
use crate::geometry_gs_stage::ENTRY_POINT;
use crate::geometry_gs_topology::{stable_output_range, topology_metadata};
use crate::geometry_records::{
    GS_PRIMITIVE_RECORD_STRIDE, GeometryLayoutInput, GeometryRecordLayout, PREFIX_OFFSET_STRIDE,
    PrimitiveBoundary, VS_OUTPUT_RECORD_STRIDE,
};
use crate::transform::PrimitiveTopology;
use crate::{Options, Source, Stage, compile};

fn context() -> GeometryGsContext {
    GeometryGsContext {
        pack: String::from("pack-fixture"),
        dimension: String::from("overworld"),
        program: String::from("gbuffers_test"),
        source_stage: String::from("geometry"),
    }
}

fn topology(value: &str) -> Option<PrimitiveTopology> {
    match value {
        "points" => Some(PrimitiveTopology::Points),
        "lines" => Some(PrimitiveTopology::Lines),
        "triangles" => Some(PrimitiveTopology::Triangles),
        _ => None,
    }
}

struct InputSpec<'a> {
    input_topology: &'a str,
    output_topology: &'a str,
    input_primitives: u64,
    emitted_vertices: u32,
    emitted_primitives: u32,
    varying_stride: u32,
    prefix_offset: u64,
    writes_layer: bool,
    layer: GeometryGsLayer,
    primitive_id: GeometryGsPrimitiveId,
    side_effects: GeometrySideEffects,
}

fn input(spec: InputSpec<'_>, accesses: Vec<GeometryGsAccess>) -> Option<GeometryGsInput> {
    let output_vertices = spec
        .input_primitives
        .checked_mul(u64::from(spec.emitted_vertices))?;
    let output_primitives = spec
        .input_primitives
        .checked_mul(u64::from(spec.emitted_primitives))?;
    let record_layout = GeometryRecordLayout::new(GeometryLayoutInput {
        input_primitives: spec.input_primitives,
        output_vertices,
        output_primitives,
        varying_stride: spec.varying_stride,
        device_buffer_limit: u64::MAX,
    })
    .ok()?;
    let output_stride = u32::try_from(record_layout.gs_vertices.stride).ok()?;
    Some(GeometryGsInput {
        context: context(),
        plan: GeometryPlan {
            kind: GeometryKind::Compute,
            reason: String::from("conformance"),
            input_topology: String::from(spec.input_topology),
            output_topology: String::from(spec.output_topology),
            max_vertices: spec.emitted_vertices,
            emitted_vertices: spec.emitted_vertices,
            emitted_primitives: spec.emitted_primitives,
            writes_layer: spec.writes_layer,
            dynamic_control_flow: true,
            side_effects: spec.side_effects,
            layer_mapping: Vec::new(),
        },
        input_primitives: spec.input_primitives,
        invocation_index: 0,
        prefix_offset: spec.prefix_offset,
        prefix_complete: true,
        primitive_boundary: PrimitiveBoundary::None,
        varying: GeometryGsVaryingIdentity {
            name: String::from("payload"),
            location: 3,
        },
        varying_stride: spec.varying_stride,
        primitive_id: spec.primitive_id,
        layer: spec.layer,
        output_stride,
        record_layout,
        accesses,
    })
}

fn assert_range(offset: u64, count: u64, byte_offset: u64, byte_size: u64, stride: u64) {
    assert_eq!(Some(byte_offset), offset.checked_mul(stride));
    assert_eq!(Some(byte_size), count.checked_mul(stride));
}

fn assert_record_layout(layout: &GeometryRecordLayout, varying_stride: u32) {
    assert_eq!(layout.prefix_offsets.stride, PREFIX_OFFSET_STRIDE);
    assert_eq!(layout.gs_primitives.stride, GS_PRIMITIVE_RECORD_STRIDE);
    assert_eq!(
        layout.gs_vertices.stride,
        VS_OUTPUT_RECORD_STRIDE + u64::from(varying_stride)
    );
    let buffers = [
        layout.vs_outputs,
        layout.counts,
        layout.prefix_offsets,
        layout.gs_vertices,
        layout.gs_primitives,
        layout.indirect_arguments,
    ];
    for buffer in buffers {
        assert_eq!(buffer.offset % 16, 0);
        assert!(buffer.offset.checked_add(buffer.size).is_some());
    }
    for pair in buffers.windows(2) {
        let Some(left) = pair.first() else { return };
        let Some(right) = pair.get(1) else { return };
        let Some(left_end) = left.offset.checked_add(left.size) else {
            return;
        };
        assert!(left_end <= right.offset);
    }
    let Some(last) = buffers.last() else { return };
    assert_eq!(last.offset.checked_add(last.size), Some(layout.total_size));
}

#[test]
fn accepts_topology_matrix_with_one_input_primitive() {
    for (name, width) in [("points", 1_u32), ("lines", 2_u32), ("triangles", 3_u32)] {
        let Some(contract) = input(
            InputSpec {
                input_topology: name,
                output_topology: name,
                input_primitives: 1,
                emitted_vertices: width,
                emitted_primitives: 1,
                varying_stride: 0,
                prefix_offset: 0,
                writes_layer: false,
                layer: GeometryGsLayer::Unwritten,
                primitive_id: GeometryGsPrimitiveId::Invocation,
                side_effects: GeometrySideEffects::default(),
            },
            Vec::new(),
        ) else {
            return;
        };
        assert!(validate_geometry_gs(&contract).is_ok());
        let Some(output) = lower_geometry_gs(contract).ok() else {
            return;
        };
        let Some(expected_topology) = topology(name) else {
            return;
        };
        assert_eq!(output.stage.metadata.entry_point, ENTRY_POINT);
        assert_eq!(output.metadata.input_topology, expected_topology);
        assert_eq!(output.metadata.output_topology, expected_topology);
        assert_eq!(output.metadata.compaction.output_vertices, u64::from(width));
        assert_eq!(output.metadata.compaction.output_primitives, 1);
        assert_record_layout(&output.record_layout, 0);
        assert_range(
            output.metadata.compaction.output_vertex_range.offset,
            output.metadata.compaction.output_vertex_range.count,
            output.metadata.compaction.output_vertex_range.byte_offset,
            output.metadata.compaction.output_vertex_range.byte_size,
            output.record_layout.gs_vertices.stride,
        );
        assert_range(
            output.metadata.compaction.output_primitive_range.offset,
            output.metadata.compaction.output_primitive_range.count,
            output
                .metadata
                .compaction
                .output_primitive_range
                .byte_offset,
            output.metadata.compaction.output_primitive_range.byte_size,
            output.record_layout.gs_primitives.stride,
        );
    }
}

#[test]
fn accepts_multiple_emitted_primitives_and_compaction_metadata() {
    let Some(contract) = input(
        InputSpec {
            input_topology: "triangles",
            output_topology: "triangles",
            input_primitives: 1,
            emitted_vertices: 6,
            emitted_primitives: 2,
            varying_stride: 8,
            prefix_offset: 0,
            writes_layer: false,
            layer: GeometryGsLayer::Unwritten,
            primitive_id: GeometryGsPrimitiveId::Preserved,
            side_effects: GeometrySideEffects::default(),
        },
        Vec::new(),
    ) else {
        return;
    };
    let Some(output) = lower_geometry_gs(contract).ok() else {
        return;
    };
    let compaction = output.metadata.compaction;
    assert_eq!(compaction.input_primitives, 1);
    assert_eq!(compaction.output_vertices, 6);
    assert_eq!(compaction.output_primitives, 2);
    assert_eq!(compaction.prefix_offset, 0);
    assert!(compaction.prefix_complete);
    assert_eq!(
        output.metadata.primitive_id,
        GeometryGsPrimitiveId::Preserved
    );
    assert_eq!(output.metadata.topology.varying_stride, 8);
    assert_eq!(output.metadata.output_stride, 40);
    assert!(
        output
            .stage
            .msl
            .contains("output_primitives[prefix_begin + 0u]")
    );
    assert!(
        output
            .stage
            .msl
            .contains("output_primitives[prefix_begin + 3u]")
    );
}

#[test]
fn accepts_zero_output_without_records_or_draw_work() {
    let Some(contract) = input(
        InputSpec {
            input_topology: "points",
            output_topology: "points",
            input_primitives: 1,
            emitted_vertices: 0,
            emitted_primitives: 0,
            varying_stride: 0,
            prefix_offset: 0,
            writes_layer: false,
            layer: GeometryGsLayer::Unwritten,
            primitive_id: GeometryGsPrimitiveId::Invocation,
            side_effects: GeometrySideEffects::default(),
        },
        Vec::new(),
    ) else {
        return;
    };
    let Some(output) = lower_geometry_gs(contract).ok() else {
        return;
    };
    assert_eq!(output.metadata.compaction.output_vertices, 0);
    assert_eq!(output.metadata.compaction.output_primitives, 0);
    assert_eq!(output.metadata.compaction.output_vertex_range.count, 0);
    assert_eq!(output.metadata.compaction.output_primitive_range.count, 0);
    assert!(!output.stage.msl.contains("output_vertices[prefix_begin"));
    assert!(validate_geometry_candidate(&output).is_ok());
}

#[test]
fn retains_ids_layers_and_varying_payloads_in_metadata_and_stage() {
    let Some(contract) = input(
        InputSpec {
            input_topology: "triangles",
            output_topology: "triangles",
            input_primitives: 1,
            emitted_vertices: 3,
            emitted_primitives: 1,
            varying_stride: 16,
            prefix_offset: 0,
            writes_layer: true,
            layer: GeometryGsLayer::Constant(7),
            primitive_id: GeometryGsPrimitiveId::Preserved,
            side_effects: GeometrySideEffects::default(),
        },
        Vec::new(),
    ) else {
        return;
    };
    let Some(output) = lower_geometry_gs(contract).ok() else {
        return;
    };
    assert_eq!(output.metadata.varying.name, "payload");
    assert_eq!(output.metadata.varying.location, 3);
    assert_eq!(output.metadata.layer, GeometryGsLayer::Constant(7));
    assert_eq!(
        output.metadata.primitive_id,
        GeometryGsPrimitiveId::Preserved
    );
    assert!(output.stage.msl.contains("gl_PrimitiveID"));
    assert!(output.stage.msl.contains("gl_Layer = 7;"));
    assert!(output.stage.msl.contains("output_varyings"));
    assert_eq!(output.stage.metadata.varying_stride, 16);
}

#[test]
fn preserves_all_side_effect_access_modes_and_operations() {
    let accesses = vec![
        GeometryGsAccess {
            resource: GeometryGsResourceKind::Image,
            binding: 1,
            mode: GeometryGsAccessMode::ReadWrite,
            operation: String::from("imageStore"),
        },
        GeometryGsAccess {
            resource: GeometryGsResourceKind::Ssbo,
            binding: 2,
            mode: GeometryGsAccessMode::Write,
            operation: String::from("ssboStore"),
        },
        GeometryGsAccess {
            resource: GeometryGsResourceKind::Atomic,
            binding: 3,
            mode: GeometryGsAccessMode::ReadWrite,
            operation: String::from("atomicAdd"),
        },
        GeometryGsAccess {
            resource: GeometryGsResourceKind::Atomic,
            binding: 4,
            mode: GeometryGsAccessMode::ReadWrite,
            operation: String::from("atomicAddFloat"),
        },
    ];
    let Some(contract) = input(
        InputSpec {
            input_topology: "triangles",
            output_topology: "triangles",
            input_primitives: 1,
            emitted_vertices: 3,
            emitted_primitives: 1,
            varying_stride: 0,
            prefix_offset: 0,
            writes_layer: false,
            layer: GeometryGsLayer::Unwritten,
            primitive_id: GeometryGsPrimitiveId::Invocation,
            side_effects: GeometrySideEffects {
                image_writes: true,
                ssbo_writes: true,
                atomics: true,
            },
        },
        accesses,
    ) else {
        return;
    };
    let Some(output) = lower_geometry_gs(contract).ok() else {
        return;
    };
    assert_eq!(output.metadata.accesses.len(), 4);
    for access in &output.metadata.accesses {
        assert!(!access.operation.is_empty());
    }
    let Some(image) = output.metadata.accesses.first() else {
        return;
    };
    assert_eq!(image.resource, GeometryGsResourceKind::Image);
    assert_eq!(image.mode, GeometryGsAccessMode::ReadWrite);
    let Some(float_atomic) = output.metadata.accesses.get(3) else {
        return;
    };
    assert_eq!(float_atomic.operation, "atomicAddFloat");
    assert_eq!(output.stage.metadata.accesses.len(), 5);
    assert!(
        output
            .stage
            .metadata
            .accesses
            .iter()
            .any(|access| access.binding == 0 && access.read && !access.write)
    );
    assert!(
        output
            .stage
            .metadata
            .accesses
            .iter()
            .any(|access| access.binding == 1 && access.read && !access.write)
    );
    assert!(
        output
            .stage
            .metadata
            .accesses
            .iter()
            .any(|access| access.binding == 2 && !access.read && access.write)
    );
    assert!(
        output
            .stage
            .metadata
            .accesses
            .iter()
            .any(|access| access.binding == 3 && !access.read && access.write)
    );
    assert!(
        output
            .stage
            .metadata
            .accesses
            .iter()
            .any(|access| access.binding == 4 && !access.read && access.write)
    );
}

#[test]
fn keeps_prefix_boundaries_disjoint_for_adjacent_invocations() {
    let Some(layout) = GeometryRecordLayout::new(GeometryLayoutInput {
        input_primitives: 2,
        output_vertices: 2,
        output_primitives: 2,
        varying_stride: 0,
        device_buffer_limit: u64::MAX,
    })
    .ok() else {
        return;
    };
    let Some(metadata) = topology_metadata(
        PrimitiveTopology::Points,
        PrimitiveTopology::Points,
        &layout,
        0,
    )
    .ok() else {
        return;
    };
    let Some(first) = stable_output_range(metadata, 0, 2, 2, 0, 1, 1).ok() else {
        return;
    };
    let Some(second) = stable_output_range(metadata, 1, 2, 2, 1, 1, 1).ok() else {
        return;
    };
    assert!(first.0.offset + first.0.count <= second.0.offset);
    assert!(first.1.offset + first.1.count <= second.1.offset);
    assert!(first.0.byte_offset + first.0.byte_size <= second.0.byte_offset);
    assert!(first.1.byte_offset + first.1.byte_size <= second.1.byte_offset);
}

#[test]
fn preserves_barrier_order_and_rejects_unsupported_operations_with_context() {
    let effects = vec![
        GeometryGsEffect::Access {
            descriptor: crate::geometry_records::SideEffectAccessDescriptor {
                kind: crate::geometry_records::SideEffectKind::Ssbo,
                access: crate::geometry_records::SideEffectAccess::Write,
                binding: 2,
                byte_offset: 0,
                byte_size: 4,
            },
            operation: String::from("ssboStore"),
            width: 4,
            ordering: GeometryGsOrdering::Relaxed,
        },
        GeometryGsEffect::Barrier {
            operation: String::from("memoryBarrierBuffer"),
            ordering: GeometryGsOrdering::Sequential,
        },
        GeometryGsEffect::OutputWrite,
    ];
    let plan = GeometryPlan {
        kind: GeometryKind::Compute,
        reason: String::from("ordered"),
        input_topology: String::from("triangles"),
        output_topology: String::from("triangles"),
        max_vertices: 3,
        emitted_vertices: 3,
        emitted_primitives: 1,
        writes_layer: false,
        dynamic_control_flow: true,
        side_effects: GeometrySideEffects {
            image_writes: false,
            ssbo_writes: true,
            atomics: false,
        },
        layer_mapping: Vec::new(),
    };
    assert!(validate_side_effect_order(&effects, &plan).is_ok());
    let Some(barrier) =
        emit_geometry_barrier("memoryBarrierBuffer", GeometryGsOrdering::Sequential, 9).ok()
    else {
        return;
    };
    assert_eq!(barrier.source_order, 9);
    assert!(barrier.text.contains("memory_order_seq_cst"));
    let Some(effect) = effects.first() else {
        return;
    };
    let Some(store) = emit_geometry_side_effect(effect, 1, 8).ok() else {
        return;
    };
    assert_eq!(store.source_order, 8);
    let diagnostic =
        unsupported_geometry_operation(&context(), "dynamic image resource binding").to_string();
    assert!(diagnostic.contains("pack-fixture:overworld:gbuffers_test:geometry"));
    assert!(diagnostic.contains("dynamic image resource binding"));
    let result = compile(
        Stage::Geometry,
        &[Source::new(
            "diagnostic.gsh",
            "layout(triangles) in vec4 inputData[]; layout(triangle_strip, max_vertices = 3) out vec4 outputData; layout(rgba32f, binding = 1) uniform image2D target; uniform int value; void main() { if (value > 0) { imageStore(target, ivec2(0), vec4(1.0)); } EmitVertex(); EmitVertex(); EmitVertex(); EndPrimitive(); }",
        )],
        &[],
        &Options::default(),
    );
    let Err(errors) = result else { return };
    let Some(error) = errors.as_slice().first() else {
        return;
    };
    assert_eq!(error.kind, crate::ErrorKind::Unsupported);
    assert!(
        error
            .message
            .contains("diagnostic.gsh:unknown:geometry:geometry")
    );
    assert!(error.message.contains("dynamic side-effect control flow"));
}

#[test]
fn rejects_invalid_ranges_and_reports_context() {
    let Some(contract) = input(
        InputSpec {
            input_topology: "triangles",
            output_topology: "triangles",
            input_primitives: 1,
            emitted_vertices: 3,
            emitted_primitives: 1,
            varying_stride: 0,
            prefix_offset: 0,
            writes_layer: false,
            layer: GeometryGsLayer::Unwritten,
            primitive_id: GeometryGsPrimitiveId::Invocation,
            side_effects: GeometrySideEffects::default(),
        },
        Vec::new(),
    ) else {
        return;
    };
    let mut output = match lower_geometry_gs(contract).ok() {
        Some(value) => value,
        None => return,
    };
    output.metadata.compaction.output_vertex_range.byte_size = 1;
    let error = validate_geometry_candidate(&output).err();
    assert!(matches!(error, Some(GeometryGsError::Invalid { .. })));
    let Some(error) = error else { return };
    assert!(error.to_string().contains("candidate byte ranges"));
}

#[test]
fn translated_geometry_fixtures_keep_render_and_compute_paths_separate() {
    for (name, source, topology_name) in [
        (
            "gbuffers_basic.gsh",
            include_str!(
                "../../../../mod/run/shaderpacks/geometry-fixtures/shaders/gbuffers_basic.gsh"
            ),
            "triangle_strip",
        ),
        (
            "gbuffers_line.gsh",
            include_str!(
                "../../../../mod/run/shaderpacks/geometry-fixtures/shaders/gbuffers_line.gsh"
            ),
            "line_strip",
        ),
        (
            "gbuffers_textured.gsh",
            include_str!(
                "../../../../mod/run/shaderpacks/geometry-fixtures/shaders/gbuffers_textured.gsh"
            ),
            "points",
        ),
    ] {
        let result = compile(
            Stage::Geometry,
            &[Source::new(name, source)],
            &[],
            &Options::default(),
        );
        let Ok(compiled) = result else {
            return;
        };
        let Some(plan) = compiled.reflection.geometry_plan.as_ref() else {
            return;
        };
        assert_eq!(plan.kind, GeometryKind::BoundedMesh);
        assert_eq!(plan.output_topology, topology_name);
        assert!(compiled.msl.is_empty());
    }
    let layered = compile(
        Stage::Geometry,
        &[Source::new(
            "gbuffers_layered.gsh",
            include_str!(
                "../../../../mod/run/shaderpacks/geometry-fixtures/shaders/gbuffers_layered.gsh"
            ),
        )],
        &[],
        &Options::default(),
    );
    let Ok(layered) = layered else { return };
    let Some(layered_reflection) = layered.reflection.geometry.as_ref() else {
        return;
    };
    assert_eq!(layered_reflection.kind, "Layered");
    assert_eq!(layered_reflection.layer_mapping, vec![1, 2, 3]);
    assert!(layered.msl.is_empty());
    let ordinary_vertex = compile(
        Stage::Vertex,
        &[Source::new(
            "ordinary.vsh",
            "layout(location = 0) in vec3 position; void main() { gl_Position = vec4(position, 1.0); }",
        )],
        &[],
        &Options::default(),
    );
    let Ok(ordinary_vertex) = ordinary_vertex else {
        return;
    };
    assert!(!ordinary_vertex.msl.contains(ENTRY_POINT));
    assert!(ordinary_vertex.reflection.geometry_plan.is_none());
}

#[test]
fn over_budget_geometry_selects_compute_candidate_with_stage_metadata() {
    let result = compile(
        Stage::Geometry,
        &[Source::new(
            "over-budget.gsh",
            "layout(triangles) in vec4 inputData[]; layout(triangle_strip, max_vertices = 257) out vec4 outputData; uniform int value; void main() { if (value > 0) { EmitVertex(); EmitVertex(); EmitVertex(); EndPrimitive(); } }",
        )],
        &[],
        &Options::default(),
    );
    let Ok(compiled) = result else { return };
    let Some(plan) = compiled.reflection.geometry_plan.as_ref() else {
        return;
    };
    assert_eq!(plan.kind, GeometryKind::Compute);
    assert_eq!(plan.max_vertices, 257);
    assert!(compiled.msl.contains(ENTRY_POINT));
    assert!(compiled.msl.contains("prefix_offsets[primitive_id]"));
}

#[test]
fn geometry_compute_fixture_reports_budget_effects_and_compaction_contract() {
    let source = include_str!(
        "../../../../mod/run/shaderpacks/geometry-fixtures/shaders/gbuffers_compute.gsh"
    );
    let result = compile(
        Stage::Geometry,
        &[Source::new("gbuffers_compute.gsh", source)],
        &[],
        &Options::default(),
    );
    let Ok(compiled) = result else { return };
    let Some(plan) = compiled.reflection.geometry_plan.as_ref() else {
        return;
    };
    assert_eq!(plan.kind, GeometryKind::Compute);
    assert_eq!(plan.max_vertices, 257);
    assert_eq!(plan.emitted_vertices, 9);
    assert_eq!(plan.emitted_primitives, 3);
    assert!(plan.writes_layer);
    assert!(plan.side_effects.image_writes);
    assert!(plan.side_effects.ssbo_writes);
    assert!(plan.side_effects.atomics);
    assert!(compiled.msl.contains("prefix_offsets[primitive_id]"));
}

#[test]
fn geometry_zero_output_fixture_preserves_side_effects() {
    let source =
        include_str!("../../../../mod/run/shaderpacks/geometry-fixtures/shaders/gbuffers_zero.gsh");
    let result = compile(
        Stage::Geometry,
        &[Source::new("gbuffers_zero.gsh", source)],
        &[],
        &Options::default(),
    );
    let Ok(compiled) = result else { return };
    let Some(plan) = compiled.reflection.geometry_plan.as_ref() else {
        return;
    };
    assert_eq!(plan.kind, GeometryKind::Compute);
    assert_eq!(plan.emitted_vertices, 0);
    assert_eq!(plan.emitted_primitives, 0);
    assert!(plan.writes_layer);
    assert!(plan.side_effects.image_writes);
    assert!(plan.side_effects.ssbo_writes);
    assert!(plan.side_effects.atomics);
}

#[test]
fn validation_rejects_non_compute_candidates_before_runtime_orchestration() {
    let Some(mut contract) = input(
        InputSpec {
            input_topology: "triangles",
            output_topology: "triangles",
            input_primitives: 1,
            emitted_vertices: 3,
            emitted_primitives: 1,
            varying_stride: 0,
            prefix_offset: 0,
            writes_layer: false,
            layer: GeometryGsLayer::Unwritten,
            primitive_id: GeometryGsPrimitiveId::Invocation,
            side_effects: GeometrySideEffects::default(),
        },
        Vec::new(),
    ) else {
        return;
    };
    contract.plan.kind = GeometryKind::BoundedMesh;
    let error = validate_geometry_gs(&contract).err();
    assert!(matches!(error, Some(GeometryGsError::Unsupported { .. })));
    let Some(error) = error else { return };
    let message = error.to_string();
    assert!(message.contains("pack-fixture:overworld:gbuffers_test:geometry"));
    assert!(message.contains("geometry plan is not compute emulation"));
}
