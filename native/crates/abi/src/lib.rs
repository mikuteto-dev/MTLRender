#![allow(unsafe_code)]

use std::ffi::{CStr, c_char, c_void};
use std::path::PathBuf;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicU32, Ordering};

use dispatch2::{
    _dispatch_source_type_memorypressure, DispatchObject, DispatchQueue, DispatchRetained,
    DispatchSource, GlobalQueueIdentifier, dispatch_function_t,
    dispatch_source_memorypressure_flags_t,
};
use gpu::{
    ArchiveConfig, Caps, Device, EvalFrame, Kind, METAL_MAX_TEXTURE_DIMENSION, PresentMode,
    SamplerDesc, Surface, TextureShape,
};
use lod::merge;
use mesher::{SectionBatchItem, Table};

#[repr(C)]
pub struct MtlrLodMeshResult {
    magic: u32,
    version: u32,
    status: u32,
    reserved: u32,
    required_bytes: u64,
    written_bytes: u64,
}

const LOD_RESULT_MAGIC: u32 = 0x4c4d_5331;
const LOD_RESULT_VERSION: u32 = 1;
const LOD_OK: u32 = 0;
const LOD_INVALID_INPUT: u32 = 1;
const LOD_OUTPUT_TOO_SMALL: u32 = 2;

#[repr(C)]
pub struct MtlrCalibration {
    version: u32,
    mode: u32,
    draw_path: u32,
    indexed: u32,
    compact: u32,
    probe_interval: u32,
    lod_min_ring: u32,
    width: u32,
    height: u32,
}

const CALIBRATION_VERSION: u32 = 1;
const CALIBRATION_MODE_TWO_PHASE_HIZ: u32 = 1;
const CALIBRATION_MODE_SINGLE_PHASE_HIZ: u32 = 2;
const CALIBRATION_MODE_OFF: u32 = 4;
const CALIBRATION_DRAW_PATH_NON_INDEXED: u32 = 1;
const CALIBRATION_DRAW_PATH_INDEXED: u32 = 2;
const CALIBRATION_DRAW_PATH_MESH: u32 = 3;

unsafe fn write_calibration(address: usize, result: MtlrCalibration) {
    let out = address as *mut MtlrCalibration;
    unsafe {
        out.write(result);
    }
}

unsafe fn lod_input<'a>(address: usize, length: usize) -> &'a [u8] {
    unsafe { std::slice::from_raw_parts(address as *const u8, length) }
}

unsafe fn lod_output<'a>(address: usize, length: usize) -> &'a mut [u8] {
    unsafe { std::slice::from_raw_parts_mut(address as *mut u8, length) }
}
use objc2::rc::autoreleasepool;
use objc2_app_kit::NSWindow;
use objc2_foundation::NSProcessInfo;

static MEMORY_PRESSURE: AtomicU32 = AtomicU32::new(0);
static MEMORY_PRESSURE_SOURCE: OnceLock<DispatchRetained<DispatchSource>> = OnceLock::new();

extern "C" fn memory_pressure_handler(source: *mut c_void) {
    let source = unsafe { &*source.cast::<DispatchSource>() };
    MEMORY_PRESSURE.fetch_or(source.data() as u32, Ordering::Relaxed);
}

fn start_memory_pressure_source() {
    MEMORY_PRESSURE_SOURCE.get_or_init(|| {
        let source = unsafe {
            DispatchSource::new(
                &raw const _dispatch_source_type_memorypressure as *mut _,
                0,
                (dispatch_source_memorypressure_flags_t::DISPATCH_MEMORYPRESSURE_WARN.0
                    | dispatch_source_memorypressure_flags_t::DISPATCH_MEMORYPRESSURE_CRITICAL.0)
                    as usize,
                Some(&DispatchQueue::global_queue(
                    GlobalQueueIdentifier::QualityOfService(dispatch2::DispatchQoS::Utility),
                )),
            )
        };
        source.set_event_handler_f(memory_pressure_handler as dispatch_function_t);
        source.activate();
        source
    });
}

pub type MtlrEvalFrame = EvalFrame;

#[repr(C)]
pub struct MtlrCaps {
    apple_family: u32,
    raytracing: u32,
    raytracing_from_render: u32,
    recommended_max_working_set: u64,
    max_buffer_length: u64,
    metalfx_spatial: u32,
    metalfx_temporal: u32,
    metalfx_frame_interpolation: u32,
}

#[repr(C)]
pub struct MtlrGlslSource {
    pub path: *const c_char,
    pub text: *const u8,
    pub text_len: usize,
}

#[repr(C)]
pub struct MtlrGlslDefine {
    pub name: *const c_char,
    pub value: *const u8,
    pub value_len: usize,
}

#[repr(C)]
pub struct MtlrGlslOptions {
    pub msl_major: u32,
    pub msl_minor: u32,
    pub max_include_depth: u32,
    pub max_source_bytes: u64,
    pub max_macro_expansion_depth: u32,
    pub max_macro_expanded_bytes: u64,
    pub max_parser_recursion_depth: u32,
    pub flags: u32,
}

#[repr(C)]
pub struct MtlrMesherSection {
    pub input: u64,
    pub input_length: u64,
    pub output: u64,
    pub output_capacity: u64,
}

#[repr(C)]
pub struct MtlrMeshDispatchArgs {
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
    pub reserved: u32,
}

fn mesh_range(offset: u64, length: u64, capacity: u64) -> bool {
    offset
        .checked_add(length)
        .is_some_and(|end| end <= capacity)
}

