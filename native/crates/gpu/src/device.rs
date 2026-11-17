use objc2::rc::Retained;
use objc2::runtime::ProtocolObject;
use objc2_app_kit::NSWindow;
use objc2_metal::{
    MTL4CommandBuffer, MTL4CommandEncoder, MTL4CommandQueue, MTL4ComputeCommandEncoder,
    MTL4RenderCommandEncoder, MTL4RenderPassDescriptor, MTL4VisibilityOptions, MTLBuffer,
    MTLClearColor, MTLCompareFunction, MTLCullMode, MTLDevice, MTLDrawable, MTLLoadAction,
    MTLPixelFormat, MTLPrimitiveType, MTLRenderStages, MTLResidencySet, MTLResidencySetDescriptor,
    MTLResourceOptions, MTLSamplerAddressMode, MTLSamplerDescriptor, MTLSamplerMinMagFilter,
    MTLSamplerMipFilter, MTLScissorRect, MTLSize, MTLStages, MTLStoreAction, MTLTexture,
    MTLTextureUsage, MTLTriangleFillMode, MTLViewport, MTLWinding,
};
use std::ffi::c_void;
use std::sync::mpsc::{Receiver, TryRecvError, sync_channel};

use crate::calibration::{CalibrationMode, CalibrationResolution};
use crate::command::{Command, Decoder};
use crate::cull::{CalibrationHiz, Culler};
use crate::encode::{Context, Encoder};
use crate::eval::{self, EvalFrame, EvalSession};
use crate::expand::Expander;
use crate::format;
use crate::frames::{CalibrationOwner, Frames};
use crate::geometry_runtime::GeometryRuntime;
use crate::gpu::{self, Caps, Gpu};
use crate::hazard::Hazards;
use crate::lod::LodCuller;
use crate::pathtrace::PathTracer;
use crate::pipeline;
use crate::pipeline::Pipeline;
use crate::plan;
use crate::resources::{Cache, Image, Kind, Resources};
use crate::sort::Sorter;
use crate::stats::{self, Counter};
use crate::surface::Surface;
use crate::timing::PassTimes;
use mtl4::{self as metal, Fx, TextureShape};

struct CalibrationHandles([u64; 10]);

struct CalibrationResources {
    width: usize,
    height: usize,
    target: u64,
    depth: u64,
    hiz: u64,
    snapshot: u64,
    pipeline: u64,
    depth_pipeline: u64,
    mesh_pipeline: u64,
    values: u64,
    indices: u64,
    query: u64,
}

const CALIBRATION_DRAWS: usize = 96;
const CALIBRATION_WARMUP_SAMPLES: usize = 1;
const CALIBRATION_SAMPLE_COUNT: usize = 3;
const CALIBRATION_QUERY_CAPACITY: usize = 128;
const CALIBRATION_WAIT_TIMEOUT_MS: u64 = 5000;

struct CalibrationSlots {
    next: usize,
    capacity: usize,
}

impl CalibrationSlots {
    fn new(capacity: usize) -> Self {
        Self { next: 0, capacity }
    }

    fn allocate(&mut self) -> Option<usize> {
        let end = self.next.checked_add(2)?;
        if end > self.capacity {
            return None;
        }
        let slot = self.next;
        self.next = end;
        Some(slot)
    }
}

fn calibration_hiz_layout(width: usize, height: usize) -> (usize, usize, usize) {
    let level_width = width.div_ceil(2).max(1).next_power_of_two();
    let level_height = height.div_ceil(2).max(1).next_power_of_two();
    let longest = level_width.max(level_height);
    let count = longest.trailing_zeros() as usize + 1;
    (level_width, level_height, count)
}

#[derive(Clone, Copy)]
enum CalibrationPath {
    NonIndexed,
    Indexed,
    Mesh,
}

pub struct Device {
    gpu: Gpu,
    resources: Resources,
    culler: Culler,
    calibration_hiz: CalibrationHiz,
    expander: Expander,
    lod_culler: LodCuller,
    path_tracer: PathTracer,
    geometry_runtime: GeometryRuntime,
    sorter: Sorter,
    cache: Cache,
    archive: Option<pipeline::Archive>,
    hazards: Hazards,
    frames: Frames,
    calibration: Option<CalibrationOwner>,
    passes: PassTimes,
    pass_timing: bool,
    feedback: Vec<Receiver<mtl4::CommitFeedback>>,
    eval: EvalSession,
    last_error: String,
    capture_path: Option<String>,
    fx: Option<Fx>,
    retired_fx: Vec<(u64, Fx)>,
    pending_calibration: Vec<(u64, CalibrationResources)>,
    _activity: mtl4_sys::Activity,
}

impl Drop for Device {
    fn drop(&mut self) {
        if self.frames.submitted > 0 {
            let _ = self.frames.wait(self.frames.submitted - 1, 5000);
        }
    }
}

pub struct SamplerDesc {
    pub repeat_u: bool,
    pub repeat_v: bool,
    pub linear_min: bool,
    pub linear_mag: bool,
    pub max_anisotropy: u32,
    pub max_lod: f32,
}

impl Device {
    pub fn new(pass_timing: bool) -> Option<Self> {
        Self::new_with_archive(pass_timing, None)
    }

    pub fn new_with_archive(
        pass_timing: bool,
        archive_config: Option<pipeline::ArchiveConfig>,
    ) -> Option<Self> {
        let (gpu, archive) = Gpu::new_with_archive(archive_config)?;
        let residency = gpu
            .device
            .newResidencySetWithDescriptor_error(&MTLResidencySetDescriptor::new())
            .ok()?;
        gpu.queue.addResidencySet(&residency);
        let frames = Frames::new(&gpu.device)?;
        let calibration = CalibrationOwner::new(&gpu.device, &gpu.queue);
        residency.addAllocation(ProtocolObject::from_ref(&*gpu.fan_indices));
        for frame in frames.slots() {
            residency.addAllocation(ProtocolObject::from_ref(&*frame.scratch));
        }
        residency.commit();
        let passes = PassTimes::new(&gpu.device, pass_timing)?;
        let mut resources = Resources::new(residency);
        let culler = Culler::new(&gpu, &mut resources)?;
        let geometry_runtime = GeometryRuntime::initialize(&gpu, &mut resources)?;
        let calibration_hiz = CalibrationHiz::new();
        let expander = Expander::new(&gpu, &mut resources)?;
        let lod_culler = LodCuller::new(&gpu, &mut resources)?;
        let sorter = Sorter::new(&gpu, &mut resources)?;
        Some(Self {
            gpu,
            resources,
            culler,
            calibration_hiz,
            expander,
            lod_culler,
            path_tracer: PathTracer::new(),
            geometry_runtime,
            sorter,
            cache: Cache::default(),
            archive,
            hazards: Hazards::default(),
            frames,
            calibration,
            passes,
            pass_timing,
            feedback: Vec::new(),
            eval: EvalSession::default(),
            last_error: String::new(),
            capture_path: None,
            fx: None,
            retired_fx: Vec::new(),
            pending_calibration: Vec::new(),
            _activity: mtl4_sys::Activity::begin("MTLRender rendering"),
        })
    }

