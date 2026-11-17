use objc2::Message;
use objc2::rc::Retained;
use objc2::runtime::ProtocolObject;
use objc2_foundation::{NSArray, NSString, NSURL};
use objc2_metal::{
    MTL4Archive, MTL4BlendState, MTL4Compiler, MTL4CompilerDescriptor, MTL4CompilerTaskOptions,
    MTL4ComputePipelineDescriptor, MTL4LibraryDescriptor, MTL4LibraryFunctionDescriptor,
    MTL4MeshRenderPipelineDescriptor, MTL4PipelineDataSetSerializer,
    MTL4PipelineDataSetSerializerConfiguration, MTL4PipelineDataSetSerializerDescriptor,
    MTL4RenderPipelineDescriptor, MTL4StaticLinkingDescriptor, MTL4TileRenderPipelineDescriptor,
    MTLBlendFactor, MTLBlendOperation, MTLColorWriteMask, MTLCompareFunction, MTLCompileOptions,
    MTLComputePipelineState, MTLCullMode, MTLDepthStencilDescriptor, MTLDepthStencilState,
    MTLDevice, MTLLibrary, MTLPixelFormat, MTLPrimitiveTopologyClass, MTLPrimitiveType,
    MTLRenderPipelineState, MTLTriangleFillMode, MTLVertexDescriptor, MTLVertexFormat,
    MTLVertexStepFunction,
};

use crate::gpu::RenderState;
use mtl4 as metal;
use std::fs;
use std::path::{Path, PathBuf};

const ARCHIVE_MAGIC: &[u8; 8] = b"MTLRPA01";
const ARCHIVE_VERSION: u32 = 1;

pub struct ArchiveConfig {
    pub path: PathBuf,
    pub key: Vec<u8>,
}

pub struct Archive {
    path: PathBuf,
    key: Vec<u8>,
    pub metal: Option<Retained<ProtocolObject<dyn MTL4Archive>>>,
    serializer: Retained<ProtocolObject<dyn MTL4PipelineDataSetSerializer>>,
    loaded: bool,
    dirty: bool,
}

fn metadata_path(path: &Path) -> PathBuf {
    PathBuf::from(format!("{}.meta", path.display()))
}
fn temporary_path(path: &Path) -> PathBuf {
    PathBuf::from(format!("{}.tmp", path.display()))
}

fn metadata(key: &[u8]) -> Option<Vec<u8>> {
    let length = u32::try_from(key.len()).ok()?;
    let mut value = Vec::with_capacity(16 + key.len());
    value.extend_from_slice(ARCHIVE_MAGIC);
    value.extend_from_slice(&ARCHIVE_VERSION.to_le_bytes());
    value.extend_from_slice(&length.to_le_bytes());
    value.extend_from_slice(key);
    Some(value)
}

fn compatible(path: &Path, key: &[u8]) -> bool {
    let Some(expected) = metadata(key) else {
        return false;
    };
    let (_, sidecar) = archive_paths(path);
    fs::read(sidecar).is_ok_and(|value| value == expected)
        && fs::metadata(path).is_ok_and(|value| value.is_file() && value.len() > 0)
}

pub fn archive_paths(path: &Path) -> (PathBuf, PathBuf) {
    (path.to_owned(), metadata_path(path))
}

pub fn compiler_descriptor(
    serializer: Option<&ProtocolObject<dyn MTL4PipelineDataSetSerializer>>,
) -> Retained<MTL4CompilerDescriptor> {
    let descriptor = MTL4CompilerDescriptor::new();
    if let Some(serializer) = serializer {
        descriptor.setPipelineDataSetSerializer(Some(serializer));
    }
    descriptor
}

