use objc2::runtime::ProtocolObject;
use objc2_metal::{
    MTL4CommandBuffer, MTL4CommandEncoder, MTL4ComputeCommandEncoder, MTL4VisibilityOptions,
    MTLBuffer, MTLDevice, MTLResourceOptions, MTLSize, MTLStages,
};

use crate::command::{Command, SortRequest};
use crate::encode::{Scratch, push_constants, wait};
use crate::gpu::Gpu;
use crate::resources::Resources;
use crate::stats::{self, Counter};
use crate::timing::{self, PassTimer};
use mtl4::{self as metal, Buffer};

const GROUP: MTLSize = MTLSize {
    width: 256,
    height: 1,
    depth: 1,
};

fn shared(gpu: &Gpu, resources: &mut Resources, size: usize) -> Option<Buffer> {
    let length = size.max(16);
    let buffer = gpu
        .device
        .newBufferWithLength_options(length, MTLResourceOptions::StorageModeShared)?;
    let zeros = vec![0u8; length];
    if !metal::write_shared(&buffer, 0, &zeros) {
        return None;
    }
    resources.track(ProtocolObject::from_ref(&*buffer));
    Some(buffer)
}

fn dispatch(encoder: &ProtocolObject<dyn MTL4ComputeCommandEncoder>, width: usize, height: usize) {
    if width == 0 || height == 0 {
        return;
    }
    encoder.dispatchThreads_threadsPerThreadgroup(
        MTLSize {
            width,
            height,
            depth: 1,
        },
        GROUP,
    );
}

fn params(request: &SortRequest, debug: bool) -> Vec<f32> {
    [
        request.count,
        request.items,
        request.indices,
        request.max_padded,
        u32::from(debug),
    ]
    .into_iter()
    .map(f32::from_bits)
    .chain(request.camera)
    .collect()
}

pub struct Sorter {
    ids: Buffer,
    indices: Buffer,
    items: usize,
    index_count: usize,
    mismatch: Buffer,
    retired: Vec<(u64, Buffer)>,
    pending: bool,
}

impl Sorter {
    pub fn new(gpu: &Gpu, resources: &mut Resources) -> Option<Self> {
        Some(Self {
            ids: shared(gpu, resources, 65536 * 4)?,
            indices: shared(gpu, resources, 65536 * 6 * 4)?,
            items: 65536,
            index_count: 65536 * 6,
            mismatch: shared(gpu, resources, 8)?,
            retired: Vec::new(),
            pending: false,
        })
    }

    fn release(&mut self, resources: &mut Resources, completed: u64) {
        let retired = std::mem::take(&mut self.retired);
        for (frame, buffer) in retired {
            if frame >= completed {
                self.retired.push((frame, buffer));
            } else {
                resources.untrack(ProtocolObject::from_ref(&*buffer));
            }
        }
    }

    fn grow(
        &mut self,
        gpu: &Gpu,
        resources: &mut Resources,
        items: usize,
        indices: usize,
        frame: u64,
    ) {
        if items <= self.items && indices <= self.index_count {
            return;
        }
        let Some(next_items) = items.max(self.items).checked_next_power_of_two() else {
            return;
        };
        let Some(next_indices) = indices.max(self.index_count).checked_next_power_of_two() else {
            return;
        };
        let Some(ids) = shared(gpu, resources, next_items.saturating_mul(4)) else {
            return;
        };
        let Some(index_data) = shared(gpu, resources, next_indices.saturating_mul(4)) else {
            return;
        };
        let old_ids = std::mem::replace(&mut self.ids, ids);
        let old_indices = std::mem::replace(&mut self.indices, index_data);
        self.retired.push((frame, old_ids));
        self.retired.push((frame, old_indices));
        self.items = next_items;
        self.index_count = next_indices;
    }

    pub fn prepare(
        &mut self,
        gpu: &Gpu,
        resources: &mut Resources,
        commands: &[Command],
        frame: u64,
        completed: u64,
    ) {
        self.release(resources, completed);
        if self.pending {
            if let Some(mismatches) = metal::read_shared_u32(&self.mismatch, 0) {
                stats::add(Counter::SortMismatches, u64::from(mismatches));
            }
            if let Some(checked) = metal::read_shared_u32(&self.mismatch, 4) {
                stats::add(Counter::SortChecked, u64::from(checked));
            }
        }
        self.pending = false;
        for command in commands {
            let Command::TerrainSort(request) = command else {
                continue;
            };
            self.grow(
                gpu,
                resources,
                request.items as usize,
                request.indices as usize * 6,
                frame,
            );
            if request.expected != 0 {
                self.pending = true;
            }
        }
    }

