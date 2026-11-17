#ifndef MTLRENDER_H
#define MTLRENDER_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct MtlrDevice MtlrDevice;
typedef struct MtlrSurface MtlrSurface;
typedef struct MtlrMesherTable MtlrMesherTable;
typedef struct MtlrGlslResult MtlrGlslResult;

enum {
    MTLR_LOD_INPUT_MAGIC = 0x4c4f4431u,
    MTLR_LOD_INPUT_VERSION = 1u,
    MTLR_LOD_OUTPUT_MAGIC = 0x4c4d5331u,
    MTLR_LOD_OUTPUT_VERSION = 1u,
    MTLR_LOD_OK = 0u,
    MTLR_LOD_INVALID_INPUT = 1u,
    MTLR_LOD_OUTPUT_TOO_SMALL = 2u
};

typedef struct MtlrLodMeshResult {
    uint32_t magic;
    uint32_t version;
    uint32_t status;
    uint32_t reserved;
    uint64_t required_bytes;
    uint64_t written_bytes;
} MtlrLodMeshResult;

enum {
    MTLR_CALIBRATION_VERSION = 1u,
    MTLR_CALIBRATION_MODE_TWO_PHASE_HIZ = 1u,
    MTLR_CALIBRATION_MODE_SINGLE_PHASE_HIZ = 2u,
    MTLR_CALIBRATION_MODE_OFF = 4u,
    MTLR_CALIBRATION_DRAW_PATH_NON_INDEXED = 1u,
    MTLR_CALIBRATION_DRAW_PATH_INDEXED = 2u,
    MTLR_CALIBRATION_DRAW_PATH_MESH = 3u
};

typedef struct MtlrCalibration {
    uint32_t version;
    uint32_t mode;
    uint32_t draw_path;
    uint32_t indexed;
    uint32_t compact;
    uint32_t probe_interval;
    uint32_t lod_min_ring;
    uint32_t width;
    uint32_t height;
} MtlrCalibration;

enum {
    MTLR_EVAL_PRESENT_TIMESTAMP_VALID = 1u << 0,
    MTLR_EVAL_PRESENT_INTERVAL_VALID = 1u << 1,
    MTLR_EVAL_PRESENTED_DRAWABLE_ID_VALID = 1u << 2,
    MTLR_EVAL_PRESENTED_SEQUENCE_VALID = 1u << 3,
    MTLR_EVAL_GPU_BUSY_VALID = 1u << 4,
    MTLR_EVAL_GPU_BUSY_UNRESOLVED = 1u << 5,
    MTLR_EVAL_GPU_FEEDBACK_VALID = 1u << 6,
    MTLR_EVAL_GPU_ENERGY_VALID = 1u << 7,
    MTLR_EVAL_CPU_PROCESS_VALID = 1u << 8,
    MTLR_EVAL_CPU_THREAD_COUNT_VALID = 1u << 9,
    MTLR_EVAL_PHYS_FOOTPRINT_VALID = 1u << 10,
    MTLR_EVAL_METAL_ALLOCATED_SIZE_VALID = 1u << 11,
    MTLR_EVAL_GPU_ENERGY_UNAVAILABLE = 1u << 12,
    MTLR_EVAL_GPU_ENERGY_RESET = 1u << 13,
    MTLR_EVAL_CPU_PROCESS_UNAVAILABLE = 1u << 14,
    MTLR_EVAL_CPU_THREAD_COUNT_UNAVAILABLE = 1u << 15,
    MTLR_EVAL_PHYS_FOOTPRINT_UNAVAILABLE = 1u << 16,
    MTLR_EVAL_METAL_ALLOCATED_SIZE_UNAVAILABLE = 1u << 17
};

enum {
    MTLR_EVAL_ENERGY_SOURCE_IO_REPORT = 1u,
    MTLR_EVAL_ENERGY_SOURCE_POWERMETRICS = 2u,
    MTLR_EVAL_ENERGY_SOURCE_UNAVAILABLE = 3u
};

typedef struct MtlrEvalFrame {
    uint64_t submitted_frame;
    uint64_t present_timestamp_ns;
    uint64_t present_interval_ns;
    uint64_t presented_drawable_id;
    uint64_t presented_sequence;
    uint64_t gpu_busy_ns;
    uint64_t gpu_energy_nj;
    uint64_t cpu_process_ns;
    uint32_t cpu_thread_count;
    uint32_t cpu_reserved;
    uint64_t phys_footprint_bytes;
    uint64_t metal_allocated_size_bytes;
    uint32_t validity_flags;
    uint32_t reserved;
} MtlrEvalFrame;

