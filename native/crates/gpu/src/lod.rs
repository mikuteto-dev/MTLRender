pub const LOD_CULL_VERSION: u32 = 1;
pub const LOD_NODE_STRIDE: usize = 64;
pub const LOD_MESH_STRIDE: usize = 20;
pub const LOD_DRAW_STRIDE: usize = 20;
use crate::encode::{Scratch, push_constants, wait};
use crate::gpu::Gpu;
use crate::resources::Resources;
use crate::timing::{self, PassTimer};
use mtl4::{self as metal, Buffer};
use objc2::runtime::ProtocolObject;
use objc2_metal::{
    MTL4CommandBuffer, MTL4CommandEncoder, MTL4ComputeCommandEncoder, MTL4VisibilityOptions,
    MTLBuffer, MTLDevice, MTLResourceOptions, MTLSize, MTLStages,
};

pub struct LodCullRequest {
    pub version: u32,
    pub nodes: u64,
    pub nodes_offset: usize,
    pub nodes_length: usize,
    pub meshes: u64,
    pub meshes_offset: usize,
    pub meshes_length: usize,
    pub camera: [f32; 3],
    pub frustum: [f32; 16],
    pub output: u64,
    pub output_offset: usize,
    pub output_length: usize,
    pub level: u32,
    pub max_level: u32,
    pub threshold: f32,
}

impl LodCullRequest {
    pub fn valid(&self) -> bool {
        self.version == LOD_CULL_VERSION
            && self.nodes_length >= LOD_NODE_STRIDE
            && self.meshes_length >= LOD_MESH_STRIDE
            && self.output_length >= LOD_DRAW_STRIDE
            && self.nodes_length.is_multiple_of(LOD_NODE_STRIDE)
            && self.meshes_length.is_multiple_of(LOD_MESH_STRIDE)
            && self.output_length.is_multiple_of(LOD_DRAW_STRIDE)
            && self.nodes_offset.checked_add(self.nodes_length).is_some()
            && self.meshes_offset.checked_add(self.meshes_length).is_some()
            && self.output_offset.checked_add(self.output_length).is_some()
            && self.level <= self.max_level
            && self.max_level <= 31
            && self.threshold.is_finite()
            && self.threshold >= 0.0
            && self.camera.iter().all(|value| value.is_finite())
            && self.frustum.iter().all(|value| value.is_finite())
    }

    pub fn node_capacity(&self) -> usize {
        self.nodes_length / LOD_NODE_STRIDE
    }

    pub fn mesh_capacity(&self) -> usize {
        self.meshes_length / LOD_MESH_STRIDE
    }

    pub fn output_capacity(&self) -> usize {
        self.output_length / LOD_DRAW_STRIDE
    }
}

pub struct LodCuller {
    queue_a: Buffer,
    queue_b: Buffer,
    counters: Buffer,
    capacity: usize,
}

fn shared(gpu: &Gpu, resources: &mut Resources, size: usize) -> Option<Buffer> {
    let buffer = gpu
        .device
        .newBufferWithLength_options(size.max(16), MTLResourceOptions::StorageModeShared)?;
    let zero = vec![0_u8; size.max(16)];
    metal::write_shared(&buffer, 0, &zero).then_some(())?;
    resources.track(ProtocolObject::from_ref(&*buffer));
    Some(buffer)
}

impl LodCuller {
    pub fn new(gpu: &Gpu, resources: &mut Resources) -> Option<Self> {
        let capacity = 65536;
        Some(Self {
            queue_a: shared(gpu, resources, capacity * 4)?,
            queue_b: shared(gpu, resources, capacity * 4)?,
            counters: shared(gpu, resources, 16)?,
            capacity,
        })
    }

    fn fallback(&self, resources: &Resources, request: &LodCullRequest) {
        let Some(meshes) = resources.buffers.get(request.meshes) else {
            return;
        };
        let Some(output) = resources.buffers.get(request.output) else {
            return;
        };
        let size = request
            .meshes_length
            .min(request.output_length)
            .min(LOD_DRAW_STRIDE);
        let _ = metal::copy_shared(
            meshes,
            request.meshes_offset,
            output,
            request.output_offset,
            size,
        );
    }

