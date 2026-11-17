use crate::geometry::GeometryPlan;

use super::{
    MAX_OUTPUT_PRIMITIVES, MAX_OUTPUT_VERTICES, MeshLoweringError, MeshLoweringErrorReason,
    PrimitiveTopology,
};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PrimitiveBoundary {
    EndPrimitive,
    Restart,
    ImplicitEnd,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum EmissionEvent {
    Vertex,
    EndPrimitive,
    Restart,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct TopologyAnalysis {
    pub input: PrimitiveTopology,
    pub output: PrimitiveTopology,
    pub vertices: u32,
    pub primitives: u32,
    pub boundary: PrimitiveBoundary,
    pub restart_count: u32,
}

pub fn analyze_events(
    input: PrimitiveTopology,
    output: PrimitiveTopology,
    events: &[EmissionEvent],
) -> Result<TopologyAnalysis, MeshLoweringError> {
    let mut vertices = 0_u32;
    let mut primitives = 0_u32;
    let mut pending = 0_u32;
    let mut boundary = PrimitiveBoundary::ImplicitEnd;
    let mut restart_count = 0_u32;
    for event in events {
        match event {
            EmissionEvent::Vertex => {
                vertices = vertices
                    .checked_add(1)
                    .ok_or_else(|| compute("vertex count overflow"))?;
                pending = pending
                    .checked_add(1)
                    .ok_or_else(|| compute("primitive count overflow"))?;
            }
            EmissionEvent::EndPrimitive | EmissionEvent::Restart => {
                primitives = primitives
                    .checked_add(primitive_count(output, pending))
                    .ok_or_else(|| compute("primitive count overflow"))?;
                pending = 0;
                boundary = if matches!(event, EmissionEvent::Restart) {
                    restart_count = restart_count
                        .checked_add(1)
                        .ok_or_else(|| compute("restart count overflow"))?;
                    PrimitiveBoundary::Restart
                } else {
                    PrimitiveBoundary::EndPrimitive
                };
            }
        }
    }
    primitives = primitives
        .checked_add(primitive_count(output, pending))
        .ok_or_else(|| compute("primitive count overflow"))?;
    if vertices > MAX_OUTPUT_VERTICES || primitives > MAX_OUTPUT_PRIMITIVES {
        return Err(compute("geometry output exceeds the mesh budget"));
    }
    Ok(TopologyAnalysis {
        input,
        output,
        vertices,
        primitives,
        boundary,
        restart_count,
    })
}

pub fn derive(plan: &GeometryPlan) -> Result<TopologyAnalysis, MeshLoweringError> {
    let input = parse(plan.input_topology.as_str())?;
    let output = parse(plan.output_topology.as_str())?;
    if plan.dynamic_control_flow {
        return Err(compute("dynamic geometry output ordering"));
    }
    if plan.emitted_vertices > MAX_OUTPUT_VERTICES
        || plan.emitted_primitives > MAX_OUTPUT_PRIMITIVES
    {
        return Err(compute("geometry output exceeds the mesh budget"));
    }
    let boundary = if plan.emitted_primitives == 0 {
        PrimitiveBoundary::ImplicitEnd
    } else {
        PrimitiveBoundary::EndPrimitive
    };
    Ok(TopologyAnalysis {
        input,
        output,
        vertices: plan.emitted_vertices,
        primitives: plan.emitted_primitives,
        boundary,
        restart_count: 0,
    })
}

fn primitive_count(topology: PrimitiveTopology, vertices: u32) -> u32 {
    match topology {
        PrimitiveTopology::Points => vertices,
        PrimitiveTopology::Lines => vertices / 2,
        PrimitiveTopology::LineStrip => vertices.saturating_sub(1),
        PrimitiveTopology::Triangles => vertices / 3,
        PrimitiveTopology::TriangleStrip => vertices.saturating_sub(2),
    }
}

fn parse(value: &str) -> Result<PrimitiveTopology, MeshLoweringError> {
    match value {
        "points" => Ok(PrimitiveTopology::Points),
        "lines" => Ok(PrimitiveTopology::Lines),
        "line_strip" => Ok(PrimitiveTopology::LineStrip),
        "triangles" => Ok(PrimitiveTopology::Triangles),
        "triangle_strip" => Ok(PrimitiveTopology::TriangleStrip),
        _ => Err(MeshLoweringError::new(
            MeshLoweringErrorReason::UnsupportedOrdering,
            "analyzed primitive topology is unsupported",
        )),
    }
}

fn compute(detail: &str) -> MeshLoweringError {
    MeshLoweringError::new(MeshLoweringErrorReason::ComputeCandidate, detail)
}
