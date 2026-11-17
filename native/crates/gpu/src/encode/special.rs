use std::collections::hash_map::Entry;

use objc2::rc::Retained;
use objc2::runtime::ProtocolObject;
use objc2_metal::{
    MTL4CommandBuffer, MTL4CommandEncoder, MTL4RenderCommandEncoder, MTL4RenderPassDescriptor,
    MTLClearColor, MTLCompareFunction, MTLPrimitiveType, MTLStages, MTLStoreAction, MTLTexture,
};
use objc2_quartz_core::CAMetalDrawable;

use super::render::{attach_color, attach_depth, scissor, viewport};
use super::{Scratch, begin_render, end_render, push_constants, wait};
use crate::command::MetalFxRequest;
use crate::gpu::Gpu;
use crate::hazard::Hazards;
use crate::resources::{Cache, Resources};
use crate::stats::{self, Counter};
use crate::surface::Surface;
use crate::timing::{self, PassTimer};
use mtl4::{self as metal, Fx, FxMode};

pub struct RegionClear {
    pub color: u64,
    pub rgba: MTLClearColor,
    pub depth: u64,
    pub depth_value: f32,
    pub area: [i32; 4],
}

pub fn clear(
    gpu: &Gpu,
    resources: &Resources,
    hazards: &mut Hazards,
    timer: &mut PassTimer,
    handle: u64,
    color: MTLClearColor,
    depth: f32,
) {
    let Some(image) = resources.images.get(handle) else {
        return;
    };
    let descriptor = MTL4RenderPassDescriptor::new();
    if image.depth {
        attach_depth(
            &descriptor,
            &image.texture,
            Some(depth),
            MTLStoreAction::Store,
        );
    } else {
        attach_color(
            &descriptor,
            0,
            &image.texture,
            Some(color),
            MTLStoreAction::Store,
        );
    }
    let stages = hazards.write(image.root);
    if let Some(encoder) = begin_render(gpu, timer, timing::PASS_CLEAR, &descriptor) {
        if !stages.is_empty() {
            wait(&*encoder, stages, MTLStages::Fragment);
        }
        if let Some(fence) = hazards.fence(&gpu.device, image.root) {
            encoder.updateFence_afterEncoderStages(fence, MTLStages::Fragment);
        }
        end_render(gpu, timer, &encoder);
    }
}

pub fn clear_region(
    gpu: &Gpu,
    resources: &Resources,
    cache: &mut Cache,
    hazards: &mut Hazards,
    scratch: &mut Scratch,
    timer: &mut PassTimer,
    request: RegionClear,
) {
    let Some(color) = resources.images.get(request.color) else {
        return;
    };
    let depth = resources.images.get(request.depth);
    let format = color.texture.pixelFormat();
    let pipeline = match cache.clear_pipelines.entry(format.0 as isize) {
        Entry::Occupied(entry) => entry.into_mut(),
        Entry::Vacant(entry) => {
            let Some(state) = gpu.clear_pipeline(format) else {
                return;
            };
            entry.insert(state)
        }
    };
    let rgba = request.rgba;
    let values = [
        rgba.red,
        rgba.green,
        rgba.blue,
        rgba.alpha,
        f64::from(request.depth_value),
        0.0,
        0.0,
        0.0,
    ]
    .map(|v| v as f32);
    let Some(address) = push_constants(scratch, &values) else {
        return;
    };

    let descriptor = MTL4RenderPassDescriptor::new();
    attach_color(&descriptor, 0, &color.texture, None, MTLStoreAction::Store);
    let mut stages = hazards.write(color.root);
    if let Some(depth) = depth {
        attach_depth(&descriptor, &depth.texture, None, MTLStoreAction::Store);
        stages |= hazards.write(depth.root);
    }
    let Some(encoder) = begin_render(gpu, timer, timing::PASS_CLEAR_REGION, &descriptor) else {
        return;
    };
    if !stages.is_empty() {
        wait(&*encoder, stages, MTLStages::Fragment);
    }
    let (width, height) = (color.texture.width() as f64, color.texture.height() as f64);
    encoder.setViewport(viewport(width, height));
    encoder.setScissorRect(scissor(request.area, width, height));
    encoder.setRenderPipelineState(pipeline);
    encoder.setDepthStencilState(
        gpu.depth_states
            .get(MTLCompareFunction::Always, depth.is_some()),
    );
    metal::bind_address(&gpu.table, 0, address);
    metal::draw(&encoder, MTLPrimitiveType::Triangle, 0, 3, 1, 0);
    if let Some(fence) = hazards.fence(&gpu.device, color.root) {
        encoder.updateFence_afterEncoderStages(fence, MTLStages::Fragment);
    }
    if let Some(depth) = depth
        && let Some(fence) = hazards.fence(&gpu.device, depth.root)
    {
        encoder.updateFence_afterEncoderStages(fence, MTLStages::Fragment);
    }
    end_render(gpu, timer, &encoder);
}

