use objc2::runtime::ProtocolObject;
use objc2_metal::{
    MTL4CommandEncoder, MTL4RenderCommandEncoder, MTL4RenderPassDescriptor, MTLBuffer,
    MTLClearColor, MTLCompareFunction, MTLFence, MTLIndexType, MTLLoadAction, MTLPixelFormat,
    MTLPrimitiveType, MTLSamplerState, MTLScissorRect, MTLSize, MTLStages, MTLStoreAction,
    MTLTexture, MTLViewport, MTLWinding,
};

use super::{RenderEncoder, begin_render, end_render, wait};
use crate::command::{
    ColorTarget, DepthTarget, GeometryDrawRequest, MeshDispatchRequest, valid_mesh_dispatch,
};
use crate::format;
use crate::gpu::{FAN_VERTICES, Gpu};
use crate::hazard::Hazards;
use crate::pipeline::{OIT_IMAGEBLOCK_SAMPLE_LENGTH, Pipeline, PipelineKind, VERTEX_BUFFER_BASE};
use crate::plan::{DEPTH, color_bit};
use crate::resources::{Cache, Resources};
use crate::timing::PassTimer;
use mtl4::{self as metal, IndexRange};

use crate::geometry_runtime::ResourceIdentity;

pub struct Pass<'a> {
    encoder: RenderEncoder,
    width: f64,
    height: f64,
    has_depth: bool,
    color_format: Option<MTLPixelFormat>,
    written: Vec<usize>,
    waited: Vec<(ResourceIdentity, MTLStages)>,
    pipeline: Option<&'a Pipeline>,
    index: Option<(&'a ProtocolObject<dyn MTLBuffer>, MTLIndexType)>,
    oit_pipeline: Option<(u32, usize)>,
    oit_draws: bool,
    oit_resolved: bool,
}

fn store(discard: u16, bit: u16) -> MTLStoreAction {
    if discard & bit == 0 {
        MTLStoreAction::Store
    } else {
        MTLStoreAction::DontCare
    }
}

pub fn viewport(width: f64, height: f64) -> MTLViewport {
    MTLViewport {
        originX: 0.0,
        originY: 0.0,
        width,
        height,
        znear: 0.0,
        zfar: 1.0,
    }
}

pub fn scissor(area: [i32; 4], width: f64, height: f64) -> MTLScissorRect {
    let [x, y, w, h] = area.map(|v| v.max(0).cast_unsigned() as usize);
    let (max_w, max_h) = (width as usize, height as usize);
    let x = x.min(max_w);
    let y = y.min(max_h);
    MTLScissorRect {
        x,
        y,
        width: w.min(max_w - x),
        height: h.min(max_h - y),
    }
}

pub fn attach_color(
    descriptor: &MTL4RenderPassDescriptor,
    index: usize,
    texture: &ProtocolObject<dyn MTLTexture>,
    clear: Option<MTLClearColor>,
    store: MTLStoreAction,
) {
    if let Some(attachment) = metal::pass_color(descriptor, index) {
        attachment.setTexture(Some(texture));
        attachment.setLoadAction(if clear.is_some() {
            MTLLoadAction::Clear
        } else {
            MTLLoadAction::Load
        });
        attachment.setStoreAction(store);
        if let Some(color) = clear {
            attachment.setClearColor(color);
        }
    }
}

pub fn attach_depth(
    descriptor: &MTL4RenderPassDescriptor,
    texture: &ProtocolObject<dyn MTLTexture>,
    clear: Option<f32>,
    store: MTLStoreAction,
) {
    let attachment = descriptor.depthAttachment();
    attachment.setTexture(Some(texture));
    attachment.setLoadAction(if clear.is_some() {
        MTLLoadAction::Clear
    } else {
        MTLLoadAction::Load
    });
    attachment.setStoreAction(store);
    if let Some(depth) = clear {
        attachment.setClearDepth(f64::from(depth));
    }
}

