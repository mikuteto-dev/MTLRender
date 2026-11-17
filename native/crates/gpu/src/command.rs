use objc2_metal::MTLClearColor;

use crate::lod::{LOD_CULL_VERSION, LodCullRequest};

pub const MESH_DISPATCH_OPCODE: u32 = 28;
pub const MESH_DISPATCH_WORDS: usize = 30;
pub const MESH_MAX_VERTICES: u32 = 256;
pub const MESH_MAX_PRIMITIVES: u32 = 512;
pub const MESH_MAX_THREADGROUP_DIMENSION: u32 = 65_535;
pub const MESH_MAX_OBJECT_THREADS: u32 = 1_024;
pub const MESH_MAX_MESH_THREADS: u32 = 1_024;
pub const MESH_MAX_PAYLOAD_BYTES: u32 = 16_384;

pub struct ColorTarget {
    pub view: u64,
    pub clear: Option<MTLClearColor>,
}

pub struct DepthTarget {
    pub view: u64,
    pub clear: Option<f32>,
}

pub struct TextureRegion {
    pub texture: u64,
    pub mip: usize,
    pub layer: usize,
    pub x: usize,
    pub y: usize,
    pub width: usize,
    pub height: usize,
}

pub struct CullRequest {
    pub phase: u32,
    pub mode: u32,
    pub units: u64,
    pub units_offset: usize,
    pub count: u32,
    pub first: [u32; 3],
    pub indices: u32,
    pub sections: u32,
    pub out: u64,
    pub out_offset: usize,
    pub args_offset: usize,
    pub depth: u64,
    pub clip: [f32; 16],
    pub previous_clip: [f32; 16],
    pub camera_block: [i32; 3],
    pub camera_offset: [f32; 3],
    pub from_args: u32,
    pub count_args_offset: usize,
}

pub const FEATURE_AABB_WORDS: u32 = 6;

pub struct FeatureCullRequest {
    pub aabbs: u64,
    pub aabbs_offset: usize,
    pub count: u32,
    pub stride_words: u32,
    pub out: u64,
    pub out_offset: usize,
    pub depth: u64,
    pub clip: [f32; 16],
    pub previous_clip: [f32; 16],
}

impl FeatureCullRequest {
    pub fn valid(&self) -> bool {
        self.valid_ranges(usize::MAX, usize::MAX, 1, 1)
    }

    pub fn valid_ranges(
        &self,
        aabbs_len: usize,
        out_len: usize,
        depth_width: usize,
        depth_height: usize,
    ) -> bool {
        let Some(bytes) = self
            .count
            .checked_mul(self.stride_words)
            .and_then(|words| words.checked_mul(4))
            .and_then(|bytes| usize::try_from(bytes).ok())
        else {
            return false;
        };
        let Some(output_bytes) = (self.count as usize).checked_mul(4) else {
            return false;
        };
        self.stride_words == FEATURE_AABB_WORDS
            && depth_width > 0
            && depth_height > 0
            && self
                .aabbs_offset
                .checked_add(bytes)
                .is_some_and(|end| end <= aabbs_len)
            && self
                .out_offset
                .checked_add(output_bytes)
                .is_some_and(|end| end <= out_len)
            && self.clip.iter().all(|value| value.is_finite())
            && self.previous_clip.iter().all(|value| value.is_finite())
    }
}

pub struct ExpandRequest {
    pub updates: u64,
    pub updates_offset: usize,
    pub update_count: u32,
    pub visible: u64,
    pub visible_offset: usize,
    pub visible_count: u32,
    pub sections: u32,
    pub cull_mask: u32,
    pub layer_mask: u32,
    pub options: u32,
    pub indices: u32,
    pub capacity: u32,
    pub camera_block: [i32; 3],
    pub camera_offset: [f32; 3],
    pub out: u64,
    pub out_offset: usize,
    pub args_offset: usize,
}

pub struct MeshDispatchRequest {
    pub object_threadgroups: [u32; 3],
    pub mesh_threadgroups: [u32; 3],
    pub payload_bytes: u32,
    pub vertex_count: u32,
    pub primitive_count: u32,
    pub resource_address: u64,
    pub resource_offset: u64,
    pub resource_length: u64,
    pub payload_offset: u64,
    pub vertex_offset: u64,
    pub vertex_bytes: u64,
    pub primitive_offset: u64,
    pub primitive_bytes: u64,
    pub dispatch_geometry: [u32; 3],
}

pub fn valid_mesh_dispatch(request: &MeshDispatchRequest) -> bool {
    if request.resource_address == 0
        || request.resource_length == 0
        || request.payload_bytes == 0
        || request.payload_bytes > MESH_MAX_PAYLOAD_BYTES
        || request.vertex_count == 0
        || request.vertex_count > MESH_MAX_VERTICES
        || request.primitive_count == 0
        || request.primitive_count > MESH_MAX_PRIMITIVES
        || request.vertex_bytes == 0
        || request.primitive_bytes == 0
    {
        return false;
    }
    let dimensions_valid = request
        .object_threadgroups
        .into_iter()
        .chain(request.mesh_threadgroups)
        .chain(request.dispatch_geometry)
        .all(|value| value != 0 && value <= MESH_MAX_THREADGROUP_DIMENSION);
    let object_threads = threadgroup_threads(request.object_threadgroups);
    let mesh_threads = threadgroup_threads(request.mesh_threadgroups);
    dimensions_valid
        && object_threads.is_some_and(|value| value <= MESH_MAX_OBJECT_THREADS)
        && mesh_threads.is_some_and(|value| value <= MESH_MAX_MESH_THREADS)
        && request
            .resource_address
            .checked_add(request.resource_offset)
            .is_some()
        && request
            .resource_offset
            .checked_add(request.resource_length)
            .is_some()
        && mesh_range(
            request.payload_offset,
            u64::from(request.payload_bytes),
            request.resource_length,
        )
        && mesh_range(
            request.vertex_offset,
            request.vertex_bytes,
            request.resource_length,
        )
        && mesh_range(
            request.primitive_offset,
            request.primitive_bytes,
            request.resource_length,
        )
}

fn threadgroup_threads(dimensions: [u32; 3]) -> Option<u32> {
    dimensions
        .into_iter()
        .try_fold(1u32, |total, value| total.checked_mul(value))
}

fn mesh_range(offset: u64, length: u64, capacity: u64) -> bool {
    offset
        .checked_add(length)
        .is_some_and(|end| end <= capacity)
}

pub struct MetalFxRequest {
    pub mode: u32,
    pub color: u64,
    pub current_color: u64,
    pub previous_color: u64,
    pub depth: u64,
    pub motion: u64,
    pub ui: u64,
    pub output: u64,
    pub input: [u32; 2],
    pub output_size: [u32; 2],
    pub delta_time: f32,
    pub motion_scale: [f32; 2],
    pub camera: [f32; 4],
    pub world_to_view: [f32; 16],
    pub view_to_clip: [f32; 16],
    pub jitter: [f32; 2],
    pub current_inverse: [f32; 16],
    pub previous_clip: [f32; 16],
    pub reset: bool,
    pub ui_composited: bool,
    pub depth_reversed: bool,
}

pub struct OitResolveRequest {
    pub k: u32,
}

pub struct SortRequest {
    pub records: u64,
    pub records_offset: usize,
    pub expected: u64,
    pub expected_offset: usize,
    pub count: u32,
    pub items: u32,
    pub indices: u32,
    pub max_padded: u32,
    pub camera: [f32; 3],
}

