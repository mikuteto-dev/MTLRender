#![allow(unsafe_code)]

use std::ffi::c_void;
use std::mem::{MaybeUninit, size_of};
use std::ptr::NonNull;
use std::sync::mpsc::SyncSender;

use block2::RcBlock;
use objc2::rc::Retained;
use objc2::runtime::ProtocolObject;
use objc2_foundation::{NSString, NSURL};
use objc2_metal::{
    MTL4ArgumentTable, MTL4CommandBuffer, MTL4CommandEncoder, MTL4CommandQueue, MTL4CommitFeedback,
    MTL4CommitOptions, MTL4Compiler, MTL4ComputeCommandEncoder, MTL4CounterHeap,
    MTL4CounterHeapDescriptor, MTL4CounterHeapType, MTL4MeshRenderPipelineDescriptor,
    MTL4RenderCommandEncoder, MTL4RenderPassDescriptor,
    MTL4RenderPipelineColorAttachmentDescriptor, MTL4RenderPipelineDescriptor,
    MTL4TileRenderPipelineDescriptor, MTLAllocation, MTLBuffer, MTLCaptureDescriptor,
    MTLCaptureDestination, MTLCaptureManager, MTLComputePipelineState, MTLDevice, MTLDrawable,
    MTLIndexType, MTLOrigin, MTLPixelFormat, MTLPrimitiveType,
    MTLRenderPassColorAttachmentDescriptor, MTLResource, MTLResourceID, MTLSize, MTLStorageMode,
    MTLTexture, MTLTextureUsage, MTLVertexAttributeDescriptor, MTLVertexBufferLayoutDescriptor,
    MTLVertexDescriptor,
};
use objc2_metal_fx::{
    MTL4FXFrameInterpolator, MTL4FXSpatialScaler, MTL4FXTemporalDenoisedScaler,
    MTL4FXTemporalScaler, MTLFXFrameInterpolatorBase, MTLFXFrameInterpolatorDescriptor,
    MTLFXSpatialScalerBase, MTLFXSpatialScalerDescriptor, MTLFXTemporalDenoisedScalerBase,
    MTLFXTemporalDenoisedScalerDescriptor, MTLFXTemporalScalerBase, MTLFXTemporalScalerDescriptor,
};

pub const MAX_BUFFER_BINDINGS: usize = 32;
pub const MAX_TEXTURE_BINDINGS: usize = 16;
pub const MAX_COLOR_ATTACHMENTS: usize = 8;
pub const MAX_VERTEX_ATTRIBUTES: usize = 31;

const KERN_SUCCESS: i32 = 0;
const TASK_VM_INFO: u32 = 22;
const THREAD_BASIC_INFO: u32 = 3;

#[repr(C)]
struct MachTimeValue {
    seconds: i32,
    microseconds: i32,
}

#[repr(C)]
struct ThreadBasicInfo {
    user_time: MachTimeValue,
    system_time: MachTimeValue,
    cpu_usage: i32,
    policy: i32,
    run_state: i32,
    flags: i32,
    suspend_count: i32,
    sleep_time: i32,
}

#[repr(C)]
struct TaskVmInfoRev1 {
    virtual_size: u64,
    region_count: i32,
    page_size: i32,
    resident_size: u64,
    resident_size_peak: u64,
    device: u64,
    device_peak: u64,
    internal: u64,
    internal_peak: u64,
    external: u64,
    external_peak: u64,
    reusable: u64,
    reusable_peak: u64,
    purgeable_volatile_pmap: u64,
    purgeable_volatile_resident: u64,
    purgeable_volatile_virtual: u64,
    compressed: u64,
    compressed_peak: u64,
    compressed_lifetime: u64,
    phys_footprint: u64,
    min_address: u64,
    max_address: u64,
}