struct MtlrCaps {
    uint32_t apple_family;
    uint32_t raytracing;
    uint32_t raytracing_from_render;
    uint64_t recommended_max_working_set;
    uint64_t max_buffer_length;
    uint32_t metalfx_spatial;
    uint32_t metalfx_temporal;
    uint32_t metalfx_frame_interpolation;
};

typedef struct MtlrCaps MtlrCaps;

typedef struct MtlrGlslSource {
    const char *path;
    const uint8_t *text;
    size_t text_len;
} MtlrGlslSource;

typedef struct MtlrGlslDefine {
    const char *name;
    const uint8_t *value;
    size_t value_len;
} MtlrGlslDefine;

typedef struct MtlrGlslOptions {
    uint32_t msl_major;
    uint32_t msl_minor;
    uint32_t max_include_depth;
    uint64_t max_source_bytes;
    uint32_t max_macro_expansion_depth;
    uint64_t max_macro_expanded_bytes;
    uint32_t max_parser_recursion_depth;
    uint32_t flags;
} MtlrGlslOptions;

typedef struct MtlrMesherSection {
    uint64_t input;
    uint64_t input_length;
    uint64_t output;
    uint64_t output_capacity;
} MtlrMesherSection;

struct MtlrFrameState {
    uint64_t submitted;
    uint64_t completed;
};

typedef struct MtlrFrameState MtlrFrameState;

enum {
    MTLR_COMMAND_PROTOCOL_VERSION = 1u,
    MTLR_MESH_DISPATCH_OPCODE = 28u,
    MTLR_MESH_DISPATCH_WORDS = 30u,
    MTLR_MESH_MAX_VERTICES = 256u,
    MTLR_MESH_MAX_PRIMITIVES = 512u,
    MTLR_MESH_MAX_THREADGROUP_DIMENSION = 65535u,
    MTLR_MESH_MAX_PAYLOAD_BYTES = 16384u
};

typedef struct MtlrMeshDispatchArgs {
    uint32_t object_threadgroups[3];
    uint32_t mesh_threadgroups[3];
    uint32_t payload_bytes;
    uint32_t vertex_count;
    uint32_t primitive_count;
    uint64_t resource_address;
    uint64_t resource_offset;
    uint64_t resource_length;
    uint64_t payload_offset;
    uint64_t vertex_offset;
    uint64_t vertex_bytes;
    uint64_t primitive_offset;
    uint64_t primitive_bytes;
    uint32_t dispatch_geometry[3];
    uint32_t reserved;
} MtlrMeshDispatchArgs;

MtlrDevice *mtlr_device_create(bool pass_timing);
MtlrDevice *mtlr_device_create_with_archive(bool pass_timing, const char *path, const uint8_t *key, size_t key_len);
uint32_t mtlr_pipeline_archive_status(MtlrDevice *device);
bool mtlr_pipeline_archive_flush(MtlrDevice *device);
int32_t mtlr_thermal_state(void);
bool mtlr_low_power_mode(void);
uint32_t mtlr_memory_pressure(void);
void mtlr_device_destroy(MtlrDevice *device);
size_t mtlr_device_name(MtlrDevice *device, char *out);
bool mtlr_device_caps(MtlrDevice *device, MtlrCaps *out);
size_t mtlr_last_error(MtlrDevice *device, char *out);
uint64_t mtlr_frame(MtlrDevice *device);
uint64_t mtlr_calibrate(MtlrDevice *device);
bool mtlr_calibrate_resolution(MtlrDevice *device, uint32_t width, uint32_t height, MtlrCalibration *out);
uint64_t mtlr_completed_frames(MtlrDevice *device);
bool mtlr_wait_frame(MtlrDevice *device, uint64_t frame, uint64_t timeout_ms);
bool mtlr_capture_next(MtlrDevice *device, const char *path);
uint64_t mtlr_gpu_memory_bytes(MtlrDevice *device);
uint64_t mtlr_native_memory_bytes(MtlrDevice *device);
uint64_t mtlr_metal_allocated_size(MtlrDevice *device);
uint64_t mtlr_phys_footprint_bytes(void);
bool mtlr_eval_start(MtlrDevice *device);
void mtlr_eval_stop(MtlrDevice *device);
uint32_t mtlr_eval_read(MtlrDevice *device, MtlrEvalFrame *out, uint32_t capacity);
size_t mtlr_eval_energy_source(char *out, size_t capacity);

