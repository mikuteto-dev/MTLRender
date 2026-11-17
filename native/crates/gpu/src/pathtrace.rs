use objc2::runtime::ProtocolObject;
use objc2_metal::{
    MTL4CommandBuffer, MTL4CommandEncoder, MTL4ComputeCommandEncoder, MTL4VisibilityOptions,
    MTLBuffer, MTLDevice, MTLPixelFormat, MTLSize, MTLStages, MTLTexture, MTLTextureUsage,
};

use crate::command::{Command, PathTraceRequest};
use crate::encode::{Scratch, push_constants, wait};
use crate::gpu::Gpu;
use crate::resources::Resources;
use crate::timing::{self, PassTimer};
use mtl4 as metal;
use mtl4::{Texture, TextureShape};

struct History {
    color: Texture,
    depth: Texture,
    width: usize,
    height: usize,
    format: MTLPixelFormat,
    scaler: Option<metal::TemporalDenoisedScaler>,
}

pub struct PathTracer {
    history: Option<History>,
    retired: Vec<(u64, History)>,
    history_valid: bool,
}

const GROUP: MTLSize = MTLSize {
    width: 8,
    height: 8,
    depth: 1,
};

fn size(width: usize, height: usize) -> MTLSize {
    MTLSize {
        width,
        height,
        depth: 1,
    }
}

fn words(request: &PathTraceRequest, step: u32, history_valid: bool) -> Vec<f32> {
    let mut values = Vec::with_capacity(55);
    values.push(f32::from_bits(request.width));
    values.push(f32::from_bits(request.height));
    values.push(f32::from_bits(step));
    values.push(f32::from_bits(request.bounces));
    values.extend(
        request
            .camera
            .iter()
            .map(|value| f32::from_bits(value.to_bits())),
    );
    values.extend(
        request
            .table_origin
            .iter()
            .map(|value| f32::from_bits(u32::from_ne_bytes(value.to_ne_bytes()))),
    );
    values.extend(
        request
            .inverse_clip
            .iter()
            .map(|value| f32::from_bits(value.to_bits())),
    );
    values.extend(
        request
            .previous_clip
            .iter()
            .map(|value| f32::from_bits(value.to_bits())),
    );
    values.push(f32::from_bits(u32::from(history_valid)));
    values.extend(
        request
            .sun_direction
            .iter()
            .map(|value| f32::from_bits(value.to_bits())),
    );
    values.extend(
        request
            .sun_color
            .iter()
            .map(|value| f32::from_bits(value.to_bits())),
    );
    values.extend(
        request
            .moon_direction
            .iter()
            .map(|value| f32::from_bits(value.to_bits())),
    );
    values.extend(
        request
            .moon_color
            .iter()
            .map(|value| f32::from_bits(value.to_bits())),
    );
    values
}

impl PathTracer {
    pub fn new() -> Self {
        Self {
            history: None,
            retired: Vec::new(),
            history_valid: false,
        }
    }

    fn release(&mut self, resources: &mut Resources, completed: u64) {
        let retired = std::mem::take(&mut self.retired);
        for (frame, history) in retired {
            if frame >= completed {
                self.retired.push((frame, history));
                continue;
            }
            resources.untrack(ProtocolObject::from_ref(&*history.color));
            resources.untrack(ProtocolObject::from_ref(&*history.depth));
            if let Some(scaler) = history.scaler.as_ref() {
                resources.untrack(ProtocolObject::from_ref(scaler.output()));
                resources.untrack(ProtocolObject::from_ref(scaler.motion()));
            }
        }
    }

    fn ensure(
        &mut self,
        gpu: &Gpu,
        resources: &mut Resources,
        request: &PathTraceRequest,
        frame: u64,
    ) -> bool {
        let Some(color) = resources.images.get(request.color) else {
            return false;
        };
        let Some(depth) = resources.images.get(request.depth) else {
            return false;
        };
        let width = color.texture.width();
        let height = color.texture.height();
        let format = color.texture.pixelFormat();
        if self.history.as_ref().is_some_and(|history| {
            history.width == width && history.height == height && history.format == format
        }) {
            return true;
        }
        let shape = TextureShape {
            width,
            height,
            layers: 1,
            mips: 1,
            cube: false,
        };
        let usage = MTLTextureUsage::ShaderRead | MTLTextureUsage::ShaderWrite;
        let Some(color_descriptor) = metal::texture_descriptor(format, &shape, usage) else {
            return false;
        };
        let Some(depth_descriptor) =
            metal::texture_descriptor(MTLPixelFormat::R32Float, &shape, usage)
        else {
            return false;
        };
        let Some(history_color) = gpu.device.newTextureWithDescriptor(&color_descriptor) else {
            return false;
        };
        let Some(history_depth) = gpu.device.newTextureWithDescriptor(&depth_descriptor) else {
            return false;
        };
        let scaler = metal::TemporalDenoisedScaler::new(
            &gpu.device,
            &gpu.compiler,
            format,
            depth.texture.pixelFormat(),
            width,
            height,
        );
        if let Some(old) = self.history.take() {
            self.retired.push((frame, old));
        }
        resources.track(ProtocolObject::from_ref(&*history_color));
        resources.track(ProtocolObject::from_ref(&*history_depth));
        if let Some(scaler) = scaler.as_ref() {
            resources.track(ProtocolObject::from_ref(scaler.output()));
            resources.track(ProtocolObject::from_ref(scaler.motion()));
        }
        self.history = Some(History {
            color: history_color,
            depth: history_depth,
            width,
            height,
            format,
            scaler,
        });
        self.history_valid = false;
        true
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
        for command in commands {
            if let Command::PathTrace(request) = command {
                let _ = self.ensure(gpu, resources, request, frame);
                break;
            }
        }
    }

