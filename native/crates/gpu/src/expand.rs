use objc2::runtime::ProtocolObject;
use objc2_metal::{
    MTL4CommandBuffer, MTL4CommandEncoder, MTL4ComputeCommandEncoder, MTL4VisibilityOptions,
    MTLBuffer, MTLDevice, MTLResourceOptions, MTLSize, MTLStages,
};

use crate::command::{Command, ExpandCheckRequest, ExpandRequest};
use crate::encode::{Scratch, push_constants, wait};
use crate::gpu::Gpu;
use crate::resources::Resources;
use crate::stats::{self, Counter};
use crate::timing::{self, PassTimer};
use mtl4::{self as metal, Buffer};

const GROUP_SIZE: usize = 256;
const GROUP_1D: MTLSize = MTLSize {
    width: GROUP_SIZE,
    height: 1,
    depth: 1,
};
const UNIT_GROUP: MTLSize = MTLSize {
    width: 1,
    height: 1,
    depth: 1,
};
const LEGACY_UNIT_BYTES: usize = 48;
const PACK_UNIT_BYTES: usize = 64;
const LEGACY_RECORD_BYTES: usize = 80;
const PACK_RECORD_BYTES: usize = 96;
const LEGACY_UPDATE_BYTES: usize = 88;
const PACK_UPDATE_BYTES: usize = 104;

pub struct Expander {
    groups: Buffer,
    locals: Buffer,
    quads: Buffer,
    totals: Buffer,
    mismatch: Buffer,
    records: Buffer,
    capacity: usize,
    sections: usize,
    retired: Vec<(u64, Buffer)>,
    pending: bool,
}

fn size1(value: usize) -> MTLSize {
    MTLSize {
        width: value,
        height: 1,
        depth: 1,
    }
}

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

fn fallback(out: &Buffer, request: &ExpandRequest) {
    for layer in 0..3usize {
        let record = [request.indices, 0, 0, 0, 0];
        let bytes: Vec<u8> = record.iter().flat_map(|word| word.to_ne_bytes()).collect();
        let offset = request.args_offset + layer * 20;
        let _ = metal::write_shared(out, offset, &bytes);
    }
}

fn params(request: &ExpandRequest, count: u32) -> Vec<f32> {
    let groups = (count as usize).div_ceil(GROUP_SIZE) as u32;
    let mut words: Vec<u32> = Vec::with_capacity(18);
    words.push(count);
    words.push(0);
    words.push(request.visible_count);
    words.push(request.visible_count.saturating_mul(2));
    words.push(request.indices);
    words.push(request.capacity);
    words.push(groups);
    words.extend(
        request
            .camera_block
            .iter()
            .map(|value| u32::from_ne_bytes(value.to_ne_bytes())),
    );
    words.extend(request.camera_offset.iter().map(|value| value.to_bits()));
    words.push(request.visible_count);
    words.push(request.sections);
    words.push(request.cull_mask);
    words.push(request.layer_mask);
    words.push(request.options);
    words.iter().map(|word| f32::from_bits(*word)).collect()
}

fn check_params(request: &ExpandCheckRequest) -> Vec<f32> {
    let mut words: Vec<u32> = Vec::with_capacity(5);
    words.push(request.count);
    words.extend(request.first);
    words.push(u32::try_from(request.gpu_bytes).unwrap_or(u32::MAX));
    words.iter().map(|word| f32::from_bits(*word)).collect()
}

fn barrier(encoder: &ProtocolObject<dyn MTL4ComputeCommandEncoder>) {
    encoder.barrierAfterEncoderStages_beforeEncoderStages_visibilityOptions(
        MTLStages::Dispatch,
        MTLStages::Dispatch,
        MTL4VisibilityOptions::Device,
    );
}

fn dispatch_threads(
    encoder: &ProtocolObject<dyn MTL4ComputeCommandEncoder>,
    threads: usize,
    group: MTLSize,
) {
    if threads == 0 {
        return;
    }
    encoder.dispatchThreads_threadsPerThreadgroup(size1(threads), group);
}

fn dispatch_groups(
    encoder: &ProtocolObject<dyn MTL4ComputeCommandEncoder>,
    groups: usize,
    group: MTLSize,
) {
    if groups == 0 {
        return;
    }
    encoder.dispatchThreadgroups_threadsPerThreadgroup(size1(groups), group);
}

impl Expander {
    pub fn new(gpu: &Gpu, resources: &mut Resources) -> Option<Self> {
        let capacity: usize = 65536;
        let groups = capacity.div_ceil(GROUP_SIZE);
        Some(Self {
            groups: shared(gpu, resources, groups * 4)?,
            locals: shared(gpu, resources, capacity * 4)?,
            quads: shared(gpu, resources, groups * 4)?,
            totals: shared(gpu, resources, 16)?,
            mismatch: shared(gpu, resources, 16)?,
            records: shared(gpu, resources, 0)?,
            capacity,
            sections: 0,
            retired: Vec::new(),
            pending: false,
        })
    }