pub struct Request<'c> {
    pub id: u32,
    pub colors: &'c [ColorTarget],
    pub depth: &'c DepthTarget,
    pub area: [i32; 4],
    pub discard: u16,
}

pub fn begin<'a>(
    gpu: &Gpu,
    resources: &Resources,
    hazards: &mut Hazards,
    timer: &mut PassTimer,
    request: Request,
) -> Option<Pass<'a>> {
    let Request {
        id,
        colors,
        depth,
        area,
        discard,
    } = request;
    let descriptor = MTL4RenderPassDescriptor::new();
    descriptor.setImageblockSampleLength(OIT_IMAGEBLOCK_SAMPLE_LENGTH);
    let mut size = (0.0, 0.0);
    let mut producer = MTLStages::empty();
    let mut consumer = MTLStages::empty();
    let mut color_format = None;
    let mut written = Vec::new();
    let mut producer_roots = Vec::new();
    for (index, target) in colors.iter().enumerate() {
        if let Some(image) = resources.images.get(target.view) {
            size = (image.texture.width() as f64, image.texture.height() as f64);
            color_format = Some(image.texture.pixelFormat());
            let action = store(discard, color_bit(index));
            attach_color(&descriptor, index, &image.texture, target.clear, action);
            let hazard = hazards.write_at(image.root, MTLStages::Fragment);
            producer |= hazard.producer;
            consumer |= hazard.consumer;
            if !hazard.producer.is_empty() {
                producer_roots.push(image.root);
            }
            written.push(image.root);
        }
    }
    let depth_image = resources.images.get(depth.view);
    if let Some(image) = depth_image {
        size = (image.texture.width() as f64, image.texture.height() as f64);
        attach_depth(
            &descriptor,
            &image.texture,
            depth.clear,
            store(discard, DEPTH),
        );
        let hazard = hazards.write_at(image.root, MTLStages::Fragment);
        producer |= hazard.producer;
        consumer |= hazard.consumer;
        if !hazard.producer.is_empty() {
            producer_roots.push(image.root);
        }
        written.push(image.root);
    }
    let encoder = begin_render(gpu, timer, id, &descriptor)?;
    if !producer.is_empty() {
        for root in &producer_roots {
            if let Some(fence) = hazards.fence(&gpu.device, *root) {
                encoder.waitForFence_beforeEncoderStages(fence, MTLStages::Fragment);
            }
        }
    }
    if !consumer.is_empty() {
        wait(&*encoder, consumer, MTLStages::Fragment);
    }
    encoder.setViewport(viewport(size.0, size.1));
    encoder.setScissorRect(scissor(area, size.0, size.1));
    encoder.setFrontFacingWinding(MTLWinding::Clockwise);
    Some(Pass {
        encoder,
        width: size.0,
        height: size.1,
        has_depth: depth_image.is_some(),
        color_format,
        written,
        waited: Vec::new(),
        pipeline: None,
        index: None,
        oit_pipeline: None,
        oit_draws: false,
        oit_resolved: false,
    })
}

impl<'a> Pass<'a> {
    pub fn end(self, gpu: &Gpu, hazards: &mut Hazards, timer: &mut PassTimer) {
        for root in &self.written {
            if let Some(fence) = hazards.fence(&gpu.device, *root) {
                self.encoder
                    .updateFence_afterEncoderStages(fence, MTLStages::Fragment);
            }
        }
        end_render(gpu, timer, &self.encoder);
    }

    pub fn resume(&mut self, area: [i32; 4]) {
        self.encoder.setViewport(viewport(self.width, self.height));
        self.encoder
            .setScissorRect(scissor(area, self.width, self.height));
        self.pipeline = None;
        self.index = None;
    }