    pub fn name(&self) -> String {
        self.gpu.device.name().to_string()
    }

    pub fn caps(&self) -> Caps {
        gpu::caps(&self.gpu.device)
    }

    pub fn last_error(&self) -> &str {
        &self.last_error
    }

    pub fn set_error(&mut self, error: String) {
        self.last_error = error;
    }

    pub fn pipeline_archive_status(&self) -> u32 {
        match self.archive.as_ref() {
            None => 0,
            Some(archive) if archive.loaded() => 2,
            Some(_) => 1,
        }
    }

    pub fn pipeline_archive_flush(&mut self) -> bool {
        self.archive
            .as_mut()
            .is_none_or(|archive| archive.flush().is_ok())
    }

    pub fn frame(&self) -> u64 {
        self.frames.submitted
    }

    pub fn completed_frames(&self) -> u64 {
        self.frames.completed()
    }

    pub fn wait_frame(&self, frame: u64, timeout_ms: u64) -> bool {
        self.frames.wait(frame, timeout_ms)
    }

    pub fn capture_next(&mut self, path: &str) -> bool {
        if path.is_empty() || self.capture_path.is_some() {
            return false;
        }
        self.capture_path = Some(path.to_owned());
        true
    }

    pub fn allocated_bytes(&self) -> u64 {
        mtl4::current_allocated_size(&self.gpu.device).unwrap_or(0)
    }

    pub fn project_allocated_bytes(&self) -> u64 {
        self.resources
            .memory_bytes()
            .saturating_add(self.frames.memory_bytes())
            .saturating_add(self.gpu.memory_bytes())
    }

    pub fn phys_footprint_bytes(&self) -> u64 {
        mtl4::phys_footprint_bytes().unwrap_or(0)
    }

    pub fn eval_start(&mut self) -> bool {
        self.passes.set_enabled(true);
        self.eval.start()
    }

    pub fn eval_stop(&mut self) {
        self.passes.set_enabled(self.pass_timing);
        self.eval.stop();
    }

    pub fn eval_read(&mut self, out: &mut [EvalFrame]) -> usize {
        self.eval.read(out)
    }

