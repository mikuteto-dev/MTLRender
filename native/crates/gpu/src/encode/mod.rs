mod compute;
mod copy;
mod render;
mod special;

use objc2::Message;
use objc2::rc::Retained;
use objc2::runtime::ProtocolObject;
use objc2_metal::{
    MTL4CommandBuffer, MTL4CommandEncoder, MTL4ComputeCommandEncoder, MTL4RenderCommandEncoder,
    MTL4RenderPassDescriptor, MTL4VisibilityOptions, MTLBuffer, MTLRenderStages, MTLSize,
    MTLStages, MTLTexture,
};
use objc2_quartz_core::CAMetalDrawable;

use crate::command::{Command, ComputeAccess, ExpandCheckRequest};
use crate::cull::Culler;
use crate::expand::Expander;
use crate::geometry_runtime::{
    AccessMode, GeometryRuntime, GeometryStage, GeometryStages, ResourceIdentity,
};
use crate::gpu::Gpu;
use crate::hazard::Hazards;
use crate::lod::LodCuller;
use crate::pathtrace;
use crate::plan::Step;
use crate::resources::{Cache, Resources};
use crate::sort::Sorter;
use crate::surface::Surface;
use crate::timing::PassTimer;
use mtl4::{self as metal, Buffer, Fx};

type RenderEncoder = Retained<ProtocolObject<dyn MTL4RenderCommandEncoder>>;

const RENDER_STAGES: MTLStages = MTLStages::Vertex.union(MTLStages::Fragment);
const STAGE_VERTEX: u32 = 1;
const STAGE_FRAGMENT: u32 = 2;

fn stage_mask(mask: u32) -> MTLStages {
    let mut stages = MTLStages::empty();
    if mask & STAGE_VERTEX != 0 {
        stages |= MTLStages::Vertex;
    }
    if mask & STAGE_FRAGMENT != 0 {
        stages |= MTLStages::Fragment;
    }
    if stages.is_empty() {
        stages |= MTLStages::Fragment;
    }
    stages
}

pub struct Scratch<'a> {
    buffer: &'a Buffer,
    offset: usize,
}

pub(crate) fn push_constants(scratch: &mut Scratch, values: &[f32]) -> Option<u64> {
    let bytes: Vec<u8> = values.iter().flat_map(|v| v.to_ne_bytes()).collect();
    if !metal::write_shared(scratch.buffer, scratch.offset, &bytes) {
        return None;
    }
    let address = scratch.buffer.gpuAddress() + scratch.offset as u64;
    scratch.offset += bytes.len().next_multiple_of(256);
    Some(address)
}

pub struct Context<'a> {
    pub gpu: &'a Gpu,
    pub resources: &'a Resources,
    pub culler: &'a mut Culler,
    pub expander: &'a Expander,
    pub lod_culler: &'a LodCuller,
    pub geometry_runtime: &'a mut GeometryRuntime,
    pub path_tracer: &'a mut pathtrace::PathTracer,
    pub sorter: &'a Sorter,
    pub cache: &'a mut Cache,
    pub hazards: &'a mut Hazards,
    pub scratch: &'a Buffer,
    pub surface: Option<&'a mut Surface>,
    pub submitted_frame: u64,
    pub evaluation: bool,
    pub fx: &'a mut Option<Fx>,
    pub retired_fx: &'a mut Vec<(u64, Fx)>,
}

pub struct Encoder<'a> {
    gpu: &'a Gpu,
    resources: &'a Resources,
    culler: &'a mut Culler,
    expander: &'a Expander,
    lod_culler: &'a LodCuller,
    geometry_runtime: &'a mut GeometryRuntime,
    path_tracer: &'a mut pathtrace::PathTracer,
    sorter: &'a Sorter,
    cache: &'a mut Cache,
    hazards: &'a mut Hazards,
    surface: Option<&'a mut Surface>,
    submitted_frame: u64,
    evaluation: bool,
    fx: &'a mut Option<Fx>,
    retired_fx: &'a mut Vec<(u64, Fx)>,
    scratch: Scratch<'a>,
    timer: &'a mut PassTimer,
    pass: Option<render::Pass<'a>>,
    compute: Option<compute::Pass>,
    open: bool,
    copies: Option<copy::Copies>,
    drawables: Vec<Retained<ProtocolObject<dyn CAMetalDrawable>>>,
    interpolation_real_view: Option<u64>,
    interpolation_present_generated: bool,
    snapshot_depth: Option<u64>,
}