pub struct FxRetirement<'a> {
    pub current: &'a mut Option<Fx>,
    pub retired: &'a mut Vec<(u64, Fx)>,
    pub frame: u64,
}

pub fn metalfx(
    gpu: &Gpu,
    resources: &Resources,
    hazards: &mut Hazards,
    scratch: &mut Scratch,
    timer: &mut PassTimer,
    retirement: FxRetirement<'_>,
    request: MetalFxRequest,
) -> bool {
    let FxRetirement {
        current: fx,
        retired: retired_fx,
        frame: submitted_frame,
    } = retirement;
    if request.mode == 3 {
        let Some(color) = resources.images.get(request.current_color) else {
            return false;
        };
        let Some(previous_color) = resources.images.get(request.previous_color) else {
            return false;
        };
        let Some(depth) = resources.images.get(request.depth) else {
            return false;
        };
        let Some(motion) = resources.images.get(request.motion) else {
            return false;
        };
        let Some(output) = resources.images.get(request.output) else {
            return false;
        };
        let ui = if request.ui == 0 {
            None
        } else {
            resources.images.get(request.ui)
        };
        if request.ui != 0 && ui.is_none() {
            return false;
        }
        if request.ui_composited && ui.is_none() {
            return false;
        }
        if !request.world_to_view.iter().all(|value| value.is_finite())
            || !request.view_to_clip.iter().all(|value| value.is_finite())
        {
            return false;
        }
        let input = (request.input[0] as usize, request.input[1] as usize);
        let output_size = (
            request.output_size[0] as usize,
            request.output_size[1] as usize,
        );
        if !fx
            .as_ref()
            .is_some_and(|effect| effect.matches(FxMode::FrameInterpolation, input, output_size))
        {
            if let Some(previous) = fx.take() {
                retired_fx.push((submitted_frame, previous));
            }
            *fx = Fx::new(
                &gpu.device,
                &gpu.compiler,
                FxMode::FrameInterpolation,
                input,
                output_size,
            );
        }
        let Some(effect) = fx.as_ref() else {
            return false;
        };
        let color_read = hazards.read(color.root, MTLStages::Dispatch);
        let previous_read = hazards.read(previous_color.root, MTLStages::Dispatch);
        let depth_read = hazards.read(depth.root, MTLStages::Dispatch);
        let motion_read = hazards.read(motion.root, MTLStages::Dispatch);
        let ui_read = ui.map(|image| hazards.read(image.root, MTLStages::Dispatch));
        hazards.write(output.root);
        let Some(encoder) = gpu.cmd.computeCommandEncoder() else {
            return false;
        };
        timer.begin(&gpu.cmd, timing::PASS_METALFX);
        for (root, rendered) in [
            (color.root, color_read),
            (previous_color.root, previous_read),
            (depth.root, depth_read),
            (motion.root, motion_read),
        ] {
            if rendered && let Some(fence) = hazards.fence(&gpu.device, root) {
                encoder.waitForFence_beforeEncoderStages(fence, MTLStages::Dispatch);
            }
        }
        if let Some(ui) = ui
            && ui_read == Some(true)
            && let Some(fence) = hazards.fence(&gpu.device, ui.root)
        {
            encoder.waitForFence_beforeEncoderStages(fence, MTLStages::Dispatch);
        }
        if color_read || previous_read || depth_read || motion_read || ui_read == Some(true) {
            wait(&*encoder, MTLStages::Fragment, MTLStages::Dispatch);
        }
        encoder.endEncoding();
        let encoded = effect.encode_frame_interpolation(metal::FrameInterpolationRequest {
            command: &gpu.cmd,
            color: &color.texture,
            previous_color: Some(&previous_color.texture),
            depth: &depth.texture,
            motion: &motion.texture,
            ui: ui.map(|image| &*image.texture),
            output: &output.texture,
            delta_time: request.delta_time,
            camera: (
                request.camera[0],
                request.camera[1],
                request.camera[2],
                request.camera[3],
            ),
            jitter: (request.jitter[0], request.jitter[1]),
            motion_scale: (request.motion_scale[0], request.motion_scale[1]),
            reset: request.reset,
            depth_reversed: request.depth_reversed,
            ui_composited: request.ui_composited,
        });
        if !encoded {
            timer.end(&gpu.cmd);
            return false;
        }
        let Some(publish) = gpu.cmd.computeCommandEncoder() else {
            timer.end(&gpu.cmd);
            return false;
        };
        wait(&*publish, MTLStages::Dispatch, MTLStages::Fragment);
        if let Some(fence) = hazards.fence(&gpu.device, output.root) {
            publish.updateFence_afterEncoderStages(fence, MTLStages::Fragment);
        }
        publish.endEncoding();
        timer.end(&gpu.cmd);
        return true;
    }
    let mode = match request.mode {
        1 => FxMode::Spatial,
        2 => FxMode::Temporal,
        _ => return false,
    };
    let input = (request.input[0] as usize, request.input[1] as usize);
    let output = (
        request.output_size[0] as usize,
        request.output_size[1] as usize,
    );
    if !fx
        .as_ref()
        .is_some_and(|effect| effect.matches(mode, input, output))
    {
        if let Some(previous) = fx.take() {
            retired_fx.push((submitted_frame, previous));
        }
        *fx = Fx::new(&gpu.device, &gpu.compiler, mode, input, output);
    }
    let Some(effect) = fx.as_mut() else {
        return false;
    };
    let Some(color) = resources.images.get(request.color) else {
        return false;
    };
    let Some(output_image) = resources.images.get(request.output) else {
        return false;
    };
    let Some(depth) = resources.images.get(request.depth) else {
        return false;
    };
    hazards.read(color.root, MTLStages::Dispatch);
    hazards.read(depth.root, MTLStages::Dispatch);
    hazards.write(output_image.root);
    if mode == FxMode::Temporal {
        let Some(motion) = effect.motion() else {
            return false;
        };
        let Some(current) = push_constants(scratch, &request.current_inverse) else {
            return false;
        };
        let Some(previous) = push_constants(scratch, &request.previous_clip) else {
            return false;
        };
        let Some(encoder) = gpu.cmd.computeCommandEncoder() else {
            return false;
        };
        timer.begin(&gpu.cmd, timing::PASS_METALFX);
        wait(&*encoder, MTLStages::Fragment, MTLStages::Dispatch);
        metal::encode_motion(metal::MotionRequest {
            encoder: &encoder,
            table: &gpu.table,
            pipeline: &gpu.kernels.fx_motion,
            depth: &depth.texture,
            motion,
            current,
            previous,
            width: input.0,
            height: input.1,
        });
    }
    if mode == FxMode::Spatial {
        timer.begin(&gpu.cmd, timing::PASS_METALFX);
    }
    let encoded = match mode {
        FxMode::Spatial => effect.encode_spatial(&gpu.cmd, &color.texture, &output_image.texture),
        FxMode::Temporal => effect.encode_temporal(
            &gpu.cmd,
            &color.texture,
            &depth.texture,
            &output_image.texture,
            (request.jitter[0], request.jitter[1]),
            request.reset,
        ),
        FxMode::FrameInterpolation => false,
    };
    timer.end(&gpu.cmd);
    if !encoded {
        *fx = None;
    }
    encoded
}