impl Archive {
    pub fn open(
        device: &ProtocolObject<dyn MTLDevice>,
        config: ArchiveConfig,
    ) -> Result<Self, String> {
        if config.path.as_os_str().is_empty() || config.key.is_empty() {
            return Err("pipeline archive configuration is empty".to_string());
        }
        let parent = config
            .path
            .parent()
            .ok_or("pipeline archive has no parent")?;
        if !parent.as_os_str().is_empty() {
            fs::create_dir_all(parent).map_err(|error| error.to_string())?;
        }
        let metal = if compatible(&config.path, &config.key) {
            let url = NSURL::fileURLWithPath(&NSString::from_str(&config.path.to_string_lossy()));
            device.newArchiveWithURL_error(&url).ok()
        } else {
            None
        };
        let descriptor = MTL4PipelineDataSetSerializerDescriptor::new();
        descriptor.setConfiguration(
            MTL4PipelineDataSetSerializerConfiguration::CaptureDescriptors
                | MTL4PipelineDataSetSerializerConfiguration::CaptureBinaries,
        );
        let serializer = device.newPipelineDataSetSerializerWithDescriptor(&descriptor);
        Ok(Self {
            path: config.path,
            key: config.key,
            loaded: metal.is_some(),
            metal,
            serializer,
            dirty: false,
        })
    }

    pub fn serializer(&self) -> &ProtocolObject<dyn MTL4PipelineDataSetSerializer> {
        &self.serializer
    }
    pub fn loaded(&self) -> bool {
        self.loaded
    }
    pub fn mark_dirty(&mut self) {
        self.dirty = true;
    }
    pub fn flush(&mut self) -> Result<(), String> {
        if !self.dirty {
            return Ok(());
        }
        let temp = temporary_path(&self.path);
        let meta_temp = temporary_path(&metadata_path(&self.path));
        let url = NSURL::fileURLWithPath(&NSString::from_str(&temp.to_string_lossy()));
        self.serializer
            .serializeAsArchiveAndFlushToURL_error(&url)
            .map_err(|error| error.localizedDescription().to_string())?;
        fs::write(
            &meta_temp,
            metadata(&self.key).ok_or("pipeline archive key is too large")?,
        )
        .map_err(|error| error.to_string())?;
        fs::rename(&temp, &self.path).map_err(|error| error.to_string())?;
        fs::rename(meta_temp, metadata_path(&self.path)).map_err(|error| error.to_string())?;
        self.dirty = false;
        Ok(())
    }
}

impl Drop for Archive {
    fn drop(&mut self) {
        let _ = self.flush();
    }
}

fn task_options(
    archive: Option<&ProtocolObject<dyn MTL4Archive>>,
) -> Option<Retained<MTL4CompilerTaskOptions>> {
    let archive = archive?;
    let options = MTL4CompilerTaskOptions::new();
    let archives = NSArray::from_retained_slice(&[archive.retain()]);
    options.setLookupArchives(Some(&archives));
    Some(options)
}

pub const VERTEX_BUFFER_BASE: usize = 16;
const INVARIANT_POSITION: &str = "[[position, invariant]]";

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum PipelineKind {
    Raster,
    Mesh,
}

pub struct OitMetadata {
    pub k: u32,
    pub imageblock_sample_length: usize,
}

pub const OIT_IMAGEBLOCK_SAMPLE_LENGTH: usize = 160;

pub struct Pipeline {
    pub state: Retained<ProtocolObject<dyn MTLRenderPipelineState>>,
    pub kind: PipelineKind,
    pub oit: Option<OitMetadata>,
    pub depth: Option<(MTLCompareFunction, bool)>,
    pub primitive: MTLPrimitiveType,
    pub fan: bool,
    pub cull: MTLCullMode,
    pub fill: MTLTriangleFillMode,
    pub depth_bias: (f32, f32),
}

pub type ComputePipeline = (
    Retained<ProtocolObject<dyn MTLComputePipelineState>>,
    [usize; 3],
);

struct Words<'a> {
    words: &'a [u32],
    at: usize,
}

impl Words<'_> {
    fn next(&mut self) -> Result<u32, String> {
        let value = self
            .words
            .get(self.at)
            .copied()
            .ok_or("pipeline descriptor is truncated")?;
        self.at += 1;
        Ok(value)
    }
}

pub fn library(
    compiler: &ProtocolObject<dyn MTL4Compiler>,
    source: &str,
) -> Result<Retained<ProtocolObject<dyn MTLLibrary>>, String> {
    let descriptor = MTL4LibraryDescriptor::new();
    descriptor.setSource(Some(&NSString::from_str(source)));
    if source.contains(INVARIANT_POSITION) {
        let options = MTLCompileOptions::new();
        options.setPreserveInvariance(true);
        descriptor.setOptions(Some(&options));
    }
    compiler
        .newLibraryWithDescriptor_error(&descriptor)
        .map_err(|e| e.localizedDescription().to_string())
}