    pub fn eval_energy_source(&self) -> &'static str {
        eval::ENERGY_SOURCE
    }

    pub fn eval_record_present(&mut self, timestamp_ns: u64, interval_ns: u64) {
        self.eval.record_present(timestamp_ns, interval_ns);
    }

    pub fn eval_record_gpu(&mut self, busy_ns: u64) {
        self.eval.record_gpu(busy_ns);
    }

    pub fn eval_record_energy(&mut self, energy_nj: u64) {
        self.eval.record_energy(energy_nj);
    }

    pub fn eval_record_process(&mut self, cpu_process_ns: Option<u64>, thread_count: Option<u32>) {
        self.eval.record_process(cpu_process_ns, thread_count);
    }

    pub fn eval_record_memory(
        &mut self,
        phys_footprint_bytes: Option<u64>,
        metal_allocated_size_bytes: Option<u64>,
    ) {
        self.eval
            .record_memory(phys_footprint_bytes, metal_allocated_size_bytes);
    }

    pub fn create_surface(&mut self, window: &NSWindow) -> Option<Surface> {
        Surface::attach(&self.gpu.device, &self.gpu.queue, window)
    }

    pub fn create_buffer(&mut self, size: usize, initial: &[u8]) -> Option<(u64, *mut c_void)> {
        let buffer = self
            .gpu
            .device
            .newBufferWithLength_options(size.max(16), MTLResourceOptions::StorageModeShared)?;
        let zeros = [0_u8; 4096];
        let mut offset = 0;
        while offset < size {
            let length = (size - offset).min(zeros.len());
            let chunk = zeros.get(..length)?;
            if !metal::write_shared(&buffer, offset, chunk) {
                return None;
            }
            offset += length;
        }
        if !initial.is_empty() && !metal::write_shared(&buffer, 0, initial) {
            return None;
        }
        self.resources.track(ProtocolObject::from_ref(&*buffer));
        let contents = buffer.contents().as_ptr();
        Some((self.resources.buffers.insert(buffer), contents))
    }

    pub fn buffer_address(&self, handle: u64) -> u64 {
        self.resources
            .buffers
            .get(handle)
            .map_or(0, |buffer| buffer.gpuAddress())
    }

    pub fn create_texture(
        &mut self,
        format: u32,
        shape: &TextureShape,
        render_target: bool,
        shader_write: bool,
    ) -> Option<u64> {
        let mut usage = MTLTextureUsage::ShaderRead;
        if render_target {
            usage |= MTLTextureUsage::RenderTarget;
        }
        if shader_write {
            usage |= MTLTextureUsage::ShaderWrite;
        }
        let descriptor = metal::texture_descriptor(MTLPixelFormat(format as _), shape, usage)?;
        let texture = self.gpu.device.newTextureWithDescriptor(&descriptor)?;
        self.resources.track(ProtocolObject::from_ref(&*texture));
        let root = Retained::as_ptr(&texture).addr();
        Some(self.resources.images.insert(Image {
            usage: usage.0 as u64,
            texture,
            depth: format::is_depth(format),
            root,
            level: 0,
        }))
    }

    pub fn create_view(&mut self, texture: u64, base_mip: u32, mips: u32) -> Option<u64> {
        let parent = self.resources.images.get(texture)?;
        let view = metal::texture_view(&parent.texture, base_mip as usize, mips as usize)?;
        let image = Image {
            usage: parent.usage,
            texture: view,
            depth: parent.depth,
            root: parent.root,
            level: parent.level + base_mip as usize,
        };
        Some(self.resources.images.insert(image))
    }

    pub fn create_sampler(&mut self, desc: &SamplerDesc) -> Option<u64> {
        let address = |repeat| {
            if repeat {
                MTLSamplerAddressMode::Repeat
            } else {
                MTLSamplerAddressMode::ClampToEdge
            }
        };
        let filter = |linear| {
            if linear {
                MTLSamplerMinMagFilter::Linear
            } else {
                MTLSamplerMinMagFilter::Nearest
            }
        };
        let descriptor = MTLSamplerDescriptor::new();
        descriptor.setSAddressMode(address(desc.repeat_u));
        descriptor.setTAddressMode(address(desc.repeat_v));
        descriptor.setMinFilter(filter(desc.linear_min));
        descriptor.setMagFilter(filter(desc.linear_mag));
        if desc.max_lod > 0.25 {
            descriptor.setMipFilter(MTLSamplerMipFilter::Linear);
            descriptor.setLodMaxClamp(desc.max_lod);
        } else {
            descriptor.setMipFilter(MTLSamplerMipFilter::NotMipmapped);
        }
        descriptor.setMaxAnisotropy(desc.max_anisotropy.clamp(1, 16) as usize);
        descriptor.setSupportArgumentBuffers(true);
        let sampler = self.gpu.device.newSamplerStateWithDescriptor(&descriptor)?;
        Some(self.resources.samplers.insert(sampler))
    }

    pub fn create_pipeline(
        &mut self,
        vertex: (&str, &str),
        fragment: (&str, &str),
        desc: &[u32],
    ) -> Option<u64> {
        match pipeline::create(
            &self.gpu.compiler,
            None,
            self.gpu.builtin_library(),
            self.gpu.raster_order_groups_supported(),
            vertex,
            fragment,
            desc,
        ) {
            Ok(pipeline) => Some(self.resources.pipelines.insert(pipeline)),
            Err(error) => {
                self.last_error = error;
                None
            }
        }
    }

    pub fn create_pack_pipeline(
        &mut self,
        vertex: (&str, &str),
        fragment: (&str, &str),
        desc: &[u32],
    ) -> Option<u64> {
        let archive = self
            .archive
            .as_ref()
            .and_then(|value| value.metal.as_deref());
        match pipeline::create(
            &self.gpu.compiler,
            archive,
            self.gpu.builtin_library(),
            self.gpu.raster_order_groups_supported(),
            vertex,
            fragment,
            desc,
        ) {
            Ok(pipeline) => {
                if let Some(archive) = self.archive.as_mut() {
                    archive.mark_dirty();
                }
                Some(self.resources.pipelines.insert(pipeline))
            }
            Err(error) => {
                self.last_error = error;
                None
            }
        }
    }

    pub fn create_mesh_pipeline(
        &mut self,
        object: (&str, &str),
        mesh: (&str, &str),
        fragment: (&str, &str),
        desc: &[u32],
    ) -> Option<u64> {
        match pipeline::create_mesh(
            &self.gpu.compiler,
            self.gpu.builtin_library(),
            self.gpu.raster_order_groups_supported(),
            object,
            mesh,
            fragment,
            desc,
        ) {
            Ok(pipeline) => Some(self.resources.pipelines.insert(pipeline)),
            Err(error) => {
                self.last_error = error;
                None
            }
        }
    }

    pub fn create_compute_pipeline(
        &mut self,
        source: &str,
        entry: &str,
        threads_per_threadgroup: [u32; 3],
    ) -> Option<u64> {
        match pipeline::create_compute(&self.gpu.compiler, source, entry, threads_per_threadgroup) {
            Ok(pipeline) => Some(self.resources.compute_pipelines.insert(pipeline)),
            Err(error) => {
                self.last_error = error;
                None
            }
        }
    }

    pub fn create_query_pool(&mut self, count: usize) -> Option<u64> {
        let heap = metal::timestamp_heap(&self.gpu.device, count)?;
        Some(self.resources.queries.insert(heap))
    }

    pub fn resolve_queries(&self, pool: u64, first: usize, out: &mut [u64]) -> bool {
        self.resources
            .queries
            .get(pool)
            .is_some_and(|heap| metal::resolve_timestamps(heap, first, out))
    }

    pub fn timestamp_frequency(&self) -> u64 {
        self.gpu.device.queryTimestampFrequency()
    }

    pub fn pass_times(&self, out: &mut [u64]) -> usize {
        let records = out.as_chunks_mut::<3>().0;
        let resolved = self.passes.resolved();
        for (record, time) in records.iter_mut().zip(resolved) {
            *record = [u64::from(time.id), time.nanos, u64::from(time.passes)];
        }
        records.len().min(resolved.len())
    }

    pub fn destroy(&mut self, kind: Kind, handle: u64) {
        self.resources
            .destroy_later(self.frames.submitted, kind, handle);
    }

    fn drain_feedback(&mut self) {
        self.feedback.retain_mut(|receiver| {
            loop {
                match receiver.try_recv() {
                    Ok(feedback) => self.eval.record_feedback(
                        feedback.frame,
                        feedback.start_ns,
                        feedback.end_ns,
                        feedback.valid,
                    ),
                    Err(TryRecvError::Empty) => return true,
                    Err(TryRecvError::Disconnected) => return false,
                }
            }
        });
    }

    pub fn submit(&mut self, stream: &[u32], mut surface: Option<&mut Surface>) {
        self.retire_completed_calibrations();
        self.drain_feedback();
        if let Some(surface) = surface.as_deref_mut() {
            surface.drain_presented(&mut self.eval);
        }
        let evaluation = self.eval.active();
        let Self {
            gpu,
            resources,
            culler,
            expander,
            path_tracer,
            geometry_runtime,
            sorter,
            cache,
            hazards,
            frames,
            passes,
            pass_timing: _,
            feedback,
            eval,
            capture_path,
            fx,
            retired_fx,
            ..
        } = self;
        let Some(frame) = stats::timed(Counter::FrameWait, || frames.begin()) else {
            return;
        };
        let completed = frames.completed();
        if !passes.start(frames.submitted, completed) {
            return;
        }
        let pass_busy = passes.busy();
        eval.begin_frame(frames.submitted);
        if evaluation {
            let process = mtl4::process_metrics();
            eval.record_process(process.cpu_process_ns, process.cpu_thread_count);
            eval.record_memory(
                process.phys_footprint_bytes,
                mtl4::current_allocated_size(&gpu.device),
            );
        }
        if let Some(busy) = pass_busy {
            eval.record_gpu_for_frame(busy.frame, busy.nanos, busy.valid);
        }
        let Some(timer) = passes.timer(frames.submitted) else {
            return;
        };
        resources.bury(completed, cache);
        retired_fx.retain(|(frame, _)| *frame >= completed);
        let mut commands: Vec<Command> = Decoder::new(stream).collect();
        geometry_runtime.prepare(frames.submitted, completed);
        culler.prepare(gpu, resources, &commands, frames.submitted, completed);
        expander.prepare(gpu, resources, &commands, frames.submitted, completed);
        path_tracer.prepare(gpu, resources, &commands, frames.submitted, completed);
        sorter.prepare(gpu, resources, &commands, frames.submitted, completed);
        resources.commit_residency();
        hazards.begin_frame(frames.submitted, completed);
        let capture = capture_path.take().and_then(|path| gpu.capture(&path));
        gpu.cmd.beginCommandBufferWithAllocator(&frame.allocator);
        let drawables = stats::timed(Counter::Encode, || {
            let steps = plan::plan(&mut commands, |view| {
                resources
                    .images
                    .get(view)
                    .map(|image| (image.root, image.level))
            });
            let mut encoder = Encoder::new(
                Context {
                    gpu,
                    resources,
                    culler,
                    expander,
                    lod_culler: &self.lod_culler,
                    geometry_runtime,
                    path_tracer,
                    sorter,
                    cache,
                    hazards,
                    scratch: &frame.scratch,
                    surface,
                    submitted_frame: frames.submitted,
                    evaluation,
                    fx,
                    retired_fx,
                },
                timer,
            );
            for (command, step) in commands.into_iter().zip(steps) {
                encoder.encode(command, step);
            }
            let drawables = encoder
                .finish()
                .into_iter()
                .map(ProtocolObject::<dyn MTLDrawable>::from_retained)
                .collect::<Vec<_>>();
            culler.finish();
            gpu.cmd.endCommandBuffer();
            drawables
        });
        for drawable in &drawables {
            gpu.queue.waitForDrawable(drawable);
        }
        let (feedback_sender, feedback_receiver) = sync_channel(8);
        stats::timed(Counter::Commit, || {
            metal::commit(
                &gpu.queue,
                &gpu.cmd,
                frames.submitted,
                feedback_sender,
                stats::record_gpu_seconds,
            );
        });
        feedback.push(feedback_receiver);
        if let Some(capture) = capture {
            capture.stop();
        }
        for drawable in &drawables {
            gpu.queue.signalDrawable(drawable);
        }
        frames.submitted += 1;
        gpu.queue
            .signalEvent_value(ProtocolObject::from_ref(&*frames.event), frames.submitted);
        for drawable in drawables {
            drawable.present();
            stats::add(Counter::Presents, 1);
        }
        eval.finish_frame();
    }

    pub fn calibrate(&mut self) -> u64 {
        self.calibrate_resolution(1, 1)
    }

    fn retire_completed_calibrations(&mut self) {
        let completed = self
            .calibration
            .as_ref()
            .map_or(u64::MAX, CalibrationOwner::completed);
        let pending = std::mem::take(&mut self.pending_calibration);
        for (completion, resources) in pending {
            if completion <= completed {
                self.retire_calibration_resources(resources);
            } else {
                self.pending_calibration.push((completion, resources));
            }
        }
    }

    pub fn calibrate_resolution(&mut self, width: u32, height: u32) -> u64 {
        self.retire_completed_calibrations();
        self.calibration_hiz
            .release(&mut self.resources, self.frames.completed());
        let Some(resolution) = CalibrationResolution::new(width, height) else {
            return 0;
        };
        let (Some(width), Some(height)) = (
            usize::try_from(resolution.width()).ok(),
            usize::try_from(resolution.height()).ok(),
        ) else {
            return 0;
        };
        if !self.calibration_hiz.ensure(
            &self.gpu,
            &mut self.resources,
            width,
            height,
            self.frames.submitted,
        ) {
            return 0;
        }
        let shape = TextureShape {
            width,
            height,
            layers: 1,
            mips: 1,
            cube: false,
        };
        let Some(target) =
            self.create_texture(MTLPixelFormat::BGRA8Unorm.0 as u32, &shape, true, false)
        else {
            return 0;
        };
        let Some(depth) =
            self.create_texture(MTLPixelFormat::Depth32Float.0 as u32, &shape, true, false)
        else {
            self.retire_calibration_handles(CalibrationHandles([
                target, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ]));
            return 0;
        };
        let (hiz_width, hiz_height, hiz_mips) = calibration_hiz_layout(width, height);
        let Some(hiz) = self.create_texture(
            MTLPixelFormat::R32Float.0 as u32,
            &TextureShape {
                width: hiz_width,
                height: hiz_height,
                layers: 1,
                mips: hiz_mips,
                cube: false,
            },
            false,
            true,
        ) else {
            self.retire_calibration_handles(CalibrationHandles([
                target, depth, 0, 0, 0, 0, 0, 0, 0, 0,
            ]));
            return 0;
        };
        let Some(snapshot) =
            self.create_texture(MTLPixelFormat::R32Float.0 as u32, &shape, false, true)
        else {
            self.retire_calibration_handles(CalibrationHandles([
                target, depth, hiz, 0, 0, 0, 0, 0, 0, 0,
            ]));
            return 0;
        };
        let Some(state) = self
            .gpu
            .calibration_pipeline(MTLPixelFormat::BGRA8Unorm, false)
        else {
            self.retire_calibration_handles(CalibrationHandles([
                target, depth, hiz, snapshot, 0, 0, 0, 0, 0, 0,
            ]));
            return 0;
        };
        let pipeline = self.resources.pipelines.insert(Pipeline {
            state,
            kind: pipeline::PipelineKind::Raster,
            oit: None,
            depth: None,
            primitive: MTLPrimitiveType::Triangle,
            fan: false,
            cull: MTLCullMode::None,
            fill: MTLTriangleFillMode::Fill,
            depth_bias: (0.0, 0.0),
        });
        let Some(depth_state) = self
            .gpu
            .calibration_pipeline(MTLPixelFormat::BGRA8Unorm, true)
        else {
            self.retire_calibration_handles(CalibrationHandles([
                target, depth, hiz, snapshot, pipeline, 0, 0, 0, 0, 0,
            ]));
            return 0;
        };
        let depth_pipeline = self.resources.pipelines.insert(Pipeline {
            state: depth_state,
            kind: pipeline::PipelineKind::Raster,
            oit: None,
            depth: Some((objc2_metal::MTLCompareFunction(6), true)),
            primitive: MTLPrimitiveType::Triangle,
            fan: false,
            cull: MTLCullMode::None,
            fill: MTLTriangleFillMode::Fill,
            depth_bias: (0.0, 0.0),
        });
        let Some(mesh_state) = self
            .gpu
            .calibration_mesh_pipeline(MTLPixelFormat::BGRA8Unorm)
        else {
            self.retire_calibration_handles(CalibrationHandles([
                target,
                depth,
                hiz,
                snapshot,
                pipeline,
                depth_pipeline,
                0,
                0,
                0,
                0,
            ]));
            return 0;
        };
        let mesh_pipeline = self.resources.pipelines.insert(Pipeline {
            state: mesh_state,
            kind: pipeline::PipelineKind::Mesh,
            oit: None,
            depth: None,
            primitive: MTLPrimitiveType::Triangle,
            fan: false,
            cull: MTLCullMode::None,
            fill: MTLTriangleFillMode::Fill,
            depth_bias: (0.0, 0.0),
        });
        let Some((values, _)) = self.create_buffer(32, &[]) else {
            self.retire_calibration_handles(CalibrationHandles([
                target,
                depth,
                hiz,
                snapshot,
                pipeline,
                depth_pipeline,
                mesh_pipeline,
                0,
                0,
                0,
            ]));
            return 0;
        };
        let Some(value_buffer) = self.resources.buffers.get(values) else {
            self.retire_calibration_handles(CalibrationHandles([
                target,
                depth,
                hiz,
                snapshot,
                pipeline,
                depth_pipeline,
                mesh_pipeline,
                values,
                0,
                0,
            ]));
            return 0;
        };
        if !metal::write_shared(value_buffer, 0, &[0; 32]) {
            self.retire_calibration_handles(CalibrationHandles([
                target,
                depth,
                hiz,
                snapshot,
                pipeline,
                depth_pipeline,
                mesh_pipeline,
                values,
                0,
                0,
            ]));
            return 0;
        }
        let Some((indices, _)) = self.create_buffer(3 * 2, &[]) else {
            self.retire_calibration_handles(CalibrationHandles([
                target,
                depth,
                hiz,
                snapshot,
                pipeline,
                depth_pipeline,
                mesh_pipeline,
                values,
                0,
                0,
            ]));
            return 0;
        };
        let Some(index_buffer) = self.resources.buffers.get(indices) else {
            self.retire_calibration_handles(CalibrationHandles([
                target,
                depth,
                hiz,
                snapshot,
                pipeline,
                depth_pipeline,
                mesh_pipeline,
                values,
                indices,
                0,
            ]));
            return 0;
        };
        if !metal::write_shared(index_buffer, 0, &[0, 0, 1, 0, 2, 0]) {
            self.retire_calibration_handles(CalibrationHandles([
                target,
                depth,
                hiz,
                snapshot,
                pipeline,
                depth_pipeline,
                mesh_pipeline,
                values,
                indices,
                0,
            ]));
            return 0;
        }
        let Some(query) = self.create_query_pool(CALIBRATION_QUERY_CAPACITY) else {
            self.retire_calibration_handles(CalibrationHandles([
                target,
                depth,
                hiz,
                snapshot,
                pipeline,
                depth_pipeline,
                mesh_pipeline,
                values,
                indices,
                0,
            ]));
            return 0;
        };
        let resources = CalibrationResources {
            width,
            height,
            target,
            depth,
            hiz,
            snapshot,
            pipeline,
            depth_pipeline,
            mesh_pipeline,
            values,
            indices,
            query,
        };
        let Some(calibration) = self.calibration.as_ref() else {
            self.last_error = "calibration command context unavailable".to_string();
            self.retire_calibration_resources(resources);
            return 0;
        };
        for handle in [target, depth, hiz, snapshot] {
            if let Some(image) = self.resources.images.get(handle) {
                calibration.track(ProtocolObject::from_ref(&*image.texture));
            }
        }
        for handle in [values, indices] {
            if let Some(buffer) = self.resources.buffers.get(handle) {
                calibration.track(ProtocolObject::from_ref(&**buffer));
            }
        }
        calibration.track_commit();
        let result = (|| {
            let mut slots = CalibrationSlots::new(CALIBRATION_QUERY_CAPACITY);
            let Some((sequential_time, indexed_time, mesh_time)) =
                self.calibration_paths(&resources, &mut slots, CALIBRATION_DRAWS)
            else {
                return 0;
            };
            let compact_time = self.calibration_pair(
                &resources,
                &mut slots,
                CALIBRATION_DRAWS,
                CalibrationPath::NonIndexed,
                CalibrationMode::Off,
            );
            let full_time = self.calibration_pair(
                &resources,
                &mut slots,
                CALIBRATION_DRAWS,
                CalibrationPath::NonIndexed,
                CalibrationMode::Off,
            );
            let two_phase_time = self.calibration_pair(
                &resources,
                &mut slots,
                CALIBRATION_DRAWS,
                CalibrationPath::NonIndexed,
                CalibrationMode::TwoPhaseHiz,
            );
            let single_phase_time = self.calibration_pair(
                &resources,
                &mut slots,
                CALIBRATION_DRAWS,
                CalibrationPath::NonIndexed,
                CalibrationMode::SinglePhaseHiz,
            );
            let off_time = self.calibration_pair(
                &resources,
                &mut slots,
                CALIBRATION_DRAWS,
                CalibrationPath::NonIndexed,
                CalibrationMode::Off,
            );
            let short_interval = self.calibration_pair(
                &resources,
                &mut slots,
                CALIBRATION_DRAWS,
                CalibrationPath::NonIndexed,
                CalibrationMode::Off,
            );
            let long_interval = self.calibration_pair(
                &resources,
                &mut slots,
                CALIBRATION_DRAWS,
                CalibrationPath::NonIndexed,
                CalibrationMode::Off,
            );
            let ring_two = self.calibration_pair(
                &resources,
                &mut slots,
                CALIBRATION_DRAWS,
                CalibrationPath::NonIndexed,
                CalibrationMode::Off,
            );
            let ring_three = self.calibration_pair(
                &resources,
                &mut slots,
                CALIBRATION_DRAWS,
                CalibrationPath::NonIndexed,
                CalibrationMode::Off,
            );
            let Some(compact_time) = compact_time else {
                return 0;
            };
            let Some(full_time) = full_time else { return 0 };
            let Some(two_phase_time) = two_phase_time else {
                return 0;
            };
            let Some(single_phase_time) = single_phase_time else {
                return 0;
            };
            let Some(off_time) = off_time else {
                return 0;
            };
            let Some(short_interval) = short_interval else {
                return 0;
            };
            let Some(long_interval) = long_interval else {
                return 0;
            };
            let Some(ring_two) = ring_two else { return 0 };
            let Some(ring_three) = ring_three else {
                return 0;
            };
            let indexed = indexed_time <= sequential_time;
            let path = select_path(sequential_time, indexed_time, mesh_time);
            let compact = compact_time <= full_time;
            let mode = select_mode(two_phase_time, single_phase_time, off_time);
            let interval = if short_interval <= long_interval {
                240
            } else {
                1920
            };
            let lod = if ring_two <= ring_three { 2 } else { 3 };
            1 | mode
                | (u64::from(compact) << 2)
                | (u64::from(lod as u32) << 8)
                | (u64::from(interval as u32) << 16)
                | (u64::from(indexed) << 32)
                | (path << 33)
        })();
        let completion = self
            .calibration
            .as_ref()
            .map_or(0, CalibrationOwner::completion);
        if self
            .calibration
            .as_ref()
            .is_some_and(|owner| owner.completed() >= completion)
        {
            self.retire_calibration_resources(resources);
        } else {
            self.pending_calibration.push((completion, resources));
        }
        result
    }

    fn retire_calibration_handles(&mut self, handles: CalibrationHandles) {
        let [
            target,
            depth,
            hiz,
            snapshot,
            pipeline,
            depth_pipeline,
            mesh_pipeline,
            values,
            indices,
            query,
        ] = handles.0;
        let frame = self.frames.submitted;
        for handle in [target, depth, hiz, snapshot] {
            if handle != 0 {
                self.resources.destroy_later(frame, Kind::Texture, handle);
            }
        }
        for handle in [pipeline, depth_pipeline, mesh_pipeline] {
            if handle != 0 {
                self.resources.destroy_later(frame, Kind::Pipeline, handle);
            }
        }
        for handle in [values, indices] {
            if handle != 0 {
                self.resources.destroy_later(frame, Kind::Buffer, handle);
            }
        }
        if query != 0 {
            self.resources.destroy_later(frame, Kind::Query, query);
        }
    }

    fn retire_calibration_resources(&mut self, resources: CalibrationResources) {
        if let Some(calibration) = self.calibration.as_ref() {
            for handle in [
                resources.target,
                resources.depth,
                resources.hiz,
                resources.snapshot,
            ] {
                if let Some(image) = self.resources.images.get(handle) {
                    calibration.untrack(ProtocolObject::from_ref(&*image.texture));
                }
            }
            for handle in [resources.values, resources.indices] {
                if let Some(buffer) = self.resources.buffers.get(handle) {
                    calibration.untrack(ProtocolObject::from_ref(&**buffer));
                }
            }
        }
        let frame = self.frames.submitted;
        for handle in [
            resources.target,
            resources.depth,
            resources.hiz,
            resources.snapshot,
        ] {
            self.resources.destroy_later(frame, Kind::Texture, handle);
        }
        for handle in [
            resources.pipeline,
            resources.depth_pipeline,
            resources.mesh_pipeline,
        ] {
            self.resources.destroy_later(frame, Kind::Pipeline, handle);
        }
        for handle in [resources.values, resources.indices] {
            self.resources.destroy_later(frame, Kind::Buffer, handle);
        }
        self.resources
            .destroy_later(frame, Kind::Query, resources.query);
    }

    fn calibration_pair(
        &mut self,
        resources: &CalibrationResources,
        slots: &mut CalibrationSlots,
        draws: usize,
        path: CalibrationPath,
        mode: CalibrationMode,
    ) -> Option<u64> {
        for _ in 0..CALIBRATION_WARMUP_SAMPLES {
            let first = slots.allocate()?;
            self.calibration_sample(resources, first, draws, path, mode)?;
        }
        let mut samples = Vec::with_capacity(CALIBRATION_SAMPLE_COUNT);
        for _ in 0..CALIBRATION_SAMPLE_COUNT {
            let first = slots.allocate()?;
            samples.push(self.calibration_sample(resources, first, draws, path, mode)?);
        }
        samples.sort_unstable();
        samples.get(1).copied()
    }

    fn calibration_paths(
        &mut self,
        resources: &CalibrationResources,
        slots: &mut CalibrationSlots,
        draws: usize,
    ) -> Option<(u64, u64, u64)> {
        let mut non_indexed = Vec::with_capacity(CALIBRATION_SAMPLE_COUNT);
        let mut indexed = Vec::with_capacity(CALIBRATION_SAMPLE_COUNT);
        let mut mesh = Vec::with_capacity(CALIBRATION_SAMPLE_COUNT);
        for round in 0..CALIBRATION_WARMUP_SAMPLES + CALIBRATION_SAMPLE_COUNT {
            for path in [
                CalibrationPath::NonIndexed,
                CalibrationPath::Indexed,
                CalibrationPath::Mesh,
            ] {
                let first = slots.allocate()?;
                let sample =
                    self.calibration_sample(resources, first, draws, path, CalibrationMode::Off)?;
                if round >= CALIBRATION_WARMUP_SAMPLES {
                    match path {
                        CalibrationPath::NonIndexed => non_indexed.push(sample),
                        CalibrationPath::Indexed => indexed.push(sample),
                        CalibrationPath::Mesh => mesh.push(sample),
                    }
                }
            }
        }
        non_indexed.sort_unstable();
        indexed.sort_unstable();
        mesh.sort_unstable();
        Some((*non_indexed.get(1)?, *indexed.get(1)?, *mesh.get(1)?))
    }

    fn encode_calibration_depth(
        &mut self,
        resources: &CalibrationResources,
        draws: usize,
        path: CalibrationPath,
    ) -> bool {
        let Some(color) = self.resources.images.get(resources.target) else {
            return false;
        };
        let Some(depth) = self.resources.images.get(resources.depth) else {
            return false;
        };
        let Some(pipeline) = self.resources.pipelines.get(resources.depth_pipeline) else {
            return false;
        };
        let descriptor = MTL4RenderPassDescriptor::new();
        let Some(color_attachment) = metal::pass_color(&descriptor, 0) else {
            return false;
        };
        color_attachment.setTexture(Some(&color.texture));
        color_attachment.setLoadAction(MTLLoadAction::DontCare);
        color_attachment.setStoreAction(MTLStoreAction::DontCare);
        let attachment = descriptor.depthAttachment();
        attachment.setTexture(Some(&depth.texture));
        attachment.setLoadAction(MTLLoadAction::Clear);
        attachment.setStoreAction(MTLStoreAction::Store);
        attachment.setClearDepth(0.0);
        let Some(encoder) = self.calibration.as_ref().and_then(|calibration| {
            calibration
                .command
                .renderCommandEncoderWithDescriptor(&descriptor)
        }) else {
            return false;
        };
        encoder.setArgumentTable_atStages(&self.gpu.table, MTLRenderStages::Vertex);
        encoder.setViewport(MTLViewport {
            originX: 0.0,
            originY: 0.0,
            width: resources.width as f64,
            height: resources.height as f64,
            znear: 0.0,
            zfar: 1.0,
        });
        encoder.setScissorRect(MTLScissorRect {
            x: 0,
            y: 0,
            width: resources.width,
            height: resources.height,
        });
        encoder.setFrontFacingWinding(MTLWinding::Clockwise);
        encoder.setRenderPipelineState(&pipeline.state);
        encoder.setCullMode(pipeline.cull);
        encoder.setTriangleFillMode(pipeline.fill);
        let Some(depth_state) = self
            .gpu
            .depth_states
            .get(MTLCompareFunction::GreaterEqual, true)
        else {
            encoder.endEncoding();
            return false;
        };
        encoder.setDepthStencilState(Some(depth_state));
        match path {
            CalibrationPath::NonIndexed => {
                metal::draw(&encoder, MTLPrimitiveType::Triangle, 0, 3, draws, 0)
            }
            CalibrationPath::Indexed => {
                let Some(index_buffer) = self.resources.buffers.get(resources.indices) else {
                    encoder.endEncoding();
                    return false;
                };
                metal::draw_indexed(
                    &encoder,
                    MTLPrimitiveType::Triangle,
                    mtl4::IndexRange {
                        buffer: index_buffer,
                        kind: objc2_metal::MTLIndexType::UInt16,
                        first: 0,
                        count: 3,
                    },
                    draws,
                    0,
                    0,
                );
            }
            CalibrationPath::Mesh => metal::draw_mesh_calibration(&encoder, draws as u32, 1, 32),
        }
        encoder.endEncoding();
        true
    }

    fn encode_calibration_compute(
        &mut self,
        resources: &CalibrationResources,
        mode: CalibrationMode,
    ) -> bool {
        let Some(encoder) = self
            .calibration
            .as_ref()
            .and_then(|calibration| calibration.command.computeCommandEncoder())
        else {
            return false;
        };
        encoder.setArgumentTable(Some(&self.gpu.table));
        let size = MTLSize {
            width: resources.width.div_ceil(8),
            height: resources.height.div_ceil(8),
            depth: 1,
        };
        let threads = MTLSize {
            width: 8,
            height: 8,
            depth: 1,
        };
        match mode {
            CalibrationMode::TwoPhaseHiz => {
                let Some(depth) = self.resources.images.get(resources.depth) else {
                    encoder.endEncoding();
                    return false;
                };
                let Some(hiz) = self.resources.images.get(resources.hiz) else {
                    encoder.endEncoding();
                    return false;
                };
                metal::bind_texture(&self.gpu.table, 0, depth.texture.gpuResourceID());
                metal::bind_texture(&self.gpu.table, 1, hiz.texture.gpuResourceID());
                encoder.setComputePipelineState(&self.gpu.kernels.calibration_hiz_reduce);
                encoder.dispatchThreadgroups_threadsPerThreadgroup(
                    MTLSize {
                        width: resources.width.div_ceil(2).div_ceil(8),
                        height: resources.height.div_ceil(2).div_ceil(8),
                        depth: 1,
                    },
                    threads,
                );
            }
            CalibrationMode::SinglePhaseHiz => {
                let Some(depth) = self.resources.images.get(resources.depth) else {
                    encoder.endEncoding();
                    return false;
                };
                let Some(snapshot) = self.resources.images.get(resources.snapshot) else {
                    encoder.endEncoding();
                    return false;
                };
                let Some(hiz) = self.resources.images.get(resources.hiz) else {
                    encoder.endEncoding();
                    return false;
                };
                metal::bind_texture(&self.gpu.table, 0, depth.texture.gpuResourceID());
                metal::bind_texture(&self.gpu.table, 1, snapshot.texture.gpuResourceID());
                encoder.setComputePipelineState(&self.gpu.kernels.snapshot_d32_to_r32);
                encoder.dispatchThreadgroups_threadsPerThreadgroup(size, threads);
                encoder.barrierAfterEncoderStages_beforeEncoderStages_visibilityOptions(
                    MTLStages::Dispatch,
                    MTLStages::Dispatch,
                    MTL4VisibilityOptions::Device,
                );
                let Some(values) = self.resources.buffers.get(resources.values) else {
                    encoder.endEncoding();
                    return false;
                };
                metal::bind_address(&self.gpu.table, 10, values.gpuAddress());
                metal::bind_texture(&self.gpu.table, 0, snapshot.texture.gpuResourceID());
                metal::bind_texture(&self.gpu.table, 1, snapshot.texture.gpuResourceID());
                metal::bind_texture(&self.gpu.table, 2, hiz.texture.gpuResourceID());
                encoder
                    .setComputePipelineState(&self.gpu.kernels.calibration_single_phase_reproject);
                encoder.dispatchThreadgroups_threadsPerThreadgroup(size, threads);
            }
            CalibrationMode::Off => {}
        }
        encoder.endEncoding();
        true
    }

    fn encode_calibration_sample(
        &mut self,
        resources: &CalibrationResources,
        slot: usize,
        draws: usize,
        path: CalibrationPath,
        depth: Option<u64>,
    ) -> bool {
        let Some(color) = self.resources.images.get(resources.target) else {
            return false;
        };
        let pipeline_handle = match path {
            CalibrationPath::Mesh => resources.mesh_pipeline,
            CalibrationPath::NonIndexed | CalibrationPath::Indexed => depth
                .map(|_| resources.depth_pipeline)
                .unwrap_or(resources.pipeline),
        };
        let Some(pipeline) = self.resources.pipelines.get(pipeline_handle) else {
            return false;
        };
        let descriptor = MTL4RenderPassDescriptor::new();
        let Some(color_attachment) = metal::pass_color(&descriptor, 0) else {
            return false;
        };
        color_attachment.setTexture(Some(&color.texture));
        color_attachment.setLoadAction(MTLLoadAction::Clear);
        color_attachment.setStoreAction(MTLStoreAction::DontCare);
        color_attachment.setClearColor(MTLClearColor {
            red: 0.0,
            green: 0.0,
            blue: 0.0,
            alpha: 1.0,
        });
        if let Some(depth_handle) = depth
            && let Some(image) = self.resources.images.get(depth_handle)
        {
            let attachment = descriptor.depthAttachment();
            attachment.setTexture(Some(&image.texture));
            attachment.setLoadAction(MTLLoadAction::Clear);
            attachment.setStoreAction(MTLStoreAction::DontCare);
            attachment.setClearDepth(0.0);
        }
        let Some(end_slot) = slot.checked_add(1) else {
            return false;
        };
        if end_slot >= CALIBRATION_QUERY_CAPACITY {
            return false;
        }
        let Some(query) = self.resources.queries.get(resources.query) else {
            return false;
        };
        let Some(calibration) = self.calibration.as_mut() else {
            return false;
        };
        calibration.prepare();
        metal::write_timestamp(&calibration.command, query, slot);
        let Some(encoder) = self.calibration.as_ref().and_then(|calibration| {
            calibration
                .command
                .renderCommandEncoderWithDescriptor(&descriptor)
        }) else {
            return false;
        };
        encoder.setArgumentTable_atStages(
            &self.gpu.table,
            MTLRenderStages::Vertex | MTLRenderStages::Fragment,
        );
        encoder.setViewport(MTLViewport {
            originX: 0.0,
            originY: 0.0,
            width: resources.width as f64,
            height: resources.height as f64,
            znear: 0.0,
            zfar: 1.0,
        });
        encoder.setScissorRect(MTLScissorRect {
            x: 0,
            y: 0,
            width: resources.width,
            height: resources.height,
        });
        encoder.setFrontFacingWinding(MTLWinding::Clockwise);
        encoder.setRenderPipelineState(&pipeline.state);
        encoder.setCullMode(pipeline.cull);
        encoder.setTriangleFillMode(pipeline.fill);
        let (compare, write) = pipeline
            .depth
            .unwrap_or((MTLCompareFunction::Always, false));
        let Some(depth_state) = self.gpu.depth_states.get(compare, write) else {
            return false;
        };
        encoder.setDepthStencilState(Some(depth_state));
        match path {
            CalibrationPath::NonIndexed => {
                metal::draw(&encoder, MTLPrimitiveType::Triangle, 0, 3, draws, 0)
            }
            CalibrationPath::Indexed => {
                let Some(index_buffer) = self.resources.buffers.get(resources.indices) else {
                    return false;
                };
                metal::draw_indexed(
                    &encoder,
                    MTLPrimitiveType::Triangle,
                    mtl4::IndexRange {
                        buffer: index_buffer,
                        kind: objc2_metal::MTLIndexType::UInt16,
                        first: 0,
                        count: 3,
                    },
                    draws,
                    0,
                    0,
                );
            }
            CalibrationPath::Mesh => metal::draw_mesh_calibration(&encoder, draws as u32, 1, 32),
        }
        encoder.endEncoding();
        let Some(calibration) = self.calibration.as_ref() else {
            return false;
        };
        metal::write_timestamp(&calibration.command, query, end_slot);
        calibration.command.endCommandBuffer();
        true
    }

    fn calibration_sample(
        &mut self,
        resources: &CalibrationResources,
        slot: usize,
        draws: usize,
        path: CalibrationPath,
        mode: CalibrationMode,
    ) -> Option<u64> {
        let _mode_resource = match mode {
            CalibrationMode::TwoPhaseHiz => resources.hiz,
            CalibrationMode::SinglePhaseHiz => resources.snapshot,
            CalibrationMode::Off => 0,
        };
        if mode == CalibrationMode::TwoPhaseHiz
            && (!self.encode_calibration_depth(resources, draws, path)
                || !self.encode_calibration_compute(resources, mode))
        {
            self.last_error = "calibration command encoding failed".to_string();
            return None;
        }
        if mode == CalibrationMode::SinglePhaseHiz
            && (!self.encode_calibration_depth(resources, draws, path)
                || !self.encode_calibration_compute(resources, mode))
        {
            self.last_error = "calibration command encoding failed".to_string();
            return None;
        }
        if !self.encode_calibration_sample(resources, slot, draws, path, None) {
            self.last_error = "calibration command encoding failed".to_string();
            return None;
        }
        let Some(calibration) = self.calibration.as_mut() else {
            self.last_error = "calibration command context unavailable".to_string();
            return None;
        };
        let Some(completion) = calibration.commit(&self.gpu.queue) else {
            self.last_error = "calibration command completion overflow".to_string();
            return None;
        };
        if !calibration.wait(completion, CALIBRATION_WAIT_TIMEOUT_MS) {
            self.last_error = "calibration command completion timeout".to_string();
            return None;
        }
        let mut result = [0u64; 2];
        if !self.resolve_queries(resources.query, slot, &mut result) || result[1] <= result[0] {
            return None;
        }
        calibration_duration(result[0], result[1], self.timestamp_frequency())
    }
}