fn valid_mesh_dispatch(args: &MtlrMeshDispatchArgs) -> bool {
    const MAX_DIMENSION: u32 = 65_535;
    const MAX_PAYLOAD: u32 = 16_384;
    if args.resource_address == 0
        || args.resource_length == 0
        || args.payload_bytes == 0
        || args.payload_bytes > MAX_PAYLOAD
        || args.vertex_count == 0
        || args.vertex_count > 256
        || args.primitive_count == 0
        || args.primitive_count > 512
        || args.vertex_bytes == 0
        || args.primitive_bytes == 0
    {
        return false;
    }
    let dimensions_valid = args
        .object_threadgroups
        .into_iter()
        .chain(args.mesh_threadgroups)
        .chain(args.dispatch_geometry)
        .all(|value| value != 0 && value <= MAX_DIMENSION);
    if !dimensions_valid
        || args
            .resource_address
            .checked_add(args.resource_offset)
            .is_none()
    {
        return false;
    }
    mesh_range(args.resource_offset, args.resource_length, u64::MAX)
        && mesh_range(
            args.payload_offset,
            u64::from(args.payload_bytes),
            args.resource_length,
        )
        && mesh_range(args.vertex_offset, args.vertex_bytes, args.resource_length)
        && mesh_range(
            args.primitive_offset,
            args.primitive_bytes,
            args.resource_length,
        )
}

pub struct MtlrGlslResult {
    msl: Vec<u8>,
    reflection: Vec<u8>,
    error: Vec<u8>,
}

#[repr(C)]
pub struct MtlrFrameState {
    submitted: u64,
    completed: u64,
}

fn text<'a>(ptr: *const c_char) -> &'a str {
    if ptr.is_null() {
        return "";
    }
    unsafe { CStr::from_ptr(ptr) }.to_str().unwrap_or_default()
}

fn words<'a>(ptr: *const u32, len: u64) -> &'a [u32] {
    if ptr.is_null() || len == 0 {
        return &[];
    }
    unsafe { std::slice::from_raw_parts(ptr, len as usize) }
}

fn pipeline_words<'a>(ptr: *const u32, len: u32) -> Result<&'a [u32], String> {
    let len = usize::try_from(len).map_err(|_| String::from("pipeline descriptor is too large"))?;
    if len != 0 && ptr.is_null() {
        return Err(String::from("pipeline descriptor is null"));
    }
    Ok(if len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(ptr, len) }
    })
}

fn values_mut<'a>(ptr: *mut u64, len: u32) -> &'a mut [u64] {
    if ptr.is_null() || len == 0 {
        return &mut [];
    }
    unsafe { std::slice::from_raw_parts_mut(ptr, len as usize) }
}

fn frame_state_mut<'a>(ptr: *mut MtlrFrameState) -> Option<&'a mut MtlrFrameState> {
    if ptr.is_null() {
        return None;
    }
    unsafe { Some(&mut *ptr) }
}

fn copy_out(value: &str, out: Option<&mut [u8; 256]>) -> usize {
    let Some(out) = out else { return 0 };
    let n = value.len().min(out.len() - 1);
    out.iter_mut()
        .zip(value.bytes().take(n).chain([0]))
        .for_each(|(dst, src)| *dst = src);
    n
}

fn glsl_bytes<'a>(ptr: *const u8, len: usize) -> Result<&'a [u8], String> {
    if len == 0 {
        return Ok(&[]);
    }
    if ptr.is_null() {
        return Err(String::from(
            "a non-empty GLSL byte range has a null pointer",
        ));
    }
    Ok(unsafe { std::slice::from_raw_parts(ptr, len) })
}

fn archive_key(ptr: *const u8, len: usize) -> Option<Vec<u8>> {
    if len == 0 || ptr.is_null() {
        return None;
    }
    Some(unsafe { std::slice::from_raw_parts(ptr, len) }.to_vec())
}

fn glsl_string(ptr: *const c_char, field: &str) -> Result<String, String> {
    if ptr.is_null() {
        return Err(format!("GLSL {field} is null"));
    }
    let value = unsafe { CStr::from_ptr(ptr) };
    let value = value
        .to_str()
        .map_err(|_| format!("GLSL {field} is not UTF-8"))?;
    Ok(String::from(value))
}

fn glsl_sources(ptr: *const MtlrGlslSource, len: usize) -> Result<Vec<glslc::Source>, String> {
    if len == 0 {
        return Ok(Vec::new());
    }
    if ptr.is_null() {
        return Err(String::from("GLSL source list is null"));
    }
    let values = unsafe { std::slice::from_raw_parts(ptr, len) };
    values
        .iter()
        .map(|value| {
            let path = glsl_string(value.path, "source path")?;
            let text = glsl_bytes(value.text, value.text_len)?;
            let text = std::str::from_utf8(text)
                .map_err(|_| format!("GLSL source {path} is not UTF-8"))?;
            Ok(glslc::Source::new(path, String::from(text)))
        })
        .collect()
}

fn glsl_defines(ptr: *const MtlrGlslDefine, len: usize) -> Result<Vec<glslc::Define>, String> {
    if len == 0 {
        return Ok(Vec::new());
    }
    if ptr.is_null() {
        return Err(String::from("GLSL define list is null"));
    }
    let values = unsafe { std::slice::from_raw_parts(ptr, len) };
    values
        .iter()
        .map(|value| {
            let name = glsl_string(value.name, "define name")?;
            let value = if value.value.is_null() {
                None
            } else {
                let value = glsl_bytes(value.value, value.value_len)?;
                Some(
                    std::str::from_utf8(value)
                        .map_err(|_| format!("GLSL define {name} is not UTF-8"))
                        .map(String::from)?,
                )
            };
            Ok(glslc::Define::new(name, value.as_deref()))
        })
        .collect()
}

