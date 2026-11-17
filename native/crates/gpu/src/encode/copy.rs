use objc2::rc::Retained;
use objc2::runtime::ProtocolObject;
use objc2_metal::{
    MTL4CommandBuffer, MTL4CommandEncoder, MTL4ComputeCommandEncoder, MTL4VisibilityOptions,
    MTLBuffer, MTLStages, MTLTexture,
};

use super::wait;
use crate::command::{Command, TextureRegion};
use crate::geometry_runtime::ResourceIdentity;
use crate::gpu::Gpu;
use crate::hazard::Hazards;
use crate::resources::{Image, Resources};
use crate::timing::{self, PassTimer};
use mtl4::{self as metal, Region};

#[derive(Clone, Copy)]
struct Span {
    key: usize,
    slice: (usize, usize),
    x: (usize, usize),
    y: (usize, usize),
}

impl Span {
    fn buffer(buffer: &ProtocolObject<dyn MTLBuffer>, offset: usize, size: usize) -> Self {
        Self {
            key: std::ptr::from_ref(buffer).addr(),
            slice: (0, 0),
            x: (offset, offset.saturating_add(size)),
            y: (0, 1),
        }
    }

    fn rows(
        buffer: &ProtocolObject<dyn MTLBuffer>,
        offset: usize,
        bytes_per_row: usize,
        rows: usize,
    ) -> Self {
        Self::buffer(buffer, offset, bytes_per_row.saturating_mul(rows))
    }

    fn texture(image: &Image, region: &TextureRegion) -> Self {
        Self {
            key: image.root,
            slice: (image.level + region.mip, region.layer),
            x: (region.x, region.x.saturating_add(region.width)),
            y: (region.y, region.y.saturating_add(region.height)),
        }
    }

    fn mip(image: &Image, level: usize, layer: usize) -> Self {
        let width = (image.texture.width() >> level).max(1);
        let height = (image.texture.height() >> level).max(1);
        Self {
            key: image.root,
            slice: (image.level + level, layer),
            x: (0, width),
            y: (0, height),
        }
    }

    fn overlaps(&self, other: &Self) -> bool {
        self.key == other.key
            && self.slice == other.slice
            && self.x.0 < other.x.1
            && other.x.0 < self.x.1
            && self.y.0 < other.y.1
            && other.y.0 < self.y.1
    }
}

fn region(region: &TextureRegion) -> Region {
    Region {
        texture_slice: region.layer,
        mip: region.mip,
        x: region.x,
        y: region.y,
        width: region.width,
        height: region.height,
    }
}

fn mipmap_range_valid(mip_count: usize, depth: bool, base_mip: usize, mip_levels: usize) -> bool {
    !depth && base_mip == 0 && mip_levels > 1 && mip_levels == mip_count
}

pub struct Copies {
    encoder: Retained<ProtocolObject<dyn MTL4ComputeCommandEncoder>>,
    reads: Vec<Span>,
    writes: Vec<Span>,
    written: Vec<ResourceIdentity>,
}

impl Copies {
    pub fn begin(gpu: &Gpu, timer: &mut PassTimer) -> Option<Self> {
        timer.begin(&gpu.cmd, timing::PASS_COPY);
        let encoder = gpu.cmd.computeCommandEncoder()?;
        Some(Self {
            encoder,
            reads: Vec::new(),
            writes: Vec::new(),
            written: Vec::new(),
        })
    }

    pub fn end(self, gpu: &Gpu, hazards: &mut Hazards, timer: &mut PassTimer) {
        for root in self.written {
            if let Some(fence) = hazards.fence_resource(&gpu.device, root) {
                self.encoder
                    .updateFence_afterEncoderStages(fence, MTLStages::Blit);
            }
        }
        self.encoder.endEncoding();
        timer.end(&gpu.cmd);
    }

    fn order(&mut self, read: Span, write: Span) {
        let conflict = self
            .writes
            .iter()
            .any(|done| done.overlaps(&read) || done.overlaps(&write))
            || self.reads.iter().any(|done| done.overlaps(&write));
        if conflict {
            self.encoder
                .barrierAfterEncoderStages_beforeEncoderStages_visibilityOptions(
                    MTLStages::Blit,
                    MTLStages::Blit,
                    MTL4VisibilityOptions::Device,
                );
            self.reads.clear();
            self.writes.clear();
        }
        self.reads.push(read);
        self.writes.push(write);
    }