pub struct ExpandCheckRequest {
    pub cpu: u64,
    pub cpu_offset: usize,
    pub count: u32,
    pub first: [u32; 3],
    pub gpu: u64,
    pub gpu_offset: usize,
    pub gpu_bytes: usize,
    pub args_offset: usize,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum ComputeAccess {
    Read,
    Write,
    ReadWrite,
}

impl ComputeAccess {
    fn decode(value: u32) -> Option<Self> {
        match value {
            0 => Some(Self::Read),
            1 => Some(Self::Write),
            2 => Some(Self::ReadWrite),
            _ => None,
        }
    }
}

pub struct ComputeBufferRequest {
    pub slot: u32,
    pub buffer: u64,
    pub offset: u64,
    pub access: ComputeAccess,
}

pub struct ComputeTextureRequest {
    pub texture_slot: u32,
    pub sampler_slot: u32,
    pub view: u64,
    pub sampler: u64,
    pub access: ComputeAccess,
}

pub struct ComputeImageRequest {
    pub slot: u32,
    pub view: u64,
    pub access: ComputeAccess,
}

pub struct ComputeBytesRequest {
    pub slot: u32,
    pub bytes: Vec<u8>,
}

pub struct ComputeDispatchIndirectRequest {
    pub buffer: u64,
    pub offset: u64,
}

pub struct GeometryDrawRequest {
    pub arguments: u64,
    pub arguments_offset: usize,
    pub indices: u64,
    pub index_offset: usize,
    pub index_width: u32,
    pub topology: u32,
    pub index_count: u32,
    pub stages: u32,
}

pub struct PathTraceRequest {
    pub color: u64,
    pub depth: u64,
    pub bricks: u64,
    pub table: u64,
    pub width: u32,
    pub height: u32,
    pub resolution_scale: f32,
    pub bounces: u32,
    pub camera: [f32; 3],
    pub inverse_clip: [f32; 16],
    pub previous_clip: [f32; 16],
    pub table_origin: [i32; 3],
    pub sun_direction: [f32; 3],
    pub sun_color: [f32; 3],
    pub moon_direction: [f32; 3],
    pub moon_color: [f32; 3],
}

pub struct GenerateMipmapsRequest {
    pub view: u64,
    pub base_mip: usize,
    pub mip_levels: usize,
}

pub enum Command {
    BeginPass {
        id: u32,
        colors: Vec<ColorTarget>,
        depth: DepthTarget,
        area: [i32; 4],
    },
    EndPass,
    SetPipeline(u64),
    BeginCompute {
        allow_concurrent: bool,
    },
    EndCompute,
    ComputePipeline(u64),
    ComputeBuffer(ComputeBufferRequest),
    ComputeTexture(ComputeTextureRequest),
    ComputeImage(ComputeImageRequest),
    ComputeBytes(ComputeBytesRequest),
    ComputeDispatch([u32; 3]),
    ComputeDispatchIndirect(ComputeDispatchIndirectRequest),
    BindBuffer {
        index: usize,
        buffer: u64,
        offset: u64,
    },
    BindTexture {
        texture_index: usize,
        sampler_index: usize,
        view: u64,
        sampler: u64,
        stages: u32,
    },
    BindTexelBuffer {
        index: usize,
        buffer: u64,
        format: u32,
    },
    VertexBuffer {
        index: usize,
        buffer: u64,
        offset: u64,
    },
    IndexBuffer {
        buffer: u64,
        wide: bool,
    },
    Draw {
        count: usize,
        instances: usize,
        first: usize,
        base_instance: usize,
    },
    DrawIndexed {
        count: usize,
        instances: usize,
        first: usize,
        base_vertex: i32,
        base_instance: usize,
    },
    DrawMesh {
        threads: u32,
        object_threads: u32,
        mesh_threads: u32,
    },
    DrawIndexedIndirect {
        buffer: u64,
        offset: usize,
    },
    GeometryDrawIndexedIndirect(GeometryDrawRequest),
    TerrainCull(CullRequest),
    FeatureCull(FeatureCullRequest),
    LodCull(LodCullRequest),
    TerrainExpand(ExpandRequest),
    MeshDispatch(MeshDispatchRequest),
    TerrainSort(SortRequest),
    TerrainOitResolve(OitResolveRequest),
    TerrainExpandCheck {
        cpu: u64,
        cpu_offset: usize,
        count: u32,
        first: [u32; 3],
        gpu: u64,
        gpu_offset: usize,
        gpu_bytes: usize,
        args_offset: usize,
    },
    PathTrace(PathTraceRequest),
    GenerateMipmaps(GenerateMipmapsRequest),
    MetalFx(MetalFxRequest),
    Scissor(Option<[i32; 4]>),
    CopyBuffer {
        src: u64,
        src_offset: usize,
        dst: u64,
        dst_offset: usize,
        size: usize,
    },
    CopyBufferToTexture {
        src: u64,
        offset: usize,
        bytes_per_row: usize,
        dst: TextureRegion,
    },
    CopyTextureToBuffer {
        src: TextureRegion,
        dst: u64,
        offset: usize,
        bytes_per_row: usize,
    },
    CopyTextureToTexture {
        src: TextureRegion,
        dst: TextureRegion,
    },
    PackDepthConvert {
        source: u64,
        destination: u64,
    },
    Clear {
        texture: u64,
        color: MTLClearColor,
        depth: f32,
    },
    ClearRegion {
        color: u64,
        rgba: MTLClearColor,
        depth: u64,
        depth_value: f32,
        area: [i32; 4],
    },
    BlitToSurface(u64),
    WriteTimestamp {
        pool: u64,
        index: usize,
    },
}

impl Command {
    pub fn is_copy(&self) -> bool {
        matches!(
            self,
            Self::CopyBuffer { .. }
                | Self::CopyBufferToTexture { .. }
                | Self::CopyTextureToBuffer { .. }
                | Self::CopyTextureToTexture { .. }
                | Self::GenerateMipmaps(_)
        )
    }
}

pub struct Decoder<'a> {
    words: &'a [u32],
    position: usize,
    invalid: bool,
}

fn read_matrix(decoder: &mut Decoder<'_>) -> Option<[f32; 16]> {
    let mut matrix = [0.0; 16];
    for value in &mut matrix {
        *value = decoder.f32()?;
    }
    Some(matrix)
}

fn finite_matrix(matrix: &[f32; 16]) -> bool {
    matrix.iter().all(|value| value.is_finite())
}

impl<'a> Decoder<'a> {
    pub fn new(words: &'a [u32]) -> Self {
        Self {
            words,
            position: 0,
            invalid: false,
        }
    }