unsafe extern "C" {
    static mach_task_self_: u32;
    fn task_threads(target_task: u32, act_list: *mut *mut u32, act_list_count: *mut u32) -> i32;
    fn thread_info(
        target_thread: u32,
        flavor: u32,
        thread_info_out: *mut i32,
        thread_info_out_count: *mut u32,
    ) -> i32;
    fn task_info(
        target_task: u32,
        flavor: u32,
        task_info_out: *mut i32,
        task_info_out_count: *mut u32,
    ) -> i32;
    fn vm_deallocate(target_task: u32, address: u64, size: u64) -> i32;
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct ProcessMetrics {
    pub cpu_process_ns: Option<u64>,
    pub cpu_thread_count: Option<u32>,
    pub phys_footprint_bytes: Option<u64>,
}

fn time_ns(value: &MachTimeValue) -> Option<u64> {
    let seconds = u64::try_from(value.seconds).ok()?;
    let microseconds = u64::try_from(value.microseconds).ok()?;
    (microseconds < 1_000_000).then_some(())?;
    seconds
        .checked_mul(1_000_000_000)?
        .checked_add(microseconds.checked_mul(1_000)?)
}

fn sum_thread_times(times: &[(MachTimeValue, MachTimeValue)]) -> Option<u64> {
    times.iter().try_fold(0_u64, |sum, (user, system)| {
        sum.checked_add(time_ns(user)?.checked_add(time_ns(system)?)?)
    })
}

pub fn current_allocated_size(device: &ProtocolObject<dyn MTLDevice>) -> Option<u64> {
    u64::try_from(device.currentAllocatedSize()).ok()
}

pub fn allocation_size(allocation: &ProtocolObject<dyn MTLAllocation>) -> Option<u64> {
    u64::try_from(allocation.allocatedSize()).ok()
}

pub fn phys_footprint_bytes() -> Option<u64> {
    process_metrics().phys_footprint_bytes
}

pub fn process_metrics() -> ProcessMetrics {
    let task = unsafe { mach_task_self_ };
    let mut thread_list = std::ptr::null_mut();
    let mut thread_count = 0_u32;
    let threads_ok =
        unsafe { task_threads(task, &mut thread_list, &mut thread_count) } == KERN_SUCCESS;
    let mut cpu_process_ns = None;
    let mut cpu_thread_count = None;
    if threads_ok && (thread_count == 0 || !thread_list.is_null()) {
        let mut times = Vec::with_capacity(thread_count as usize);
        let mut valid = true;
        for index in 0..thread_count as usize {
            let thread_ptr = unsafe { thread_list.add(index) };
            let thread = unsafe { thread_ptr.read() };
            let mut info = MaybeUninit::<ThreadBasicInfo>::uninit();
            let mut count = (size_of::<ThreadBasicInfo>() / size_of::<u32>()) as u32;
            let result = unsafe {
                thread_info(
                    thread,
                    THREAD_BASIC_INFO,
                    info.as_mut_ptr().cast::<i32>(),
                    &mut count,
                )
            };
            if result != KERN_SUCCESS {
                valid = false;
                break;
            }
            let info = unsafe { info.assume_init() };
            if time_ns(&info.user_time).is_none() || time_ns(&info.system_time).is_none() {
                valid = false;
                break;
            }
            times.push((info.user_time, info.system_time));
        }
        if valid {
            cpu_process_ns = sum_thread_times(&times);
            cpu_thread_count = Some(thread_count);
        }
    }
    if !thread_list.is_null() && thread_count > 0 {
        let size = (thread_count as usize).saturating_mul(size_of::<u32>()) as u64;
        unsafe { vm_deallocate(task, thread_list.cast::<c_void>() as usize as u64, size) };
    }
    let mut vm_info = MaybeUninit::<TaskVmInfoRev1>::uninit();
    let mut vm_count = (size_of::<TaskVmInfoRev1>() / size_of::<u32>()) as u32;
    let phys_footprint_bytes = (unsafe {
        task_info(
            task,
            TASK_VM_INFO,
            vm_info.as_mut_ptr().cast::<i32>(),
            &mut vm_count,
        )
    } == KERN_SUCCESS)
        .then(|| unsafe { vm_info.assume_init().phys_footprint });
    ProcessMetrics {
        cpu_process_ns,
        cpu_thread_count,
        phys_footprint_bytes,
    }
}

pub fn supports_temporal_denoised_scaler(device: &ProtocolObject<dyn MTLDevice>) -> bool {
    let metal4 = unsafe { MTLFXTemporalDenoisedScalerDescriptor::supportsMetal4FX(device) };
    let device_support = unsafe { MTLFXTemporalDenoisedScalerDescriptor::supportsDevice(device) };
    metal4 && device_support
}

pub type Buffer = Retained<ProtocolObject<dyn MTLBuffer>>;
pub type Texture = Retained<ProtocolObject<dyn MTLTexture>>;

pub struct Capture {
    manager: Retained<MTLCaptureManager>,
}

impl Capture {
    pub fn start(device: &ProtocolObject<dyn MTLDevice>, path: &str) -> Option<Self> {
        let manager = unsafe { MTLCaptureManager::sharedCaptureManager() };
        let descriptor = MTLCaptureDescriptor::new();
        descriptor.set_capture_device(device);
        descriptor.setDestination(MTLCaptureDestination::GPUTraceDocument);
        let path = NSString::from_str(path);
        let url = NSURL::fileURLWithPath(&path);
        descriptor.setOutputURL(Some(&url));
        manager
            .startCaptureWithDescriptor_error(&descriptor)
            .ok()
            .map(|()| Self { manager })
    }

    pub fn stop(self) {
        self.manager.stopCapture();
    }
}

pub struct TemporalDenoisedScaler {
    scaler: Retained<ProtocolObject<dyn MTL4FXTemporalDenoisedScaler>>,
    output: Texture,
    motion: Texture,
}

impl TemporalDenoisedScaler {
    pub fn new(
        device: &ProtocolObject<dyn MTLDevice>,
        compiler: &ProtocolObject<dyn MTL4Compiler>,
        format: MTLPixelFormat,
        depth_format: MTLPixelFormat,
        width: usize,
        height: usize,
    ) -> Option<Self> {
        if !supports_temporal_denoised_scaler(device) {
            return None;
        }
        let descriptor = unsafe { MTLFXTemporalDenoisedScalerDescriptor::new() };
        unsafe { descriptor.setColorTextureFormat(format) };
        unsafe { descriptor.setDepthTextureFormat(depth_format) };
        unsafe { descriptor.setMotionTextureFormat(MTLPixelFormat::RG16Float) };
        unsafe { descriptor.setOutputTextureFormat(format) };
        unsafe { descriptor.setInputWidth(width) };
        unsafe { descriptor.setInputHeight(height) };
        unsafe { descriptor.setOutputWidth(width) };
        unsafe { descriptor.setOutputHeight(height) };
        let scaler =
            unsafe { descriptor.newTemporalDenoisedScalerWithDevice_compiler(device, compiler) }?;
        let shape = TextureShape {
            width,
            height,
            layers: 1,
            mips: 1,
            cube: false,
        };
        let usage = MTLTextureUsage::ShaderRead | MTLTextureUsage::ShaderWrite;
        let output_descriptor = texture_descriptor(format, &shape, usage)?;
        let motion_descriptor = texture_descriptor(MTLPixelFormat::RG16Float, &shape, usage)?;
        let output = device.newTextureWithDescriptor(&output_descriptor)?;
        let motion = device.newTextureWithDescriptor(&motion_descriptor)?;
        Some(Self {
            scaler,
            output,
            motion,
        })
    }

    pub fn output(&self) -> &ProtocolObject<dyn MTLTexture> {
        &self.output
    }

    pub fn motion(&self) -> &ProtocolObject<dyn MTLTexture> {
        &self.motion
    }

    pub fn encode(
        &self,
        command_buffer: &ProtocolObject<dyn MTL4CommandBuffer>,
        color: &ProtocolObject<dyn MTLTexture>,
        depth: &ProtocolObject<dyn MTLTexture>,
    ) {
        unsafe { self.scaler.setColorTexture(Some(color)) };
        unsafe { self.scaler.setDepthTexture(Some(depth)) };
        unsafe { self.scaler.setMotionTexture(Some(&self.motion)) };
        unsafe { self.scaler.setOutputTexture(Some(&self.output)) };
        unsafe { self.scaler.setDepthReversed(true) };
        unsafe { self.scaler.encodeToCommandBuffer(command_buffer) };
    }
}

pub fn pass_color(
    pass: &MTL4RenderPassDescriptor,
    index: usize,
) -> Option<Retained<MTLRenderPassColorAttachmentDescriptor>> {
    (index < MAX_COLOR_ATTACHMENTS)
        .then(|| unsafe { pass.colorAttachments().objectAtIndexedSubscript(index) })
}

pub fn pipeline_color(
    pipeline: &MTL4RenderPipelineDescriptor,
    index: usize,
) -> Option<Retained<MTL4RenderPipelineColorAttachmentDescriptor>> {
    (index < MAX_COLOR_ATTACHMENTS)
        .then(|| unsafe { pipeline.colorAttachments().objectAtIndexedSubscript(index) })
}

pub fn mesh_pipeline_color(
    pipeline: &MTL4MeshRenderPipelineDescriptor,
    index: usize,
) -> Option<Retained<MTL4RenderPipelineColorAttachmentDescriptor>> {
    (index < MAX_COLOR_ATTACHMENTS)
        .then(|| unsafe { pipeline.colorAttachments().objectAtIndexedSubscript(index) })
}

pub fn tile_pipeline_color(
    pipeline: &MTL4TileRenderPipelineDescriptor,
    index: usize,
) -> Option<Retained<objc2_metal::MTLTileRenderPipelineColorAttachmentDescriptor>> {
    (index < MAX_COLOR_ATTACHMENTS)
        .then(|| unsafe { pipeline.colorAttachments().objectAtIndexedSubscript(index) })
}

pub fn vertex_layout(
    descriptor: &MTLVertexDescriptor,
    buffer_index: usize,
    stride: usize,
) -> Option<Retained<MTLVertexBufferLayoutDescriptor>> {
    if buffer_index >= MAX_BUFFER_BINDINGS || stride == 0 || !stride.is_multiple_of(4) {
        return None;
    }
    let layout = unsafe { descriptor.layouts().objectAtIndexedSubscript(buffer_index) };
    unsafe { layout.setStride(stride) };
    Some(layout)
}

pub fn set_step_rate(layout: &MTLVertexBufferLayoutDescriptor, rate: usize) {
    if rate > 0 {
        unsafe { layout.setStepRate(rate) };
    }
}

pub fn vertex_attribute(
    descriptor: &MTLVertexDescriptor,
    location: usize,
    offset: usize,
    buffer_index: usize,
) -> Option<Retained<MTLVertexAttributeDescriptor>> {
    if location >= MAX_VERTEX_ATTRIBUTES
        || buffer_index >= MAX_BUFFER_BINDINGS
        || !offset.is_multiple_of(4)
    {
        return None;
    }
    let attribute = unsafe { descriptor.attributes().objectAtIndexedSubscript(location) };
    unsafe { attribute.setOffset(offset) };
    unsafe { attribute.setBufferIndex(buffer_index) };
    Some(attribute)
}

pub fn bind_address(table: &ProtocolObject<dyn MTL4ArgumentTable>, index: usize, address: u64) {
    if index < MAX_BUFFER_BINDINGS {
        unsafe { table.setAddress_atIndex(address, index) };
    }
}

pub fn bind_texture(
    table: &ProtocolObject<dyn MTL4ArgumentTable>,
    index: usize,
    id: MTLResourceID,
) {
    if index < MAX_TEXTURE_BINDINGS {
        unsafe { table.setTexture_atIndex(id, index) };
    }
}

pub fn bind_sampler(
    table: &ProtocolObject<dyn MTL4ArgumentTable>,
    index: usize,
    id: MTLResourceID,
) {
    if index < MAX_TEXTURE_BINDINGS {
        unsafe { table.setSamplerState_atIndex(id, index) };
    }
}

pub fn dispatch_threadgroups_indirect(
    encoder: &ProtocolObject<dyn MTL4ComputeCommandEncoder>,
    address: u64,
    threads_per_threadgroup: MTLSize,
) {
    if address.is_multiple_of(4) {
        unsafe {
            encoder.dispatchThreadgroupsWithIndirectBuffer_threadsPerThreadgroup(
                address,
                threads_per_threadgroup,
            )
        };
    }
}

pub fn draw(
    encoder: &ProtocolObject<dyn MTL4RenderCommandEncoder>,
    primitive: MTLPrimitiveType,
    first: usize,
    count: usize,
    instances: usize,
    base_instance: usize,
) {
    if count > 0 && instances > 0 {
        unsafe {
            encoder.drawPrimitives_vertexStart_vertexCount_instanceCount_baseInstance(
                primitive,
                first,
                count,
                instances,
                base_instance,
            )
        };
    }
}

pub struct IndexRange<'a> {
    pub buffer: &'a ProtocolObject<dyn MTLBuffer>,
    pub kind: MTLIndexType,
    pub first: usize,
    pub count: usize,
}