    pub fn encode(
        &self,
        gpu: &Gpu,
        resources: &Resources,
        scratch: &mut Scratch,
        timer: &mut PassTimer,
        request: &SortRequest,
    ) {
        let Some(records) = resources.buffers.get(request.records) else {
            return;
        };
        let expected = if request.expected == 0 {
            None
        } else {
            resources.buffers.get(request.expected)
        };
        let items = request.items as usize;
        let indices = request.indices as usize * 6;
        if request.count == 0
            || items > self.items
            || indices > self.index_count
            || request.max_padded == 0
        {
            return;
        }
        let debug = expected.is_some();
        let Some(params) = push_constants(scratch, &params(request, debug)) else {
            return;
        };
        metal::bind_address(
            &gpu.table,
            0,
            records.gpuAddress() + request.records_offset as u64,
        );
        metal::bind_address(&gpu.table, 1, self.ids.gpuAddress());
        metal::bind_address(&gpu.table, 2, self.indices.gpuAddress());
        metal::bind_address(
            &gpu.table,
            3,
            expected.map_or(0, |buffer| {
                buffer.gpuAddress() + request.expected_offset as u64
            }),
        );
        metal::bind_address(&gpu.table, 4, self.mismatch.gpuAddress());
        metal::bind_address(&gpu.table, 5, params);

        timer.begin(&gpu.cmd, timing::PASS_SORT);
        let Some(encoder) = gpu.cmd.computeCommandEncoder() else {
            timer.end(&gpu.cmd);
            return;
        };
        encoder.setArgumentTable(Some(&gpu.table));
        wait(&*encoder, MTLStages::Dispatch, MTLStages::Dispatch);
        let count = request.count as usize;
        let width = request.max_padded as usize;
        encoder.setComputePipelineState(&gpu.kernels.sort_copy);
        dispatch(&encoder, width, count);
        encoder.barrierAfterEncoderStages_beforeEncoderStages_visibilityOptions(
            MTLStages::Dispatch,
            MTLStages::Dispatch,
            MTL4VisibilityOptions::Device,
        );
        encoder.setComputePipelineState(&gpu.kernels.sort_init);
        dispatch(&encoder, width, count);
        encoder.barrierAfterEncoderStages_beforeEncoderStages_visibilityOptions(
            MTLStages::Dispatch,
            MTLStages::Dispatch,
            MTL4VisibilityOptions::Device,
        );
        let mut k = 2usize;
        while k <= width {
            let mut j = k / 2;
            while j > 0 {
                let stage = [
                    f32::from_bits(k as u32),
                    f32::from_bits(j as u32),
                    f32::from_bits(request.count),
                    0.0,
                    0.0,
                    request.camera[0],
                    request.camera[1],
                    request.camera[2],
                ];
                let Some(stage_params) = push_constants(scratch, &stage) else {
                    encoder.endEncoding();
                    timer.end(&gpu.cmd);
                    return;
                };
                metal::bind_address(&gpu.table, 5, stage_params);
                encoder.setComputePipelineState(&gpu.kernels.sort_stage);
                dispatch(&encoder, width, count);
                encoder.barrierAfterEncoderStages_beforeEncoderStages_visibilityOptions(
                    MTLStages::Dispatch,
                    MTLStages::Dispatch,
                    MTL4VisibilityOptions::Device,
                );
                j /= 2;
            }
            if k > width / 2 {
                break;
            }
            k *= 2;
        }
        metal::bind_address(&gpu.table, 5, params);
        encoder.setComputePipelineState(&gpu.kernels.sort_write);
        dispatch(&encoder, width, count);
        if debug {
            encoder.barrierAfterEncoderStages_beforeEncoderStages_visibilityOptions(
                MTLStages::Dispatch,
                MTLStages::Dispatch,
                MTL4VisibilityOptions::Device,
            );
            encoder.setComputePipelineState(&gpu.kernels.sort_check);
            dispatch(&encoder, width, count);
        }
        encoder.barrierAfterStages_beforeQueueStages_visibilityOptions(
            MTLStages::Dispatch,
            MTLStages::Vertex | MTLStages::Fragment,
            MTL4VisibilityOptions::Device,
        );
        encoder.endEncoding();
        timer.end(&gpu.cmd);
    }
}