impl<'a> Encoder<'a> {
    pub fn new(context: Context<'a>, timer: &'a mut PassTimer) -> Self {
        let Context {
            gpu,
            resources,
            culler,
            expander,
            lod_culler,
            geometry_runtime,
            path_tracer,
            sorter,
            cache,
            hazards,
            scratch,
            surface,
            submitted_frame,
            evaluation,
            fx,
            retired_fx,
        } = context;
        Self {
            gpu,
            resources,
            culler,
            expander,
            lod_culler,
            geometry_runtime,
            path_tracer,
            sorter,
            cache,
            hazards,
            surface,
            submitted_frame,
            evaluation,
            fx,
            retired_fx,
            scratch: Scratch {
                buffer: scratch,
                offset: 0,
            },
            timer,
            pass: None,
            compute: None,
            open: false,
            copies: None,
            drawables: Vec::new(),
            interpolation_real_view: None,
            interpolation_present_generated: false,
            snapshot_depth: None,
        }
    }

    fn pack_depth_convert(&mut self, source: u64, destination: u64) {
        let Some(source_image) = self.resources.images.get(source) else {
            return;
        };
        let Some(destination_image) = self.resources.images.get(destination) else {
            return;
        };
        if !source_image.depth
            || source_image.root == destination_image.root
            || source_image.texture.width() != destination_image.texture.width()
            || source_image.texture.height() != destination_image.texture.height()
        {
            return;
        }
        let source_hazard = self
            .hazards
            .compute(source_image.root, crate::command::ComputeAccess::Read);
        let destination_hazard = self
            .hazards
            .write_at(destination_image.root, MTLStages::Dispatch);
        let Some(encoder) = self.gpu.cmd.computeCommandEncoder() else {
            return;
        };
        if !source_hazard.producer.is_empty()
            && let Some(fence) = self.hazards.fence(&self.gpu.device, source_image.root)
        {
            encoder.waitForFence_beforeEncoderStages(fence, MTLStages::Dispatch);
            wait(&*encoder, source_hazard.producer, MTLStages::Dispatch);
        }
        if !destination_hazard.producer.is_empty()
            && let Some(fence) = self.hazards.fence(&self.gpu.device, destination_image.root)
        {
            encoder.waitForFence_beforeEncoderStages(fence, MTLStages::Dispatch);
            wait(&*encoder, destination_hazard.producer, MTLStages::Dispatch);
        }
        if !destination_hazard.consumer.is_empty() {
            encoder.barrierAfterEncoderStages_beforeEncoderStages_visibilityOptions(
                destination_hazard.consumer,
                MTLStages::Dispatch,
                MTL4VisibilityOptions::Device,
            );
        }
        metal::bind_texture(&self.gpu.table, 0, source_image.texture.gpuResourceID());
        metal::bind_texture(
            &self.gpu.table,
            1,
            destination_image.texture.gpuResourceID(),
        );
        encoder.setArgumentTable(Some(&self.gpu.table));
        encoder.setComputePipelineState(&self.gpu.kernels.pack_depth_convert);
        let size = MTLSize {
            width: destination_image.texture.width(),
            height: destination_image.texture.height(),
            depth: 1,
        };
        encoder.dispatchThreads_threadsPerThreadgroup(
            size,
            MTLSize {
                width: 8,
                height: 8,
                depth: 1,
            },
        );
        encoder.barrierAfterEncoderStages_beforeEncoderStages_visibilityOptions(
            MTLStages::Dispatch,
            MTLStages::Vertex | MTLStages::Fragment | MTLStages::Dispatch,
            MTL4VisibilityOptions::Device,
        );
        if let Some(fence) = self.hazards.fence(&self.gpu.device, destination_image.root) {
            encoder.updateFence_afterEncoderStages(fence, MTLStages::Dispatch);
        }
        encoder.endEncoding();
    }