    fn u32(&mut self) -> Option<u32> {
        let value = self.words.get(self.position).copied()?;
        self.position = self.position.checked_add(1)?;
        Some(value)
    }
    fn i32(&mut self) -> Option<i32> {
        self.u32().map(u32::cast_signed)
    }
    fn f32(&mut self) -> Option<f32> {
        self.u32().map(f32::from_bits)
    }
    fn u64(&mut self) -> Option<u64> {
        Some(u64::from(self.u32()?) | (u64::from(self.u32()?) << 32))
    }
    fn usize(&mut self) -> Option<usize> {
        self.u32().map(|v| v as usize)
    }
    fn checked_range(&mut self) -> Option<(usize, usize)> {
        let offset = usize::try_from(self.u64()?).ok()?;
        let length = usize::try_from(self.u64()?).ok()?;
        offset.checked_add(length).map(|_| (offset, length))
    }
    fn flag(&mut self) -> Option<bool> {
        self.u32().map(|v| v != 0)
    }
    fn bool_flag(&mut self) -> Option<bool> {
        match self.u32()? {
            0 => Some(false),
            1 => Some(true),
            _ => None,
        }
    }
    fn take(&mut self, count: usize) -> Option<Decoder<'a>> {
        let end = self.position.checked_add(count)?;
        let payload = self.words.get(self.position..end)?;
        self.position = end;
        Some(Decoder::new(payload))
    }
    fn rgba(&mut self) -> Option<MTLClearColor> {
        Some(MTLClearColor {
            red: f64::from(self.f32()?),
            green: f64::from(self.f32()?),
            blue: f64::from(self.f32()?),
            alpha: f64::from(self.f32()?),
        })
    }
    fn area(&mut self) -> Option<[i32; 4]> {
        Some([self.i32()?, self.i32()?, self.i32()?, self.i32()?])
    }
    fn bytes(&mut self, length: usize) -> Option<Vec<u8>> {
        let word_count = length.checked_add(7)?.checked_div(8)?.checked_mul(2)?;
        if self.words.len().saturating_sub(self.position) < word_count {
            return None;
        }
        let mut bytes = Vec::with_capacity(length.min(word_count.saturating_mul(4)));
        for _ in 0..word_count {
            let word = self.u32()?.to_le_bytes();
            for byte in word {
                if bytes.len() < length {
                    bytes.push(byte);
                }
            }
        }
        (bytes.len() == length).then_some(bytes)
    }

    fn access(&mut self) -> Option<ComputeAccess> {
        ComputeAccess::decode(self.u32()?)
    }

    fn region(&mut self, texture: u64, mip: usize, layer: usize) -> Option<TextureRegion> {
        Some(TextureRegion {
            texture,
            mip,
            layer,
            x: self.usize()?,
            y: self.usize()?,
            width: self.usize()?,
            height: self.usize()?,
        })
    }

    fn command(&mut self, op: u32) -> Option<Command> {
        Some(match op {
            1 => {
                let id = self.u32()?;
                let mut colors = Vec::new();
                for _ in 0..self.usize()? {
                    let view = self.u64()?;
                    let clear = self.flag()?;
                    let color = self.rgba()?;
                    colors.push(ColorTarget {
                        view,
                        clear: clear.then_some(color),
                    });
                }
                let view = self.u64()?;
                let clear = self.flag()?;
                let value = self.f32()?;
                Command::BeginPass {
                    id,
                    colors,
                    depth: DepthTarget {
                        view,
                        clear: clear.then_some(value),
                    },
                    area: self.area()?,
                }
            }
            2 => Command::EndPass,
            3 => Command::SetPipeline(self.u64()?),
            29 => Command::EndCompute,
            30 => Command::ComputePipeline(self.u64()?),
            31 => Command::ComputeBuffer(ComputeBufferRequest {
                slot: self.u32()?,
                buffer: self.u64()?,
                offset: self.u64()?,
                access: self.access()?,
            }),
            32 => Command::ComputeTexture(ComputeTextureRequest {
                texture_slot: self.u32()?,
                sampler_slot: self.u32()?,
                view: self.u64()?,
                sampler: self.u64()?,
                access: self.access()?,
            }),
            33 => Command::ComputeImage(ComputeImageRequest {
                slot: self.u32()?,
                view: self.u64()?,
                access: self.access()?,
            }),
            34 => {
                let slot = self.u32()?;
                let length = self.usize()?;
                Command::ComputeBytes(ComputeBytesRequest {
                    slot,
                    bytes: self.bytes(length)?,
                })
            }
            35 => Command::ComputeDispatch([self.u32()?, self.u32()?, self.u32()?]),
            36 => Command::ComputeDispatchIndirect(ComputeDispatchIndirectRequest {
                buffer: self.u64()?,
                offset: self.u64()?,
            }),
            4 => Command::BindBuffer {
                index: self.usize()?,
                buffer: self.u64()?,
                offset: self.u64()?,
            },
            5 => Command::BindTexture {
                texture_index: self.usize()?,
                sampler_index: self.usize()?,
                view: self.u64()?,
                sampler: self.u64()?,
                stages: self.u32()?,
            },
            6 => Command::BindTexelBuffer {
                index: self.usize()?,
                buffer: self.u64()?,
                format: self.u32()?,
            },
            7 => Command::VertexBuffer {
                index: self.usize()?,
                buffer: self.u64()?,
                offset: self.u64()?,
            },
            8 => Command::IndexBuffer {
                buffer: self.u64()?,
                wide: self.flag()?,
            },
            9 => Command::Draw {
                count: self.usize()?,
                instances: self.usize()?,
                first: self.usize()?,
                base_instance: self.usize()?,
            },
            10 => Command::DrawIndexed {
                count: self.usize()?,
                instances: self.usize()?,
                first: self.usize()?,
                base_vertex: self.i32()?,
                base_instance: self.usize()?,
            },
            37 => Command::DrawMesh {
                threads: self.u32()?,
                object_threads: self.u32()?,
                mesh_threads: self.u32()?,
            },
            11 => Command::Scissor(Some(self.area()?)),
            12 => Command::Scissor(None),
            13 => Command::CopyBuffer {
                src: self.u64()?,
                src_offset: self.usize()?,
                dst: self.u64()?,
                dst_offset: self.usize()?,
                size: self.usize()?,
            },
            14 => {
                let (src, offset, bytes_per_row) = (self.u64()?, self.usize()?, self.usize()?);
                let (texture, mip, layer) = (self.u64()?, self.usize()?, self.usize()?);
                Command::CopyBufferToTexture {
                    src,
                    offset,
                    bytes_per_row,
                    dst: self.region(texture, mip, layer)?,
                }
            }
            15 => {
                let (texture, mip) = (self.u64()?, self.usize()?);
                let src = self.region(texture, mip, 0)?;
                Command::CopyTextureToBuffer {
                    src,
                    dst: self.u64()?,
                    offset: self.usize()?,
                    bytes_per_row: self.usize()?,
                }
            }
            16 => {
                let (src, dst, mip) = (self.u64()?, self.u64()?, self.usize()?);
                let (dx, dy, sx, sy, width, height) = (
                    self.usize()?,
                    self.usize()?,
                    self.usize()?,
                    self.usize()?,
                    self.usize()?,
                    self.usize()?,
                );
                Command::CopyTextureToTexture {
                    src: TextureRegion {
                        texture: src,
                        mip,
                        layer: 0,
                        x: sx,
                        y: sy,
                        width,
                        height,
                    },
                    dst: TextureRegion {
                        texture: dst,
                        mip,
                        layer: 0,
                        x: dx,
                        y: dy,
                        width,
                        height,
                    },
                }
            }
            41 => Command::PackDepthConvert {
                source: self.u64()?,
                destination: self.u64()?,
            },
            17 => Command::Clear {
                texture: self.u64()?,
                color: self.rgba()?,
                depth: self.f32()?,
            },
            18 => Command::ClearRegion {
                color: self.u64()?,
                rgba: self.rgba()?,
                depth: self.u64()?,
                depth_value: self.f32()?,
                area: self.area()?,
            },
            19 => Command::BlitToSurface(self.u64()?),
            20 => Command::WriteTimestamp {
                pool: self.u64()?,
                index: self.usize()?,
            },
            26 => {
                let mode = self.u32()?;
                if mode == 3 {
                    let payload_words = usize::try_from(self.u32()?).ok()?;
                    if payload_words != 60 {
                        return None;
                    }
                    let mut payload = self.take(payload_words)?;
                    let current_color = payload.u64()?;
                    let request = MetalFxRequest {
                        mode,
                        color: current_color,
                        current_color,
                        previous_color: payload.u64()?,
                        depth: payload.u64()?,
                        motion: payload.u64()?,
                        ui: payload.u64()?,
                        output: payload.u64()?,
                        input: [payload.u32()?, payload.u32()?],
                        output_size: [payload.u32()?, payload.u32()?],
                        delta_time: payload.f32()?,
                        motion_scale: [payload.f32()?, payload.f32()?],
                        camera: [
                            payload.f32()?,
                            payload.f32()?,
                            payload.f32()?,
                            payload.f32()?,
                        ],
                        world_to_view: read_matrix(&mut payload)?,
                        view_to_clip: read_matrix(&mut payload)?,
                        jitter: [payload.f32()?, payload.f32()?],
                        current_inverse: [0.0; 16],
                        previous_clip: [0.0; 16],
                        reset: payload.bool_flag()?,
                        ui_composited: payload.bool_flag()?,
                        depth_reversed: payload.bool_flag()?,
                    };
                    if payload.position == payload.words.len() {
                        Command::MetalFx(request)
                    } else {
                        return None;
                    }
                } else {
                    let color = self.u64()?;
                    let depth = self.u64()?;
                    let output = self.u64()?;
                    let input = [self.u32()?, self.u32()?];
                    let output_size = [self.u32()?, self.u32()?];
                    let jitter = [self.f32()?, self.f32()?];
                    let mut current_inverse = [0.0; 16];
                    for value in &mut current_inverse {
                        *value = self.f32()?;
                    }
                    let mut previous_clip = [0.0; 16];
                    for value in &mut previous_clip {
                        *value = self.f32()?;
                    }
                    Command::MetalFx(MetalFxRequest {
                        mode,
                        color,
                        current_color: color,
                        previous_color: 0,
                        depth,
                        motion: 0,
                        ui: 0,
                        output,
                        input,
                        output_size,
                        delta_time: 0.0,
                        motion_scale: [0.0; 2],
                        camera: [0.0; 4],
                        world_to_view: [0.0; 16],
                        view_to_clip: [0.0; 16],
                        jitter,
                        current_inverse,
                        previous_clip,
                        reset: self.flag()?,
                        ui_composited: false,
                        depth_reversed: true,
                    })
                }
            }
            21 => {
                let phase = self.u32()?;
                let mode = self.u32()?;
                let units = self.u64()?;
                let units_offset = self.u64()? as usize;
                let count = self.u32()?;
                let first = [self.u32()?, self.u32()?, self.u32()?];
                let indices = self.u32()?;
                let sections = self.u32()?;
                let out = self.u64()?;
                let out_offset = self.u64()? as usize;
                let args_offset = self.u64()? as usize;
                let depth = self.u64()?;
                let mut clip = [0.0f32; 16];
                for value in clip.iter_mut() {
                    *value = self.f32()?;
                }
                let mut previous_clip = [0.0f32; 16];
                for value in previous_clip.iter_mut() {
                    *value = self.f32()?;
                }
                let camera_block = [self.i32()?, self.i32()?, self.i32()?];
                let camera_offset = [self.f32()?, self.f32()?, self.f32()?];
                let from_args = self.u32()?;
                let count_args_offset = self.u64()? as usize;
                let request = CullRequest {
                    phase,
                    mode,
                    units,
                    units_offset,
                    count,
                    first,
                    indices,
                    sections,
                    out,
                    out_offset,
                    args_offset,
                    depth,
                    clip,
                    previous_clip,
                    camera_block,
                    camera_offset,
                    from_args,
                    count_args_offset,
                };
                if !finite_matrix(&request.clip)
                    || !finite_matrix(&request.previous_clip)
                    || request.camera_offset.iter().any(|value| !value.is_finite())
                {
                    return None;
                }
                Command::TerrainCull(request)
            }
            22 => Command::DrawIndexedIndirect {
                buffer: self.u64()?,
                offset: self.u64()? as usize,
            },
            43 => Command::GeometryDrawIndexedIndirect(GeometryDrawRequest {
                arguments: self.u64()?,
                arguments_offset: usize::try_from(self.u64()?).ok()?,
                indices: self.u64()?,
                index_offset: usize::try_from(self.u64()?).ok()?,
                index_width: self.u32()?,
                topology: self.u32()?,
                index_count: self.u32()?,
                stages: self.u32()?,
            }),
            39 => {
                let aabbs = self.u64()?;
                let aabbs_offset = usize::try_from(self.u64()?).ok()?;
                let count = self.u32()?;
                let stride_words = self.u32()?;
                let out = self.u64()?;
                let out_offset = usize::try_from(self.u64()?).ok()?;
                let depth = self.u64()?;
                let clip = read_matrix(self)?;
                let previous_clip = read_matrix(self)?;
                let request = FeatureCullRequest {
                    aabbs,
                    aabbs_offset,
                    count,
                    stride_words,
                    out,
                    out_offset,
                    depth,
                    clip,
                    previous_clip,
                };
                Some(Command::FeatureCull(request))?
            }
            38 => {
                let version = self.u32()?;
                if version != LOD_CULL_VERSION {
                    return None;
                }
                let nodes = self.u64()?;
                let (nodes_offset, nodes_length) = self.checked_range()?;
                let meshes = self.u64()?;
                let (meshes_offset, meshes_length) = self.checked_range()?;
                let camera = [self.f32()?, self.f32()?, self.f32()?];
                let mut frustum = [0.0; 16];
                for value in &mut frustum {
                    *value = self.f32()?;
                }
                let output = self.u64()?;
                let (output_offset, output_length) = self.checked_range()?;
                let level = self.u32()?;
                let max_level = self.u32()?;
                let threshold = self.f32()?;
                let request = LodCullRequest {
                    version,
                    nodes,
                    nodes_offset,
                    nodes_length,
                    meshes,
                    meshes_offset,
                    meshes_length,
                    camera,
                    frustum,
                    output,
                    output_offset,
                    output_length,
                    level,
                    max_level,
                    threshold,
                };
                request.valid().then_some(Command::LodCull(request))?
            }
            23 => {
                let updates = self.u64()?;
                let updates_offset = self.u64()? as usize;
                let update_count = self.u32()?;
                let visible = self.u64()?;
                let visible_offset = self.u64()? as usize;
                let visible_count = self.u32()?;
                let sections = self.u32()?;
                let cull_mask = self.u32()?;
                let layer_mask = self.u32()?;
                let options = self.u32()?;
                let indices = self.u32()?;
                let capacity = self.u32()?;
                let camera_block = [self.i32()?, self.i32()?, self.i32()?];
                let camera_offset = [self.f32()?, self.f32()?, self.f32()?];
                let out = self.u64()?;
                let out_offset = self.u64()? as usize;
                let args_offset = self.u64()? as usize;
                Command::TerrainExpand(ExpandRequest {
                    updates,
                    updates_offset,
                    update_count,
                    visible,
                    visible_offset,
                    visible_count,
                    sections,
                    cull_mask,
                    layer_mask,
                    options,
                    indices,
                    capacity,
                    camera_block,
                    camera_offset,
                    out,
                    out_offset,
                    args_offset,
                })
            }
            40 => {
                let k = self.u32()?;
                if !(4..=8).contains(&k) {
                    return None;
                }
                Command::TerrainOitResolve(OitResolveRequest { k })
            }
            24 => Command::TerrainExpandCheck {
                cpu: self.u64()?,
                cpu_offset: self.u64()? as usize,
                count: self.u32()?,
                first: [self.u32()?, self.u32()?, self.u32()?],
                gpu: self.u64()?,
                gpu_offset: self.u64()? as usize,
                gpu_bytes: self.u64()? as usize,
                args_offset: self.u64()? as usize,
            },
            42 => Command::GenerateMipmaps(GenerateMipmapsRequest {
                view: self.u64()?,
                base_mip: self.usize()?,
                mip_levels: self.usize()?,
            }),
            25 => {
                let color = self.u64()?;
                let depth = self.u64()?;
                let bricks = self.u64()?;
                let table = self.u64()?;
                let width = self.u32()?;
                let height = self.u32()?;
                let resolution_scale = self.f32()?;
                let bounces = self.u32()?;
                let camera = [self.f32()?, self.f32()?, self.f32()?];
                let mut inverse_clip = [0.0; 16];
                for value in &mut inverse_clip {
                    *value = self.f32()?;
                }
                let mut previous_clip = [0.0; 16];
                for value in &mut previous_clip {
                    *value = self.f32()?;
                }
                let table_origin = [self.i32()?, self.i32()?, self.i32()?];
                let sun_direction = [self.f32()?, self.f32()?, self.f32()?];
                let sun_color = [self.f32()?, self.f32()?, self.f32()?];
                let moon_direction = [self.f32()?, self.f32()?, self.f32()?];
                let moon_color = [self.f32()?, self.f32()?, self.f32()?];
                let request = PathTraceRequest {
                    color,
                    depth,
                    bricks,
                    table,
                    width,
                    height,
                    resolution_scale,
                    bounces,
                    camera,
                    inverse_clip,
                    previous_clip,
                    table_origin,
                    sun_direction,
                    sun_color,
                    moon_direction,
                    moon_color,
                };
                if !request.resolution_scale.is_finite()
                    || !request.camera.iter().all(|value| value.is_finite())
                    || !finite_matrix(&request.inverse_clip)
                    || !finite_matrix(&request.previous_clip)
                    || !request.sun_direction.iter().all(|value| value.is_finite())
                    || !request.sun_color.iter().all(|value| value.is_finite())
                    || !request.moon_direction.iter().all(|value| value.is_finite())
                    || !request.moon_color.iter().all(|value| value.is_finite())
                {
                    return None;
                }
                Command::PathTrace(request)
            }
            27 => Command::TerrainSort(SortRequest {
                records: self.u64()?,
                records_offset: self.usize()?,
                expected: self.u64()?,
                expected_offset: self.usize()?,
                count: self.u32()?,
                items: self.u32()?,
                indices: self.u32()?,
                max_padded: self.u32()?,
                camera: [self.f32()?, self.f32()?, self.f32()?],
            }),
            28 => {
                let object_threadgroups = [self.u32()?, self.u32()?, self.u32()?];
                let mesh_threadgroups = [self.u32()?, self.u32()?, self.u32()?];
                let payload_bytes = self.u32()?;
                let vertex_count = self.u32()?;
                let primitive_count = self.u32()?;
                let resource_address = self.u64()?;
                let resource_offset = self.u64()?;
                let resource_length = self.u64()?;
                let payload_offset = self.u64()?;
                let vertex_offset = self.u64()?;
                let vertex_bytes = self.u64()?;
                let primitive_offset = self.u64()?;
                let primitive_bytes = self.u64()?;
                let dispatch_geometry = [self.u32()?, self.u32()?, self.u32()?];
                let _reserved = self.u32()?;
                let request = MeshDispatchRequest {
                    object_threadgroups,
                    mesh_threadgroups,
                    payload_bytes,
                    vertex_count,
                    primitive_count,
                    resource_address,
                    resource_offset,
                    resource_length,
                    payload_offset,
                    vertex_offset,
                    vertex_bytes,
                    primitive_offset,
                    primitive_bytes,
                    dispatch_geometry,
                };
                valid_mesh_dispatch(&request).then_some(Command::MeshDispatch(request))?
            }
            _ => return None,
        })
    }
}

