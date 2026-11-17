use objc2::rc::Retained;
use objc2::runtime::ProtocolObject;
use objc2_metal::{
    MTL4CommandBuffer, MTL4CommandEncoder, MTL4ComputeCommandEncoder, MTL4VisibilityOptions,
    MTLBuffer, MTLSamplerState, MTLSize, MTLStages, MTLTexture,
};

use super::{Scratch, wait};
use crate::command::{
    ComputeAccess, ComputeBufferRequest, ComputeBytesRequest, ComputeDispatchIndirectRequest,
    ComputeImageRequest, ComputeTextureRequest,
};
use crate::geometry_runtime::{AccessMode, ResourceIdentity};
use crate::gpu::Gpu;
use crate::hazard::Hazards;
use crate::resources::Resources;
use crate::timing::{self, PassTimer};
use mtl4 as metal;

type ComputeEncoder = Retained<ProtocolObject<dyn MTL4ComputeCommandEncoder>>;

pub struct Pass {
    encoder: ComputeEncoder,
    pipeline: Option<[usize; 3]>,
    written: Vec<ResourceIdentity>,
    allow_concurrent: bool,
}

fn size(values: [u32; 3]) -> MTLSize {
    MTLSize {
        width: values[0] as usize,
        height: values[1] as usize,
        depth: values[2] as usize,
    }
}

fn valid_dimensions(values: [u32; 3]) -> bool {
    !values.contains(&0)
}

fn valid_indirect(offset: u64, length: usize) -> Option<usize> {
    if !offset.is_multiple_of(4) {
        return None;
    }
    let offset = usize::try_from(offset).ok()?;
    let end = offset.checked_add(12)?;
    (end <= length).then_some(offset)
}

fn slot(value: u32) -> Option<usize> {
    value.try_into().ok()
}

pub fn begin(gpu: &Gpu, timer: &mut PassTimer, allow_concurrent: bool) -> Option<Pass> {
    timer.begin(&gpu.cmd, timing::PASS_COMPUTE);
    let encoder = gpu.cmd.computeCommandEncoder()?;
    encoder.setArgumentTable(Some(&gpu.table));
    if !allow_concurrent {
        wait(
            &*encoder,
            MTLStages::Vertex | MTLStages::Fragment | MTLStages::Dispatch | MTLStages::Blit,
            MTLStages::Dispatch,
        );
    }
    Some(Pass {
        encoder,
        pipeline: None,
        written: Vec::new(),
        allow_concurrent,
    })
}

impl Pass {
    pub fn set_pipeline(&mut self, resources: &Resources, handle: u64) {
        let Some(pipeline) = resources.compute_pipelines.get(handle) else {
            return;
        };
        self.encoder.setComputePipelineState(&pipeline.0);
        self.pipeline = Some(pipeline.1);
    }

    pub fn declare_access(
        &mut self,
        gpu: &Gpu,
        hazards: &mut Hazards,
        root: ResourceIdentity,
        access: ComputeAccess,
    ) {
        self.access(gpu, hazards, root, access);
    }

    fn access(
        &mut self,
        gpu: &Gpu,
        hazards: &mut Hazards,
        root: ResourceIdentity,
        access: ComputeAccess,
    ) {
        let mode = match access {
            ComputeAccess::Read => AccessMode::Read,
            ComputeAccess::Write => AccessMode::Write,
            ComputeAccess::ReadWrite => AccessMode::ReadWrite,
        };
        let hazard = hazards.compute_resource(root, mode);
        if !hazard.producer.is_empty() {
            if let Some(fence) = hazards.fence_resource(&gpu.device, root) {
                self.encoder
                    .waitForFence_beforeEncoderStages(fence, MTLStages::Dispatch);
            }
            wait(&*self.encoder, hazard.producer, MTLStages::Dispatch);
        }
        if !hazard.consumer.is_empty() {
            self.encoder
                .barrierAfterEncoderStages_beforeEncoderStages_visibilityOptions(
                    hazard.consumer,
                    MTLStages::Dispatch,
                    MTL4VisibilityOptions::Device,
                );
        }
        if matches!(access, ComputeAccess::Write | ComputeAccess::ReadWrite) {
            self.written.push(root);
        }
    }

    pub fn bind_buffer(
        &mut self,
        gpu: &Gpu,
        resources: &Resources,
        hazards: &mut Hazards,
        request: ComputeBufferRequest,
    ) {
        let Some(index) = slot(request.slot) else {
            return;
        };
        let Some(buffer) = resources.buffers.get(request.buffer) else {
            return;
        };
        let Some(offset) = usize::try_from(request.offset).ok() else {
            return;
        };
        if offset > buffer.length() {
            return;
        }
        let Some(address) = buffer.gpuAddress().checked_add(request.offset) else {
            return;
        };
        self.access(
            gpu,
            hazards,
            ResourceIdentity::BufferHandle(request.buffer),
            request.access,
        );
        metal::bind_address(&gpu.table, index, address);
    }

