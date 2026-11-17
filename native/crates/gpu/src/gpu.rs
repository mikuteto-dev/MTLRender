use objc2::rc::Retained;
use objc2::runtime::ProtocolObject;
use objc2_metal::{
    MTL4ArgumentTable, MTL4ArgumentTableDescriptor, MTL4CommandBuffer, MTL4CommandQueue,
    MTL4Compiler, MTL4MeshRenderPipelineDescriptor, MTL4RenderPipelineDescriptor,
    MTLCompareFunction, MTLComputePipelineState, MTLCreateSystemDefaultDevice,
    MTLDepthStencilState, MTLDevice, MTLGPUFamily, MTLLibrary, MTLPixelFormat,
    MTLRenderPipelineState, MTLResource, MTLResourceOptions,
};

use crate::pipeline;
use crate::pipeline::{Archive, ArchiveConfig};
use mtl4::{self as metal, Buffer, Capture};

pub type RenderState = Retained<ProtocolObject<dyn MTLRenderPipelineState>>;

pub const FAN_VERTICES: u32 = 1 << 16;

const APPLE_FAMILIES: [(MTLGPUFamily, u32); 4] = [
    (MTLGPUFamily::Apple7, 7),
    (MTLGPUFamily::Apple8, 8),
    (MTLGPUFamily::Apple9, 9),
    (MTLGPUFamily::Apple10, 10),
];

#[derive(Default)]
pub struct Caps {
    pub apple_family: u32,
    pub raytracing: bool,
    pub raytracing_from_render: bool,
    pub recommended_max_working_set: u64,
    pub max_buffer_length: u64,
    pub metalfx_spatial: bool,
    pub metalfx_temporal: bool,
    pub metalfx_frame_interpolation: bool,
}

pub fn caps(device: &ProtocolObject<dyn MTLDevice>) -> Caps {
    Caps {
        apple_family: APPLE_FAMILIES
            .iter()
            .rev()
            .find(|(family, _)| device.supportsFamily(*family))
            .map_or(0, |(_, number)| *number),
        raytracing: device.supportsRaytracing(),
        raytracing_from_render: device.supportsRaytracingFromRender(),
        recommended_max_working_set: device.recommendedMaxWorkingSetSize(),
        max_buffer_length: u64::try_from(device.maxBufferLength()).unwrap_or(u64::MAX),
        metalfx_spatial: mtl4::Fx::supports(device, mtl4::FxMode::Spatial),
        metalfx_temporal: mtl4::Fx::supports(device, mtl4::FxMode::Temporal),
        metalfx_frame_interpolation: mtl4::Fx::supports_frame_interpolation(device),
    }
}

pub struct Kernels {
    pub calibration_synthetic_depth: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub calibration_hiz_reduce: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub calibration_single_phase_reproject: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub snapshot_d32_to_r32: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub pack_depth_convert: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub hiz_first4: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub hiz_reduce4: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub mark: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub feature_mark: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub scan_groups: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub scan_offsets: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub scatter: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub expand_count: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub expand_offsets: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub expand_write: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub expand_check: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub records_scatter: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub geometry_scan_groups: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub geometry_scan_offsets: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub geometry_compact: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub geometry_indirect: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub lod_seed: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub lod_expand: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub lod_emit: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub lod_reset: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub path_trace: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub path_atrous: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub fx_motion: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub sort_copy: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub sort_init: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub sort_stage: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub sort_write: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub sort_check: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub tessellate_triangle: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub tessellate_quad: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    pub tessellate_isoline: Retained<ProtocolObject<dyn MTLComputePipelineState>>,
}

pub struct DepthStates(Vec<Retained<ProtocolObject<dyn MTLDepthStencilState>>>);

impl DepthStates {
    fn new(device: &ProtocolObject<dyn MTLDevice>) -> Option<Self> {
        (0..16u32)
            .map(|i| pipeline::depth_state(device, MTLCompareFunction((i / 2) as _), i % 2 == 1))
            .collect::<Option<Vec<_>>>()
            .map(Self)
    }

    pub fn get(
        &self,
        compare: MTLCompareFunction,
        write: bool,
    ) -> Option<&ProtocolObject<dyn MTLDepthStencilState>> {
        let index = compare.0.min(7) * 2 + usize::from(write);
        self.0.get(index).map(|state| &**state)
    }
}

pub struct Gpu {
    pub device: Retained<ProtocolObject<dyn MTLDevice>>,
    pub queue: Retained<ProtocolObject<dyn MTL4CommandQueue>>,
    pub compiler: Retained<ProtocolObject<dyn MTL4Compiler>>,
    pub cmd: Retained<ProtocolObject<dyn MTL4CommandBuffer>>,
    pub table: Retained<ProtocolObject<dyn MTL4ArgumentTable>>,
    pub depth_states: DepthStates,
    pub blit: RenderState,
    pub fan_indices: Buffer,
    pub kernels: Kernels,
    pub oit_tile: Option<RenderState>,
    builtin: Retained<ProtocolObject<dyn MTLLibrary>>,
}