pub fn function(
    library: &ProtocolObject<dyn MTLLibrary>,
    name: &str,
) -> Retained<MTL4LibraryFunctionDescriptor> {
    let function = MTL4LibraryFunctionDescriptor::new();
    function.setName(Some(&NSString::from_str(name)));
    function.setLibrary(Some(library));
    function
}

pub fn compute(
    compiler: &ProtocolObject<dyn MTL4Compiler>,
    library: &ProtocolObject<dyn MTLLibrary>,
    name: &str,
) -> Option<Retained<ProtocolObject<dyn MTLComputePipelineState>>> {
    let descriptor = MTL4ComputePipelineDescriptor::new();
    descriptor.setComputeFunctionDescriptor(Some(&function(library, name)));
    compiler
        .newComputePipelineStateWithDescriptor_compilerTaskOptions_error(&descriptor, None)
        .ok()
}

pub fn oit_tile_pipeline(
    compiler: &ProtocolObject<dyn MTL4Compiler>,
    builtin: &ProtocolObject<dyn MTLLibrary>,
    color: MTLPixelFormat,
) -> Option<RenderState> {
    let descriptor = MTL4TileRenderPipelineDescriptor::new();
    descriptor.setTileFunctionDescriptor(Some(&function(builtin, "oit_kbuffer_resolve_fs")));
    metal::tile_pipeline_color(&descriptor, 0)?.setPixelFormat(color);
    compiler
        .newRenderPipelineStateWithDescriptor_compilerTaskOptions_error(&descriptor, None)
        .ok()
}

fn oit_linking(builtin: &ProtocolObject<dyn MTLLibrary>) -> Retained<MTL4StaticLinkingDescriptor> {
    let descriptors = [
        function(builtin, "oit_insert").into_super(),
        function(builtin, "oit_resolve").into_super(),
    ];
    let functions = NSArray::from_retained_slice(&descriptors);
    let linking = MTL4StaticLinkingDescriptor::new();
    linking.setPrivateFunctionDescriptors(Some(&functions));
    linking
}

fn threadgroup_size(threads_per_threadgroup: [u32; 3]) -> Result<[usize; 3], String> {
    if threads_per_threadgroup.contains(&0) {
        return Err("compute threadgroup dimensions must be positive".to_string());
    }
    Ok(threads_per_threadgroup.map(|value| value as usize))
}

pub fn create_compute(
    compiler: &ProtocolObject<dyn MTL4Compiler>,
    source: &str,
    name: &str,
    threads_per_threadgroup: [u32; 3],
) -> Result<ComputePipeline, String> {
    let threads_per_threadgroup = threadgroup_size(threads_per_threadgroup)?;
    let library = library(compiler, source)?;
    let state = compute(compiler, &library, name).ok_or("failed to create compute pipeline")?;
    Ok((state, threads_per_threadgroup))
}

pub fn depth_state(
    device: &ProtocolObject<dyn MTLDevice>,
    compare: MTLCompareFunction,
    write: bool,
) -> Option<Retained<ProtocolObject<dyn MTLDepthStencilState>>> {
    let descriptor = MTLDepthStencilDescriptor::new();
    descriptor.setDepthCompareFunction(compare);
    descriptor.setDepthWriteEnabled(write);
    device.newDepthStencilStateWithDescriptor(&descriptor)
}