impl Iterator for Decoder<'_> {
    type Item = Command;

    fn next(&mut self) -> Option<Command> {
        if self.invalid {
            return None;
        }
        let start = self.position;
        let header = self.u32()?;
        let op = header & u16::MAX as u32;
        let size = header >> 16;
        let framed = op == MESH_DISPATCH_OPCODE && size != 0;
        if framed {
            let size = usize::try_from(size).ok();
            let valid = size.is_some_and(|size| {
                size == MESH_DISPATCH_WORDS
                    && size.is_multiple_of(2)
                    && start
                        .checked_add(size)
                        .is_some_and(|end| end <= self.words.len())
            });
            if !valid {
                self.invalid = true;
                return None;
            }
        } else if header != op {
            self.invalid = true;
            return None;
        }
        let result = if op == MESH_DISPATCH_OPCODE && !framed {
            Some(Command::BeginCompute {
                allow_concurrent: self.flag()?,
            })
        } else {
            self.command(op)
        };
        if result.is_none() {
            self.invalid = true;
            return None;
        }
        if framed {
            let size = usize::try_from(size).ok()?;
            if self.position != start.checked_add(size)? {
                self.invalid = true;
                return None;
            }
        }
        result
    }
}

#[cfg(test)]
mod tests {
    use super::{
        Command, ComputeAccess, Decoder, GenerateMipmapsRequest, MESH_DISPATCH_OPCODE,
        MESH_DISPATCH_WORDS, MeshDispatchRequest, valid_mesh_dispatch,
    };