    pub fn wait_fence(
        &mut self,
        root: usize,
        fence: &ProtocolObject<dyn MTLFence>,
        stages: MTLStages,
    ) {
        self.wait_fence_resource(ResourceIdentity::ImageRoot(root), fence, stages);
    }

    pub fn wait_fence_resource(
        &mut self,
        root: ResourceIdentity,
        fence: &ProtocolObject<dyn MTLFence>,
        stages: MTLStages,
    ) {
        if self
            .waited
            .iter()
            .any(|&(waited_root, waited)| waited_root == root && waited.contains(stages))
        {
            return;
        }
        self.encoder.waitForFence_beforeEncoderStages(fence, stages);
        self.waited.push((root, stages));
    }

    pub fn set_pipeline(&mut self, gpu: &Gpu, resources: &'a Resources, handle: u64) {
        let Some(pipeline) = resources.pipelines.get(handle) else {
            return;
        };
        self.encoder.setRenderPipelineState(&pipeline.state);
        self.encoder.setCullMode(pipeline.cull);
        self.encoder.setTriangleFillMode(pipeline.fill);
        self.encoder.setDepthBias_slopeScale_clamp(
            pipeline.depth_bias.0,
            pipeline.depth_bias.1,
            0.0,
        );
        let (compare, write) = match pipeline.depth {
            Some(depth) if self.has_depth => depth,
            _ => (MTLCompareFunction::Always, false),
        };
        self.encoder
            .setDepthStencilState(gpu.depth_states.get(compare, write));
        self.oit_pipeline = pipeline
            .oit
            .as_ref()
            .map(|metadata| (metadata.k, metadata.imageblock_sample_length));
        self.pipeline = Some(pipeline);
    }

    pub fn resolve_oit(&mut self, gpu: &Gpu, k: u32) {
        if self.oit_resolved || !self.oit_draws || !self.has_depth {
            return;
        }
        let Some((pipeline_k, sample_length)) = self.oit_pipeline else {
            return;
        };
        if pipeline_k != k || sample_length == 0 || sample_length > OIT_IMAGEBLOCK_SAMPLE_LENGTH {
            return;
        }
        if self.color_format != Some(MTLPixelFormat::BGRA8Unorm) {
            return;
        }
        let Some(tile) = gpu.oit_tile.as_ref() else {
            return;
        };
        self.encoder.setRenderPipelineState(tile);
        self.encoder.dispatchThreadsPerTile(MTLSize {
            width: 1,
            height: 1,
            depth: 1,
        });
        self.oit_resolved = true;
    }

    pub fn set_index_buffer(&mut self, resources: &'a Resources, handle: u64, wide: bool) {
        let kind = if wide {
            MTLIndexType::UInt32
        } else {
            MTLIndexType::UInt16
        };
        self.index = resources
            .buffers
            .get(handle)
            .map(|buffer| (&**buffer, kind));
    }

    pub fn scissor(&self, area: Option<[i32; 4]>) {
        let area = area.unwrap_or([0, 0, self.width as i32, self.height as i32]);
        self.encoder
            .setScissorRect(scissor(area, self.width, self.height));
    }

    pub fn draw(
        &mut self,
        gpu: &Gpu,
        count: usize,
        instances: usize,
        first: usize,
        base_instance: usize,
    ) {
        let Some(pipeline) = self.pipeline else {
            return;
        };
        if pipeline.kind != PipelineKind::Raster {
            return;
        }
        self.oit_draws |= self.oit_pipeline.is_some();
        if !pipeline.fan {
            metal::draw(
                &self.encoder,
                pipeline.primitive,
                first,
                count,
                instances,
                base_instance,
            );
        } else if (3..=FAN_VERTICES as usize).contains(&count) {
            let indices = IndexRange {
                buffer: &gpu.fan_indices,
                kind: MTLIndexType::UInt32,
                first: 0,
                count: (count - 2) * 3,
            };
            metal::draw_indexed(
                &self.encoder,
                MTLPrimitiveType::Triangle,
                indices,
                instances,
                first as isize,
                base_instance,
            );
        }
    }