pub struct PresentTarget<'a> {
    pub surface: &'a mut Surface,
    pub submitted_frame: u64,
    pub evaluation: bool,
}

pub fn blit(
    gpu: &Gpu,
    resources: &Resources,
    hazards: &mut Hazards,
    timer: &mut PassTimer,
    target: PresentTarget,
    view: u64,
) -> Option<Retained<ProtocolObject<dyn CAMetalDrawable>>> {
    let PresentTarget {
        surface,
        submitted_frame,
        evaluation,
    } = target;
    let source = resources.images.get(view)?;
    let rendered = hazards.read(source.root, MTLStages::Fragment);
    if surface.offscreen() {
        return None;
    }
    let drawable = stats::timed(Counter::Drawable, || {
        surface.next_drawable(submitted_frame, evaluation)
    })?;
    let target = drawable.texture();
    let descriptor = MTL4RenderPassDescriptor::new();
    attach_color(
        &descriptor,
        0,
        &target,
        Some(MTLClearColor {
            red: 0.0,
            green: 0.0,
            blue: 0.0,
            alpha: 1.0,
        }),
        MTLStoreAction::Store,
    );
    let encoder = begin_render(gpu, timer, timing::PASS_PRESENT, &descriptor)?;
    if rendered {
        wait(&*encoder, MTLStages::Fragment, MTLStages::Fragment);
    }
    let width = source.texture.width().min(target.width()) as f64;
    let height = source.texture.height().min(target.height()) as f64;
    encoder.setViewport(viewport(width, height));
    encoder.setRenderPipelineState(&gpu.blit);
    metal::bind_texture(&gpu.table, 0, source.texture.gpuResourceID());
    metal::draw(&encoder, MTLPrimitiveType::Triangle, 0, 3, 1, 0);
    end_render(gpu, timer, &encoder);
    Some(drawable)
}