    pub fn encode(
        &mut self,
        gpu: &Gpu,
        resources: &Resources,
        scratch: &mut Scratch,
        timer: &mut PassTimer,
        request: &PathTraceRequest,
    ) {
        let Some(color) = resources.images.get(request.color) else {
            return;
        };
        let Some(depth) = resources.images.get(request.depth) else {
            return;
        };
        let Some(bricks) = resources.buffers.get(request.bricks) else {
            return;
        };
        let Some(table) = resources.buffers.get(request.table) else {
            return;
        };
        let Some(history) = self.history.as_ref() else {
            return;
        };
        let scale = request.resolution_scale.clamp(0.125, 1.0);
        let step = (1.0 / scale).ceil() as u32;
        let Some(params) = push_constants(scratch, &words(request, step, self.history_valid))
        else {
            return;
        };
        metal::bind_address(&gpu.table, 0, bricks.gpuAddress());
        metal::bind_address(&gpu.table, 1, table.gpuAddress());
        metal::bind_address(&gpu.table, 2, params);
        metal::bind_texture(&gpu.table, 0, color.texture.gpuResourceID());
        metal::bind_texture(&gpu.table, 1, depth.texture.gpuResourceID());
        metal::bind_texture(&gpu.table, 2, history.color.gpuResourceID());
        metal::bind_texture(&gpu.table, 3, history.depth.gpuResourceID());
        let width = (request.width as usize).div_ceil(step as usize);
        let height = (request.height as usize).div_ceil(step as usize);
        if width == 0 || height == 0 {
            return;
        }
        timer.begin(&gpu.cmd, timing::PASS_PATH_TRACE);
        let Some(encoder) = gpu.cmd.computeCommandEncoder() else {
            timer.end(&gpu.cmd);
            return;
        };
        wait(&*encoder, MTLStages::Fragment, MTLStages::Dispatch);
        encoder.setArgumentTable(Some(&gpu.table));
        encoder.setComputePipelineState(&gpu.kernels.path_trace);
        encoder.dispatchThreads_threadsPerThreadgroup(size(width, height), GROUP);
        encoder.barrierAfterStages_beforeQueueStages_visibilityOptions(
            MTLStages::Dispatch,
            MTLStages::Fragment,
            MTL4VisibilityOptions::Device,
        );
        encoder.endEncoding();
        timer.end(&gpu.cmd);
        timer.begin(&gpu.cmd, timing::PASS_PATH_DENOISE);
        if let Some(scaler) = history.scaler.as_ref() {
            scaler.encode(&gpu.cmd, &color.texture, &depth.texture);
            let Some(copy) = gpu.cmd.computeCommandEncoder() else {
                timer.end(&gpu.cmd);
                return;
            };
            wait(&*copy, MTLStages::Dispatch, MTLStages::Dispatch);
            let region = metal::Region {
                texture_slice: 0,
                mip: 0,
                x: 0,
                y: 0,
                width: request.width as usize,
                height: request.height as usize,
            };
            metal::copy_texture_to_texture(
                &copy,
                scaler.output(),
                &region,
                &color.texture,
                &region,
            );
            copy.barrierAfterStages_beforeQueueStages_visibilityOptions(
                MTLStages::Blit,
                MTLStages::Fragment,
                MTL4VisibilityOptions::Device,
            );
            copy.endEncoding();
        } else {
            let Some(denoiser) = gpu.cmd.computeCommandEncoder() else {
                timer.end(&gpu.cmd);
                return;
            };
            wait(&*denoiser, MTLStages::Dispatch, MTLStages::Dispatch);
            denoiser.setArgumentTable(Some(&gpu.table));
            denoiser.setComputePipelineState(&gpu.kernels.path_atrous);
            denoiser.dispatchThreads_threadsPerThreadgroup(
                size(request.width as usize, request.height as usize),
                GROUP,
            );
            denoiser.barrierAfterStages_beforeQueueStages_visibilityOptions(
                MTLStages::Dispatch,
                MTLStages::Fragment,
                MTL4VisibilityOptions::Device,
            );
            denoiser.endEncoding();
        }
        timer.end(&gpu.cmd);
        self.history_valid = true;
    }
}