    pub fn draw_mesh(&mut self, threads: u32, object_threads: u32, mesh_threads: u32) {
        if self
            .pipeline
            .is_some_and(|pipeline| pipeline.kind == PipelineKind::Mesh)
        {
            self.oit_draws |= self.oit_pipeline.is_some();
            metal::draw_mesh_calibration(&self.encoder, threads, object_threads, mesh_threads);
        }
    }

    pub fn draw_mesh_dispatch(
        &mut self,
        buffer: &ProtocolObject<dyn MTLBuffer>,
        request: &MeshDispatchRequest,
    ) {
        if !self
            .pipeline
            .is_some_and(|pipeline| pipeline.kind == PipelineKind::Mesh)
            || !valid_mesh_dispatch(request)
        {
            return;
        }
        let Some(resource_end) = request.resource_offset.checked_add(request.resource_length)
        else {
            return;
        };
        let Some(buffer_end) = u64::try_from(buffer.length()).ok() else {
            return;
        };
        if request.resource_address != buffer.gpuAddress()
            || resource_end > buffer_end
            || request
                .payload_offset
                .checked_add(u64::from(request.payload_bytes))
                .is_none_or(|end| end > request.resource_length)
            || request
                .vertex_offset
                .checked_add(request.vertex_bytes)
                .is_none_or(|end| end > request.resource_length)
            || request
                .primitive_offset
                .checked_add(request.primitive_bytes)
                .is_none_or(|end| end > request.resource_length)
        {
            return;
        }
        self.oit_draws |= self.oit_pipeline.is_some();
        let arguments = metal::MeshArguments {
            grid: request.dispatch_geometry,
            object_group: request.object_threadgroups,
            mesh_group: request.mesh_threadgroups,
            payload_bytes: request.payload_bytes,
            vertex_count: request.vertex_count,
            primitive_count: request.primitive_count,
            resource_address: request.resource_address,
            resource_offset: request.resource_offset,
            resource_length: request.resource_length,
            payload_offset: request.payload_offset,
            vertex_offset: request.vertex_offset,
            vertex_bytes: request.vertex_bytes,
            primitive_offset: request.primitive_offset,
            primitive_bytes: request.primitive_bytes,
        };
        metal::draw_mesh(&self.encoder, &arguments);
    }

    pub fn draw_indexed(
        &mut self,
        count: usize,
        instances: usize,
        first: usize,
        base_vertex: i32,
        base_instance: usize,
    ) {
        let (Some(pipeline), Some((buffer, kind))) = (self.pipeline, self.index) else {
            return;
        };
        if pipeline.kind != PipelineKind::Raster {
            return;
        }
        self.oit_draws |= self.oit_pipeline.is_some();
        let primitive = if pipeline.fan {
            MTLPrimitiveType::Triangle
        } else {
            pipeline.primitive
        };
        let indices = IndexRange {
            buffer,
            kind,
            first,
            count,
        };
        metal::draw_indexed(
            &self.encoder,
            primitive,
            indices,
            instances,
            base_vertex as isize,
            base_instance,
        );
    }

    pub fn draw_indexed_indirect(
        &mut self,
        arguments: &ProtocolObject<dyn MTLBuffer>,
        offset: usize,
    ) {
        let Some((pipeline, buffer, kind)) = self.indexed_draw_state() else {
            return;
        };
        let primitive = if pipeline.fan {
            MTLPrimitiveType::Triangle
        } else {
            pipeline.primitive
        };
        metal::draw_indexed_indirect(&self.encoder, primitive, kind, buffer, arguments, offset);
    }

