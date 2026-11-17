use crate::geometry::GeometrySideEffects;
use crate::geometry_vs::ResourceAccess;
use crate::transform::PrimitiveTopology;

pub const ENTRY_POINT: &str = "mtlr_geometry_count_stage";
pub const LOCAL_SIZE: [u32; 3] = [64, 1, 1];

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CountInput {
    pub input_records: u64,
    pub input_vertex_stride: u32,
    pub output_stride: u32,
    pub device_buffer_limit: u64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PrimitiveBoundary {
    pub restart: bool,
    pub end: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CountResult {
    pub vertices: u32,
    pub primitives: u32,
}

#[derive(Debug, Eq, PartialEq)]
pub struct CountMetadata {
    pub entry_point: String,
    pub local_size: [u32; 3],
    pub dispatch: [u64; 3],
    pub input_stride: u32,
    pub output_stride: u32,
    pub resources: Vec<ResourceAccess>,
    pub side_effects: GeometrySideEffects,
}

#[derive(Debug, Eq, PartialEq)]
pub struct CountStage {
    pub msl: String,
    pub metadata: CountMetadata,
}

#[derive(Debug, Eq, PartialEq)]
pub enum Error {
    InvalidInput(&'static str),
    Overflow(&'static str),
    DeviceLimit { requested: u64, limit: u64 },
}

pub fn count(
    topology: PrimitiveTopology,
    vertices: &[PrimitiveBoundary],
) -> Result<CountResult, Error> {
    let mut available = 0_u32;
    let mut primitives = 0_u32;
    let mut output_vertices = 0_u32;
    let mut segment_started = false;
    let width = match topology {
        PrimitiveTopology::Points => 1,
        PrimitiveTopology::Lines | PrimitiveTopology::LineStrip => 2,
        PrimitiveTopology::Triangles | PrimitiveTopology::TriangleStrip => 3,
    };
    let strip = matches!(
        topology,
        PrimitiveTopology::LineStrip | PrimitiveTopology::TriangleStrip
    );
    for boundary in vertices {
        if boundary.restart {
            available = 0;
            segment_started = false;
            continue;
        }
        available = available
            .checked_add(1)
            .ok_or(Error::Overflow("primitive vertex count"))?;
        if available >= width {
            primitives = primitives
                .checked_add(1)
                .ok_or(Error::Overflow("primitive output count"))?;
            if strip {
                let added = if segment_started { 1 } else { width };
                output_vertices = output_vertices
                    .checked_add(added)
                    .ok_or(Error::Overflow("strip output vertices"))?;
                segment_started = true;
            } else {
                output_vertices = output_vertices
                    .checked_add(width)
                    .ok_or(Error::Overflow("output vertices"))?;
                available = 0;
            }
        }
        if boundary.end {
            available = 0;
            segment_started = false;
        }
    }
    let vertices = output_vertices;
    Ok(CountResult {
        vertices,
        primitives,
    })
}

pub fn lower(
    topology: PrimitiveTopology,
    input: CountInput,
    side_effects: GeometrySideEffects,
) -> Result<CountStage, Error> {
    if input.input_records == 0 || input.input_vertex_stride == 0 || input.output_stride == 0 {
        return Err(Error::InvalidInput(
            "geometry count input and strides must be non-zero",
        ));
    }
    let input_bytes = input
        .input_records
        .checked_mul(u64::from(input.input_vertex_stride))
        .ok_or(Error::Overflow("count input buffer"))?;
    let output_bytes = input
        .input_records
        .checked_mul(u64::from(input.output_stride))
        .ok_or(Error::Overflow("count output buffer"))?;
    let requested = input_bytes
        .checked_add(output_bytes)
        .ok_or(Error::Overflow("count buffers"))?;
    if requested > input.device_buffer_limit {
        return Err(Error::DeviceLimit {
            requested,
            limit: input.device_buffer_limit,
        });
    }
    let groups = input
        .input_records
        .checked_add(u64::from(LOCAL_SIZE[0] - 1))
        .ok_or(Error::Overflow("geometry count dispatch"))?
        / u64::from(LOCAL_SIZE[0]);
    let metadata = CountMetadata {
        entry_point: String::from(ENTRY_POINT),
        local_size: LOCAL_SIZE,
        dispatch: [groups, 1, 1],
        input_stride: input.input_vertex_stride,
        output_stride: input.output_stride,
        resources: resources(side_effects),
        side_effects,
    };
    let topology_code = match topology {
        PrimitiveTopology::Points => "1u",
        PrimitiveTopology::Lines => "2u",
        PrimitiveTopology::LineStrip => "2u",
        PrimitiveTopology::Triangles => "3u",
        PrimitiveTopology::TriangleStrip => "3u",
    };
    let mut msl = String::from("#include <metal_stdlib>\nusing namespace metal;\n\n");
    msl.push_str("kernel void ");
    msl.push_str(ENTRY_POINT);
    msl.push_str("(const device uint *vertex_counts [[buffer(0)]], const device uint *boundary_flags [[buffer(1)]], device uint2 *counts [[buffer(2)]], uint3 gid [[thread_position_in_grid]]) { uint i = gid.x; if (i >= ");
    msl.push_str(&input.input_records.to_string());
    msl.push_str("u) return; uint width = ");
    msl.push_str(topology_code);
    msl.push_str("; uint count = vertex_counts[i]; uint flags = boundary_flags[i]; uint primitives = ((flags & 1u) != 0u || count < width) ? 0u : count - width + 1u; counts[i] = uint2(count, primitives); }\n");
    Ok(CountStage { msl, metadata })
}

fn resources(side_effects: GeometrySideEffects) -> Vec<ResourceAccess> {
    let mut result = vec![
        ResourceAccess {
            binding: 0,
            read: true,
            write: false,
            atomic: false,
        },
        ResourceAccess {
            binding: 1,
            read: true,
            write: false,
            atomic: false,
        },
        ResourceAccess {
            binding: 2,
            read: false,
            write: true,
            atomic: false,
        },
    ];
    if side_effects.image_writes || side_effects.ssbo_writes {
        result.push(ResourceAccess {
            binding: 8,
            read: false,
            write: true,
            atomic: false,
        });
    }
    if side_effects.atomics {
        result.push(ResourceAccess {
            binding: 9,
            read: true,
            write: true,
            atomic: true,
        });
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input(end: bool) -> Vec<PrimitiveBoundary> {
        vec![
            PrimitiveBoundary {
                restart: false,
                end,
            },
            PrimitiveBoundary {
                restart: false,
                end: false,
            },
            PrimitiveBoundary {
                restart: false,
                end: false,
            },
        ]
    }

    #[test]
    fn counts_all_topologies_and_zero_output() {
        assert_eq!(
            count(PrimitiveTopology::Points, &input(false)).ok(),
            Some(CountResult {
                vertices: 3,
                primitives: 3
            })
        );
        assert_eq!(
            count(PrimitiveTopology::Lines, &input(false)).ok(),
            Some(CountResult {
                vertices: 2,
                primitives: 1
            })
        );
        assert_eq!(
            count(PrimitiveTopology::Triangles, &input(false)).ok(),
            Some(CountResult {
                vertices: 3,
                primitives: 1
            })
        );
        assert_eq!(
            count(
                PrimitiveTopology::TriangleStrip,
                &[PrimitiveBoundary {
                    restart: true,
                    end: false
                }]
            )
            .ok(),
            Some(CountResult {
                vertices: 0,
                primitives: 0
            })
        );
    }

    #[test]
    fn restart_and_end_preserve_stable_segments() {
        let values = [
            PrimitiveBoundary {
                restart: false,
                end: false,
            },
            PrimitiveBoundary {
                restart: false,
                end: true,
            },
            PrimitiveBoundary {
                restart: false,
                end: false,
            },
            PrimitiveBoundary {
                restart: true,
                end: false,
            },
            PrimitiveBoundary {
                restart: false,
                end: false,
            },
        ];
        assert_eq!(
            count(PrimitiveTopology::Lines, &values).ok(),
            Some(CountResult {
                vertices: 2,
                primitives: 1
            })
        );
    }

    #[test]
    fn emits_metadata_and_side_effect_access() {
        let result = lower(
            PrimitiveTopology::Triangles,
            CountInput {
                input_records: 65,
                input_vertex_stride: 16,
                output_stride: 16,
                device_buffer_limit: 4096,
            },
            GeometrySideEffects {
                image_writes: false,
                ssbo_writes: true,
                atomics: true,
            },
        );
        assert!(result.is_ok());
        let Ok(stage) = result else { return };
        assert_eq!(stage.metadata.entry_point, ENTRY_POINT);
        assert_eq!(stage.metadata.dispatch, [2, 1, 1]);
        assert_eq!(
            stage.metadata.resources.get(3).map(|value| value.atomic),
            Some(false)
        );
        assert!(stage.msl.contains(ENTRY_POINT));
    }

    #[test]
    fn rejects_buffer_limit() {
        assert!(matches!(
            lower(
                PrimitiveTopology::Points,
                CountInput {
                    input_records: 1,
                    input_vertex_stride: 16,
                    output_stride: 16,
                    device_buffer_limit: 1
                },
                GeometrySideEffects::default()
            ),
            Err(Error::DeviceLimit { .. })
        ));
    }
}