    pub fn encode(
        &self,
        gpu: &Gpu,
        resources: &Resources,
        scratch: &mut Scratch,
        timer: &mut PassTimer,
        request: &LodCullRequest,
    ) {
        let Some(nodes) = resources.buffers.get(request.nodes) else {
            self.fallback(resources, request);
            return;
        };
        let Some(meshes) = resources.buffers.get(request.meshes) else {
            self.fallback(resources, request);
            return;
        };
        let Some(output) = resources.buffers.get(request.output) else {
            return;
        };
        if !request.valid()
            || request.node_capacity() > self.capacity
            || request.output_capacity() > self.capacity
        {
            self.fallback(resources, request);
            return;
        }
        let mut words = Vec::with_capacity(25);
        words.extend(request.frustum.iter().map(|value| value.to_bits()));
        words.extend(request.camera.iter().map(|value| value.to_bits()));
        words.extend([
            request.threshold.to_bits(),
            request.level,
            request.max_level,
            request.node_capacity() as u32,
            request.mesh_capacity() as u32,
            request.output_capacity() as u32,
        ]);
        let Some(params) = push_constants(
            scratch,
            &words
                .iter()
                .map(|word| f32::from_bits(*word))
                .collect::<Vec<_>>(),
        ) else {
            self.fallback(resources, request);
            return;
        };
        metal::bind_address(
            &gpu.table,
            0,
            nodes.gpuAddress() + request.nodes_offset as u64,
        );
        metal::bind_address(
            &gpu.table,
            1,
            meshes.gpuAddress() + request.meshes_offset as u64,
        );
        metal::bind_address(&gpu.table, 2, params);
        metal::bind_address(&gpu.table, 3, self.queue_a.gpuAddress());
        metal::bind_address(&gpu.table, 4, self.queue_b.gpuAddress());
        metal::bind_address(&gpu.table, 5, self.counters.gpuAddress());
        metal::bind_address(
            &gpu.table,
            6,
            output.gpuAddress() + request.output_offset as u64,
        );
        let Some(encoder) = gpu.cmd.computeCommandEncoder() else {
            self.fallback(resources, request);
            return;
        };
        encoder.setArgumentTable(Some(&gpu.table));
        wait(&*encoder, MTLStages::Dispatch, MTLStages::Dispatch);
        timer.begin(&gpu.cmd, timing::PASS_CULL);
        encoder.setComputePipelineState(&gpu.kernels.lod_seed);
        encoder.dispatchThreads_threadsPerThreadgroup(
            MTLSize {
                width: 1,
                height: 1,
                depth: 1,
            },
            MTLSize {
                width: 1,
                height: 1,
                depth: 1,
            },
        );
        encoder.barrierAfterEncoderStages_beforeEncoderStages_visibilityOptions(
            MTLStages::Dispatch,
            MTLStages::Dispatch,
            MTL4VisibilityOptions::Device,
        );
        let rounds = request
            .max_level
            .saturating_sub(request.level)
            .saturating_add(1);
        for round in 0..rounds {
            metal::bind_address(
                &gpu.table,
                3,
                if round % 2 == 0 {
                    self.queue_a.gpuAddress()
                } else {
                    self.queue_b.gpuAddress()
                },
            );
            metal::bind_address(
                &gpu.table,
                4,
                if round % 2 == 0 {
                    self.queue_b.gpuAddress()
                } else {
                    self.queue_a.gpuAddress()
                },
            );
            if round > 0 {
                encoder.setComputePipelineState(&gpu.kernels.lod_reset);
                encoder.dispatchThreads_threadsPerThreadgroup(
                    MTLSize {
                        width: 1,
                        height: 1,
                        depth: 1,
                    },
                    MTLSize {
                        width: 1,
                        height: 1,
                        depth: 1,
                    },
                );
                encoder.barrierAfterEncoderStages_beforeEncoderStages_visibilityOptions(
                    MTLStages::Dispatch,
                    MTLStages::Dispatch,
                    MTL4VisibilityOptions::Device,
                );
            }
            encoder.setComputePipelineState(&gpu.kernels.lod_expand);
            encoder.dispatchThreadgroups_threadsPerThreadgroup(
                MTLSize {
                    width: self.capacity.div_ceil(256),
                    height: 1,
                    depth: 1,
                },
                MTLSize {
                    width: 256,
                    height: 1,
                    depth: 1,
                },
            );
            encoder.barrierAfterEncoderStages_beforeEncoderStages_visibilityOptions(
                MTLStages::Dispatch,
                MTLStages::Dispatch,
                MTL4VisibilityOptions::Device,
            );
        }
        encoder.barrierAfterStages_beforeQueueStages_visibilityOptions(
            MTLStages::Dispatch,
            MTLStages::Vertex,
            MTL4VisibilityOptions::Device,
        );
        encoder.endEncoding();
        timer.end(&gpu.cmd);
    }
}

#[cfg(test)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct LodBounds {
    pub min: [f32; 3],
    pub max: [f32; 3],
    pub height_min: f32,
    pub height_max: f32,
    pub opaque: bool,
}

#[cfg(test)]
impl LodBounds {
    pub fn projected_area(&self, camera: [f32; 3]) -> f32 {
        let center = [
            (self.min[0] + self.max[0]) * 0.5,
            (self.min[1] + self.max[1]) * 0.5,
            (self.min[2] + self.max[2]) * 0.5,
        ];
        let extent = [
            (self.max[0] - self.min[0]).abs(),
            (self.max[1] - self.min[1]).abs(),
            (self.max[2] - self.min[2]).abs(),
        ];
        let radius =
            (extent[0] * extent[0] + extent[1] * extent[1] + extent[2] * extent[2]).sqrt() * 0.5;
        let dx = center[0] - camera[0];
        let dy = center[1] - camera[1];
        let dz = center[2] - camera[2];
        let distance = (dx * dx + dy * dy + dz * dz).sqrt().max(radius);
        radius * radius / (distance * distance)
    }