    fn release(&mut self, resources: &mut Resources, completed: u64) {
        let retired = std::mem::take(&mut self.retired);
        for (frame, buffer) in retired {
            if frame >= completed {
                self.retired.push((frame, buffer));
                continue;
            }
            resources.untrack(ProtocolObject::from_ref(&*buffer));
        }
    }

    fn grow(&mut self, gpu: &Gpu, resources: &mut Resources, needed: usize, frame: u64) {
        if needed <= self.capacity {
            return;
        }
        let Some(capacity) = needed.checked_next_power_of_two() else {
            return;
        };
        let Some(locals) = shared(gpu, resources, capacity.saturating_mul(4)) else {
            return;
        };
        let groups = capacity.div_ceil(GROUP_SIZE).saturating_mul(4);
        let Some(group_sums) = shared(gpu, resources, groups) else {
            return;
        };
        let Some(group_quads) = shared(gpu, resources, groups) else {
            return;
        };
        let old_locals = std::mem::replace(&mut self.locals, locals);
        let old_groups = std::mem::replace(&mut self.groups, group_sums);
        let old_quads = std::mem::replace(&mut self.quads, group_quads);
        self.retired.push((frame, old_locals));
        self.retired.push((frame, old_groups));
        self.retired.push((frame, old_quads));
        self.capacity = capacity;
    }