fn glsl_options(value: *const MtlrGlslOptions) -> glslc::Options {
    let Some(value) = (!value.is_null()).then_some(value) else {
        return glslc::Options::default();
    };
    glslc::Options {
        msl_major: u16::try_from(unsafe { (*value).msl_major }).unwrap_or(u16::MAX),
        msl_minor: u16::try_from(unsafe { (*value).msl_minor }).unwrap_or(u16::MAX),
        max_include_depth: unsafe { (*value).max_include_depth },
        max_source_bytes: unsafe { (*value).max_source_bytes },
        max_macro_expansion_depth: unsafe { (*value).max_macro_expansion_depth },
        max_macro_expanded_bytes: unsafe { (*value).max_macro_expanded_bytes },
        max_parser_recursion_depth: unsafe { (*value).max_parser_recursion_depth },
        lenient_iris_compatibility: unsafe { (*value).flags & 1 != 0 },
    }
}

fn glsl_result_error(message: String) -> *mut MtlrGlslResult {
    Box::into_raw(Box::new(MtlrGlslResult {
        msl: Vec::new(),
        reflection: Vec::new(),
        error: message.into_bytes(),
    }))
}

fn glsl_result(
    result: Result<glslc::CompiledShader, String>,
    stage: glslc::Stage,
) -> *mut MtlrGlslResult {
    match result {
        Ok(shader) => Box::into_raw(Box::new(MtlrGlslResult {
            msl: shader.msl.into_bytes(),
            reflection: glslc::reflect::encode(&shader.reflection, stage),
            error: Vec::new(),
        })),
        Err(error) => glsl_result_error(error),
    }
}

fn copy_glsl_bytes(value: &[u8], out: *mut u8, capacity: usize) -> usize {
    if !out.is_null() && capacity != 0 {
        let length = value.len().min(capacity);
        let Some(source) = value.get(..length) else {
            return value.len();
        };
        let destination = unsafe { std::slice::from_raw_parts_mut(out, length) };
        destination.copy_from_slice(source);
    }
    value.len()
}

fn glsl_result_is_ok(result: *const MtlrGlslResult) -> bool {
    if result.is_null() {
        return false;
    }
    unsafe { (*result).error.is_empty() }
}

fn glsl_result_bytes(
    result: *const MtlrGlslResult,
    out: *mut u8,
    capacity: usize,
    select: fn(&MtlrGlslResult) -> &[u8],
) -> usize {
    if result.is_null() {
        return 0;
    }
    let value = unsafe { select(&*result) };
    copy_glsl_bytes(value, out, capacity)
}

fn destroy_glsl_result(result: *mut MtlrGlslResult) {
    if result.is_null() {
        return;
    }
    drop(unsafe { Box::from_raw(result) });
}

fn eval_output<'a>(ptr: *mut MtlrEvalFrame, capacity: u32) -> Option<&'a mut [MtlrEvalFrame]> {
    if ptr.is_null() || capacity == 0 {
        return None;
    }
    let capacity = usize::try_from(capacity).ok()?;
    let output = unsafe { std::slice::from_raw_parts_mut(ptr, capacity) };
    Some(output)
}