pub fn create_mesh(
    compiler: &ProtocolObject<dyn MTL4Compiler>,
    builtin: &ProtocolObject<dyn MTLLibrary>,
    raster_order_groups_supported: bool,
    object: (&str, &str),
    mesh: (&str, &str),
    fragment: (&str, &str),
    words: &[u32],
) -> Result<Pipeline, String> {
    let mut w = Words { words, at: 0 };
    let _primitive_code = w.next()?;
    let cull = match w.next()? {
        1 => MTLCullMode::Back,
        2 => MTLCullMode::Front,
        _ => MTLCullMode::None,
    };
    let wireframe = w.next()? != 0;
    let depth_enabled = w.next()? != 0;
    let depth_compare = MTLCompareFunction(w.next()? as _);
    let depth_write = w.next()? != 0;
    let _depth_format = MTLPixelFormat(w.next()? as _);
    let bias_constant = f32::from_bits(w.next()?);
    let bias_slope = f32::from_bits(w.next()?);

    let object_library = library(compiler, object.0)?;
    let mesh_library = library(compiler, mesh.0)?;
    let fragment_library = library(compiler, fragment.0)?;
    let descriptor = MTL4MeshRenderPipelineDescriptor::new();
    descriptor.setObjectFunctionDescriptor(Some(&function(&object_library, object.1)));
    descriptor.setMeshFunctionDescriptor(Some(&function(&mesh_library, mesh.1)));
    descriptor.setFragmentFunctionDescriptor(Some(&function(&fragment_library, fragment.1)));

    for index in 0..w.next()? as usize {
        let attachment =
            metal::mesh_pipeline_color(&descriptor, index).ok_or("too many color attachments")?;
        attachment.setPixelFormat(MTLPixelFormat(w.next()? as _));
        let blend = w.next()? != 0;
        attachment.setBlendingState(if blend {
            MTL4BlendState::Enabled
        } else {
            MTL4BlendState::Disabled
        });
        attachment.setSourceRGBBlendFactor(MTLBlendFactor(w.next()? as _));
        attachment.setDestinationRGBBlendFactor(MTLBlendFactor(w.next()? as _));
        attachment.setRgbBlendOperation(MTLBlendOperation(w.next()? as _));
        attachment.setSourceAlphaBlendFactor(MTLBlendFactor(w.next()? as _));
        attachment.setDestinationAlphaBlendFactor(MTLBlendFactor(w.next()? as _));
        attachment.setAlphaBlendOperation(MTLBlendOperation(w.next()? as _));
        attachment.setWriteMask(MTLColorWriteMask(w.next()? as _));
    }

    for _ in 0..w.next()? as usize {
        let _slot = w.next()?;
        let _stride = w.next()?;
        let _step_rate = w.next()?;
    }
    for _ in 0..w.next()? as usize {
        let _location = w.next()?;
        let _format = w.next()?;
        let _offset = w.next()?;
        let _slot = w.next()?;
    }

    let oit = w
        .words
        .get(w.at)
        .copied()
        .filter(|marker| *marker == 1)
        .map(|_| OitMetadata {
            k: 8,
            imageblock_sample_length: 0,
        });
    let oit = oit.filter(|_| raster_order_groups_supported);
    if oit.is_some() {
        let linking = oit_linking(builtin);
        descriptor.setFragmentStaticLinkingDescriptor(Some(&linking));
    }
    let (state, oit) = match compiler
        .newRenderPipelineStateWithDescriptor_compilerTaskOptions_error(&descriptor, None)
    {
        Ok(state) => (state, oit),
        Err(_error) if oit.is_some() => {
            descriptor.setFragmentStaticLinkingDescriptor(None);
            let state = compiler
                .newRenderPipelineStateWithDescriptor_compilerTaskOptions_error(&descriptor, None)
                .map_err(|fallback| fallback.localizedDescription().to_string())?;
            (state, None)
        }
        Err(error) => return Err(error.localizedDescription().to_string()),
    };
    let sample_length = state.imageblockSampleLength();
    Ok(Pipeline {
        state,
        kind: PipelineKind::Mesh,
        oit: oit.map(|metadata| OitMetadata {
            imageblock_sample_length: sample_length,
            ..metadata
        }),
        depth: depth_enabled.then_some((depth_compare, depth_write)),
        primitive: MTLPrimitiveType::Triangle,
        fan: false,
        cull,
        fill: if wireframe {
            MTLTriangleFillMode::Lines
        } else {
            MTLTriangleFillMode::Fill
        },
        depth_bias: (bias_constant, bias_slope),
    })
}