pub fn draw_mesh_calibration(
    encoder: &ProtocolObject<dyn MTL4RenderCommandEncoder>,
    threads: u32,
    object_threads: u32,
    mesh_threads: u32,
) {
    if threads == 0 || object_threads == 0 || mesh_threads == 0 {
        return;
    }
    let threads_per_grid = MTLSize {
        width: threads as usize,
        height: 1,
        depth: 1,
    };
    let threads_per_object_threadgroup = MTLSize {
        width: object_threads as usize,
        height: 1,
        depth: 1,
    };
    let threads_per_mesh_threadgroup = MTLSize {
        width: mesh_threads as usize,
        height: 1,
        depth: 1,
    };
    encoder.drawMeshThreads_threadsPerObjectThreadgroup_threadsPerMeshThreadgroup(
        threads_per_grid,
        threads_per_object_threadgroup,
        threads_per_mesh_threadgroup,
    );
}

pub struct MeshArguments {
    pub grid: [u32; 3],
    pub object_group: [u32; 3],
    pub mesh_group: [u32; 3],
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
}

pub fn draw_mesh(
    encoder: &ProtocolObject<dyn MTL4RenderCommandEncoder>,
    arguments: &MeshArguments,
) {
    if !mesh_arguments_valid(arguments) {
        return;
    }
    let threads_per_grid = MTLSize {
        width: arguments.grid[0] as usize,
        height: arguments.grid[1] as usize,
        depth: arguments.grid[2] as usize,
    };
    let threads_per_object_threadgroup = MTLSize {
        width: arguments.object_group[0] as usize,
        height: arguments.object_group[1] as usize,
        depth: arguments.object_group[2] as usize,
    };
    let threads_per_mesh_threadgroup = MTLSize {
        width: arguments.mesh_group[0] as usize,
        height: arguments.mesh_group[1] as usize,
        depth: arguments.mesh_group[2] as usize,
    };
    encoder.drawMeshThreads_threadsPerObjectThreadgroup_threadsPerMeshThreadgroup(
        threads_per_grid,
        threads_per_object_threadgroup,
        threads_per_mesh_threadgroup,
    );
}