impl Gpu {
    pub fn new() -> Option<Self> {
        Self::new_with_archive(None).map(|(gpu, _)| gpu)
    }

    pub fn new_with_archive(config: Option<ArchiveConfig>) -> Option<(Self, Option<Archive>)> {
        let device = MTLCreateSystemDefaultDevice()?;
        if !device.supportsFamily(MTLGPUFamily::Metal4)
            || !device.supportsFamily(MTLGPUFamily::Apple7)
        {
            return None;
        }
        let queue = device.newMTL4CommandQueue()?;
        let archive = config
            .map(|config| Archive::open(&device, config))
            .transpose()
            .ok()?;
        let compiler = device
            .newCompilerWithDescriptor_error(&pipeline::compiler_descriptor(
                archive.as_ref().map(Archive::serializer),
            ))
            .ok()?;
        let table_descriptor = MTL4ArgumentTableDescriptor::new();
        table_descriptor.setMaxBufferBindCount(metal::MAX_BUFFER_BINDINGS);
        table_descriptor.setMaxTextureBindCount(metal::MAX_TEXTURE_BINDINGS);
        table_descriptor.setMaxSamplerStateBindCount(metal::MAX_TEXTURE_BINDINGS);
        let table = device
            .newArgumentTableWithDescriptor_error(&table_descriptor)
            .ok()?;
        let fan_indices = device.newBufferWithLength_options(
            (FAN_VERTICES as usize - 2) * 12,
            MTLResourceOptions::StorageModeShared,
        )?;
        let fan: Vec<u8> = (0..FAN_VERTICES - 2)
            .flat_map(|t| [0, t + 1, t + 2])
            .flat_map(u32::to_ne_bytes)
            .collect();
        metal::write_shared(&fan_indices, 0, &fan).then_some(())?;
        let builtin = pipeline::library(
            &compiler,
            concat!(
                include_str!("builtin.metal"),
                include_str!("lod_kernels.metal"),
                include_str!("pathtrace.metal"),
                include_str!("pathdenoise.metal"),
                include_str!("tessellate.metal")
            ),
        )
        .ok()?;
        let blit = builtin_pipeline(
            &compiler,
            &builtin,
            "blit_flip_fs",
            MTLPixelFormat::BGRA8Unorm,
        )?;
        let oit_tile = pipeline::oit_tile_pipeline(&compiler, &builtin, MTLPixelFormat::BGRA8Unorm);
        let kernels = Kernels {
            calibration_synthetic_depth: pipeline::compute(
                &compiler,
                &builtin,
                "calibration_synthetic_depth",
            )?,
            calibration_hiz_reduce: pipeline::compute(
                &compiler,
                &builtin,
                "calibration_hiz_reduce",
            )?,
            calibration_single_phase_reproject: pipeline::compute(
                &compiler,
                &builtin,
                "calibration_single_phase_reproject",
            )?,
            snapshot_d32_to_r32: pipeline::compute(
                &compiler,
                &builtin,
                "cull_snapshot_d32_to_r32",
            )?,
            pack_depth_convert: pipeline::compute(&compiler, &builtin, "pack_depth_convert")?,
            hiz_first4: pipeline::compute(&compiler, &builtin, "cull_hiz_first4")?,
            hiz_reduce4: pipeline::compute(&compiler, &builtin, "cull_hiz_reduce4")?,
            mark: pipeline::compute(&compiler, &builtin, "cull_mark")?,
            feature_mark: pipeline::compute(&compiler, &builtin, "feature_mark")?,
            scan_groups: pipeline::compute(&compiler, &builtin, "cull_scan_groups")?,
            scan_offsets: pipeline::compute(&compiler, &builtin, "cull_scan_offsets")?,
            scatter: pipeline::compute(&compiler, &builtin, "cull_scatter")?,
            expand_count: pipeline::compute(&compiler, &builtin, "expand_count")?,
            expand_offsets: pipeline::compute(&compiler, &builtin, "expand_offsets")?,
            expand_write: pipeline::compute(&compiler, &builtin, "expand_write")?,
            expand_check: pipeline::compute(&compiler, &builtin, "expand_check")?,
            records_scatter: pipeline::compute(&compiler, &builtin, "records_scatter")?,
            geometry_scan_groups: pipeline::compute(&compiler, &builtin, "geometry_scan_groups")?,
            geometry_scan_offsets: pipeline::compute(&compiler, &builtin, "geometry_scan_offsets")?,
            geometry_compact: pipeline::compute(&compiler, &builtin, "geometry_compact")?,
            geometry_indirect: pipeline::compute(&compiler, &builtin, "geometry_indirect")?,
            lod_seed: pipeline::compute(&compiler, &builtin, "lod_seed")?,
            lod_expand: pipeline::compute(&compiler, &builtin, "lod_expand")?,
            lod_emit: pipeline::compute(&compiler, &builtin, "lod_emit")?,
            lod_reset: pipeline::compute(&compiler, &builtin, "lod_reset")?,
            path_trace: pipeline::compute(&compiler, &builtin, "path_trace")?,
            path_atrous: pipeline::compute(&compiler, &builtin, "path_atrous")?,
            fx_motion: pipeline::compute(&compiler, &builtin, "fx_motion")?,
            sort_copy: pipeline::compute(&compiler, &builtin, "sort_copy")?,
            sort_init: pipeline::compute(&compiler, &builtin, "sort_init")?,
            sort_stage: pipeline::compute(&compiler, &builtin, "sort_stage")?,
            sort_write: pipeline::compute(&compiler, &builtin, "sort_write")?,
            sort_check: pipeline::compute(&compiler, &builtin, "sort_check")?,
            tessellate_triangle: pipeline::compute(&compiler, &builtin, "tessellate_triangle")?,
            tessellate_quad: pipeline::compute(&compiler, &builtin, "tessellate_quad")?,
            tessellate_isoline: pipeline::compute(&compiler, &builtin, "tessellate_isoline")?,
        };
        let depth_states = DepthStates::new(&device)?;
        let cmd = device.newCommandBuffer()?;
        Some((
            Self {
                device,
                queue,
                compiler,
                cmd,
                table,
                depth_states,
                blit,
                fan_indices,
                kernels,
                oit_tile,
                builtin,
            },
            archive,
        ))
    }