pub fn create(
    compiler: &ProtocolObject<dyn MTL4Compiler>,
    archive: Option<&ProtocolObject<dyn MTL4Archive>>,
    builtin: &ProtocolObject<dyn MTLLibrary>,
    raster_order_groups_supported: bool,
    vertex: (&str, &str),
    fragment: (&str, &str),
    words: &[u32],
) -> Result<Pipeline, String> {
    let mut w = Words { words, at: 0 };
    let primitive_code = w.next()?;
    let cull = match w.next()? {
        1 => MTLCullMode::Back,
        2 => MTLCullMode::Front,
        _ => MTLCullMode::None,
    };
    let wireframe = w.next()? != 0;
    let depth_enabled = w.next()? != 0;
    let depth_compare = MTLCompareFunction(w.next()? as _);
    let depth_write = w.next()? != 0;
    let _depth_format = MTLPixelFormat(w.next()? as _);
    let bias_constant = f32::from_bits(w.next()?);
    let bias_slope = f32::from_bits(w.next()?);

    let vertex_library = library(compiler, vertex.0)?;
    let descriptor = MTL4RenderPipelineDescriptor::new();
    descriptor.setVertexFunctionDescriptor(Some(&function(&vertex_library, vertex.1)));
    if !fragment.0.is_empty() {
        let fragment_library = library(compiler, fragment.0)?;
        descriptor.setFragmentFunctionDescriptor(Some(&function(&fragment_library, fragment.1)));
    }

    for index in 0..w.next()? as usize {
        let attachment =
            metal::pipeline_color(&descriptor, index).ok_or("too many color attachments")?;
        attachment.setPixelFormat(MTLPixelFormat(w.next()? as _));
        let blend = w.next()? != 0;
        attachment.setBlendingState(if blend {
            MTL4BlendState::Enabled
        } else {
            MTL4BlendState::Disabled
        });
        attachment.setSourceRGBBlendFactor(MTLBlendFactor(w.next()? as _));
        attachment.setDestinationRGBBlendFactor(MTLBlendFactor(w.next()? as _));
        attachment.setRgbBlendOperation(MTLBlendOperation(w.next()? as _));
        attachment.setSourceAlphaBlendFactor(MTLBlendFactor(w.next()? as _));
        attachment.setDestinationAlphaBlendFactor(MTLBlendFactor(w.next()? as _));
        attachment.setAlphaBlendOperation(MTLBlendOperation(w.next()? as _));
        attachment.setWriteMask(MTLColorWriteMask(w.next()? as _));
    }

    let vertex_descriptor = MTLVertexDescriptor::new();
    let layouts = w.next()? as usize;
    for _ in 0..layouts {
        let slot = w.next()? as usize;
        let stride = w.next()? as usize;
        let step_rate = w.next()? as usize;
        let layout = metal::vertex_layout(&vertex_descriptor, VERTEX_BUFFER_BASE + slot, stride)
            .ok_or("invalid vertex buffer layout")?;
        if step_rate > 0 {
            layout.setStepFunction(MTLVertexStepFunction::PerInstance);
            metal::set_step_rate(&layout, step_rate);
        }
    }
    for _ in 0..w.next()? {
        let location = w.next()? as usize;
        let format = MTLVertexFormat(w.next()? as _);
        let offset = w.next()? as usize;
        let slot = w.next()? as usize;
        let attribute = metal::vertex_attribute(
            &vertex_descriptor,
            location,
            offset,
            VERTEX_BUFFER_BASE + slot,
        )
        .ok_or("invalid vertex attribute")?;
        attribute.setFormat(format);
    }
    if layouts > 0 {
        descriptor.setVertexDescriptor(Some(&vertex_descriptor));
    }

    let oit = w
        .words
        .get(w.at)
        .copied()
        .filter(|marker| *marker == 1)
        .map(|_| OitMetadata {
            k: 8,
            imageblock_sample_length: 0,
        });
    let oit = oit.filter(|_| raster_order_groups_supported);
    if oit.is_some() {
        let linking = oit_linking(builtin);
        descriptor.setFragmentStaticLinkingDescriptor(Some(&linking));
    }

    let (primitive, topology, fan) = match primitive_code {
        0 => (
            MTLPrimitiveType::Point,
            MTLPrimitiveTopologyClass::Point,
            false,
        ),
        1 => (
            MTLPrimitiveType::Line,
            MTLPrimitiveTopologyClass::Line,
            false,
        ),
        2 => (
            MTLPrimitiveType::LineStrip,
            MTLPrimitiveTopologyClass::Line,
            false,
        ),
        4 => (
            MTLPrimitiveType::TriangleStrip,
            MTLPrimitiveTopologyClass::Triangle,
            false,
        ),
        5 => (
            MTLPrimitiveType::Triangle,
            MTLPrimitiveTopologyClass::Triangle,
            true,
        ),
        _ => (
            MTLPrimitiveType::Triangle,
            MTLPrimitiveTopologyClass::Triangle,
            false,
        ),
    };
    descriptor.setInputPrimitiveTopology(topology);

    let options = task_options(archive);
    let (state, oit) = match compiler
        .newRenderPipelineStateWithDescriptor_compilerTaskOptions_error(
            &descriptor,
            options.as_deref(),
        ) {
        Ok(state) => (state, oit),
        Err(_error) if oit.is_some() => {
            descriptor.setFragmentStaticLinkingDescriptor(None);
            let state = compiler
                .newRenderPipelineStateWithDescriptor_compilerTaskOptions_error(
                    &descriptor,
                    options.as_deref(),
                )
                .map_err(|fallback| fallback.localizedDescription().to_string())?;
            (state, None)
        }
        Err(error) => return Err(error.localizedDescription().to_string()),
    };
    let sample_length = state.imageblockSampleLength();
    Ok(Pipeline {
        state,
        kind: PipelineKind::Raster,
        oit: oit.map(|metadata| OitMetadata {
            imageblock_sample_length: sample_length,
            ..metadata
        }),
        depth: depth_enabled.then_some((depth_compare, depth_write)),
        primitive,
        fan,
        cull,
        fill: if wireframe {
            MTLTriangleFillMode::Lines
        } else {
            MTLTriangleFillMode::Fill
        },
        depth_bias: (bias_constant, bias_slope),
    })
}