    fn oit_resolve_words(k: u32) -> Vec<u32> {
        vec![40, k, 2]
    }

    #[test]
    fn decodes_oit_resolve_and_stays_in_sync() {
        let words = oit_resolve_words(4);
        let mut decoder = Decoder::new(&words);
        assert!(
            matches!(decoder.next(), Some(Command::TerrainOitResolve(request)) if request.k == 4)
        );
        assert!(matches!(decoder.next(), Some(Command::EndPass)));
    }

    #[test]
    fn rejects_truncated_oit_resolve() {
        let words = oit_resolve_words(8);
        for length in 0..2 {
            if let Some(prefix) = words.get(..length) {
                assert!(Decoder::new(prefix).next().is_none());
            }
        }
        assert!(Decoder::new(&[40, 3]).next().is_none());
        assert!(Decoder::new(&[40, 9]).next().is_none());
    }

    fn word_u64(words: &mut Vec<u32>, value: u64) {
        words.push(value as u32);
        words.push((value >> 32) as u32);
    }

    fn word_f32(words: &mut Vec<u32>, value: f32) {
        words.push(value.to_bits());
    }

    fn frame_interpolation_words() -> Vec<u32> {
        let mut words = vec![26, 3, 60];
        for handle in [1, 2, 3, 4, 5, 6] {
            word_u64(&mut words, handle);
        }
        words.extend([1280, 720, 2560, 1440]);
        for value in [0.016, 1.0, -1.0, 0.1, 1000.0, 70.0, 16.0] {
            word_f32(&mut words, value);
        }
        for value in 0..32 {
            word_f32(&mut words, value as f32);
        }
        word_f32(&mut words, 0.5);
        word_f32(&mut words, -0.5);
        words.extend([1, 1, 1]);
        words
    }

    fn spatial_words() -> Vec<u32> {
        let mut words = vec![26, 1];
        for handle in [
            0x1122_3344_5566_7788,
            0x2233_4455_6677_8899,
            0x3344_5566_7788_99aa,
        ] {
            word_u64(&mut words, handle);
        }
        words.extend([1280, 720, 2560, 1440]);
        word_f32(&mut words, 0.25);
        word_f32(&mut words, -0.5);
        for value in 0..16 {
            word_f32(&mut words, value as f32 + 0.5);
        }
        for value in 16..32 {
            word_f32(&mut words, value as f32 + 0.5);
        }
        words.push(1);
        words.push(2);
        words
    }

    fn mesh_request() -> MeshDispatchRequest {
        MeshDispatchRequest {
            object_threadgroups: [1, 1, 1],
            mesh_threadgroups: [1, 1, 1],
            payload_bytes: 1,
            vertex_count: 1,
            primitive_count: 1,
            resource_address: 0x1000,
            resource_offset: 0,
            resource_length: 64,
            payload_offset: 0,
            vertex_offset: 1,
            vertex_bytes: 1,
            primitive_offset: 2,
            primitive_bytes: 1,
            dispatch_geometry: [1, 1, 1],
        }
    }

    fn mesh_words(request: &MeshDispatchRequest) -> Vec<u32> {
        let mut words = vec![(MESH_DISPATCH_WORDS as u32) << 16 | MESH_DISPATCH_OPCODE];
        words.extend(request.object_threadgroups);
        words.extend(request.mesh_threadgroups);
        words.push(request.payload_bytes);
        words.push(request.vertex_count);
        words.push(request.primitive_count);
        for value in [
            request.resource_address,
            request.resource_offset,
            request.resource_length,
            request.payload_offset,
            request.vertex_offset,
            request.vertex_bytes,
            request.primitive_offset,
            request.primitive_bytes,
        ] {
            word_u64(&mut words, value);
        }
        words.extend(request.dispatch_geometry);
        words.push(0);
        words
    }

    #[test]
    fn decodes_spatial_scaler_and_stays_in_sync() {
        let words = spatial_words();
        let mut decoder = Decoder::new(&words);
        assert!(matches!(decoder.next(), Some(Command::MetalFx(request))
            if request.mode == 1
                && request.color == 0x1122_3344_5566_7788
                && request.depth == 0x2233_4455_6677_8899
                && request.output == 0x3344_5566_7788_99aa
                && request.input == [1280, 720]
                && request.output_size == [2560, 1440]
                && request.jitter == [0.25, -0.5]
                && request.current_inverse[0] == 0.5
                && request.current_inverse[15] == 15.5
                && request.previous_clip[0] == 16.5
                && request.previous_clip[15] == 31.5
                && request.reset));
        assert!(matches!(decoder.next(), Some(Command::EndPass)));
    }

    #[test]
    fn decodes_frame_interpolation_and_stays_in_sync() {
        let mut words = frame_interpolation_words();
        words.push(2);
        let mut decoder = Decoder::new(&words);
        let command = decoder.next();
        assert!(matches!(command, Some(Command::MetalFx(request))
            if request.mode == 3
                && request.current_color == 1
                && request.previous_color == 2
                && request.depth == 3
                && request.motion == 4
                && request.ui == 5
                && request.output == 6
                && request.input == [1280, 720]
                && request.output_size == [2560, 1440]
                && request.camera == [0.1, 1000.0, 70.0, 16.0]
                && request.world_to_view[15] == 15.0
                && request.view_to_clip[0] == 16.0
                && request.jitter == [0.5, -0.5]
                && request.reset
                && request.ui_composited
                && request.depth_reversed));
        assert!(matches!(decoder.next(), Some(Command::EndPass)));
    }