fn mesh_arguments_valid(arguments: &MeshArguments) -> bool {
    const MAX_DIMENSION: u32 = 65_535;
    const MAX_THREADS: u32 = 1_024;
    const MAX_PAYLOAD: u32 = 16_384;
    if arguments.resource_address == 0
        || !arguments.resource_address.is_multiple_of(4)
        || arguments.resource_length == 0
        || arguments.payload_bytes == 0
        || arguments.payload_bytes > MAX_PAYLOAD
        || arguments.vertex_count == 0
        || arguments.vertex_count > 256
        || arguments.primitive_count == 0
        || arguments.primitive_count > 512
        || arguments.vertex_bytes == 0
        || arguments.primitive_bytes == 0
        || !arguments.resource_offset.is_multiple_of(4)
        || !arguments.payload_offset.is_multiple_of(4)
        || !arguments.vertex_offset.is_multiple_of(4)
        || !arguments.primitive_offset.is_multiple_of(4)
    {
        return false;
    }
    let dimensions_valid = arguments
        .grid
        .into_iter()
        .chain(arguments.object_group)
        .chain(arguments.mesh_group)
        .all(|value| value != 0 && value <= MAX_DIMENSION);
    let object_threads = threadgroup_threads(arguments.object_group);
    let mesh_threads = threadgroup_threads(arguments.mesh_group);
    dimensions_valid
        && object_threads.is_some_and(|value| value <= MAX_THREADS)
        && mesh_threads.is_some_and(|value| value <= MAX_THREADS)
        && arguments
            .resource_address
            .checked_add(arguments.resource_offset)
            .is_some()
        && mesh_range(
            arguments.resource_offset,
            arguments.resource_length,
            u64::MAX,
        )
        && mesh_range(
            arguments.payload_offset,
            u64::from(arguments.payload_bytes),
            arguments.resource_length,
        )
        && mesh_range(
            arguments.vertex_offset,
            arguments.vertex_bytes,
            arguments.resource_length,
        )
        && mesh_range(
            arguments.primitive_offset,
            arguments.primitive_bytes,
            arguments.resource_length,
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

pub fn draw_indexed(
    encoder: &ProtocolObject<dyn MTL4RenderCommandEncoder>,
    primitive: MTLPrimitiveType,
    indices: IndexRange,
    instances: usize,
    base_vertex: isize,
    base_instance: usize,
) {
    let size = if indices.kind == MTLIndexType::UInt32 {
        4
    } else {
        2
    };
    let offset = indices.first * size;
    let length = indices.buffer.length();
    if indices.count == 0 || instances == 0 || offset + indices.count * size > length {
        return;
    }
    unsafe {
        encoder.drawIndexedPrimitives_indexCount_indexType_indexBuffer_indexBufferLength_instanceCount_baseVertex_baseInstance(
            primitive,
            indices.count,
            indices.kind,
            indices.buffer.gpuAddress() + offset as u64,
            length - offset,
            instances,
            base_vertex,
            base_instance,
        )
    };
}

pub fn draw_indexed_indirect(
    encoder: &ProtocolObject<dyn MTL4RenderCommandEncoder>,
    primitive: MTLPrimitiveType,
    kind: MTLIndexType,
    indices: &ProtocolObject<dyn MTLBuffer>,
    arguments: &ProtocolObject<dyn MTLBuffer>,
    offset: usize,
) {
    draw_indexed_indirect_at(encoder, primitive, kind, indices, 0, arguments, offset);
}

pub fn draw_indexed_indirect_at(
    encoder: &ProtocolObject<dyn MTL4RenderCommandEncoder>,
    primitive: MTLPrimitiveType,
    kind: MTLIndexType,
    indices: &ProtocolObject<dyn MTLBuffer>,
    index_offset: usize,
    arguments: &ProtocolObject<dyn MTLBuffer>,
    argument_offset: usize,
) {
    let index_size = if kind == MTLIndexType::UInt32 { 4 } else { 2 };
    let Some(index_end) = index_offset.checked_add(index_size) else {
        return;
    };
    let Some(argument_end) = argument_offset.checked_add(20) else {
        return;
    };
    if !index_offset.is_multiple_of(index_size)
        || !argument_offset.is_multiple_of(4)
        || index_end > indices.length()
        || argument_end > arguments.length()
    {
        return;
    }
    unsafe {
        encoder.drawIndexedPrimitives_indexType_indexBuffer_indexBufferLength_indirectBuffer(
            primitive,
            kind,
            indices.gpuAddress() + index_offset as u64,
            indices.length() - index_offset,
            arguments.gpuAddress() + argument_offset as u64,
        )
    };
}

pub fn add_presented_handler<F>(
    drawable: &ProtocolObject<dyn objc2_quartz_core::CAMetalDrawable>,
    submitted_frame: u64,
    on_presented: F,
) where
    F: Fn(u64, u64, u64) + 'static,
{
    let handler = RcBlock::new(
        move |drawable: NonNull<ProtocolObject<dyn objc2_metal::MTLDrawable>>| {
            let drawable = unsafe { drawable.as_ref() };
            let timestamp = drawable.presentedTime();
            if !timestamp.is_finite() || timestamp <= 0.0 {
                return;
            }
            let nanos = timestamp * 1_000_000_000.0;
            if nanos > u64::MAX as f64 {
                return;
            }
            on_presented(submitted_frame, nanos as u64, drawable.drawableID() as u64);
        },
    );
    unsafe { drawable.addPresentedHandler(RcBlock::as_ptr(&handler)) };
}

pub struct CommitFeedback {
    pub frame: u64,
    pub start_ns: u64,
    pub end_ns: u64,
    pub valid: bool,
}

pub fn commit(
    queue: &ProtocolObject<dyn MTL4CommandQueue>,
    cmd: &ProtocolObject<dyn MTL4CommandBuffer>,
    frame: u64,
    feedback_sender: SyncSender<CommitFeedback>,
    on_gpu_seconds: fn(f64, f64),
) {
    let options = MTL4CommitOptions::new();
    let handler = RcBlock::new(
        move |feedback: NonNull<ProtocolObject<dyn MTL4CommitFeedback>>| {
            let feedback = unsafe { feedback.as_ref() };
            let start = feedback.GPUStartTime();
            let end = feedback.GPUEndTime();
            on_gpu_seconds(start, end);
            let valid = start.is_finite() && end.is_finite() && start >= 0.0 && end > start;
            let start_ns = if valid && start <= u64::MAX as f64 / 1_000_000_000.0 {
                (start * 1_000_000_000.0) as u64
            } else {
                0
            };
            let end_ns = if valid && end <= u64::MAX as f64 / 1_000_000_000.0 {
                (end * 1_000_000_000.0) as u64
            } else {
                0
            };
            let _ = feedback_sender.send(CommitFeedback {
                frame,
                start_ns,
                end_ns,
                valid: valid && end_ns > start_ns,
            });
        },
    );
    unsafe { options.addFeedbackHandler(RcBlock::as_ptr(&handler)) };
    let mut list = [NonNull::from(cmd)];
    let list = NonNull::from(&mut list).cast();
    unsafe { queue.commit_count_options(list, 1, &options) };
}

pub fn copy_buffer(
    encoder: &ProtocolObject<dyn MTL4ComputeCommandEncoder>,
    src: &ProtocolObject<dyn MTLBuffer>,
    src_offset: usize,
    dst: &ProtocolObject<dyn MTLBuffer>,
    dst_offset: usize,
    size: usize,
) {
    if size == 0 || src_offset + size > src.length() || dst_offset + size > dst.length() {
        return;
    }
    unsafe {
        encoder.copyFromBuffer_sourceOffset_toBuffer_destinationOffset_size(
            src, src_offset, dst, dst_offset, size,
        )
    };
}

pub struct Region {
    pub texture_slice: usize,
    pub mip: usize,
    pub x: usize,
    pub y: usize,
    pub width: usize,
    pub height: usize,
}

impl Region {
    fn fits(&self, texture: &ProtocolObject<dyn MTLTexture>) -> bool {
        let w = (texture.width() >> self.mip).max(1);
        let h = (texture.height() >> self.mip).max(1);
        self.mip < texture.mipmapLevelCount()
            && self.width > 0
            && self.height > 0
            && self.x + self.width <= w
            && self.y + self.height <= h
    }

    fn origin(&self) -> MTLOrigin {
        MTLOrigin {
            x: self.x,
            y: self.y,
            z: 0,
        }
    }

    fn size(&self) -> MTLSize {
        MTLSize {
            width: self.width,
            height: self.height,
            depth: 1,
        }
    }
}

pub fn copy_buffer_to_texture(
    encoder: &ProtocolObject<dyn MTL4ComputeCommandEncoder>,
    src: &ProtocolObject<dyn MTLBuffer>,
    offset: usize,
    bytes_per_row: usize,
    dst: &ProtocolObject<dyn MTLTexture>,
    region: &Region,
) {
    let image = bytes_per_row * region.height;
    if !region.fits(dst) || offset + image > src.length() {
        return;
    }
    unsafe {
        encoder.copyFromBuffer_sourceOffset_sourceBytesPerRow_sourceBytesPerImage_sourceSize_toTexture_destinationSlice_destinationLevel_destinationOrigin(
            src,
            offset,
            bytes_per_row,
            image,
            region.size(),
            dst,
            region.texture_slice,
            region.mip,
            region.origin(),
        )
    };
}

pub fn copy_texture_to_buffer(
    encoder: &ProtocolObject<dyn MTL4ComputeCommandEncoder>,
    src: &ProtocolObject<dyn MTLTexture>,
    region: &Region,
    dst: &ProtocolObject<dyn MTLBuffer>,
    offset: usize,
    bytes_per_row: usize,
) {
    let image = bytes_per_row * region.height;
    if !region.fits(src) || offset + image > dst.length() {
        return;
    }
    unsafe {
        encoder.copyFromTexture_sourceSlice_sourceLevel_sourceOrigin_sourceSize_toBuffer_destinationOffset_destinationBytesPerRow_destinationBytesPerImage(
            src,
            region.texture_slice,
            region.mip,
            region.origin(),
            region.size(),
            dst,
            offset,
            bytes_per_row,
            image,
        )
    };
}

pub fn copy_texture_to_texture(
    encoder: &ProtocolObject<dyn MTL4ComputeCommandEncoder>,
    src: &ProtocolObject<dyn MTLTexture>,
    from: &Region,
    dst: &ProtocolObject<dyn MTLTexture>,
    to: &Region,
) {
    if !from.fits(src) || !to.fits(dst) || from.width != to.width || from.height != to.height {
        return;
    }
    unsafe {
        encoder.copyFromTexture_sourceSlice_sourceLevel_sourceOrigin_sourceSize_toTexture_destinationSlice_destinationLevel_destinationOrigin(
            src,
            from.texture_slice,
            from.mip,
            from.origin(),
            from.size(),
            dst,
            to.texture_slice,
            to.mip,
            to.origin(),
        )
    };
}

pub fn generate_mipmaps(
    encoder: &ProtocolObject<dyn MTL4ComputeCommandEncoder>,
    texture: &ProtocolObject<dyn MTLTexture>,
    base_mip: usize,
    mip_levels: usize,
) {
    let format = texture.pixelFormat().0;
    let depth = matches!(format, 250 | 252 | 253 | 260);
    let count = texture.mipmapLevelCount();
    if depth || base_mip != 0 || mip_levels != count || count <= 1 {
        return;
    }
    unsafe { encoder.generateMipmapsForTexture(texture) };
}

pub type CounterHeap = Retained<ProtocolObject<dyn MTL4CounterHeap>>;

pub const MAX_TIMESTAMPS: usize = 4096;

pub fn timestamp_heap(device: &ProtocolObject<dyn MTLDevice>, count: usize) -> Option<CounterHeap> {
    if count == 0 || count > MAX_TIMESTAMPS {
        return None;
    }
    let descriptor = MTL4CounterHeapDescriptor::new();
    descriptor.setType(MTL4CounterHeapType::Timestamp);
    unsafe { descriptor.setCount(count) };
    device.newCounterHeapWithDescriptor_error(&descriptor).ok()
}

pub fn write_timestamp(
    cmd: &ProtocolObject<dyn MTL4CommandBuffer>,
    heap: &ProtocolObject<dyn MTL4CounterHeap>,
    index: usize,
) {
    if index < heap.count() {
        unsafe { cmd.writeTimestampIntoHeap_atIndex(heap, index) };
    }
}

pub fn resolve_timestamps(
    heap: &ProtocolObject<dyn MTL4CounterHeap>,
    first: usize,
    out: &mut [u64],
) -> bool {
    let count = out.len();
    if count == 0 || first.saturating_add(count) > heap.count() {
        return false;
    }
    let range = objc2_foundation::NSRange::new(first, count);
    let Some(data) = (unsafe { heap.resolveCounterRange(range) }) else {
        return false;
    };
    let bytes = data.to_vec();
    if bytes.len() < count * 8 {
        return false;
    }
    for (value, chunk) in out.iter_mut().zip(bytes.as_chunks::<8>().0) {
        *value = u64::from_ne_bytes(*chunk);
    }
    true
}

pub fn write_shared(buffer: &ProtocolObject<dyn MTLBuffer>, offset: usize, bytes: &[u8]) -> bool {
    if offset + bytes.len() > buffer.length() {
        return false;
    }
    let contents = unsafe {
        std::slice::from_raw_parts_mut(buffer.contents().cast::<u8>().as_ptr(), buffer.length())
    };
    contents
        .get_mut(offset..offset + bytes.len())
        .map(|dst| dst.copy_from_slice(bytes))
        .is_some()
}

pub fn read_shared_u32(buffer: &ProtocolObject<dyn MTLBuffer>, offset: usize) -> Option<u32> {
    if !offset.is_multiple_of(4) {
        return None;
    }
    let end = offset.checked_add(4)?;
    if end > buffer.length() {
        return None;
    }
    let contents = buffer.contents().cast::<u8>().as_ptr();
    if contents.is_null() {
        return None;
    }
    let pointer = contents.wrapping_add(offset).cast::<u32>();
    if pointer.align_offset(4) != 0 {
        return None;
    }
    Some(unsafe { pointer.read() })
}

pub fn copy_shared(
    source: &ProtocolObject<dyn MTLBuffer>,
    source_offset: usize,
    target: &ProtocolObject<dyn MTLBuffer>,
    target_offset: usize,
    length: usize,
) -> bool {
    let Some(source_end) = source_offset.checked_add(length) else {
        return false;
    };
    let Some(target_end) = target_offset.checked_add(length) else {
        return false;
    };
    if source_end > source.length() || target_end > target.length() {
        return false;
    }
    let source_contents = source.contents().cast::<u8>().as_ptr();
    let target_contents = target.contents().cast::<u8>().as_ptr();
    if source_contents.is_null() || target_contents.is_null() {
        return false;
    }
    let source_pointer = source_contents.wrapping_add(source_offset);
    let target_pointer = target_contents.wrapping_add(target_offset);
    unsafe {
        std::ptr::copy_nonoverlapping(source_pointer, target_pointer, length);
    }
    true
}

pub struct TextureShape {
    pub width: usize,
    pub height: usize,
    pub layers: usize,
    pub mips: usize,
    pub cube: bool,
}

pub const MAX_TEXTURE_SIZE: usize = 16384;

pub fn texture_descriptor(
    format: objc2_metal::MTLPixelFormat,
    shape: &TextureShape,
    usage: objc2_metal::MTLTextureUsage,
) -> Option<Retained<objc2_metal::MTLTextureDescriptor>> {
    let max_mips = (usize::BITS - shape.width.max(shape.height).max(1).leading_zeros()) as usize;
    let valid = (1..=MAX_TEXTURE_SIZE).contains(&shape.width)
        && (1..=MAX_TEXTURE_SIZE).contains(&shape.height)
        && (1..=max_mips).contains(&shape.mips)
        && (shape.cube || shape.layers == 1);
    if !valid {
        return None;
    }
    let descriptor = objc2_metal::MTLTextureDescriptor::new();
    descriptor.setTextureType(if shape.cube {
        objc2_metal::MTLTextureType::TypeCube
    } else {
        objc2_metal::MTLTextureType::Type2D
    });
    descriptor.setPixelFormat(format);
    unsafe { descriptor.setWidth(shape.width) };
    unsafe { descriptor.setHeight(shape.height) };
    unsafe { descriptor.setMipmapLevelCount(shape.mips) };
    descriptor.setStorageMode(objc2_metal::MTLStorageMode::Private);
    descriptor.setUsage(usage);
    Some(descriptor)
}

pub fn texture_view(
    texture: &ProtocolObject<dyn MTLTexture>,
    base_mip: usize,
    mips: usize,
) -> Option<Texture> {
    if mips == 0 || base_mip + mips > texture.mipmapLevelCount() {
        return None;
    }
    let slices = if texture.textureType() == objc2_metal::MTLTextureType::TypeCube {
        6
    } else {
        1
    };
    unsafe {
        texture.newTextureViewWithPixelFormat_textureType_levels_slices(
            texture.pixelFormat(),
            texture.textureType(),
            objc2_foundation::NSRange::new(base_mip, mips),
            objc2_foundation::NSRange::new(0, slices),
        )
    }
}

pub fn texel_view(
    buffer: &ProtocolObject<dyn MTLBuffer>,
    format: objc2_metal::MTLPixelFormat,
    texel_size: usize,
) -> Option<Texture> {
    let width = buffer.length() / texel_size;
    if width == 0 {
        return None;
    }
    let descriptor = unsafe {
        objc2_metal::MTLTextureDescriptor::textureBufferDescriptorWithPixelFormat_width_resourceOptions_usage(
            format,
            width,
            buffer.resourceOptions(),
            objc2_metal::MTLTextureUsage::ShaderRead,
        )
    };
    buffer.newTextureWithDescriptor_offset_bytesPerRow(&descriptor, 0, width * texel_size)
}

pub struct MotionRequest<'a> {
    pub encoder: &'a ProtocolObject<dyn MTL4ComputeCommandEncoder>,
    pub table: &'a ProtocolObject<dyn MTL4ArgumentTable>,
    pub pipeline: &'a ProtocolObject<dyn MTLComputePipelineState>,
    pub depth: &'a ProtocolObject<dyn MTLTexture>,
    pub motion: &'a ProtocolObject<dyn MTLTexture>,
    pub current: u64,
    pub previous: u64,
    pub width: usize,
    pub height: usize,
}

pub fn encode_motion(request: MotionRequest<'_>) {
    let MotionRequest {
        encoder,
        table,
        pipeline,
        depth,
        motion,
        current,
        previous,
        width,
        height,
    } = request;
    encoder.setComputePipelineState(pipeline);
    bind_texture(table, 0, depth.gpuResourceID());
    bind_texture(table, 1, motion.gpuResourceID());
    bind_address(table, 0, current);
    bind_address(table, 1, previous);
    encoder.setArgumentTable(Some(table));
    encoder.dispatchThreads_threadsPerThreadgroup(
        MTLSize {
            width,
            height,
            depth: 1,
        },
        MTLSize {
            width: 8,
            height: 8,
            depth: 1,
        },
    );
    encoder.endEncoding();
}

pub struct Fx {
    mode: FxMode,
    input: (usize, usize),
    output: (usize, usize),
    spatial: Option<Retained<ProtocolObject<dyn MTL4FXSpatialScaler>>>,
    temporal: Option<Retained<ProtocolObject<dyn MTL4FXTemporalScaler>>>,
    frame_interpolator: Option<Retained<ProtocolObject<dyn MTL4FXFrameInterpolator>>>,
    motion: Option<Texture>,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum FxMode {
    Spatial,
    Temporal,
    FrameInterpolation,
}

pub struct FrameInterpolationRequest<'a> {
    pub command: &'a ProtocolObject<dyn MTL4CommandBuffer>,
    pub color: &'a ProtocolObject<dyn MTLTexture>,
    pub previous_color: Option<&'a ProtocolObject<dyn MTLTexture>>,
    pub depth: &'a ProtocolObject<dyn MTLTexture>,
    pub motion: &'a ProtocolObject<dyn MTLTexture>,
    pub ui: Option<&'a ProtocolObject<dyn MTLTexture>>,
    pub output: &'a ProtocolObject<dyn MTLTexture>,
    pub delta_time: f32,
    pub camera: (f32, f32, f32, f32),
    pub jitter: (f32, f32),
    pub motion_scale: (f32, f32),
    pub reset: bool,
    pub depth_reversed: bool,
    pub ui_composited: bool,
}

impl Fx {
    pub fn supports_frame_interpolation(device: &ProtocolObject<dyn MTLDevice>) -> bool {
        unsafe { MTLFXFrameInterpolatorDescriptor::supportsMetal4FX(device) }
    }

    pub fn supports(device: &ProtocolObject<dyn MTLDevice>, mode: FxMode) -> bool {
        match mode {
            FxMode::Spatial => unsafe { MTLFXSpatialScalerDescriptor::supportsMetal4FX(device) },
            FxMode::Temporal => unsafe { MTLFXTemporalScalerDescriptor::supportsMetal4FX(device) },
            FxMode::FrameInterpolation => Self::supports_frame_interpolation(device),
        }
    }

    pub fn new(
        device: &ProtocolObject<dyn MTLDevice>,
        compiler: &ProtocolObject<dyn MTL4Compiler>,
        mode: FxMode,
        input: (usize, usize),
        output: (usize, usize),
    ) -> Option<Self> {
        if input.0 == 0 || input.1 == 0 || output.0 == 0 || output.1 == 0 {
            return None;
        }
        match mode {
            FxMode::Spatial => {
                let descriptor = unsafe { MTLFXSpatialScalerDescriptor::new() };
                unsafe {
                    descriptor.setColorTextureFormat(objc2_metal::MTLPixelFormat::RGBA8Unorm)
                };
                unsafe {
                    descriptor.setOutputTextureFormat(objc2_metal::MTLPixelFormat::RGBA8Unorm)
                };
                unsafe { descriptor.setInputWidth(input.0) };
                unsafe { descriptor.setInputHeight(input.1) };
                unsafe { descriptor.setOutputWidth(output.0) };
                unsafe { descriptor.setOutputHeight(output.1) };
                let spatial =
                    unsafe { descriptor.newSpatialScalerWithDevice_compiler(device, compiler) }?;
                Some(Self {
                    mode,
                    input,
                    output,
                    spatial: Some(spatial),
                    temporal: None,
                    frame_interpolator: None,
                    motion: None,
                })
            }
            FxMode::Temporal => {
                let descriptor = unsafe { MTLFXTemporalScalerDescriptor::new() };
                unsafe {
                    descriptor.setColorTextureFormat(objc2_metal::MTLPixelFormat::RGBA8Unorm)
                };
                unsafe {
                    descriptor.setDepthTextureFormat(objc2_metal::MTLPixelFormat::Depth32Float)
                };
                unsafe {
                    descriptor.setMotionTextureFormat(objc2_metal::MTLPixelFormat::RG16Float)
                };
                unsafe {
                    descriptor.setOutputTextureFormat(objc2_metal::MTLPixelFormat::RGBA8Unorm)
                };
                unsafe { descriptor.setInputWidth(input.0) };
                unsafe { descriptor.setInputHeight(input.1) };
                unsafe { descriptor.setOutputWidth(output.0) };
                unsafe { descriptor.setOutputHeight(output.1) };
                unsafe { descriptor.setAutoExposureEnabled(false) };
                unsafe { descriptor.setRequiresSynchronousInitialization(true) };
                let temporal =
                    unsafe { descriptor.newTemporalScalerWithDevice_compiler(device, compiler) }?;
                unsafe { temporal.setDepthReversed(true) };
                let shape = TextureShape {
                    width: input.0,
                    height: input.1,
                    layers: 1,
                    mips: 1,
                    cube: false,
                };
                let motion_descriptor = texture_descriptor(
                    objc2_metal::MTLPixelFormat::RG16Float,
                    &shape,
                    MTLTextureUsage::ShaderRead | MTLTextureUsage::ShaderWrite,
                )?;
                let motion = device.newTextureWithDescriptor(&motion_descriptor)?;
                Some(Self {
                    mode,
                    input,
                    output,
                    spatial: None,
                    temporal: Some(temporal),
                    frame_interpolator: None,
                    motion: Some(motion),
                })
            }
            FxMode::FrameInterpolation => {
                if !Self::supports_frame_interpolation(device) {
                    return None;
                }
                let descriptor = unsafe { MTLFXFrameInterpolatorDescriptor::new() };
                unsafe { descriptor.setColorTextureFormat(MTLPixelFormat::RGBA8Unorm) };
                unsafe { descriptor.setOutputTextureFormat(MTLPixelFormat::RGBA8Unorm) };
                unsafe { descriptor.setDepthTextureFormat(MTLPixelFormat::Depth32Float) };
                unsafe { descriptor.setMotionTextureFormat(MTLPixelFormat::RG16Float) };
                unsafe { descriptor.setUITextureFormat(MTLPixelFormat::RGBA8Unorm) };
                unsafe { descriptor.setInputWidth(input.0) };
                unsafe { descriptor.setInputHeight(input.1) };
                unsafe { descriptor.setOutputWidth(output.0) };
                unsafe { descriptor.setOutputHeight(output.1) };
                unsafe {
                    let _: () = objc2::msg_send![&*descriptor, setRequiresPrevColorTexture: true];
                }
                let frame_interpolator = unsafe {
                    descriptor.newFrameInterpolatorWithDevice_compiler(device, compiler)
                }?;
                Some(Self {
                    mode,
                    input,
                    output,
                    spatial: None,
                    temporal: None,
                    frame_interpolator: Some(frame_interpolator),
                    motion: None,
                })
            }
        }
    }

    pub fn matches(&self, mode: FxMode, input: (usize, usize), output: (usize, usize)) -> bool {
        self.mode == mode && self.input == input && self.output == output
    }

    pub fn motion(&self) -> Option<&ProtocolObject<dyn MTLTexture>> {
        self.motion.as_deref()
    }

    pub fn encode_spatial(
        &self,
        command: &ProtocolObject<dyn MTL4CommandBuffer>,
        color: &ProtocolObject<dyn MTLTexture>,
        output: &ProtocolObject<dyn MTLTexture>,
    ) -> bool {
        let Some(scaler) = self.spatial.as_ref() else {
            return false;
        };
        unsafe { scaler.setInputContentWidth(self.input.0) };
        unsafe { scaler.setInputContentHeight(self.input.1) };
        unsafe { scaler.setColorTexture(Some(color)) };
        unsafe { scaler.setOutputTexture(Some(output)) };
        unsafe { scaler.encodeToCommandBuffer(command) };
        true
    }

    pub fn encode_frame_interpolation(&self, request: FrameInterpolationRequest<'_>) -> bool {
        let FrameInterpolationRequest {
            command,
            color,
            previous_color,
            depth,
            motion,
            ui,
            output,
            delta_time,
            camera,
            jitter,
            motion_scale,
            reset,
            depth_reversed,
            ui_composited,
        } = request;
        let Some(interpolator) = self.frame_interpolator.as_ref() else {
            return false;
        };
        let color_usage = unsafe { interpolator.colorTextureUsage() };
        let output_usage = unsafe { interpolator.outputTextureUsage() };
        let depth_usage = unsafe { interpolator.depthTextureUsage() };
        let motion_usage = unsafe { interpolator.motionTextureUsage() };
        let ui_usage = unsafe { interpolator.uiTextureUsage() };
        if self.mode != FxMode::FrameInterpolation
            || output.storageMode() != MTLStorageMode::Private
            || !color.usage().contains(color_usage)
            || !output.usage().contains(output_usage)
            || !depth.usage().contains(depth_usage)
            || !motion.usage().contains(motion_usage)
            || color.pixelFormat() != MTLPixelFormat::RGBA8Unorm
            || output.pixelFormat() != MTLPixelFormat::RGBA8Unorm
            || depth.pixelFormat() != MTLPixelFormat::Depth32Float
            || motion.pixelFormat() != MTLPixelFormat::RG16Float
            || color.width() != self.input.0
            || color.height() != self.input.1
            || depth.width() != self.input.0
            || depth.height() != self.input.1
            || motion.width() != self.input.0
            || motion.height() != self.input.1
            || output.width() != self.output.0
            || output.height() != self.output.1
        {
            return false;
        }
        let Some(previous) = previous_color else {
            return false;
        };
        if previous.pixelFormat() != MTLPixelFormat::RGBA8Unorm
            || previous.width() != self.input.0
            || previous.height() != self.input.1
            || !previous.usage().contains(color_usage)
        {
            return false;
        }
        if let Some(ui_texture) = ui
            && (ui_texture.pixelFormat() != MTLPixelFormat::RGBA8Unorm
                || ui_texture.width() != self.output.0
                || ui_texture.height() != self.output.1
                || !ui_texture.usage().contains(ui_usage))
        {
            return false;
        }
        unsafe { interpolator.setColorTexture(Some(color)) };
        unsafe { interpolator.setPrevColorTexture(Some(previous)) };
        unsafe { interpolator.setDepthTexture(Some(depth)) };
        unsafe { interpolator.setMotionTexture(Some(motion)) };
        unsafe { interpolator.setUITexture(ui) };
        unsafe { interpolator.setOutputTexture(Some(output)) };
        unsafe { interpolator.setDeltaTime(delta_time) };
        unsafe { interpolator.setNearPlane(camera.0) };
        unsafe { interpolator.setFarPlane(camera.1) };
        unsafe { interpolator.setFieldOfView(camera.2) };
        unsafe { interpolator.setAspectRatio(camera.3) };
        unsafe { interpolator.setJitterOffsetX(jitter.0) };
        unsafe { interpolator.setJitterOffsetY(jitter.1) };
        unsafe { interpolator.setMotionVectorScaleX(motion_scale.0) };
        unsafe { interpolator.setMotionVectorScaleY(motion_scale.1) };
        unsafe { interpolator.setShouldResetHistory(reset) };
        unsafe { interpolator.setDepthReversed(depth_reversed) };
        unsafe { interpolator.setIsUITextureComposited(ui_composited) };
        unsafe { interpolator.encodeToCommandBuffer(command) };
        true
    }

    pub fn encode_temporal(
        &self,
        command: &ProtocolObject<dyn MTL4CommandBuffer>,
        color: &ProtocolObject<dyn MTLTexture>,
        depth: &ProtocolObject<dyn MTLTexture>,
        output: &ProtocolObject<dyn MTLTexture>,
        jitter: (f32, f32),
        reset: bool,
    ) -> bool {
        let Some(motion) = self.motion.as_ref() else {
            return false;
        };
        let Some(scaler) = self.temporal.as_ref() else {
            return false;
        };
        unsafe { scaler.setInputContentWidth(self.input.0) };
        unsafe { scaler.setInputContentHeight(self.input.1) };
        unsafe { scaler.setColorTexture(Some(color)) };
        unsafe { scaler.setDepthTexture(Some(depth)) };
        unsafe { scaler.setMotionTexture(Some(motion)) };
        unsafe { scaler.setOutputTexture(Some(output)) };
        unsafe { scaler.setJitterOffsetX(jitter.0) };
        unsafe { scaler.setJitterOffsetY(jitter.1) };
        unsafe { scaler.setMotionVectorScaleX(1.0) };
        unsafe { scaler.setMotionVectorScaleY(1.0) };
        unsafe { scaler.setReset(reset) };
        unsafe { scaler.encodeToCommandBuffer(command) };
        true
    }
}

#[cfg(test)]
mod process_tests {
    use super::{MachTimeValue, sum_thread_times, time_ns};

    fn time(seconds: i32, microseconds: i32) -> MachTimeValue {
        MachTimeValue {
            seconds,
            microseconds,
        }
    }

    #[test]
    fn empty_thread_snapshot_has_zero_time() {
        assert_eq!(sum_thread_times(&[]), Some(0));
    }

    #[test]
    fn invalid_time_is_an_error() {
        assert_eq!(time_ns(&time(-1, 0)), None);
        assert_eq!(time_ns(&time(1, 1_000_000)), None);
    }

    #[test]
    fn sums_all_threads_in_nanoseconds() {
        let values = [(time(1, 2), time(3, 4)), (time(0, 500), time(0, 600))];
        assert_eq!(sum_thread_times(&values), Some(4_001_106_000));
    }
}