#[cfg(test)]
mod tests {
    use super::{archive_paths, compatible, metadata, threadgroup_size};
    use std::fs;
    use std::path::PathBuf;

    fn test_path(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!("mtlrender-archive-{}-{}", std::process::id(), name))
    }

    #[test]
    fn archive_key_rejects_missing_and_corrupt_metadata() {
        let path = test_path("metadata");
        let (_, sidecar) = archive_paths(&path);
        let _ = fs::remove_file(&path);
        let _ = fs::remove_file(&sidecar);
        assert!(!compatible(&path, b"key"));
        let result = fs::write(&path, [1u8]);
        assert!(result.is_ok());
        let result = fs::write(&sidecar, [0u8]);
        assert!(result.is_ok());
        assert!(!compatible(&path, b"key"));
        let _ = fs::remove_file(&path);
        let _ = fs::remove_file(&sidecar);
    }

    #[test]
    fn archive_metadata_rejects_version_and_key_mismatch() {
        let path = test_path("version");
        let (_, sidecar) = archive_paths(&path);
        let _ = fs::remove_file(&path);
        let _ = fs::remove_file(&sidecar);
        let result = fs::write(&path, [1u8]);
        assert!(result.is_ok());
        let mut value = match metadata(b"key") {
            Some(value) => value,
            None => return,
        };
        if let Some(version) = value.get_mut(8..12) {
            version.copy_from_slice(&2u32.to_le_bytes());
        }
        let result = fs::write(&sidecar, value);
        assert!(result.is_ok());
        assert!(!compatible(&path, b"key"));
        let _ = fs::remove_file(&path);
        let _ = fs::remove_file(&sidecar);
    }

    #[test]
    fn archive_paths_use_atomic_sibling_metadata() {
        let path = PathBuf::from("cache/archive.bin");
        let (archive, sidecar) = archive_paths(&path);
        assert_eq!(archive, path);
        assert_eq!(sidecar, PathBuf::from("cache/archive.bin.meta"));
    }

    #[test]
    fn accepts_positive_threadgroup_dimensions() {
        assert_eq!(threadgroup_size([8, 4, 1]), Ok([8, 4, 1]));
    }

    #[test]
    fn rejects_zero_threadgroup_dimensions() {
        assert!(threadgroup_size([8, 0, 1]).is_err());
    }
}