    pub fn horizon_safe(&self, camera: [f32; 3]) -> bool {
        if !self.opaque || !self.height_min.is_finite() || !self.height_max.is_finite() {
            return true;
        }
        let dx = self.min[0].min(self.max[0]) - camera[0];
        let dz = self.min[2].min(self.max[2]) - camera[2];
        let horizontal = (dx * dx + dz * dz).sqrt();
        let vertical = self.height_max.max(self.height_min) - camera[1];
        vertical >= -horizontal
    }
}

#[cfg(test)]
fn frustum_visible(bounds: &LodBounds, frustum: &[f32; 16]) -> bool {
    for plane in frustum.as_chunks::<4>().0 {
        let [a, b, c, d] = *plane;
        let point = [
            if a >= 0.0 {
                bounds.max[0]
            } else {
                bounds.min[0]
            },
            if b >= 0.0 {
                bounds.max[1]
            } else {
                bounds.min[1]
            },
            if c >= 0.0 {
                bounds.max[2]
            } else {
                bounds.min[2]
            },
        ];
        if a * point[0] + b * point[1] + c * point[2] + d < 0.0 {
            return false;
        }
    }
    true
}

#[cfg(test)]
fn output_slot(index: usize, capacity: usize) -> Option<usize> {
    (index < capacity).then_some(index)
}

#[cfg(test)]
fn select_node(bounds: &LodBounds, camera: [f32; 3], frustum: &[f32; 16], threshold: f32) -> bool {
    frustum_visible(bounds, frustum)
        && bounds.horizon_safe(camera)
        && bounds.projected_area(camera) <= threshold
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bounds() -> LodBounds {
        LodBounds {
            min: [-1.0, -1.0, 4.0],
            max: [1.0, 1.0, 6.0],
            height_min: -1.0,
            height_max: 1.0,
            opaque: true,
        }
    }

    fn frustum() -> [f32; 16] {
        [
            1.0, 0.0, 0.0, 100.0, -1.0, 0.0, 0.0, 100.0, 0.0, 1.0, 0.0, 100.0, 0.0, -1.0, 0.0,
            100.0,
        ]
    }

    #[test]
    fn empty_tree_is_invalid() {
        let request = LodCullRequest {
            version: 1,
            nodes: 0,
            nodes_offset: 0,
            nodes_length: 0,
            meshes: 0,
            meshes_offset: 0,
            meshes_length: 0,
            camera: [0.0; 3],
            frustum: [0.0; 16],
            output: 0,
            output_offset: 0,
            output_length: 0,
            level: 0,
            max_level: 0,
            threshold: 1.0,
        };
        assert!(!request.valid());
    }

    #[test]
    fn one_parent_is_selected_when_children_are_too_small() {
        assert!(select_node(&bounds(), [0.0, 0.0, 0.0], &frustum(), 1.0));
    }

    #[test]
    fn child_threshold_selection_keeps_large_parent() {
        let value = bounds().projected_area([0.0, 0.0, 0.0]);
        assert!(!select_node(
            &bounds(),
            [0.0, 0.0, 0.0],
            &frustum(),
            value * 0.5
        ));
    }

    #[test]
    fn missing_child_fallback_is_bounded() {
        assert_eq!(output_slot(0, 1), Some(0));
        assert_eq!(output_slot(1, 1), None);
    }

    #[test]
    fn projected_area_selects_parent_at_threshold() {
        let value = bounds().projected_area([0.0, 0.0, 0.0]);
        assert!(select_node(&bounds(), [0.0, 0.0, 0.0], &frustum(), value));
        assert!(!select_node(
            &bounds(),
            [0.0, 0.0, 0.0],
            &frustum(),
            value * 0.5
        ));
    }

    #[test]
    fn frustum_rejects_bounds() {
        let mut outside = bounds();
        outside.min[0] = 200.0;
        outside.max[0] = 201.0;
        assert!(!frustum_visible(&outside, &frustum()));
    }

    #[test]
    fn unavailable_opaque_bound_is_safe() {
        let mut value = bounds();
        value.opaque = false;
        value.height_min = f32::NAN;
        assert!(value.horizon_safe([0.0, 0.0, 0.0]));
    }

    #[test]
    fn output_layout_is_indirect_arguments() {
        assert_eq!(LOD_DRAW_STRIDE, 5 * std::mem::size_of::<u32>());
    }

    #[test]
    fn output_capacity_overflow_is_rejected() {
        assert_eq!(output_slot(4, 4), None);
    }

    #[test]
    fn capacity_overflow_is_rejected() {
        let request = LodCullRequest {
            version: 1,
            nodes: 0,
            nodes_offset: usize::MAX,
            nodes_length: LOD_NODE_STRIDE,
            meshes: 0,
            meshes_offset: 0,
            meshes_length: LOD_MESH_STRIDE,
            camera: [0.0; 3],
            frustum: [0.0; 16],
            output: 0,
            output_offset: 0,
            output_length: LOD_DRAW_STRIDE,
            level: 0,
            max_level: 0,
            threshold: 1.0,
        };
        assert!(!request.valid());
    }
}
