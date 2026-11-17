use crate::geometry::GeometryPlan;

use super::topology::{TopologyAnalysis, derive};
use super::{
    MAX_OUTPUT_PRIMITIVES, MAX_OUTPUT_VERTICES, MeshLoweringError, MeshLoweringErrorReason,
    MeshSideEffects,
};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ValidationResult {
    pub topology: TopologyAnalysis,
    pub side_effects: MeshSideEffects,
}

pub fn validate(
    plan: &GeometryPlan,
    side_effects: MeshSideEffects,
) -> Result<ValidationResult, MeshLoweringError> {
    if plan.kind == crate::geometry::GeometryKind::Compute {
        return Err(compute("geometry analysis selected compute emulation"));
    }
    if plan.max_vertices == 0 && plan.emitted_vertices != 0 {
        return Err(compute("geometry output vertex bound is unknown"));
    }
    if plan.emitted_vertices > MAX_OUTPUT_VERTICES {
        return Err(compute("geometry output exceeds the vertex budget"));
    }
    if plan.emitted_primitives > MAX_OUTPUT_PRIMITIVES {
        return Err(compute("geometry output exceeds the primitive budget"));
    }
    let topology = derive(plan)?;
    Ok(ValidationResult {
        topology,
        side_effects: MeshSideEffects {
            image_writes: side_effects.image_writes || plan.side_effects.image_writes,
            ssbo_writes: side_effects.ssbo_writes || plan.side_effects.ssbo_writes,
            atomics: side_effects.atomics || plan.side_effects.atomics,
            barriers: side_effects.barriers,
            ordering_sensitive: side_effects.ordering_sensitive,
        },
    })
}

fn compute(detail: &str) -> MeshLoweringError {
    MeshLoweringError::new(MeshLoweringErrorReason::ComputeCandidate, detail)
}