    fn grow_records(
        &mut self,
        gpu: &Gpu,
        resources: &mut Resources,
        needed: usize,
        pack: bool,
        frame: u64,
    ) {
        if needed <= self.sections {
            return;
        }
        debug_assert_eq!(LEGACY_UNIT_BYTES, 48);
        debug_assert_eq!(PACK_UNIT_BYTES, 64);
        debug_assert_eq!(LEGACY_UPDATE_BYTES, 88);
        debug_assert_eq!(PACK_UPDATE_BYTES, 104);
        let stride = if pack {
            PACK_RECORD_BYTES * 3
        } else {
            LEGACY_RECORD_BYTES * 3
        };
        let Some(bytes) = needed.checked_mul(stride) else {
            return;
        };
        let Some(records) = shared(gpu, resources, bytes) else {
            return;
        };
        let old = std::mem::replace(&mut self.records, records);
        self.retired.push((frame, old));
        self.sections = needed;
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
            if let Some(units) = metal::read_shared_u32(&self.totals, 0) {
                stats::add(Counter::ExpandUnits, units as u64);
            }
            if let Some(quads) = metal::read_shared_u32(&self.totals, 4) {
                stats::add(Counter::ExpandQuads, quads as u64);
            }
        }
        self.pending = false;
        if let Some(units) = metal::read_shared_u32(&self.mismatch, 0) {
            stats::set(Counter::ExpandMismatchUnits, u64::from(units));
        }
        if let Some(layers) = metal::read_shared_u32(&self.mismatch, 4) {
            stats::set(Counter::ExpandMismatchLayers, u64::from(layers));
        }
        if let Some(checked) = metal::read_shared_u32(&self.mismatch, 8) {
            stats::set(Counter::ExpandChecked, u64::from(checked));
        }
        for command in commands {
            let Command::TerrainExpand(request) = command else {
                continue;
            };
            if request.visible_count > 0 {
                self.pending = true;
            }
            self.grow_records(
                gpu,
                resources,
                request.sections as usize,
                (request.options & 2) != 0,
                frame,
            );
            let work = (request.visible_count as usize).saturating_mul(3);
            self.grow(gpu, resources, work, frame);
        }
    }

    pub fn encode(
        &self,
        gpu: &Gpu,
        resources: &Resources,
        scratch: &mut Scratch,
        timer: &mut PassTimer,
        request: &ExpandRequest,
    ) {
        let Some(out) = resources.buffers.get(request.out) else {
            return;
        };
        let Some(visible) = resources.buffers.get(request.visible) else {
            fallback(out, request);
            return;
        };
        let updates = if request.update_count > 0 {
            match resources.buffers.get(request.updates) {
                Some(buffer) => Some(buffer),
                None => {
                    fallback(out, request);
                    return;
                }
            }
        } else {
            None
        };
        let count = request.visible_count.saturating_mul(3);
        if count == 0 || count as usize > self.capacity || request.sections as usize > self.sections
        {
            fallback(out, request);
            return;
        }
        let values = params(request, count);
        let Some(params) = push_constants(scratch, &values) else {
            fallback(out, request);
            return;
        };
        let updates_address = updates.map_or_else(
            || self.records.gpuAddress(),
            |buffer| buffer.gpuAddress() + request.updates_offset as u64,
        );
        metal::bind_address(&gpu.table, 0, updates_address);
        metal::bind_address(&gpu.table, 1, params);
        metal::bind_address(&gpu.table, 2, self.groups.gpuAddress());
        metal::bind_address(&gpu.table, 3, self.totals.gpuAddress());
        metal::bind_address(&gpu.table, 4, out.gpuAddress() + request.out_offset as u64);
        metal::bind_address(&gpu.table, 5, self.locals.gpuAddress());
        metal::bind_address(&gpu.table, 6, self.quads.gpuAddress());
        metal::bind_address(&gpu.table, 7, self.records.gpuAddress());
        metal::bind_address(
            &gpu.table,
            8,
            visible.gpuAddress() + request.visible_offset as u64,
        );
        metal::bind_address(&gpu.table, 9, out.gpuAddress() + request.args_offset as u64);

        timer.begin(&gpu.cmd, timing::PASS_EXPAND);
        let Some(encoder) = gpu.cmd.computeCommandEncoder() else {
            fallback(out, request);
            timer.end(&gpu.cmd);
            return;
        };
        encoder.setArgumentTable(Some(&gpu.table));
        wait(&*encoder, MTLStages::Dispatch, MTLStages::Dispatch);

        if request.update_count > 0 {
            encoder.setComputePipelineState(&gpu.kernels.records_scatter);
            dispatch_threads(&encoder, request.update_count as usize, GROUP_1D);
            barrier(&encoder);
        }

        let groups = (count as usize).div_ceil(GROUP_SIZE);
        encoder.setComputePipelineState(&gpu.kernels.expand_count);
        dispatch_groups(&encoder, groups, GROUP_1D);
        barrier(&encoder);
        encoder.setComputePipelineState(&gpu.kernels.expand_offsets);
        dispatch_threads(&encoder, 1, UNIT_GROUP);
        barrier(&encoder);
        encoder.setComputePipelineState(&gpu.kernels.expand_write);
        dispatch_groups(&encoder, groups, GROUP_1D);

        encoder.barrierAfterStages_beforeQueueStages_visibilityOptions(
            MTLStages::Dispatch,
            MTLStages::Vertex | MTLStages::Fragment,
            MTL4VisibilityOptions::Device,
        );
        encoder.endEncoding();
        timer.end(&gpu.cmd);
    }

    pub fn encode_check(
        &self,
        gpu: &Gpu,
        resources: &Resources,
        scratch: &mut Scratch,
        timer: &mut PassTimer,
        request: &ExpandCheckRequest,
    ) {
        let Some(cpu) = resources.buffers.get(request.cpu) else {
            return;
        };
        let Some(gpu_units) = resources.buffers.get(request.gpu) else {
            return;
        };
        let Some(params) = push_constants(scratch, &check_params(request)) else {
            return;
        };
        metal::bind_address(&gpu.table, 0, cpu.gpuAddress() + request.cpu_offset as u64);
        metal::bind_address(&gpu.table, 1, params);
        metal::bind_address(&gpu.table, 2, self.mismatch.gpuAddress());
        metal::bind_address(
            &gpu.table,
            4,
            gpu_units.gpuAddress() + request.gpu_offset as u64,
        );
        metal::bind_address(
            &gpu.table,
            9,
            gpu_units.gpuAddress() + request.args_offset as u64,
        );

        timer.begin(&gpu.cmd, timing::PASS_EXPAND);
        let Some(encoder) = gpu.cmd.computeCommandEncoder() else {
            timer.end(&gpu.cmd);
            return;
        };
        encoder.setArgumentTable(Some(&gpu.table));
        wait(&*encoder, MTLStages::Dispatch, MTLStages::Dispatch);
        encoder.setComputePipelineState(&gpu.kernels.expand_check);
        dispatch_threads(&encoder, (request.count as usize).max(1), GROUP_1D);
        encoder.endEncoding();
        timer.end(&gpu.cmd);
    }
}

#[cfg(test)]
mod tests {
    use super::{
        LEGACY_RECORD_BYTES, LEGACY_UNIT_BYTES, LEGACY_UPDATE_BYTES, PACK_RECORD_BYTES,
        PACK_UNIT_BYTES, PACK_UPDATE_BYTES,
    };

    #[test]
    fn terrain_abi_keeps_legacy_and_pack_sizes_separate() {
        assert_eq!(LEGACY_UNIT_BYTES, 48);
        assert_eq!(LEGACY_RECORD_BYTES, 80);
        assert_eq!(LEGACY_UPDATE_BYTES, 88);
        assert_eq!(PACK_UNIT_BYTES, 64);
        assert_eq!(PACK_RECORD_BYTES, 96);
        assert_eq!(PACK_UPDATE_BYTES, 104);
    }
}