    pub fn sync(
        &mut self,
        gpu: &Gpu,
        hazards: &mut Hazards,
        resources: &Resources,
        command: &Command,
    ) {
        let mut access = |root: ResourceIdentity, write: bool| {
            if write {
                let hazard = hazards.write_resource(root, MTLStages::Blit);
                if !hazard.producer.is_empty()
                    && !hazard.producer.contains(MTLStages::Blit)
                    && let Some(fence) = hazards.fence_resource(&gpu.device, root)
                {
                    self.encoder
                        .waitForFence_beforeEncoderStages(fence, MTLStages::Blit);
                }
                if !hazard.consumer.is_empty() {
                    wait(&*self.encoder, hazard.consumer, MTLStages::Blit);
                }
                if !self.written.contains(&root) {
                    self.written.push(root);
                }
            } else if hazards.read_resource(root, MTLStages::Blit)
                && !self.written.contains(&root)
                && let Some(fence) = hazards.fence_resource(&gpu.device, root)
            {
                self.encoder
                    .waitForFence_beforeEncoderStages(fence, MTLStages::Blit);
            }
        };
        match command {
            Command::CopyBuffer {
                src: src_handle,
                dst: dst_handle,
                ..
            } => {
                if resources.buffers.get(*src_handle).is_some()
                    && resources.buffers.get(*dst_handle).is_some()
                {
                    access(ResourceIdentity::BufferHandle(*src_handle), false);
                    access(ResourceIdentity::BufferHandle(*dst_handle), true);
                }
            }
            Command::CopyBufferToTexture {
                src: src_handle,
                dst,
                ..
            } => {
                if let Some(dst) = resources.images.get(dst.texture)
                    && resources.buffers.get(*src_handle).is_some()
                {
                    access(ResourceIdentity::BufferHandle(*src_handle), false);
                    access(ResourceIdentity::ImageRoot(dst.root), true);
                }
            }
            Command::CopyTextureToBuffer {
                src,
                dst: dst_handle,
                ..
            } => {
                if let Some(src) = resources.images.get(src.texture)
                    && resources.buffers.get(*dst_handle).is_some()
                {
                    access(ResourceIdentity::ImageRoot(src.root), false);
                    access(ResourceIdentity::BufferHandle(*dst_handle), true);
                }
            }
            Command::CopyTextureToTexture { src, dst } => {
                if let (Some(src), Some(dst)) = (
                    resources.images.get(src.texture),
                    resources.images.get(dst.texture),
                ) {
                    access(ResourceIdentity::ImageRoot(src.root), false);
                    access(ResourceIdentity::ImageRoot(dst.root), true);
                }
            }
            Command::GenerateMipmaps(request) => {
                if let Some(image) = resources.images.get(request.view)
                    && mipmap_range_valid(
                        image.texture.mipmapLevelCount(),
                        image.depth,
                        request.base_mip,
                        request.mip_levels,
                    )
                {
                    access(ResourceIdentity::ImageRoot(image.root), true);
                }
            }
            _ => {}
        }
    }

    pub fn encode(&mut self, resources: &Resources, command: &Command) {
        match command {
            Command::CopyBuffer {
                src,
                src_offset,
                dst,
                dst_offset,
                size,
            } => {
                if let (Some(src), Some(dst)) =
                    (resources.buffers.get(*src), resources.buffers.get(*dst))
                {
                    self.order(
                        Span::buffer(src, *src_offset, *size),
                        Span::buffer(dst, *dst_offset, *size),
                    );
                    metal::copy_buffer(&self.encoder, src, *src_offset, dst, *dst_offset, *size);
                }
            }
            Command::CopyBufferToTexture {
                src,
                offset,
                bytes_per_row,
                dst,
            } => {
                if let (Some(src), Some(image)) = (
                    resources.buffers.get(*src),
                    resources.images.get(dst.texture),
                ) {
                    self.order(
                        Span::rows(src, *offset, *bytes_per_row, dst.height),
                        Span::texture(image, dst),
                    );
                    metal::copy_buffer_to_texture(
                        &self.encoder,
                        src,
                        *offset,
                        *bytes_per_row,
                        &image.texture,
                        &region(dst),
                    );
                }
            }
            Command::CopyTextureToBuffer {
                src,
                dst,
                offset,
                bytes_per_row,
            } => {
                if let (Some(image), Some(dst)) = (
                    resources.images.get(src.texture),
                    resources.buffers.get(*dst),
                ) {
                    self.order(
                        Span::texture(image, src),
                        Span::rows(dst, *offset, *bytes_per_row, src.height),
                    );
                    metal::copy_texture_to_buffer(
                        &self.encoder,
                        &image.texture,
                        &region(src),
                        dst,
                        *offset,
                        *bytes_per_row,
                    );
                }
            }
            Command::CopyTextureToTexture { src, dst } => {
                if let (Some(from), Some(to)) = (
                    resources.images.get(src.texture),
                    resources.images.get(dst.texture),
                ) {
                    self.order(Span::texture(from, src), Span::texture(to, dst));
                    metal::copy_texture_to_texture(
                        &self.encoder,
                        &from.texture,
                        &region(src),
                        &to.texture,
                        &region(dst),
                    );
                }
            }
            Command::GenerateMipmaps(request) => {
                let Some(image) = resources.images.get(request.view) else {
                    return;
                };
                if !mipmap_range_valid(
                    image.texture.mipmapLevelCount(),
                    image.depth,
                    request.base_mip,
                    request.mip_levels,
                ) {
                    return;
                }
                let layers = if image.texture.textureType() == objc2_metal::MTLTextureType::TypeCube
                {
                    6
                } else {
                    1
                };
                for layer in 0..layers {
                    for level in 1..request.mip_levels {
                        self.order(
                            Span::mip(image, request.base_mip, layer),
                            Span::mip(image, level, layer),
                        );
                    }
                }
                metal::generate_mipmaps(
                    &self.encoder,
                    &image.texture,
                    request.base_mip,
                    request.mip_levels,
                );
            }
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::mipmap_range_valid;

    #[test]
    fn accepts_complete_color_mip_chain() {
        assert!(mipmap_range_valid(4, false, 0, 4));
    }

    #[test]
    fn rejects_invalid_mip_ranges_and_depth() {
        assert!(!mipmap_range_valid(1, false, 0, 1));
        assert!(!mipmap_range_valid(4, false, 1, 3));
        assert!(!mipmap_range_valid(4, false, 0, 3));
        assert!(!mipmap_range_valid(4, true, 0, 4));
    }
}