    fn read_buffer(&mut self, handle: u64, stages: MTLStages) {
        if self.resources.buffers.get(handle).is_none() {
            return;
        }
        let root = ResourceIdentity::BufferHandle(handle);
        let written = self.hazards.read_resource(root, stages);
        if written
            && let Some(pass) = self.pass.as_mut()
            && let Some(fence) = self.hazards.fence_resource(&self.gpu.device, root)
        {
            pass.wait_fence_resource(root, fence, stages);
        }
    }

    fn geometry_stages(stages: GeometryStages) -> MTLStages {
        let mut result = MTLStages::empty();
        if stages.contains(GeometryStage::Dispatch) {
            result |= MTLStages::Dispatch;
        }
        if stages.contains(GeometryStage::Vertex) {
            result |= MTLStages::Vertex;
        }
        if stages.contains(GeometryStage::Fragment) {
            result |= MTLStages::Fragment;
        }
        result
    }

    fn sync_geometry_compute(&mut self, pass: &mut compute::Pass) {
        for record in self.geometry_runtime.accesses().records() {
            if !matches!(record.producer(), GeometryStage::Dispatch)
                || !record.consumers().contains(GeometryStage::Dispatch)
            {
                continue;
            }
            let access = match record.mode() {
                AccessMode::Read => ComputeAccess::Read,
                AccessMode::Write => ComputeAccess::Write,
                AccessMode::ReadWrite => ComputeAccess::ReadWrite,
            };
            pass.declare_access(self.gpu, self.hazards, record.resource(), access);
        }
    }

    fn sync_geometry_render(&mut self) {
        let records = self.geometry_runtime.accesses().records();
        for record in records {
            let stages = Self::geometry_stages(record.consumers());
            let render_stages = stages & (MTLStages::Vertex | MTLStages::Fragment);
            if render_stages.is_empty() {
                continue;
            }
            let mode = record.mode();
            if matches!(mode, AccessMode::Write | AccessMode::ReadWrite)
                && matches!(record.producer(), GeometryStage::Dispatch)
            {
                self.hazards.compute_resource(record.resource(), mode);
            }
            if self.hazards.read_resource(record.resource(), render_stages)
                && let Some(pass) = self.pass.as_mut()
                && let Some(fence) = self
                    .hazards
                    .fence_resource(&self.gpu.device, record.resource())
            {
                pass.wait_fence_resource(record.resource(), fence, render_stages);
            }
        }
    }