fn calibration_duration(start: u64, end: u64, frequency: u64) -> Option<u64> {
    if frequency == 0 || end <= start {
        return None;
    }
    end.checked_sub(start)?
        .checked_mul(1_000_000_000)?
        .checked_div(frequency)
}

fn select_mode(two_phase: u64, single_phase: u64, off: u64) -> u64 {
    if two_phase <= single_phase && two_phase <= off {
        return 1 << 1;
    }
    if single_phase <= off {
        return 1 << 3;
    }
    0
}

fn select_path(non_indexed: u64, indexed: u64, mesh: u64) -> u64 {
    let mut selected = 2u64;
    let mut selected_time = indexed;
    if non_indexed.saturating_mul(99) < selected_time.saturating_mul(100) {
        selected = 1;
        selected_time = non_indexed;
    }
    if mesh.saturating_mul(99) < selected_time.saturating_mul(100) {
        selected = 3;
    }
    selected
}

#[cfg(test)]
mod calibration_tests {
    use super::{
        CALIBRATION_QUERY_CAPACITY, CalibrationSlots, calibration_duration, calibration_hiz_layout,
    };

    #[test]
    fn hiz_layout_matches_framebuffer_dimensions() {
        assert_eq!(calibration_hiz_layout(1280, 720), (1024, 512, 11));
        assert_eq!(calibration_hiz_layout(1920, 1200), (1024, 1024, 11));
    }

    #[test]
    fn calibration_slots_increment_by_two_and_reject_pool_overflow() {
        let mut slots = CalibrationSlots::new(4);
        assert_eq!(slots.allocate(), Some(0));
        assert_eq!(slots.allocate(), Some(2));
        assert_eq!(slots.allocate(), None);
    }

    #[test]
    fn calibration_slots_fit_the_query_pool() {
        let mut slots = CalibrationSlots::new(CALIBRATION_QUERY_CAPACITY);
        let mut last = None;
        for _ in 0..(CALIBRATION_QUERY_CAPACITY / 2) {
            last = slots.allocate();
        }
        assert_eq!(last, Some(CALIBRATION_QUERY_CAPACITY - 2));
        assert_eq!(slots.allocate(), None);
    }

    #[test]
    fn calibration_duration_rejects_invalid_timestamps_and_frequency() {
        assert_eq!(calibration_duration(10, 10, 1), None);
        assert_eq!(calibration_duration(11, 10, 1), None);
        assert_eq!(calibration_duration(10, 11, 0), None);
        assert_eq!(calibration_duration(10, 11, 1_000_000_000), Some(1));
    }
}