    #[test]
    fn rejects_truncated_frame_interpolation_without_consuming_next_command() {
        let words = frame_interpolation_words();
        for length in 0..words.len() {
            if let Some(prefix_words) = words.get(..length) {
                let prefix = prefix_words.to_vec();
                let mut decoder = Decoder::new(&prefix);
                assert!(decoder.next().is_none());
            }
        }
        let mut invalid_size = frame_interpolation_words();
        if let Some(size) = invalid_size.get_mut(2) {
            *size = 61;
        }
        invalid_size.push(2);
        let mut decoder = Decoder::new(&invalid_size);
        assert!(decoder.next().is_none());
    }

    #[test]
    fn decodes_pack_depth_conversion_and_stays_in_sync() {
        let mut words = vec![41];
        word_u64(&mut words, 0x1122_3344_5566_7788);
        word_u64(&mut words, 0x2233_4455_6677_8899);
        words.push(2);
        let mut decoder = Decoder::new(&words);
        assert!(
            matches!(decoder.next(), Some(Command::PackDepthConvert { source, destination })
            if source == 0x1122_3344_5566_7788 && destination == 0x2233_4455_6677_8899)
        );
        assert!(matches!(decoder.next(), Some(Command::EndPass)));
    }

    #[test]
    fn decodes_compute_commands_and_stays_in_sync() {
        let mut words = vec![28, 0, 30];
        word_u64(&mut words, 0x1122_3344_5566_7788);
        words.extend([31, 4]);
        word_u64(&mut words, 1);
        word_u64(&mut words, 2);
        words.push(0);
        words.extend([32, 5, 6]);
        word_u64(&mut words, 3);
        word_u64(&mut words, 4);
        words.push(1);
        words.extend([33, 7]);
        word_u64(&mut words, 5);
        words.push(2);
        words.extend([34, 8, 0, 34, 9, 9]);
        words.extend([
            0x0807_0605,
            0x0000_0009,
            0x0000_0000,
            0x0000_0000,
            35,
            1,
            2,
            3,
            36,
        ]);
        word_u64(&mut words, 10);
        word_u64(&mut words, 11);
        words.push(29);

        let mut decoder = Decoder::new(&words);
        assert!(matches!(
            decoder.next(),
            Some(Command::BeginCompute {
                allow_concurrent: false
            })
        ));
        assert!(matches!(
            decoder.next(),
            Some(Command::ComputePipeline(0x1122_3344_5566_7788))
        ));
        assert!(
            matches!(decoder.next(), Some(Command::ComputeBuffer(request)) if request.access == ComputeAccess::Read)
        );
        assert!(
            matches!(decoder.next(), Some(Command::ComputeTexture(request)) if request.access == ComputeAccess::Write)
        );
        assert!(
            matches!(decoder.next(), Some(Command::ComputeImage(request)) if request.access == ComputeAccess::ReadWrite)
        );
        assert!(
            matches!(decoder.next(), Some(Command::ComputeBytes(request)) if request.bytes.is_empty())
        );
        assert!(
            matches!(decoder.next(), Some(Command::ComputeBytes(request)) if request.bytes == vec![5, 6, 7, 8, 9, 0, 0, 0, 0])
        );
        assert!(matches!(
            decoder.next(),
            Some(Command::ComputeDispatch([1, 2, 3]))
        ));
        assert!(
            matches!(decoder.next(), Some(Command::ComputeDispatchIndirect(request)) if request.buffer == 10 && request.offset == 11)
        );
        assert!(matches!(decoder.next(), Some(Command::EndCompute)));
    }

    fn feature_cull_words() -> Vec<u32> {
        let mut words = vec![39];
        word_u64(&mut words, 0x1122_3344_5566_7788);
        word_u64(&mut words, 24);
        words.extend([3, 6]);
        word_u64(&mut words, 0x8877_6655_4433_2211);
        word_u64(&mut words, 48);
        word_u64(&mut words, 0xaabb_ccdd_eeff_0011);
        for value in 0..16 {
            word_f32(&mut words, value as f32 + 0.25);
        }
        for value in 16..32 {
            word_f32(&mut words, value as f32 - 0.5);
        }
        words
    }

    #[test]
    fn decodes_complete_feature_cull_request() {
        let command = Decoder::new(&feature_cull_words()).next();
        assert!(matches!(command, Some(Command::FeatureCull(request))
            if request.aabbs == 0x1122_3344_5566_7788
                && request.aabbs_offset == 24
                && request.count == 3
                && request.stride_words == 6
                && request.out == 0x8877_6655_4433_2211
                && request.out_offset == 48
                && request.depth == 0xaabb_ccdd_eeff_0011
                && request.clip[0] == 0.25
                && request.clip[15] == 15.25
                && request.previous_clip[0] == 15.5
                && request.previous_clip[15] == 30.5));
    }

    #[test]
    fn rejects_truncated_feature_cull_requests() {
        let words = feature_cull_words();
        for length in 0..words.len() {
            if let Some(prefix) = words.get(..length) {
                assert!(Decoder::new(prefix).next().is_none());
            }
        }
    }

    #[test]
    fn preserves_malformed_feature_cull_for_fail_open_encoding() {
        let mut invalid_stride = feature_cull_words();
        if let Some(stride) = invalid_stride.get_mut(6) {
            *stride = 7;
        }
        assert!(
            matches!(Decoder::new(&invalid_stride).next(), Some(Command::FeatureCull(request)) if request.stride_words == 7)
        );

        let mut invalid_range = feature_cull_words();
        if let Some(offset) = invalid_range.get_mut(3) {
            *offset = u32::MAX;
        }
        if let Some(offset) = invalid_range.get_mut(4) {
            *offset = u32::MAX;
        }
        if let Some(offset) = invalid_range.get_mut(9) {
            *offset = u32::MAX;
        }
        if let Some(offset) = invalid_range.get_mut(10) {
            *offset = u32::MAX;
        }
        assert!(
            matches!(Decoder::new(&invalid_range).next(), Some(Command::FeatureCull(request)) if request.aabbs_offset == usize::MAX && request.out_offset == usize::MAX)
        );

        let mut invalid_matrix = feature_cull_words();
        if let Some(value) = invalid_matrix.get_mut(23) {
            *value = f32::NAN.to_bits();
        }
        assert!(
            matches!(Decoder::new(&invalid_matrix).next(), Some(Command::FeatureCull(request)) if request.clip.iter().any(|value| value.is_nan()))
        );

        let mut invalid_previous_matrix = feature_cull_words();
        if let Some(value) = invalid_previous_matrix.get_mut(39) {
            *value = f32::INFINITY.to_bits();
        }
        assert!(
            matches!(Decoder::new(&invalid_previous_matrix).next(), Some(Command::FeatureCull(request)) if request.previous_clip.iter().any(|value| value.is_infinite()))
        );
    }

    #[test]
    fn rejects_truncated_compute_variable_payloads() {
        let commands = [
            vec![31, 0, 1],
            vec![32, 0, 1, 2],
            vec![33, 0, 1],
            vec![34, 0, 9, 1],
            vec![34, 0, u32::MAX],
        ];
        for words in commands {
            assert!(Decoder::new(&words).next().is_none());
        }
    }

    #[test]
    fn rejects_unknown_compute_access() {
        let words = [31, 0, 1, 0, 9];
        assert!(Decoder::new(&words).next().is_none());
    }

    #[test]
    fn decodes_direct_compute_dispatch_dimensions() {
        let words = [35, 7, 1, 1, 35, 7, 5, 1, 35, 7, 5, 3, 29];
        let mut decoder = Decoder::new(&words);
        assert!(matches!(
            decoder.next(),
            Some(Command::ComputeDispatch([7, 1, 1]))
        ));
        assert!(matches!(
            decoder.next(),
            Some(Command::ComputeDispatch([7, 5, 1]))
        ));
        assert!(matches!(
            decoder.next(),
            Some(Command::ComputeDispatch([7, 5, 3]))
        ));
        assert!(matches!(decoder.next(), Some(Command::EndCompute)));
    }