    pub fn bind_texture(
        &mut self,
        gpu: &Gpu,
        resources: &Resources,
        hazards: &mut Hazards,
        request: ComputeTextureRequest,
    ) {
        let Some(texture_index) = slot(request.texture_slot) else {
            return;
        };
        let Some(image) = resources.images.get(request.view) else {
            return;
        };
        self.access(
            gpu,
            hazards,
            ResourceIdentity::ImageRoot(image.root),
            request.access,
        );
        metal::bind_texture(&gpu.table, texture_index, image.texture.gpuResourceID());
        if let Some(sampler_index) = slot(request.sampler_slot)
            && let Some(sampler) = resources.samplers.get(request.sampler)
        {
            metal::bind_sampler(&gpu.table, sampler_index, sampler.gpuResourceID());
        }
    }

    pub fn bind_image(
        &mut self,
        gpu: &Gpu,
        resources: &Resources,
        hazards: &mut Hazards,
        request: ComputeImageRequest,
    ) {
        let Some(index) = slot(request.slot) else {
            return;
        };
        let Some(image) = resources.images.get(request.view) else {
            return;
        };
        self.access(
            gpu,
            hazards,
            ResourceIdentity::ImageRoot(image.root),
            request.access,
        );
        metal::bind_texture(&gpu.table, index, image.texture.gpuResourceID());
    }

    pub fn bind_bytes(&mut self, gpu: &Gpu, scratch: &mut Scratch, request: ComputeBytesRequest) {
        let Some(index) = slot(request.slot) else {
            return;
        };
        let Some(address) = push_constants_bytes(scratch, &request.bytes) else {
            return;
        };
        metal::bind_address(&gpu.table, index, address);
    }

    pub fn dispatch(&self, dimensions: [u32; 3]) {
        let Some(pipeline) = self.pipeline else {
            return;
        };
        if !valid_dimensions(dimensions) {
            return;
        }
        self.encoder.dispatchThreadgroups_threadsPerThreadgroup(
            size(dimensions),
            size(pipeline.map(|v| v as u32)),
        );
    }

    pub fn dispatch_indirect(
        &mut self,
        gpu: &Gpu,
        resources: &Resources,
        hazards: &mut Hazards,
        request: ComputeDispatchIndirectRequest,
    ) {
        let Some(pipeline) = self.pipeline else {
            return;
        };
        let Some(buffer) = resources.buffers.get(request.buffer) else {
            return;
        };
        let Some(offset) = valid_indirect(request.offset, buffer.length()) else {
            return;
        };
        self.access(
            gpu,
            hazards,
            ResourceIdentity::BufferHandle(request.buffer),
            ComputeAccess::Read,
        );
        let Some(address) = buffer.gpuAddress().checked_add(offset as u64) else {
            return;
        };
        metal::dispatch_threadgroups_indirect(
            &self.encoder,
            address,
            size(pipeline.map(|v| v as u32)),
        );
    }

    pub fn end(self, gpu: &Gpu, hazards: &mut Hazards, timer: &mut PassTimer) {
        let mut published = Vec::new();
        for root in self.written {
            if !published.contains(&root) {
                if let Some(fence) = hazards.fence_resource(&gpu.device, root) {
                    self.encoder
                        .updateFence_afterEncoderStages(fence, MTLStages::Dispatch);
                }
                published.push(root);
            }
        }
        if !self.allow_concurrent {
            super::wait(
                &*self.encoder,
                MTLStages::Dispatch,
                MTLStages::Vertex | MTLStages::Fragment | MTLStages::Dispatch | MTLStages::Blit,
            );
        }
        self.encoder.endEncoding();
        timer.end(&gpu.cmd);
    }
}

fn push_constants_bytes(scratch: &mut Scratch, bytes: &[u8]) -> Option<u64> {
    let end = scratch.offset.checked_add(bytes.len())?;
    let next = scratch
        .offset
        .checked_add(bytes.len().next_multiple_of(256))?;
    if end > scratch.buffer.length() || !metal::write_shared(scratch.buffer, scratch.offset, bytes)
    {
        return None;
    }
    let address = scratch
        .buffer
        .gpuAddress()
        .checked_add(scratch.offset as u64)?;
    scratch.offset = next;
    Some(address)
}

#[cfg(test)]
mod tests {
    use super::{valid_dimensions, valid_indirect};

    #[test]
    fn rejects_zero_dispatch_dimensions() {
        assert!(!valid_dimensions([1, 0, 1]));
        assert!(valid_dimensions([1, 2, 1]));
    }

    #[test]
    fn validates_indirect_argument_alignment_and_extent() {
        assert!(valid_indirect(4, 16).is_some());
        assert!(valid_indirect(2, 16).is_none());
        assert!(valid_indirect(8, 19).is_none());
    }
}