MtlrSurface *mtlr_surface_create(MtlrDevice *device, void *ns_window);
void mtlr_surface_destroy(MtlrSurface *surface);
void mtlr_surface_configure(MtlrSurface *surface, uint32_t width, uint32_t height, uint32_t mode, uint32_t refresh);
bool mtlr_surface_visible(MtlrSurface *surface);

uint64_t mtlr_buffer_create(MtlrDevice *device, uint64_t size, const void *initial_data, uint64_t initial_len, void **contents);
uint64_t mtlr_buffer_address(MtlrDevice *device, uint64_t buffer);
uint64_t mtlr_texture_create(MtlrDevice *device, uint32_t format, uint32_t width, uint32_t height, uint32_t layers, uint32_t mips, bool cube, bool render_target, bool shader_write);
uint64_t mtlr_view_create(MtlrDevice *device, uint64_t texture, uint32_t base_mip, uint32_t mips);
uint64_t mtlr_sampler_create(MtlrDevice *device, const uint32_t *words);
uint64_t mtlr_pipeline_create(MtlrDevice *device, const char *vertex, const char *vertex_entry, const char *fragment, const char *fragment_entry, const uint32_t *desc, uint32_t desc_len);
uint64_t mtlr_pack_pipeline_create(MtlrDevice *device, const char *vertex, const char *vertex_entry, const char *fragment, const char *fragment_entry, const uint32_t *desc, uint32_t desc_len);
uint64_t mtlr_mesh_pipeline_create(MtlrDevice *device, const char *object, const char *object_entry, const char *mesh, const char *mesh_entry, const char *fragment, const char *fragment_entry, const uint32_t *desc, uint32_t desc_len);
uint64_t mtlr_compute_pipeline_create(MtlrDevice *device, const char *source, const char *entry, uint32_t threads_x, uint32_t threads_y, uint32_t threads_z);
uint64_t mtlr_query_pool_create(MtlrDevice *device, uint32_t count);
bool mtlr_query_resolve(MtlrDevice *device, uint64_t pool, uint32_t first, uint32_t count, uint64_t *out);
uint64_t mtlr_timestamp_frequency(MtlrDevice *device);
uint32_t mtlr_pass_times(MtlrDevice *device, uint64_t *out, uint32_t len);
void mtlr_destroy(MtlrDevice *device, uint32_t kind, uint64_t handle);

uint64_t mtlr_stat_nanos(uint32_t counter);

void mtlr_submit(MtlrDevice *device, const uint32_t *stream, uint64_t words, MtlrSurface *surface, MtlrFrameState *out);
bool mtlr_mesh_dispatch_validate(const MtlrMeshDispatchArgs *args);

MtlrGlslResult *mtlr_glsl_compile(uint32_t stage,
    const MtlrGlslSource *sources, size_t source_count,
    const MtlrGlslDefine *defines, size_t define_count,
    const MtlrGlslOptions *options);
bool mtlr_glsl_result_ok(const MtlrGlslResult *result);
size_t mtlr_glsl_result_msl(const MtlrGlslResult *result, uint8_t *out, size_t capacity);
size_t mtlr_glsl_result_reflection(const MtlrGlslResult *result, uint8_t *out, size_t capacity);
size_t mtlr_glsl_result_error(const MtlrGlslResult *result, uint8_t *out, size_t capacity);
void mtlr_glsl_result_destroy(MtlrGlslResult *result);

MtlrMesherTable *mtlr_mesher_table_create(void);
void mtlr_mesher_table_destroy(MtlrMesherTable *table);
bool mtlr_mesher_table_add_state(MtlrMesherTable *table, uint32_t state_id, uint32_t flags, uint64_t bytes, uint64_t length);
size_t mtlr_mesher_table_state_count(MtlrMesherTable *table);
size_t mtlr_mesher_table_quad_count(MtlrMesherTable *table);
size_t mtlr_mesher_table_max_output(MtlrMesherTable *table);
bool mtlr_mesher_mesh_block(uint64_t input, uint64_t length, uint64_t output);
size_t mtlr_mesher_mesh_section(MtlrMesherTable *table, uint64_t input, uint64_t length, uint64_t output, uint64_t capacity);
bool mtlr_mesher_mesh_sections(MtlrMesherTable *table, const MtlrMesherSection *items, size_t item_count, size_t *written);
MtlrLodMeshResult mtlr_lod_mesh(const uint8_t *input, size_t input_len, uint8_t *output, size_t output_capacity);
size_t mtlr_lod_merge(uint64_t input, uint64_t length, uint32_t color_shift, uint32_t light_shift, uint64_t output, uint64_t capacity, uint32_t *faces, uint32_t face_count);

#endif