    pub fn encode(&mut self, command: Command, step: Step) {
        let is_compute = matches!(
            command,
            Command::BeginCompute { .. }
                | Command::EndCompute
                | Command::ComputePipeline(_)
                | Command::ComputeBuffer(_)
                | Command::ComputeTexture(_)
                | Command::ComputeImage(_)
                | Command::ComputeBytes(_)
                | Command::ComputeDispatch(_)
                | Command::ComputeDispatchIndirect(_)
        );
        if !is_compute {
            self.end_compute();
        }
        if !command.is_copy() {
            self.end_copies();
        }
        if matches!(&command, Command::GenerateMipmaps(_)) {
            self.end_pass();
        }
        let resumes = step.merge && matches!(command, Command::BeginPass { .. });
        if !self.open && !resumes {
            self.end_pass();
        }
        match command {
            Command::BeginPass {
                id,
                colors,
                depth,
                area,
            } => {
                match self.pass.as_mut() {
                    Some(pass) if resumes => {
                        pass.resume(area);
                        self.timer.merge();
                    }
                    _ => {
                        self.end_pass();
                        self.pass = render::begin(
                            self.gpu,
                            self.resources,
                            self.hazards,
                            self.timer,
                            render::Request {
                                id,
                                colors: &colors,
                                depth: &depth,
                                area,
                                discard: step.discard,
                            },
                        );
                    }
                }
                self.open = true;
            }
            Command::EndPass => self.open = false,
            Command::SetPipeline(handle) => {
                if let Some(pass) = self.pass.as_mut() {
                    pass.set_pipeline(self.gpu, self.resources, handle);
                }
            }
            Command::BeginCompute { allow_concurrent } => {
                self.end_compute();
                self.end_pass();
                self.end_copies();
                self.compute = compute::begin(self.gpu, self.timer, allow_concurrent);
                if let Some(mut pass) = self.compute.take() {
                    self.sync_geometry_compute(&mut pass);
                    self.compute = Some(pass);
                }
            }
            Command::EndCompute => self.end_compute(),
            Command::ComputePipeline(handle) => {
                if let Some(pass) = self.compute.as_mut() {
                    pass.set_pipeline(self.resources, handle);
                }
            }
            Command::ComputeBuffer(request) => {
                if let Some(pass) = self.compute.as_mut() {
                    pass.bind_buffer(self.gpu, self.resources, self.hazards, request);
                }
            }
            Command::ComputeTexture(request) => {
                if let Some(pass) = self.compute.as_mut() {
                    pass.bind_texture(self.gpu, self.resources, self.hazards, request);
                }
            }
            Command::ComputeImage(request) => {
                if let Some(pass) = self.compute.as_mut() {
                    pass.bind_image(self.gpu, self.resources, self.hazards, request);
                }
            }
            Command::ComputeBytes(request) => {
                if let Some(pass) = self.compute.as_mut() {
                    pass.bind_bytes(self.gpu, &mut self.scratch, request);
                }
            }
            Command::ComputeDispatch(dimensions) => {
                if let Some(pass) = self.compute.as_ref() {
                    pass.dispatch(dimensions);
                }
            }
            Command::ComputeDispatchIndirect(request) => {
                if let Some(pass) = self.compute.as_mut() {
                    pass.dispatch_indirect(self.gpu, self.resources, self.hazards, request);
                }
            }
            Command::PackDepthConvert {
                source,
                destination,
            } => {
                self.pack_depth_convert(source, destination);
            }
            Command::BindBuffer {
                index,
                buffer,
                offset,
            } => {
                self.read_buffer(buffer, RENDER_STAGES);
                render::bind_buffer(self.gpu, self.resources, index, buffer, offset);
            }
            Command::VertexBuffer {
                index,
                buffer,
                offset,
            } => {
                self.read_buffer(buffer, MTLStages::Vertex);
                render::bind_vertex_buffer(self.gpu, self.resources, index, buffer, offset);
            }
            Command::BindTexture {
                texture_index,
                sampler_index,
                view,
                sampler,
                stages,
            } => {
                if let Some(image) = self.resources.images.get(view) {
                    let root = image.root;
                    let stages = stage_mask(stages);
                    if self.hazards.read(root, stages)
                        && let Some(pass) = self.pass.as_mut()
                        && let Some(fence) = self.hazards.fence(&self.gpu.device, root)
                    {
                        pass.wait_fence(root, fence, stages);
                    }
                }
                render::bind_texture(
                    self.gpu,
                    self.resources,
                    texture_index,
                    sampler_index,
                    view,
                    sampler,
                );
            }
            Command::BindTexelBuffer {
                index,
                buffer,
                format,
            } => {
                self.read_buffer(buffer, MTLStages::Fragment);
                render::bind_texel_buffer(
                    self.gpu,
                    self.resources,
                    self.cache,
                    index,
                    buffer,
                    format,
                );
            }
            Command::IndexBuffer { buffer, wide } => {
                self.read_buffer(buffer, MTLStages::Vertex);
                if let Some(pass) = self.pass.as_mut() {
                    pass.set_index_buffer(self.resources, buffer, wide);
                }
            }
            Command::Draw {
                count,
                instances,
                first,
                base_instance,
            } => {
                if let Some(pass) = self.pass.as_mut() {
                    pass.draw(self.gpu, count, instances, first, base_instance);
                }
            }
            Command::DrawIndexed {
                count,
                instances,
                first,
                base_vertex,
                base_instance,
            } => {
                if let Some(pass) = self.pass.as_mut() {
                    pass.draw_indexed(count, instances, first, base_vertex, base_instance);
                }
            }
            Command::DrawMesh {
                threads,
                object_threads,
                mesh_threads,
            } => {
                if let Some(pass) = self.pass.as_mut() {
                    pass.draw_mesh(threads, object_threads, mesh_threads);
                }
            }
            Command::DrawIndexedIndirect { buffer, offset } => {
                self.read_buffer(buffer, MTLStages::Vertex);
                if let Some(pass) = self.pass.as_mut()
                    && let Some(buffer) = self.resources.buffers.get(buffer)
                {
                    pass.draw_indexed_indirect(buffer, offset);
                }
            }
            Command::GeometryDrawIndexedIndirect(request) => {
                if request.stages & STAGE_VERTEX != 0 && request.index_count != 0 {
                    self.sync_geometry_render();
                    self.read_buffer(request.arguments, stage_mask(request.stages));
                    self.read_buffer(request.indices, MTLStages::Vertex);
                    if let (Some(arguments), Some(indices)) = (
                        self.resources.buffers.get(request.arguments),
                        self.resources.buffers.get(request.indices),
                    ) && let Some(pass) = self.pass.as_mut()
                    {
                        pass.draw_geometry_indexed_indirect(&request, arguments, indices);
                    }
                }
            }
            Command::LodCull(request) => {
                self.lod_culler.encode(
                    self.gpu,
                    self.resources,
                    &mut self.scratch,
                    self.timer,
                    &request,
                );
            }
            Command::FeatureCull(request) => {
                if request.depth != 0
                    && let Some(image) = self.resources.images.get(request.depth)
                {
                    self.hazards.read(image.root, MTLStages::Fragment);
                }
                self.culler.encode_feature(
                    self.gpu,
                    self.resources,
                    &mut self.scratch,
                    self.timer,
                    &request,
                );
            }
            Command::TerrainCull(request) => {
                if request.mode == 2 {
                    self.end_pass();
                    self.snapshot_depth = (request.depth != 0).then_some(request.depth);
                }
                self.culler.encode(
                    self.gpu,
                    self.resources,
                    self.hazards,
                    &mut self.scratch,
                    self.timer,
                    &request,
                );
                if request.depth != 0
                    && let Some(image) = self.resources.images.get(request.depth)
                {
                    self.hazards.read(image.root, MTLStages::Fragment);
                }
            }
            Command::TerrainExpand(request) => {
                self.expander.encode(
                    self.gpu,
                    self.resources,
                    &mut self.scratch,
                    self.timer,
                    &request,
                );
            }
            Command::MeshDispatch(request) => {
                if !crate::command::valid_mesh_dispatch(&request) {
                    return;
                }
                let Some(buffer) = self
                    .resources
                    .buffers
                    .find(|buffer| buffer.gpuAddress() == request.resource_address)
                else {
                    return;
                };
                let root = std::ptr::from_ref(buffer).addr();
                if self.hazards.read(root, MTLStages::Vertex)
                    && let Some(pass) = self.pass.as_mut()
                    && let Some(fence) = self.hazards.fence(&self.gpu.device, root)
                {
                    pass.wait_fence(root, fence, MTLStages::Vertex);
                }
                if let Some(pass) = self.pass.as_mut() {
                    pass.draw_mesh_dispatch(buffer, &request);
                }
            }
            Command::PathTrace(request) => {
                self.path_tracer.encode(
                    self.gpu,
                    self.resources,
                    &mut self.scratch,
                    self.timer,
                    &request,
                );
            }
            Command::TerrainSort(request) => {
                self.sorter.encode(
                    self.gpu,
                    self.resources,
                    &mut self.scratch,
                    self.timer,
                    &request,
                );
            }
            Command::TerrainOitResolve(request) => {
                if let Some(pass) = self.pass.as_mut() {
                    pass.resolve_oit(self.gpu, request.k);
                }
            }
            Command::TerrainExpandCheck {
                cpu,
                cpu_offset,
                count,
                first,
                gpu,
                gpu_offset,
                gpu_bytes,
                args_offset,
            } => {
                self.expander.encode_check(
                    self.gpu,
                    self.resources,
                    &mut self.scratch,
                    self.timer,
                    &ExpandCheckRequest {
                        cpu,
                        cpu_offset,
                        count,
                        first,
                        gpu,
                        gpu_offset,
                        gpu_bytes,
                        args_offset,
                    },
                );
            }
            Command::MetalFx(request) => {
                self.end_pass();
                self.end_copies();
                let real_view = (request.mode == 3).then_some(request.current_color);
                let present_generated = request.mode == 3 && !request.reset;
                let encoded = special::metalfx(
                    self.gpu,
                    self.resources,
                    self.hazards,
                    &mut self.scratch,
                    self.timer,
                    special::FxRetirement {
                        current: self.fx,
                        retired: self.retired_fx,
                        frame: self.submitted_frame,
                    },
                    request,
                );
                if encoded {
                    self.interpolation_real_view = real_view;
                    self.interpolation_present_generated = present_generated;
                }
            }
            Command::Scissor(area) => {
                if let Some(pass) = self.pass.as_ref() {
                    pass.scissor(area);
                }
            }
            copy @ (Command::GenerateMipmaps(_)
            | Command::CopyBuffer { .. }
            | Command::CopyBufferToTexture { .. }
            | Command::CopyTextureToBuffer { .. }
            | Command::CopyTextureToTexture { .. }) => {
                if self.copies.is_none() {
                    self.copies = copy::Copies::begin(self.gpu, self.timer);
                }
                if let Some(copies) = self.copies.as_mut() {
                    copies.sync(self.gpu, self.hazards, self.resources, &copy);
                    copies.encode(self.resources, &copy);
                }
            }
            Command::Clear {
                texture,
                color,
                depth,
            } => {
                if step.discard == 0 {
                    special::clear(
                        self.gpu,
                        self.resources,
                        self.hazards,
                        self.timer,
                        texture,
                        color,
                        depth,
                    );
                }
            }
            Command::ClearRegion {
                color,
                rgba,
                depth,
                depth_value,
                area,
            } => {
                special::clear_region(
                    self.gpu,
                    self.resources,
                    self.cache,
                    self.hazards,
                    &mut self.scratch,
                    self.timer,
                    special::RegionClear {
                        color,
                        rgba,
                        depth,
                        depth_value,
                        area,
                    },
                );
            }
            Command::WriteTimestamp { pool, index } => {
                if self.pass.is_none()
                    && let Some(heap) = self.resources.queries.get(pool)
                {
                    metal::write_timestamp(&self.gpu.cmd, heap, index);
                }
            }
            Command::BlitToSurface(view) => {
                if let Some(surface) = self.surface.as_deref_mut() {
                    let present_view = if self.interpolation_present_generated {
                        Some(view)
                    } else {
                        self.interpolation_real_view
                    };
                    if let Some(present_view) = present_view
                        && let Some(drawable) = special::blit(
                            self.gpu,
                            self.resources,
                            self.hazards,
                            self.timer,
                            special::PresentTarget {
                                surface,
                                submitted_frame: self.submitted_frame,
                                evaluation: self.evaluation,
                            },
                            present_view,
                        )
                    {
                        self.drawables.push(drawable);
                    }
                    if self.interpolation_present_generated
                        && let Some(real_view) = self.interpolation_real_view.take()
                        && let Some(drawable) = special::blit(
                            self.gpu,
                            self.resources,
                            self.hazards,
                            self.timer,
                            special::PresentTarget {
                                surface,
                                submitted_frame: self.submitted_frame,
                                evaluation: self.evaluation,
                            },
                            real_view,
                        )
                    {
                        self.drawables.push(drawable);
                    }
                    self.interpolation_present_generated = false;
                }
            }
        }
    }