    pub fn draw_geometry_indexed_indirect(
        &mut self,
        request: &GeometryDrawRequest,
        arguments: &ProtocolObject<dyn MTLBuffer>,
        indices: &ProtocolObject<dyn MTLBuffer>,
    ) {
        let Some(pipeline) = self.raster_pipeline() else {
            return;
        };
        let kind = match request.index_width {
            2 => MTLIndexType::UInt16,
            4 => MTLIndexType::UInt32,
            _ => return,
        };
        let primitive = match request.topology {
            0 => MTLPrimitiveType::Point,
            1 => MTLPrimitiveType::Line,
            2 => MTLPrimitiveType::LineStrip,
            3 => MTLPrimitiveType::Triangle,
            4 => MTLPrimitiveType::TriangleStrip,
            _ => return,
        };
        if pipeline.fan || pipeline.primitive != primitive || request.index_count == 0 {
            return;
        }
        let index_size = request.index_width as usize;
        let Some(index_bytes) = (request.index_count as usize).checked_mul(index_size) else {
            return;
        };
        let Some(index_end) = request.index_offset.checked_add(index_bytes) else {
            return;
        };
        if !request.index_offset.is_multiple_of(index_size)
            || index_end > indices.length()
            || !request.arguments_offset.is_multiple_of(4)
        {
            return;
        }
        self.oit_draws |= self.oit_pipeline.is_some();
        metal::draw_indexed_indirect_at(
            &self.encoder,
            primitive,
            kind,
            indices,
            request.index_offset,
            arguments,
            request.arguments_offset,
        );
    }

    fn raster_pipeline(&self) -> Option<&Pipeline> {
        self.pipeline
            .filter(|pipeline| pipeline.kind == PipelineKind::Raster)
    }

    fn indexed_draw_state(
        &self,
    ) -> Option<(&Pipeline, &ProtocolObject<dyn MTLBuffer>, MTLIndexType)> {
        let (Some(pipeline), Some((buffer, kind))) = (self.raster_pipeline(), self.index) else {
            return None;
        };
        (buffer.length() % index_size(kind) == 0).then_some((pipeline, buffer, kind))
    }
}

fn index_size(kind: MTLIndexType) -> usize {
    if kind == MTLIndexType::UInt32 { 4 } else { 2 }
}

pub fn bind_buffer(gpu: &Gpu, resources: &Resources, index: usize, handle: u64, offset: u64) {
    if let Some(buffer) = resources.buffers.get(handle) {
        metal::bind_address(&gpu.table, index, buffer.gpuAddress() + offset);
    }
}

pub fn bind_vertex_buffer(
    gpu: &Gpu,
    resources: &Resources,
    index: usize,
    handle: u64,
    offset: u64,
) {
    bind_buffer(gpu, resources, VERTEX_BUFFER_BASE + index, handle, offset);
}

pub fn bind_texture(
    gpu: &Gpu,
    resources: &Resources,
    texture_index: usize,
    sampler_index: usize,
    view: u64,
    sampler: u64,
) {
    if let Some(image) = resources.images.get(view) {
        metal::bind_texture(&gpu.table, texture_index, image.texture.gpuResourceID());
    }
    if let Some(sampler) = resources.samplers.get(sampler) {
        metal::bind_sampler(&gpu.table, sampler_index, sampler.gpuResourceID());
    }
}

pub fn bind_texel_buffer(
    gpu: &Gpu,
    resources: &Resources,
    cache: &mut Cache,
    index: usize,
    handle: u64,
    format: u32,
) {
    use std::collections::hash_map::Entry;
    let view = match cache.texel_views.entry((handle, format)) {
        Entry::Occupied(entry) => entry.into_mut(),
        Entry::Vacant(entry) => {
            let Some(buffer) = resources.buffers.get(handle) else {
                return;
            };
            let Some(view) = metal::texel_view(
                buffer,
                MTLPixelFormat(format as _),
                format::texel_size(format),
            ) else {
                return;
            };
            entry.insert(view)
        }
    };
    metal::bind_texture(&gpu.table, index, view.gpuResourceID());
}