    fn lod_cull_words(version: u32, lengths: [(u64, u64); 3]) -> Vec<u32> {
        let mut words = vec![38, version];
        for (handle, range) in [
            (0x1122_3344_5566_7788, lengths[0]),
            (0x0102_0304_0506_0708, lengths[1]),
        ] {
            word_u64(&mut words, handle);
            word_u64(&mut words, range.0);
            word_u64(&mut words, range.1);
        }
        for value in [1.0f32, 2.0, 3.0] {
            word_f32(&mut words, value);
        }
        for value in 0..16 {
            word_f32(&mut words, value as f32);
        }
        word_u64(&mut words, 0x9999_aaaa_bbbb_cccc);
        word_u64(&mut words, lengths[2].0);
        word_u64(&mut words, lengths[2].1);
        words.push(1);
        words.push(4);
        word_f32(&mut words, 0.25);
        words
    }

    #[test]
    fn decodes_lod_cull() {
        let words = lod_cull_words(1, [(8, 64), (16, 120), (32, 20)]);
        let command = Decoder::new(&words).next();
        assert!(matches!(command, Some(Command::LodCull(_))));
        if let Some(Command::LodCull(request)) = command {
            assert_eq!(request.version, 1);
            assert_eq!(request.nodes_offset, 8);
            assert_eq!(request.nodes_length, 64);
            assert_eq!(request.meshes_offset, 16);
            assert_eq!(request.meshes_length, 120);
            assert_eq!(request.output_offset, 32);
            assert_eq!(request.output_length, 20);
            assert_eq!(request.camera, [1.0, 2.0, 3.0]);
            assert_eq!(request.level, 1);
            assert_eq!(request.max_level, 4);
            assert_eq!(request.threshold, 0.25);
        }
    }

    #[test]
    fn rejects_malformed_lod_cull() {
        let valid = lod_cull_words(1, [(8, 64), (16, 120), (32, 20)]);
        assert!(
            valid
                .get(..valid.len() - 1)
                .is_some_and(|prefix| Decoder::new(prefix).next().is_none())
        );
        assert!(
            Decoder::new(&lod_cull_words(2, [(8, 64), (16, 128), (32, 20)]))
                .next()
                .is_none()
        );
        assert!(
            Decoder::new(&lod_cull_words(1, [(0, 0), (16, 128), (32, 20)]))
                .next()
                .is_none()
        );
        assert!(
            Decoder::new(&lod_cull_words(
                1,
                [(usize::MAX as u64, 1), (16, 128), (32, 20)]
            ))
            .next()
            .is_none()
        );
    }

    #[test]
    fn accepts_mesh_limits_and_rejects_invalid_ranges() {
        let mut request = mesh_request();
        request.vertex_count = 256;
        request.primitive_count = 512;
        assert!(valid_mesh_dispatch(&request));
        request.resource_address = u64::MAX;
        request.resource_offset = 1;
        assert!(!valid_mesh_dispatch(&request));
        request = mesh_request();
        request.vertex_offset = 64;
        assert!(!valid_mesh_dispatch(&request));
        request = mesh_request();
        request.payload_offset = u64::MAX;
        assert!(!valid_mesh_dispatch(&request));
    }

    #[test]
    fn decodes_mesh_dispatch_and_rejects_malformed_tail() {
        let words = mesh_words(&mesh_request());
        assert!(matches!(
            Decoder::new(&words).next(),
            Some(Command::MeshDispatch(_))
        ));
        let mut malformed = vec![((MESH_DISPATCH_WORDS as u32 - 2) << 16) | MESH_DISPATCH_OPCODE];
        if let Some(tail) = words.get(1..) {
            malformed.extend(tail);
        }
        malformed.push(2);
        let mut decoder = Decoder::new(&malformed);
        assert!(decoder.next().is_none());
        assert!(decoder.next().is_none());
    }

    #[test]
    fn decodes_terrain_cull() {
        let clip: [f32; 16] = std::array::from_fn(|i| i as f32 * 0.5 - 2.0);
        let previous_clip: [f32; 16] = std::array::from_fn(|i| 32.0 - i as f32 * 0.25);
        let camera_offset = [1.25f32, -3.5, 0.75];
        let mut words = vec![21, 1, 3];
        word_u64(&mut words, 0x1122_3344_5566_7788);
        word_u64(&mut words, 0x99aa_bbcc_ddee_ff00);
        words.push(1234);
        words.push(11);
        words.push(22);
        words.push(33);
        words.push(4096);
        words.push(77);
        word_u64(&mut words, 0x0102_0304_0506_0708);
        word_u64(&mut words, 0x1111_2222_3333_4444);
        word_u64(&mut words, 0x5555_6666_7777_8888);
        word_u64(&mut words, 0x9999_aaaa_bbbb_cccc);
        for value in clip {
            word_f32(&mut words, value);
        }
        for value in previous_clip {
            word_f32(&mut words, value);
        }
        words.push((-7i32) as u32);
        words.push((-8i32) as u32);
        words.push((-9i32) as u32);
        for value in camera_offset {
            word_f32(&mut words, value);
        }
        words.push(1);
        word_u64(&mut words, 0x1234_5678_9abc_def0);

        let command = Decoder::new(&words).next();
        assert!(matches!(command, Some(Command::TerrainCull(_))));
        if let Some(Command::TerrainCull(request)) = command {
            assert_eq!(request.phase, 1);
            assert_eq!(request.mode, 3);
            assert_eq!(request.units, 0x1122_3344_5566_7788);
            assert_eq!(request.units_offset, 0x99aa_bbcc_ddee_ff00);
            assert_eq!(request.count, 1234);
            assert_eq!(request.first, [11, 22, 33]);
            assert_eq!(request.indices, 4096);
            assert_eq!(request.sections, 77);
            assert_eq!(request.out, 0x0102_0304_0506_0708);
            assert_eq!(request.out_offset, 0x1111_2222_3333_4444);
            assert_eq!(request.args_offset, 0x5555_6666_7777_8888);
            assert_eq!(request.depth, 0x9999_aaaa_bbbb_cccc);
            assert_eq!(request.clip, clip);
            assert_eq!(request.previous_clip, previous_clip);
            assert_eq!(request.camera_block, [-7, -8, -9]);
            assert_eq!(request.camera_offset, camera_offset);
            assert_eq!(request.from_args, 1);
            assert_eq!(request.count_args_offset, 0x1234_5678_9abc_def0);
        }
    }

    #[test]
    fn decodes_terrain_expand() {
        let camera_offset = [0.5f32, -1.25, 2.0];
        let mut words = vec![23];
        word_u64(&mut words, 0x1122_3344_5566_7788);
        word_u64(&mut words, 0x99aa_bbcc_ddee_ff00);
        words.push(1234);
        word_u64(&mut words, 0x0102_0304_0506_0708);
        word_u64(&mut words, 0x1111_2222_3333_4444);
        words.push(77);
        words.push(65536);
        words.push(3);
        words.push(7);
        words.push(1);
        words.push(4096);
        words.push(8192);
        words.push((-7i32) as u32);
        words.push((-8i32) as u32);
        words.push((-9i32) as u32);
        for value in camera_offset {
            word_f32(&mut words, value);
        }
        word_u64(&mut words, 0x5555_6666_7777_8888);
        word_u64(&mut words, 0x9999_aaaa_bbbb_cccc);
        word_u64(&mut words, 0xdddd_eeee_ffff_0000);

        let command = Decoder::new(&words).next();
        assert!(matches!(command, Some(Command::TerrainExpand(_))));
        if let Some(Command::TerrainExpand(request)) = command {
            assert_eq!(request.updates, 0x1122_3344_5566_7788);
            assert_eq!(request.updates_offset, 0x99aa_bbcc_ddee_ff00);
            assert_eq!(request.update_count, 1234);
            assert_eq!(request.visible, 0x0102_0304_0506_0708);
            assert_eq!(request.visible_offset, 0x1111_2222_3333_4444);
            assert_eq!(request.visible_count, 77);
            assert_eq!(request.sections, 65536);
            assert_eq!(request.cull_mask, 3);
            assert_eq!(request.layer_mask, 7);
            assert_eq!(request.options, 1);
            assert_eq!(request.indices, 4096);
            assert_eq!(request.capacity, 8192);
            assert_eq!(request.camera_block, [-7, -8, -9]);
            assert_eq!(request.camera_offset, camera_offset);
            assert_eq!(request.out, 0x5555_6666_7777_8888);
            assert_eq!(request.out_offset, 0x9999_aaaa_bbbb_cccc);
            assert_eq!(request.args_offset, 0xdddd_eeee_ffff_0000);
        }
    }