    pub fn finish(mut self) -> Vec<Retained<ProtocolObject<dyn CAMetalDrawable>>> {
        self.end_compute();
        self.end_pass();
        self.end_copies();
        self.encode_snapshot();
        self.end_compute();
        self.drawables
    }

    fn encode_snapshot(&mut self) {
        let Some(depth) = self.snapshot_depth else {
            self.culler.snapshot_encode_failed();
            return;
        };
        let Some(image) = self.resources.images.get(depth) else {
            self.culler.snapshot_encode_failed();
            return;
        };
        let valid_source =
            image.depth && image.texture.pixelFormat() == objc2_metal::MTLPixelFormat::Depth32Float;
        let Some(source) = valid_source.then(|| {
            self.culler.snapshot_source_for(
                image.root,
                image.texture.width(),
                image.texture.height(),
                image.texture.pixelFormat(),
                image.usage,
            )
        }) else {
            self.culler.snapshot_encode_failed();
            return;
        };
        let Some(source) = source else {
            self.culler.snapshot_encode_failed();
            return;
        };
        let Some(destination_root) = self.culler.snapshot_destination_root() else {
            self.culler.snapshot_encode_failed();
            return;
        };
        if crate::cull::snapshot_self_dependency(source.root, destination_root) {
            self.culler.snapshot_encode_failed();
            return;
        }
        let gpu = self.gpu;
        let hazards = &mut *self.hazards;
        self.culler
            .snapshot_encode_hook(source, &image.texture, |src, dst| {
                let source_hazard = hazards.compute(source.root, ComputeAccess::Read);
                let destination_hazard = hazards.write_at(destination_root, MTLStages::Dispatch);
                let Some(encoder) = gpu.cmd.computeCommandEncoder() else {
                    return false;
                };
                if !source_hazard.producer.is_empty()
                    && let Some(fence) = hazards.fence(&gpu.device, source.root)
                {
                    encoder.waitForFence_beforeEncoderStages(fence, MTLStages::Dispatch);
                    wait(&*encoder, source_hazard.producer, MTLStages::Dispatch);
                }
                if !destination_hazard.producer.is_empty()
                    && let Some(fence) = hazards.fence(&gpu.device, destination_root)
                {
                    encoder.waitForFence_beforeEncoderStages(fence, MTLStages::Dispatch);
                    wait(&*encoder, destination_hazard.producer, MTLStages::Dispatch);
                }
                if !destination_hazard.consumer.is_empty() {
                    encoder.barrierAfterEncoderStages_beforeEncoderStages_visibilityOptions(
                        destination_hazard.consumer,
                        MTLStages::Dispatch,
                        MTL4VisibilityOptions::Device,
                    );
                }
                metal::bind_texture(&gpu.table, 0, src.gpuResourceID());
                metal::bind_texture(&gpu.table, 1, dst.gpuResourceID());
                encoder.setArgumentTable(Some(&gpu.table));
                encoder.setComputePipelineState(&gpu.kernels.snapshot_d32_to_r32);
                let size = MTLSize {
                    width: src.width(),
                    height: src.height(),
                    depth: 1,
                };
                encoder.dispatchThreads_threadsPerThreadgroup(
                    size,
                    MTLSize {
                        width: 8,
                        height: 8,
                        depth: 1,
                    },
                );
                encoder.barrierAfterEncoderStages_beforeEncoderStages_visibilityOptions(
                    MTLStages::Dispatch,
                    MTLStages::Vertex | MTLStages::Fragment | MTLStages::Dispatch,
                    MTL4VisibilityOptions::Device,
                );
                if let Some(fence) = hazards.fence(&gpu.device, destination_root) {
                    encoder.updateFence_afterEncoderStages(fence, MTLStages::Dispatch);
                }
                encoder.endEncoding();
                true
            });
    }