fn copy_source(out: *mut c_char, capacity: usize) -> usize {
    if out.is_null() || capacity == 0 {
        return 0;
    }
    let value = gpu::ENERGY_SOURCE;
    let n = value.len().min(capacity.saturating_sub(1));
    let output = unsafe { std::slice::from_raw_parts_mut(out.cast::<u8>(), capacity) };
    output
        .iter_mut()
        .zip(value.bytes().take(n).chain([0]))
        .for_each(|(dst, src)| *dst = src);
    n
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_device_create(pass_timing: bool) -> Option<Box<Device>> {
    start_memory_pressure_source();
    autoreleasepool(move |_| Device::new(pass_timing).map(Box::new))
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_device_create_with_archive(
    pass_timing: bool,
    path: *const c_char,
    key: *const u8,
    key_len: usize,
) -> Option<Box<Device>> {
    start_memory_pressure_source();
    let path = glsl_string(path, "archive path").ok()?;
    let key = archive_key(key, key_len)?;
    autoreleasepool(move |_| {
        Device::new_with_archive(
            pass_timing,
            Some(ArchiveConfig {
                path: PathBuf::from(path),
                key,
            }),
        )
        .map(Box::new)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_thermal_state() -> i32 {
    NSProcessInfo::processInfo().thermalState().0 as i32
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_low_power_mode() -> bool {
    NSProcessInfo::processInfo().isLowPowerModeEnabled()
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_memory_pressure() -> u32 {
    MEMORY_PRESSURE.swap(0, Ordering::AcqRel)
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_device_destroy(device: Option<Box<Device>>) {
    autoreleasepool(move |_| drop(device));
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_pipeline_archive_status(device: Option<&Device>) -> u32 {
    device.map_or(0, Device::pipeline_archive_status)
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_pipeline_archive_flush(device: Option<&mut Device>) -> bool {
    device.is_some_and(Device::pipeline_archive_flush)
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_device_name(device: Option<&Device>, out: Option<&mut [u8; 256]>) -> usize {
    autoreleasepool(move |_| device.map_or(0, |d| copy_out(&d.name(), out)))
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_device_caps(device: Option<&Device>, out: Option<&mut MtlrCaps>) -> bool {
    autoreleasepool(move |_| {
        let (Some(device), Some(out)) = (device, out) else {
            return false;
        };
        let Caps {
            apple_family,
            raytracing,
            raytracing_from_render,
            recommended_max_working_set,
            max_buffer_length,
            metalfx_spatial,
            metalfx_temporal,
            metalfx_frame_interpolation,
        } = device.caps();
        *out = MtlrCaps {
            apple_family,
            raytracing: u32::from(raytracing),
            raytracing_from_render: u32::from(raytracing_from_render),
            recommended_max_working_set,
            max_buffer_length,
            metalfx_spatial: u32::from(metalfx_spatial),
            metalfx_temporal: u32::from(metalfx_temporal),
            metalfx_frame_interpolation: u32::from(metalfx_frame_interpolation),
        };
        true
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_last_error(device: Option<&Device>, out: Option<&mut [u8; 256]>) -> usize {
    device.map_or(0, |d| copy_out(d.last_error(), out))
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_frame(device: Option<&Device>) -> u64 {
    device.map_or(0, Device::frame)
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_calibrate(_device: Option<&mut Device>) -> u64 {
    0
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_calibrate_resolution(
    device: Option<&mut Device>,
    width: u32,
    height: u32,
    out: *mut MtlrCalibration,
) -> bool {
    if width == 0
        || height == 0
        || width > METAL_MAX_TEXTURE_DIMENSION
        || height > METAL_MAX_TEXTURE_DIMENSION
        || out.is_null()
    {
        return false;
    }
    let result = autoreleasepool(move |_| {
        let device = device?;
        let packed = device.calibrate_resolution(width, height);
        (packed != 0).then_some(MtlrCalibration {
            version: CALIBRATION_VERSION,
            mode: if packed & 8 != 0 {
                CALIBRATION_MODE_SINGLE_PHASE_HIZ
            } else if packed & 2 != 0 {
                CALIBRATION_MODE_TWO_PHASE_HIZ
            } else {
                CALIBRATION_MODE_OFF
            },
            draw_path: match (packed >> 33) & 0x3 {
                1 => CALIBRATION_DRAW_PATH_NON_INDEXED,
                2 => CALIBRATION_DRAW_PATH_INDEXED,
                3 => CALIBRATION_DRAW_PATH_MESH,
                _ if packed & 1 != 0 => CALIBRATION_DRAW_PATH_INDEXED,
                _ => CALIBRATION_DRAW_PATH_NON_INDEXED,
            },
            indexed: (packed & 1 != 0) as u32,
            compact: (packed & 4 != 0) as u32,
            probe_interval: ((packed >> 16) & 0xffff) as u32,
            lod_min_ring: ((packed >> 8) & 0xff) as u32,
            width,
            height,
        })
    });
    let Some(result) = result else {
        return false;
    };
    unsafe {
        write_calibration(out as usize, result);
    }
    true
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_completed_frames(device: Option<&Device>) -> u64 {
    autoreleasepool(move |_| device.map_or(0, Device::completed_frames))
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_wait_frame(device: Option<&Device>, frame: u64, timeout_ms: u64) -> bool {
    autoreleasepool(move |_| device.is_some_and(|d| d.wait_frame(frame, timeout_ms)))
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_capture_next(device: Option<&mut Device>, path: *const c_char) -> bool {
    autoreleasepool(move |_| {
        let (Some(device), Some(path)) = (device, (!path.is_null()).then_some(path)) else {
            return false;
        };
        let Ok(path) = (unsafe { CStr::from_ptr(path) }).to_str() else {
            return false;
        };
        device.capture_next(path)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_gpu_memory_bytes(device: Option<&Device>) -> u64 {
    autoreleasepool(move |_| device.map_or(0, Device::allocated_bytes))
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_native_memory_bytes(device: Option<&Device>) -> u64 {
    autoreleasepool(move |_| device.map_or(0, Device::project_allocated_bytes))
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_metal_allocated_size(device: Option<&Device>) -> u64 {
    autoreleasepool(move |_| device.map_or(0, Device::allocated_bytes))
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_phys_footprint_bytes() -> u64 {
    autoreleasepool(|_| gpu::phys_footprint_bytes().unwrap_or(0))
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_eval_start(device: Option<&mut Device>) -> bool {
    autoreleasepool(move |_| device.is_some_and(Device::eval_start))
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_eval_stop(device: Option<&mut Device>) {
    autoreleasepool(move |_| {
        if let Some(device) = device {
            device.eval_stop();
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_eval_read(
    device: Option<&mut Device>,
    out: *mut MtlrEvalFrame,
    capacity: u32,
) -> u32 {
    autoreleasepool(move |_| {
        let (Some(device), Some(out)) = (device, eval_output(out, capacity)) else {
            return 0;
        };
        u32::try_from(device.eval_read(out)).unwrap_or(u32::MAX)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_eval_energy_source(out: *mut c_char, capacity: usize) -> usize {
    copy_source(out, capacity)
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_surface_create(
    device: Option<&mut Device>,
    window: Option<&NSWindow>,
) -> Option<Box<Surface>> {
    autoreleasepool(move |_| device?.create_surface(window?).map(Box::new))
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_surface_destroy(surface: Option<Box<Surface>>) {
    autoreleasepool(move |_| drop(surface));
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_surface_configure(
    surface: Option<&Surface>,
    width: u32,
    height: u32,
    mode: u32,
    refresh: u32,
) {
    autoreleasepool(move |_| {
        if let Some(surface) = surface {
            surface.configure(width, height, PresentMode::from_code(mode), refresh);
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_surface_visible(surface: Option<&Surface>) -> bool {
    autoreleasepool(move |_| surface.is_some_and(Surface::visible))
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_buffer_create(
    device: Option<&mut Device>,
    size: u64,
    initial_data: *const c_void,
    initial_len: u64,
    contents: Option<&mut *mut c_void>,
) -> u64 {
    autoreleasepool(move |_| {
        let (Some(device), Some(contents)) = (device, contents) else {
            return 0;
        };
        let Ok(size) = usize::try_from(size) else {
            return 0;
        };
        let Ok(initial_len) = usize::try_from(initial_len) else {
            return 0;
        };
        if initial_len > size || (initial_len != 0 && initial_data.is_null()) {
            return 0;
        }
        let initial = if initial_len == 0 {
            &[]
        } else {
            unsafe { std::slice::from_raw_parts(initial_data.cast::<u8>(), initial_len) }
        };
        device
            .create_buffer(size, initial)
            .map_or(0, |(handle, ptr)| {
                *contents = ptr;
                handle
            })
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_buffer_address(device: Option<&Device>, buffer: u64) -> u64 {
    device.map_or(0, |d| d.buffer_address(buffer))
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_texture_create(
    device: Option<&mut Device>,
    format: u32,
    width: u32,
    height: u32,
    layers: u32,
    mips: u32,
    cube: bool,
    render_target: bool,
    shader_write: bool,
) -> u64 {
    autoreleasepool(move |_| {
        let shape = TextureShape {
            width: width as usize,
            height: height as usize,
            layers: layers as usize,
            mips: mips as usize,
            cube,
        };
        device
            .and_then(|d| d.create_texture(format, &shape, render_target, shader_write))
            .unwrap_or(0)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_view_create(
    device: Option<&mut Device>,
    texture: u64,
    base_mip: u32,
    mips: u32,
) -> u64 {
    autoreleasepool(move |_| {
        device
            .and_then(|d| d.create_view(texture, base_mip, mips))
            .unwrap_or(0)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_sampler_create(
    device: Option<&mut Device>,
    words: Option<&[u32; 6]>,
) -> u64 {
    autoreleasepool(move |_| {
        let (Some(device), Some(&[u, v, min, mag, anisotropy, max_lod])) = (device, words) else {
            return 0;
        };
        let desc = SamplerDesc {
            repeat_u: u == 0,
            repeat_v: v == 0,
            linear_min: min != 0,
            linear_mag: mag != 0,
            max_anisotropy: anisotropy,
            max_lod: f32::from_bits(max_lod),
        };
        device.create_sampler(&desc).unwrap_or(0)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_pipeline_create(
    device: Option<&mut Device>,
    vertex: *const c_char,
    vertex_entry: *const c_char,
    fragment: *const c_char,
    fragment_entry: *const c_char,
    desc: *const u32,
    desc_len: u32,
) -> u64 {
    autoreleasepool(move |_| {
        let vertex = (text(vertex), text(vertex_entry));
        let fragment = (text(fragment), text(fragment_entry));
        device
            .and_then(|d| d.create_pipeline(vertex, fragment, words(desc, u64::from(desc_len))))
            .unwrap_or(0)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_pack_pipeline_create(
    device: Option<&mut Device>,
    vertex: *const c_char,
    vertex_entry: *const c_char,
    fragment: *const c_char,
    fragment_entry: *const c_char,
    desc: *const u32,
    desc_len: u32,
) -> u64 {
    autoreleasepool(move |_| {
        let vertex = (text(vertex), text(vertex_entry));
        let fragment = (text(fragment), text(fragment_entry));
        device
            .and_then(|d| {
                d.create_pack_pipeline(vertex, fragment, words(desc, u64::from(desc_len)))
            })
            .unwrap_or(0)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_mesh_pipeline_create(
    device: Option<&mut Device>,
    object: *const c_char,
    object_entry: *const c_char,
    mesh: *const c_char,
    mesh_entry: *const c_char,
    fragment: *const c_char,
    fragment_entry: *const c_char,
    desc: *const u32,
    desc_len: u32,
) -> u64 {
    autoreleasepool(move |_| {
        let Some(device) = device else {
            return 0;
        };
        let object = match glsl_string(object, "mesh object source") {
            Ok(value) => value,
            Err(error) => {
                device.set_error(error);
                return 0;
            }
        };
        let object_entry = match glsl_string(object_entry, "mesh object entry") {
            Ok(value) => value,
            Err(error) => {
                device.set_error(error);
                return 0;
            }
        };
        let mesh = match glsl_string(mesh, "mesh source") {
            Ok(value) => value,
            Err(error) => {
                device.set_error(error);
                return 0;
            }
        };
        let mesh_entry = match glsl_string(mesh_entry, "mesh entry") {
            Ok(value) => value,
            Err(error) => {
                device.set_error(error);
                return 0;
            }
        };
        let fragment = match glsl_string(fragment, "mesh fragment source") {
            Ok(value) => value,
            Err(error) => {
                device.set_error(error);
                return 0;
            }
        };
        let fragment_entry = match glsl_string(fragment_entry, "mesh fragment entry") {
            Ok(value) => value,
            Err(error) => {
                device.set_error(error);
                return 0;
            }
        };
        let words = match pipeline_words(desc, desc_len) {
            Ok(words) => words,
            Err(error) => {
                device.set_error(error);
                return 0;
            }
        };
        device
            .create_mesh_pipeline(
                (&object, &object_entry),
                (&mesh, &mesh_entry),
                (&fragment, &fragment_entry),
                words,
            )
            .unwrap_or(0)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_compute_pipeline_create(
    device: Option<&mut Device>,
    source: *const c_char,
    entry: *const c_char,
    threads_x: u32,
    threads_y: u32,
    threads_z: u32,
) -> u64 {
    autoreleasepool(move |_| {
        device
            .and_then(|d| {
                d.create_compute_pipeline(
                    text(source),
                    text(entry),
                    [threads_x, threads_y, threads_z],
                )
            })
            .unwrap_or(0)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_destroy(device: Option<&mut Device>, kind: u32, handle: u64) {
    autoreleasepool(move |_| {
        let kind = match kind {
            0 => Kind::Buffer,
            1 => Kind::Texture,
            2 => Kind::Sampler,
            3 => Kind::Pipeline,
            4 => Kind::Query,
            5 => Kind::ComputePipeline,
            _ => Kind::Query,
        };
        if let Some(device) = device {
            device.destroy(kind, handle);
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_query_pool_create(device: Option<&mut Device>, count: u32) -> u64 {
    autoreleasepool(move |_| {
        device
            .and_then(|d| d.create_query_pool(count as usize))
            .unwrap_or(0)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_query_resolve(
    device: Option<&Device>,
    pool: u64,
    first: u32,
    count: u32,
    out: *mut u64,
) -> bool {
    autoreleasepool(move |_| {
        let Some(device) = device else { return false };
        device.resolve_queries(pool, first as usize, values_mut(out, count))
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_timestamp_frequency(device: Option<&Device>) -> u64 {
    device.map_or(0, Device::timestamp_frequency)
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_pass_times(device: Option<&Device>, out: *mut u64, len: u32) -> u32 {
    device.map_or(0, |d| {
        u32::try_from(d.pass_times(values_mut(out, len))).unwrap_or(u32::MAX)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_mesh_dispatch_validate(args: *const MtlrMeshDispatchArgs) -> bool {
    let Some(args) = (!args.is_null()).then_some(args) else {
        return false;
    };
    valid_mesh_dispatch(unsafe { &*args })
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_submit(
    device: Option<&mut Device>,
    stream: *const u32,
    len: u64,
    surface: Option<&mut Surface>,
    out: *mut MtlrFrameState,
) {
    autoreleasepool(move |_| {
        if let Some(device) = device {
            device.submit(words(stream, len), surface);
            let Some(out) = frame_state_mut(out) else {
                return;
            };
            out.submitted = device.frame();
            out.completed = device.completed_frames();
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_stat_nanos(counter: u32) -> u64 {
    gpu::nanos(counter as usize)
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_lod_mesh(
    input: *const u8,
    input_len: usize,
    output: *mut u8,
    output_capacity: usize,
) -> MtlrLodMeshResult {
    let base = |status, required_bytes, written_bytes| MtlrLodMeshResult {
        magic: LOD_RESULT_MAGIC,
        version: LOD_RESULT_VERSION,
        status,
        reserved: 0,
        required_bytes,
        written_bytes,
    };
    if input.is_null() && input_len != 0 {
        return base(LOD_INVALID_INPUT, 0, 0);
    }
    let source = if input_len == 0 {
        &[]
    } else {
        unsafe { lod_input(input as usize, input_len) }
    };
    let required = match lod::required_for(source) {
        Ok(value) => value,
        Err(_) => return base(LOD_INVALID_INPUT, 0, 0),
    };
    if output.is_null() || output_capacity < required {
        return base(LOD_OUTPUT_TOO_SMALL, required as u64, 0);
    }
    let target = unsafe { lod_output(output as usize, required) };
    match lod::mesh(source, target) {
        Ok(result) => base(LOD_OK, result.bytes as u64, result.bytes as u64),
        Err(_) => base(LOD_INVALID_INPUT, required as u64, 0),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_mesher_table_create() -> Option<Box<Table>> {
    Some(Box::new(Table::new()))
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_mesher_table_destroy(table: Option<Box<Table>>) {
    drop(table);
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_mesher_table_add_state(
    table: Option<&mut Table>,
    state_id: u32,
    flags: u32,
    bytes: u64,
    length: u64,
) -> bool {
    let Some(table) = table else { return false };
    let Ok(length) = usize::try_from(length) else {
        return false;
    };
    let bytes = unsafe { bytes_from_address(bytes, length) };
    table.add_state(state_id, flags, bytes)
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_mesher_table_state_count(table: Option<&Table>) -> usize {
    table.map_or(0, Table::state_count)
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_mesher_table_quad_count(table: Option<&Table>) -> usize {
    table.map_or(0, Table::quad_count)
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_mesher_table_max_output(table: Option<&Table>) -> usize {
    table.map_or(0, |value| {
        value
            .max_output_bytes()
            .saturating_mul(4096)
            .saturating_add(12)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_mesher_mesh_block(input: u64, length: u64, output: u64) -> bool {
    let Ok(length) = usize::try_from(length) else {
        return false;
    };
    if input == 0 || output == 0 || length == 0 {
        return false;
    }
    let (input, output) = unsafe { buffers_from_address(input, output, length) };
    mesher::mesh_block_quads(input, output)
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_mesher_mesh_section(
    table: Option<&Table>,
    input: u64,
    length: u64,
    output: u64,
    capacity: u64,
) -> usize {
    let Some(table) = table else { return 0 };
    let Ok(length) = usize::try_from(length) else {
        return 0;
    };
    let Ok(capacity) = usize::try_from(capacity) else {
        return 0;
    };
    if !valid_address(input, length) || !valid_address(output, capacity) {
        return 0;
    }
    let input = unsafe { bytes_from_address(input, length) };
    let output = unsafe { bytes_from_address_mut(output, capacity) };
    mesher::mesh_section(table, input, output).unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_mesher_mesh_sections(
    table: Option<&Table>,
    descriptors: *const MtlrMesherSection,
    item_count: usize,
    written: *mut usize,
) -> bool {
    let Some(table) = table else { return false };
    if item_count == 0 {
        return true;
    }
    let Some(descriptor_bytes) = item_count.checked_mul(std::mem::size_of::<MtlrMesherSection>())
    else {
        return false;
    };
    let Some(written_bytes) = item_count.checked_mul(std::mem::size_of::<usize>()) else {
        return false;
    };
    if !valid_address(descriptors as u64, descriptor_bytes)
        || !valid_address(written as u64, written_bytes)
    {
        return false;
    }
    let descriptors = unsafe { mesher_descriptors(descriptors as u64, item_count) };
    let written = unsafe { mesher_written(written as u64, item_count) };
    written.fill(0);
    let mut items = descriptors
        .iter()
        .map(|descriptor| {
            let input_length = usize::try_from(descriptor.input_length).ok();
            let output_capacity = usize::try_from(descriptor.output_capacity).ok();
            match (input_length, output_capacity) {
                (Some(input_length), Some(output_capacity))
                    if valid_address(descriptor.input, input_length)
                        && valid_address(descriptor.output, output_capacity) =>
                {
                    SectionBatchItem {
                        input: unsafe { bytes_from_address(descriptor.input, input_length) },
                        output: unsafe {
                            bytes_from_address_mut(descriptor.output, output_capacity)
                        },
                        result: Default::default(),
                    }
                }
                _ => SectionBatchItem {
                    input: &[],
                    output: &mut [],
                    result: Default::default(),
                },
            }
        })
        .collect::<Vec<_>>();
    mesher::mesh_sections(table, &mut items);
    for (destination, item) in written.iter_mut().zip(items.iter()) {
        *destination = item.result.written;
    }
    true
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_lod_merge(
    input: u64,
    length: u64,
    color_shift: u32,
    light_shift: u32,
    output: u64,
    capacity: u64,
    faces: *mut u32,
    face_count: u32,
) -> usize {
    let Ok(length) = usize::try_from(length) else {
        return 0;
    };
    let Ok(capacity) = usize::try_from(capacity) else {
        return 0;
    };
    let Ok(face_count) = usize::try_from(face_count) else {
        return 0;
    };
    if input == 0
        || output == 0
        || faces.is_null()
        || length == 0
        || capacity < length
        || !length.is_multiple_of(lod::QUAD_STRIDE)
        || face_count < lod::FACE_COUNT
    {
        return 0;
    }
    let face_bytes = match face_count.checked_mul(std::mem::size_of::<u32>()) {
        Some(value) => value,
        None => return 0,
    };
    if !valid_address(input, length)
        || !valid_address(output, capacity)
        || !valid_address(faces as u64, face_bytes)
        || !(faces as usize).is_multiple_of(std::mem::align_of::<u32>())
    {
        return 0;
    }
    let input = unsafe { bytes_from_address(input, length) };
    let output = unsafe { bytes_from_address_mut(output, capacity) };
    let faces = unsafe { u32s_from_address(faces as u64, face_count) };
    merge(input, color_shift, light_shift, output, faces).unwrap_or(0)
}

unsafe fn u32s_from_address<'a>(address: u64, length: usize) -> &'a mut [u32] {
    let pointer = address as *mut u32;
    unsafe { std::slice::from_raw_parts_mut(pointer, length) }
}

unsafe fn mesher_descriptors<'a>(address: u64, count: usize) -> &'a [MtlrMesherSection] {
    let pointer = address as *const MtlrMesherSection;
    unsafe { std::slice::from_raw_parts(pointer, count) }
}

unsafe fn mesher_written<'a>(address: u64, count: usize) -> &'a mut [usize] {
    let pointer = address as *mut usize;
    unsafe { std::slice::from_raw_parts_mut(pointer, count) }
}

fn valid_address(address: u64, length: usize) -> bool {
    if address == 0 || address > isize::MAX as u64 {
        return false;
    }
    let Ok(length) = u64::try_from(length) else {
        return false;
    };
    address
        .checked_add(length)
        .is_some_and(|end| end <= isize::MAX as u64)
}

unsafe fn bytes_from_address<'a>(address: u64, length: usize) -> &'a [u8] {
    if address == 0 || length == 0 {
        return &[];
    }
    let pointer = address as *const u8;
    unsafe { std::slice::from_raw_parts(pointer, length) }
}

unsafe fn bytes_from_address_mut<'a>(address: u64, length: usize) -> &'a mut [u8] {
    let pointer = address as *mut u8;
    unsafe { std::slice::from_raw_parts_mut(pointer, length) }
}

unsafe fn buffers_from_address<'a>(
    input: u64,
    output: u64,
    length: usize,
) -> (&'a [u8], &'a mut [u8]) {
    let input_pointer = input as *const u8;
    let output_pointer = output as *mut u8;
    let input = unsafe { std::slice::from_raw_parts(input_pointer, length) };
    let output = unsafe { std::slice::from_raw_parts_mut(output_pointer, length) };
    (input, output)
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_glsl_compile(
    stage: u32,
    sources: *const MtlrGlslSource,
    source_count: usize,
    defines: *const MtlrGlslDefine,
    define_count: usize,
    options: *const MtlrGlslOptions,
) -> *mut MtlrGlslResult {
    let Some(stage) = glslc::Stage::from_u32(stage) else {
        return glsl_result_error(String::from("GLSL stage value is invalid"));
    };
    let sources = match glsl_sources(sources, source_count) {
        Ok(sources) => sources,
        Err(error) => return glsl_result_error(error),
    };
    let defines = match glsl_defines(defines, define_count) {
        Ok(defines) => defines,
        Err(error) => return glsl_result_error(error),
    };
    let options = glsl_options(options);
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        glslc::compile(stage, &sources, &defines, &options).map_err(|error| error.to_string())
    }));
    match result {
        Ok(result) => glsl_result(result, stage),
        Err(_) => glsl_result_error(String::from("GLSL compiler failed internally")),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_glsl_result_ok(result: *const MtlrGlslResult) -> bool {
    glsl_result_is_ok(result)
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_glsl_result_msl(
    result: *const MtlrGlslResult,
    out: *mut u8,
    capacity: usize,
) -> usize {
    glsl_result_bytes(result, out, capacity, |result| &result.msl)
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_glsl_result_reflection(
    result: *const MtlrGlslResult,
    out: *mut u8,
    capacity: usize,
) -> usize {
    glsl_result_bytes(result, out, capacity, |result| &result.reflection)
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_glsl_result_error(
    result: *const MtlrGlslResult,
    out: *mut u8,
    capacity: usize,
) -> usize {
    glsl_result_bytes(result, out, capacity, |result| &result.error)
}

#[unsafe(no_mangle)]
pub extern "C" fn mtlr_glsl_result_destroy(result: *mut MtlrGlslResult) {
    destroy_glsl_result(result);
}

#[cfg(test)]
mod tests {
    use super::{
        MtlrMeshDispatchArgs, MtlrMesherSection, mtlr_glsl_compile, mtlr_glsl_result_destroy,
        mtlr_glsl_result_error, mtlr_glsl_result_ok, mtlr_lod_merge, mtlr_mesh_dispatch_validate,
        mtlr_mesher_mesh_sections,
    };
    use mesher::Table;

    fn mesh_args() -> MtlrMeshDispatchArgs {
        MtlrMeshDispatchArgs {
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
            reserved: 0,
        }
    }

    #[test]
    fn mesh_dispatch_validation_accepts_contract_limits() {
        let mut args = mesh_args();
        args.vertex_count = 256;
        args.primitive_count = 512;
        assert!(mtlr_mesh_dispatch_validate(&args));
        args.resource_address = u64::MAX;
        args.resource_offset = 1;
        assert!(!mtlr_mesh_dispatch_validate(&args));
        assert!(!mtlr_mesh_dispatch_validate(std::ptr::null()));
    }

    #[test]
    fn mesh_dispatch_validation_rejects_zero_and_overflow() {
        let mut args = mesh_args();
        args.payload_bytes = 0;
        assert!(!mtlr_mesh_dispatch_validate(&args));
        args = mesh_args();
        args.vertex_count = 257;
        assert!(!mtlr_mesh_dispatch_validate(&args));
        args = mesh_args();
        args.primitive_count = 513;
        assert!(!mtlr_mesh_dispatch_validate(&args));
        args = mesh_args();
        args.vertex_offset = 64;
        assert!(!mtlr_mesh_dispatch_validate(&args));
        args = mesh_args();
        args.payload_offset = u64::MAX;
        assert!(!mtlr_mesh_dispatch_validate(&args));
    }

    #[test]
    fn mesher_batch_accepts_empty_input() {
        let table = Table::new();
        assert!(mtlr_mesher_mesh_sections(
            Some(&table),
            std::ptr::null(),
            0,
            std::ptr::null_mut(),
        ));
    }

    #[test]
    fn mesher_batch_rejects_null_descriptors() {
        let table = Table::new();
        let mut written = [99usize];
        assert!(!mtlr_mesher_mesh_sections(
            Some(&table),
            std::ptr::null(),
            1,
            written.as_mut_ptr(),
        ));
        assert_eq!(written, [99]);
    }

    #[test]
    fn mesher_batch_keeps_invalid_items_isolated() {
        let table = Table::new();
        let input = [0u8; 1];
        let mut output = [0u8; 16];
        let items = [
            MtlrMesherSection {
                input: input.as_ptr() as u64,
                input_length: input.len() as u64,
                output: output.as_mut_ptr() as u64,
                output_capacity: output.len() as u64,
            },
            MtlrMesherSection {
                input: 0,
                input_length: 1,
                output: 1,
                output_capacity: 1,
            },
        ];
        let mut written = [99usize, 99usize];
        assert!(mtlr_mesher_mesh_sections(
            Some(&table),
            items.as_ptr(),
            items.len(),
            written.as_mut_ptr(),
        ));
        assert_eq!(written, [0, 0]);
    }

    #[test]
    fn lod_merge_writes_one_quad_and_all_face_counts() {
        let input = vec![0x5au8; lod::QUAD_STRIDE];
        let mut output = vec![0u8; lod::QUAD_STRIDE];
        let mut faces = [u32::MAX; lod::FACE_COUNT];
        let written = mtlr_lod_merge(
            input.as_ptr() as u64,
            input.len() as u64,
            3,
            2,
            output.as_mut_ptr() as u64,
            output.len() as u64,
            faces.as_mut_ptr(),
            faces.len() as u32,
        );
        assert_eq!(written, lod::QUAD_STRIDE);
        assert_eq!(output, input);
        assert_eq!(faces, [0, 0, 0, 1, 0, 0, 0]);
    }

    #[test]
    fn lod_merge_rejects_invalid_ranges_without_dereferencing_them() {
        let mut faces = [0u32; lod::FACE_COUNT];
        assert_eq!(mtlr_lod_merge(0, 1, 0, 0, 1, 1, faces.as_mut_ptr(), 7), 0);
        assert_eq!(mtlr_lod_merge(1, 0, 0, 0, 1, 1, faces.as_mut_ptr(), 7), 0);
        assert_eq!(
            mtlr_lod_merge(1, 112, 0, 0, 1, 112, faces.as_mut_ptr(), 6),
            0
        );
        assert_eq!(
            mtlr_lod_merge(u64::MAX, 112, 0, 0, 1, 112, faces.as_mut_ptr(), 7),
            0
        );
        assert_eq!(
            mtlr_lod_merge(1, 112, 0, 0, u64::MAX, 112, faces.as_mut_ptr(), 7),
            0
        );
        assert_eq!(
            mtlr_lod_merge(1, 112, 0, 0, 1, 112, std::ptr::null_mut(), 7),
            0
        );
    }

    #[test]
    fn glsl_ffi_rejects_an_unknown_stage() {
        let result = mtlr_glsl_compile(
            99,
            std::ptr::null(),
            0,
            std::ptr::null(),
            0,
            std::ptr::null(),
        );
        assert!(!mtlr_glsl_result_ok(result));
        assert!(mtlr_glsl_result_error(result, std::ptr::null_mut(), 0) > 0);
        mtlr_glsl_result_destroy(result);
    }

    #[test]
    fn glsl_ffi_keeps_stub_errors_in_the_result_handle() {
        let result = mtlr_glsl_compile(
            0,
            std::ptr::null(),
            0,
            std::ptr::null(),
            0,
            std::ptr::null(),
        );
        assert!(!mtlr_glsl_result_ok(result));
        assert!(mtlr_glsl_result_error(result, std::ptr::null_mut(), 0) > 0);
        mtlr_glsl_result_destroy(result);
    }
}