    #[test]
    fn decodes_terrain_expand_check() {
        let mut words = vec![24];
        word_u64(&mut words, 0x1122_3344_5566_7788);
        word_u64(&mut words, 0x99aa_bbcc_ddee_ff00);
        words.push(1234);
        words.push(11);
        words.push(22);
        words.push(33);
        word_u64(&mut words, 0x0102_0304_0506_0708);
        word_u64(&mut words, 0x1111_2222_3333_4444);
        word_u64(&mut words, 0x5555_6666_7777_8888);
        word_u64(&mut words, 0x9999_aaaa_bbbb_cccc);

        let command = Decoder::new(&words).next();
        assert!(matches!(command, Some(Command::TerrainExpandCheck { .. })));
        if let Some(Command::TerrainExpandCheck {
            cpu,
            cpu_offset,
            count,
            first,
            gpu,
            gpu_offset,
            gpu_bytes,
            args_offset,
        }) = command
        {
            assert_eq!(cpu, 0x1122_3344_5566_7788);
            assert_eq!(cpu_offset, 0x99aa_bbcc_ddee_ff00);
            assert_eq!(count, 1234);
            assert_eq!(first, [11, 22, 33]);
            assert_eq!(gpu, 0x0102_0304_0506_0708);
            assert_eq!(gpu_offset, 0x1111_2222_3333_4444);
            assert_eq!(gpu_bytes, 0x5555_6666_7777_8888);
            assert_eq!(args_offset, 0x9999_aaaa_bbbb_cccc);
        }
    }

    #[test]
    fn decodes_bind_texture_stages() {
        let mut words = vec![5];
        words.push(3);
        words.push(4);
        word_u64(&mut words, 0x1122_3344_5566_7788);
        word_u64(&mut words, 0x99aa_bbcc_ddee_ff00);
        words.push(3);

        let command = Decoder::new(&words).next();
        assert!(matches!(
            command,
            Some(Command::BindTexture {
                texture_index: 3,
                sampler_index: 4,
                view: 0x1122_3344_5566_7788,
                sampler: 0x99aa_bbcc_ddee_ff00,
                stages: 3,
            })
        ));
    }

    fn path_trace_words() -> Vec<u32> {
        let mut words = vec![25];
        word_u64(&mut words, 1);
        word_u64(&mut words, 2);
        word_u64(&mut words, 3);
        word_u64(&mut words, 4);
        words.push(1920);
        words.push(1080);
        word_f32(&mut words, 0.5);
        words.push(1);
        word_f32(&mut words, 1.0);
        word_f32(&mut words, 2.0);
        word_f32(&mut words, 3.0);
        for value in 0..16 {
            word_f32(&mut words, value as f32);
        }
        for value in 16..32 {
            word_f32(&mut words, value as f32);
        }
        words.push((-4i32) as u32);
        words.push((-3i32) as u32);
        words.push((-2i32) as u32);
        for value in [0.1f32, 0.2, 0.3] {
            word_f32(&mut words, value);
        }
        for value in [0.4f32, 0.5, 0.6] {
            word_f32(&mut words, value);
        }
        for value in [0.7f32, 0.8, 0.9] {
            word_f32(&mut words, value);
        }
        for value in [1.1f32, 1.2, 1.3] {
            word_f32(&mut words, value);
        }
        words
    }

    #[test]
    fn decodes_path_trace() {
        let words = path_trace_words();
        let command = Decoder::new(&words).next();
        assert!(matches!(command, Some(Command::PathTrace(_))));
        if let Some(Command::PathTrace(request)) = command {
            assert_eq!(request.color, 1);
            assert_eq!(request.depth, 2);
            assert_eq!(request.width, 1920);
            assert_eq!(request.height, 1080);
            assert_eq!(request.table_origin, [-4, -3, -2]);
            assert_eq!(request.sun_direction, [0.1, 0.2, 0.3]);
            assert_eq!(request.sun_color, [0.4, 0.5, 0.6]);
            assert_eq!(request.moon_direction, [0.7, 0.8, 0.9]);
            assert_eq!(request.moon_color, [1.1, 1.2, 1.3]);
        }
    }

    #[test]
    fn rejects_truncated_path_trace() {
        let words = path_trace_words();
        for length in 51..words.len() {
            assert!(
                words
                    .get(..length)
                    .is_some_and(|prefix| Decoder::new(prefix).next().is_none())
            );
        }
    }

    fn generate_mipmaps_words() -> Vec<u32> {
        vec![42, 0x5566_7788, 0x1122_3344, 3, 5]
    }

    #[test]
    fn decodes_generate_mipmaps_and_classifies_as_copy() {
        let words = generate_mipmaps_words();
        let command = Decoder::new(&words).next();
        assert!(matches!(
            command,
            Some(Command::GenerateMipmaps(GenerateMipmapsRequest {
                view: 0x1122_3344_5566_7788,
                base_mip: 3,
                mip_levels: 5,
            }))
        ));
        assert!(
            Decoder::new(&words)
                .next()
                .is_some_and(|command| command.is_copy())
        );
        let mut followed = words;
        followed.push(2);
        let mut decoder = Decoder::new(&followed);
        assert!(matches!(decoder.next(), Some(Command::GenerateMipmaps(_))));
        assert!(matches!(decoder.next(), Some(Command::EndPass)));
    }

    #[test]
    fn rejects_truncated_generate_mipmaps() {
        let words = generate_mipmaps_words();
        for length in 0..words.len() {
            assert!(
                words
                    .get(..length)
                    .is_some_and(|prefix| Decoder::new(prefix).next().is_none())
            );
        }
    }

    #[test]
    fn decodes_draw_mesh_and_stays_in_sync() {
        let words = [37, 128, 32, 64, 2];
        let mut decoder = Decoder::new(&words);
        assert!(matches!(
            decoder.next(),
            Some(Command::DrawMesh {
                threads: 128,
                object_threads: 32,
                mesh_threads: 64,
            })
        ));
        assert!(matches!(decoder.next(), Some(Command::EndPass)));
    }

    #[test]
    fn decodes_geometry_draw_and_stays_in_sync() {
        let mut words = vec![43];
        word_u64(&mut words, 10);
        word_u64(&mut words, 20);
        word_u64(&mut words, 30);
        word_u64(&mut words, 40);
        words.extend([4, 3, 12, 1, 2]);
        let mut decoder = Decoder::new(&words);
        assert!(matches!(
            decoder.next(),
            Some(Command::GeometryDrawIndexedIndirect(request))
                if request.arguments == 10
                    && request.arguments_offset == 20
                    && request.indices == 30
                    && request.index_offset == 40
                    && request.index_width == 4
                    && request.topology == 3
                    && request.index_count == 12
                    && request.stages == 1
        ));
        assert!(matches!(decoder.next(), Some(Command::EndPass)));
    }

    #[test]
    fn rejects_truncated_geometry_draw_without_losing_sync() {
        let mut words = vec![43];
        word_u64(&mut words, 10);
        assert!(Decoder::new(&words).next().is_none());
    }

    #[test]
    fn decodes_draw_indexed_indirect_and_stays_in_sync() {
        let mut words = vec![22];
        word_u64(&mut words, 0x0000_0001_0000_0002);
        word_u64(&mut words, 0x0000_0003_0000_0040);
        words.push(2);
        let mut decoder = Decoder::new(&words);
        assert!(matches!(
            decoder.next(),
            Some(Command::DrawIndexedIndirect {
                buffer: 0x0000_0001_0000_0002,
                offset: 0x0000_0003_0000_0040,
            })
        ));
        assert!(matches!(decoder.next(), Some(Command::EndPass)));
    }
}