    fn end_compute(&mut self) {
        if let Some(compute) = self.compute.take() {
            compute.end(self.gpu, self.hazards, self.timer);
        }
    }

    fn end_pass(&mut self) {
        self.open = false;
        if let Some(pass) = self.pass.take() {
            pass.end(self.gpu, self.hazards, self.timer);
        }
    }

    fn end_copies(&mut self) {
        if let Some(copies) = self.copies.take() {
            copies.end(self.gpu, self.hazards, self.timer);
        }
    }
}

pub(crate) fn wait(
    encoder: &(impl MTL4CommandEncoder + Message),
    after: MTLStages,
    before: MTLStages,
) {
    encoder.barrierAfterQueueStages_beforeStages_visibilityOptions(
        after,
        before,
        MTL4VisibilityOptions::Device,
    );
}

fn begin_render(
    gpu: &Gpu,
    timer: &mut PassTimer,
    id: u32,
    descriptor: &MTL4RenderPassDescriptor,
) -> Option<RenderEncoder> {
    timer.begin(&gpu.cmd, id);
    let encoder = gpu.cmd.renderCommandEncoderWithDescriptor(descriptor)?;
    encoder.setArgumentTable_atStages(
        &gpu.table,
        MTLRenderStages::Vertex | MTLRenderStages::Fragment,
    );
    Some(encoder)
}

fn end_render(
    gpu: &Gpu,
    timer: &mut PassTimer,
    encoder: &ProtocolObject<dyn MTL4RenderCommandEncoder>,
) {
    encoder.endEncoding();
    timer.end(&gpu.cmd);
}