    pub fn builtin_library(&self) -> &ProtocolObject<dyn MTLLibrary> {
        &self.builtin
    }

    pub fn raster_order_groups_supported(&self) -> bool {
        self.device.areRasterOrderGroupsSupported()
    }

    pub fn memory_bytes(&self) -> u64 {
        u64::try_from(self.fan_indices.allocatedSize()).unwrap_or(0)
    }

    pub fn capture(&mut self, path: &str) -> Option<Capture> {
        let capture = Capture::start(&self.device, path)?;
        let Some(command) = self.device.newCommandBuffer() else {
            capture.stop();
            return None;
        };
        self.cmd = command;
        Some(capture)
    }

    pub fn clear_pipeline(&self, color: MTLPixelFormat) -> Option<RenderState> {
        builtin_pipeline(&self.compiler, &self.builtin, "clear_fs", color)
    }

    pub fn calibration_pipeline(&self, color: MTLPixelFormat, _depth: bool) -> Option<RenderState> {
        let descriptor = MTL4RenderPipelineDescriptor::new();
        descriptor
            .setVertexFunctionDescriptor(Some(&pipeline::function(&self.builtin, "fullscreen_vs")));
        descriptor.setFragmentFunctionDescriptor(Some(&pipeline::function(
            &self.builtin,
            "calibration_fs",
        )));
        metal::pipeline_color(&descriptor, 0)?.setPixelFormat(color);
        self.compiler
            .newRenderPipelineStateWithDescriptor_compilerTaskOptions_error(&descriptor, None)
            .ok()
    }

    pub fn calibration_mesh_pipeline(&self, color: MTLPixelFormat) -> Option<RenderState> {
        let descriptor = MTL4MeshRenderPipelineDescriptor::new();
        descriptor.setObjectFunctionDescriptor(Some(&pipeline::function(
            &self.builtin,
            "calibration_object",
        )));
        descriptor.setMeshFunctionDescriptor(Some(&pipeline::function(
            &self.builtin,
            "calibration_mesh",
        )));
        descriptor.setFragmentFunctionDescriptor(Some(&pipeline::function(
            &self.builtin,
            "calibration_fs",
        )));
        metal::mesh_pipeline_color(&descriptor, 0)?.setPixelFormat(color);
        self.compiler
            .newRenderPipelineStateWithDescriptor_compilerTaskOptions_error(&descriptor, None)
            .ok()
    }
}

fn builtin_pipeline(
    compiler: &ProtocolObject<dyn MTL4Compiler>,
    library: &ProtocolObject<dyn MTLLibrary>,
    fragment: &str,
    color: MTLPixelFormat,
) -> Option<RenderState> {
    let descriptor = MTL4RenderPipelineDescriptor::new();
    descriptor.setVertexFunctionDescriptor(Some(&pipeline::function(library, "fullscreen_vs")));
    descriptor.setFragmentFunctionDescriptor(Some(&pipeline::function(library, fragment)));
    metal::pipeline_color(&descriptor, 0)?.setPixelFormat(color);
    compiler
        .newRenderPipelineStateWithDescriptor_compilerTaskOptions_error(&descriptor, None)
        .ok()
}
