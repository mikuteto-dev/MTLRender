use objc2::runtime::ProtocolObject;
use objc2_metal::{
    MTL4CommandBuffer, MTL4CommandEncoder, MTL4ComputeCommandEncoder, MTL4VisibilityOptions,
    MTLBuffer, MTLDevice, MTLPixelFormat, MTLResourceOptions, MTLSize, MTLStages, MTLTexture,
    MTLTextureUsage,
};

use crate::command::{Command, ComputeAccess, CullRequest, FeatureCullRequest};

use crate::encode::{Scratch, push_constants, wait};
use crate::gpu::Gpu;
use crate::hazard::Hazards;
use crate::resources::Resources;
use crate::stats::{self, Counter};
use crate::timing::{self, PassTimer};
use mtl4::{self as metal, Buffer, Texture, TextureShape};

const GROUP_SIZE: usize = 256;
const GROUP_1D: MTLSize = MTLSize {
    width: GROUP_SIZE,
    height: 1,
    depth: 1,
};
const GROUP_2D: MTLSize = MTLSize {
    width: 8,
    height: 8,
    depth: 1,
};
const UNIT_GROUP: MTLSize = MTLSize {
    width: 1,
    height: 1,
    depth: 1,
};

pub(crate) struct Hiz {
    texture: Texture,
    levels: Vec<Texture>,
    width: usize,
    height: usize,
    level_width: usize,
    level_height: usize,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SnapshotValidity {
    Missing,
    Invalid,
    Recorded,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub struct SnapshotSource {
    pub root: usize,
    pub width: usize,
    pub height: usize,
    pub format: MTLPixelFormat,
    pub usage: u64,
    pub generation: u64,
}

struct Snapshot {
    texture: Texture,
    view: Texture,
    source: SnapshotSource,
    validity: SnapshotValidity,
}

pub(crate) struct CalibrationHiz {
    hiz: Option<Hiz>,
    snapshot: Option<Texture>,
    retired: Vec<(u64, Option<Hiz>, Option<Texture>)>,
}

enum Retired {
    Buffer(Buffer),
    Texture(Texture),
    Snapshot { texture: Texture, view: Texture },
}

#[derive(Clone, Copy, PartialEq)]
struct FeatureHistoryIdentity {
    clip: [f32; 16],
    previous_clip: [f32; 16],
    level: u64,
    width: usize,
    height: usize,
    capacity: usize,
}

struct FeatureHistoryInput {
    count: usize,
    level: u64,
    width: usize,
    height: usize,
    clip: [f32; 16],
    previous_clip: [f32; 16],
}

pub struct Culler {
    history_a: Buffer,
    history_b: Buffer,
    read_a: bool,
    marks: Buffer,
    visible: Buffer,
    groups: Buffer,
    totals_a: Buffer,
    totals_b: Buffer,
    units: usize,
    sections: usize,
    hiz_resource: Option<Hiz>,
    snapshot: Option<Snapshot>,
    snapshot_source: Option<SnapshotSource>,
    snapshot_generation: u64,
    snapshot_recorded: Option<bool>,
    snapshot_pending_source: Option<SnapshotSource>,
    snapshot_frame_source: Option<SnapshotSource>,
    single_phase_valid: bool,
    last_clip: Option<[f32; 16]>,
    next_clip: Option<[f32; 16]>,
    retired: Vec<(u64, Retired)>,
    swap: bool,
    pending: bool,
    feature_history: Buffer,
    feature_buffer_capacity: usize,
    feature_capacity: usize,
    feature_streak: Vec<u8>,
    feature_identity: Option<FeatureHistoryIdentity>,
    feature_last_clip: Option<[f32; 16]>,
    feature_next_clip: Option<[f32; 16]>,
}

fn size1(value: usize) -> MTLSize {
    MTLSize {
        width: value,
        height: 1,
        depth: 1,
    }
}

fn shared(gpu: &Gpu, resources: &mut Resources, size: usize) -> Option<Buffer> {
    let length = size.max(16);
    let buffer = gpu
        .device
        .newBufferWithLength_options(length, MTLResourceOptions::StorageModeShared)?;
    let zeros = vec![0u8; length];
    if !metal::write_shared(&buffer, 0, &zeros) {
        return None;
    }
    resources.track(ProtocolObject::from_ref(&*buffer));
    Some(buffer)
}

fn hiz_layout(width: usize, height: usize) -> (usize, usize, usize) {
    let level_width = width.div_ceil(2).max(1).next_power_of_two();
    let level_height = height.div_ceil(2).max(1).next_power_of_two();
    let longest = level_width.max(level_height);
    let count = longest.trailing_zeros() as usize + 1;
    (level_width, level_height, count)
}

fn hiz_resize_needed(current: Option<(usize, usize)>, width: usize, height: usize) -> bool {
    current != Some((width, height))
}

fn matrix_valid(matrix: &[f32; 16]) -> bool {
    matrix.iter().any(|value| *value != 0.0) && matrix.iter().all(|value| value.is_finite())
}

fn matrices_continuous(previous: &[f32; 16], last: Option<&[f32; 16]>) -> bool {
    matrix_valid(previous) && last.is_some_and(|expected| expected == previous)
}

fn snapshot_identity_matches(left: SnapshotSource, right: SnapshotSource) -> bool {
    left.root == right.root
        && left.width == right.width
        && left.height == right.height
        && left.format == right.format
        && left.usage == right.usage
}

pub(crate) fn snapshot_self_dependency(source_root: usize, destination_root: usize) -> bool {
    source_root == destination_root
}

fn snapshot_readable(
    validity: SnapshotValidity,
    source_matches: bool,
    matrices_match: bool,
) -> bool {
    validity == SnapshotValidity::Recorded && source_matches && matrices_match
}

fn snapshot_transition(
    current: SnapshotValidity,
    recorded: Option<bool>,
    source_matches: bool,
    matrices_match: bool,
) -> SnapshotValidity {
    match recorded {
        Some(true) if source_matches && matrices_match => SnapshotValidity::Recorded,
        Some(_) => SnapshotValidity::Invalid,
        None if snapshot_readable(current, source_matches, matrices_match) => current,
        None => current,
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FeatureVisibility {
    Visible,
    Outside,
    Inconclusive,
}

#[derive(Clone, Copy, PartialEq)]
pub struct FeatureProjection {
    pub min: [f32; 2],
    pub max: [f32; 2],
    pub nearest: f32,
}

fn clip_point(matrix: &[f32; 16], point: [f32; 3]) -> Option<[f32; 4]> {
    let x = point.first().copied()?;
    let y = point.get(1).copied()?;
    let z = point.get(2).copied()?;
    let get = |index: usize| matrix.get(index).copied();
    Some([
        get(0)? * x + get(4)? * y + get(8)? * z + get(12)?,
        get(1)? * x + get(5)? * y + get(9)? * z + get(13)?,
        get(2)? * x + get(6)? * y + get(10)? * z + get(14)?,
        get(3)? * x + get(7)? * y + get(11)? * z + get(15)?,
    ])
}

pub fn project_feature_aabb(
    aabb: [f32; 6],
    clip: &[f32; 16],
    expansion: f32,
) -> FeatureVisibilityProjection {
    if !expansion.is_finite() || expansion < 0.0 || !matrix_valid(clip) {
        return FeatureVisibilityProjection::Inconclusive;
    }
    if !aabb.iter().all(|value| value.is_finite()) {
        return FeatureVisibilityProjection::Inconclusive;
    }
    let Some(aabb_x0) = aabb.first().copied() else {
        return FeatureVisibilityProjection::Inconclusive;
    };
    let Some(aabb_y0) = aabb.get(1).copied() else {
        return FeatureVisibilityProjection::Inconclusive;
    };
    let Some(aabb_z0) = aabb.get(2).copied() else {
        return FeatureVisibilityProjection::Inconclusive;
    };
    let Some(aabb_x1) = aabb.get(3).copied() else {
        return FeatureVisibilityProjection::Inconclusive;
    };
    let Some(aabb_y1) = aabb.get(4).copied() else {
        return FeatureVisibilityProjection::Inconclusive;
    };
    let Some(aabb_z1) = aabb.get(5).copied() else {
        return FeatureVisibilityProjection::Inconclusive;
    };
    let lo = [
        aabb_x0 - expansion,
        aabb_y0 - expansion,
        aabb_z0 - expansion,
    ];
    let hi = [
        aabb_x1 + expansion,
        aabb_y1 + expansion,
        aabb_z1 + expansion,
    ];
    if lo.iter().zip(hi.iter()).any(|(low, high)| low > high) {
        return FeatureVisibilityProjection::Inconclusive;
    }
    if lo.iter().all(|value| *value <= 0.0) && hi.iter().all(|value| *value >= 0.0) {
        return FeatureVisibilityProjection::Inconclusive;
    }
    let mut min = [f32::INFINITY; 2];
    let mut max = [f32::NEG_INFINITY; 2];
    let mut nearest = f32::NEG_INFINITY;
    for corner in 0..8usize {
        let Some(lo_x) = lo.first().copied() else {
            return FeatureVisibilityProjection::Inconclusive;
        };
        let Some(lo_y) = lo.get(1).copied() else {
            return FeatureVisibilityProjection::Inconclusive;
        };
        let Some(lo_z) = lo.get(2).copied() else {
            return FeatureVisibilityProjection::Inconclusive;
        };
        let Some(hi_x) = hi.first().copied() else {
            return FeatureVisibilityProjection::Inconclusive;
        };
        let Some(hi_y) = hi.get(1).copied() else {
            return FeatureVisibilityProjection::Inconclusive;
        };
        let Some(hi_z) = hi.get(2).copied() else {
            return FeatureVisibilityProjection::Inconclusive;
        };
        let point = [
            if corner & 1 == 0 { lo_x } else { hi_x },
            if corner & 2 == 0 { lo_y } else { hi_y },
            if corner & 4 == 0 { lo_z } else { hi_z },
        ];
        let Some(clip_point) = clip_point(clip, point) else {
            return FeatureVisibilityProjection::Inconclusive;
        };
        let Some(w) = clip_point.get(3).copied() else {
            return FeatureVisibilityProjection::Inconclusive;
        };
        if !clip_point.iter().all(|value| value.is_finite()) || w <= 0.0 {
            return FeatureVisibilityProjection::Inconclusive;
        }
        let Some(x_clip) = clip_point.first().copied() else {
            return FeatureVisibilityProjection::Inconclusive;
        };
        let Some(y_clip) = clip_point.get(1).copied() else {
            return FeatureVisibilityProjection::Inconclusive;
        };
        let Some(z_clip) = clip_point.get(2).copied() else {
            return FeatureVisibilityProjection::Inconclusive;
        };
        let x = x_clip / w;
        let y = y_clip / w;
        let z = z_clip / w;
        if !x.is_finite() || !y.is_finite() || !z.is_finite() {
            return FeatureVisibilityProjection::Inconclusive;
        }
        if let Some(value) = min.first_mut() {
            *value = value.min(x);
        }
        if let Some(value) = min.get_mut(1) {
            *value = value.min(y);
        }
        if let Some(value) = max.first_mut() {
            *value = value.max(x);
        }
        if let Some(value) = max.get_mut(1) {
            *value = value.max(y);
        }
        nearest = nearest.max(z);
    }
    let Some(max_x) = max.first().copied() else {
        return FeatureVisibilityProjection::Inconclusive;
    };
    let Some(max_y) = max.get(1).copied() else {
        return FeatureVisibilityProjection::Inconclusive;
    };
    let Some(min_x) = min.first().copied() else {
        return FeatureVisibilityProjection::Inconclusive;
    };
    let Some(min_y) = min.get(1).copied() else {
        return FeatureVisibilityProjection::Inconclusive;
    };
    if max_x < -1.0 || min_x > 1.0 || max_y < -1.0 || min_y > 1.0 {
        FeatureVisibilityProjection::Outside
    } else {
        FeatureVisibilityProjection::Projected(FeatureProjection { min, max, nearest })
    }
}

pub enum FeatureVisibilityProjection {
    Projected(FeatureProjection),
    Outside,
    Inconclusive,
}

pub type FeatureHistoryKey = (u64, usize, usize, usize, [f32; 16], [f32; 16]);

pub fn feature_history_identity_changed(
    previous: Option<FeatureHistoryKey>,
    current: FeatureHistoryKey,
) -> bool {
    let (level, width, height, capacity, clip, previous_clip) = current;
    let Some((old_level, old_width, old_height, old_capacity, old_clip, old_previous_clip)) =
        previous
    else {
        return true;
    };
    !matrix_valid(&clip)
        || !matrix_valid(&previous_clip)
        || level != old_level
        || width != old_width
        || height != old_height
        || capacity != old_capacity
        || clip != old_clip
        || previous_clip != old_previous_clip
}

#[cfg(test)]
fn single_phase_reproject_visible(
    aabb: [f32; 6],
    current_clip: &[f32; 16],
    previous_clip: &[f32; 16],
    width: usize,
    height: usize,
    samples: &[f32],
) -> bool {
    if width == 0 || height == 0 || samples.len() < 5 {
        return true;
    }
    if !aabb.iter().all(|value| value.is_finite())
        || !matrix_valid(current_clip)
        || !matrix_valid(previous_clip)
    {
        return true;
    }
    let Some(lo_x) = aabb.first().copied() else {
        return true;
    };
    let Some(lo_y) = aabb.get(1).copied() else {
        return true;
    };
    let Some(lo_z) = aabb.get(2).copied() else {
        return true;
    };
    let Some(hi_x) = aabb.get(3).copied() else {
        return true;
    };
    let Some(hi_y) = aabb.get(4).copied() else {
        return true;
    };
    let Some(hi_z) = aabb.get(5).copied() else {
        return true;
    };
    let mut previous_min = [f32::INFINITY; 2];
    let mut previous_max = [f32::NEG_INFINITY; 2];
    let mut nearest: f32 = 0.0;
    for corner in 0..8usize {
        let point = [
            if corner & 1 == 0 { lo_x } else { hi_x },
            if corner & 2 == 0 { lo_y } else { hi_y },
            if corner & 4 == 0 { lo_z } else { hi_z },
        ];
        let Some(old_point) = clip_point(previous_clip, point) else {
            return true;
        };
        let Some(new_point) = clip_point(current_clip, point) else {
            return true;
        };
        let Some(old_w) = old_point.get(3).copied() else {
            return true;
        };
        let Some(new_w) = new_point.get(3).copied() else {
            return true;
        };
        if old_w <= 0.0 || new_w <= 0.0 {
            return true;
        }
        if !old_point.iter().all(|value| value.is_finite())
            || !new_point.iter().all(|value| value.is_finite())
        {
            return true;
        }
        let Some(old_x) = old_point.first().copied() else {
            return true;
        };
        let Some(old_y) = old_point.get(1).copied() else {
            return true;
        };
        let Some(new_z_value) = new_point.get(2).copied() else {
            return true;
        };
        let old_ndc = [old_x / old_w, old_y / old_w];
        let new_z = new_z_value / new_w;
        if !old_ndc.iter().all(|value| value.is_finite()) || !new_z.is_finite() {
            return true;
        }
        let Some(old_ndc_x) = old_ndc.first().copied() else {
            return true;
        };
        let Some(old_ndc_y) = old_ndc.get(1).copied() else {
            return true;
        };
        if let Some(value) = previous_min.first_mut() {
            *value = value.min(old_ndc_x);
        }
        if let Some(value) = previous_min.get_mut(1) {
            *value = value.min(old_ndc_y);
        }
        if let Some(value) = previous_max.first_mut() {
            *value = value.max(old_ndc_x);
        }
        if let Some(value) = previous_max.get_mut(1) {
            *value = value.max(old_ndc_y);
        }
        nearest = nearest.max(new_z);
    }
    let Some(previous_min_x) = previous_min.first().copied() else {
        return true;
    };
    let Some(previous_min_y) = previous_min.get(1).copied() else {
        return true;
    };
    let Some(previous_max_x) = previous_max.first().copied() else {
        return true;
    };
    let Some(previous_max_y) = previous_max.get(1).copied() else {
        return true;
    };
    if previous_max_x < -1.0
        || previous_min_x > 1.0
        || previous_max_y < -1.0
        || previous_min_y > 1.0
        || previous_max_x - previous_min_x >= 2.0
        || previous_max_y - previous_min_y >= 2.0
    {
        return true;
    }
    let previous_span = [
        previous_max_x - previous_min_x,
        previous_max_y - previous_min_y,
    ];
    if previous_span
        .iter()
        .any(|value| *value <= 0.0 || !value.is_finite())
    {
        return true;
    }
    for index in 0..5usize {
        let Some(depth) = samples.get(index).copied() else {
            return true;
        };
        if !depth.is_finite() || depth <= 0.0 {
            return true;
        }
        let current_z = depth;
        if nearest >= current_z {
            return true;
        }
    }
    false
}

#[cfg(test)]
fn hiz_level_bounds(
    ndc_min: [f32; 2],
    ndc_max: [f32; 2],
    width: usize,
    height: usize,
    levels: usize,
) -> Option<(usize, usize, usize, usize, usize)> {
    if width == 0
        || height == 0
        || levels == 0
        || !ndc_min.iter().all(|v| v.is_finite())
        || !ndc_max.iter().all(|v| v.is_finite())
    {
        return None;
    }
    let [min_x, min_y] = ndc_min;
    let [max_x, max_y] = ndc_max;
    if max_x < -1.0 || min_x > 1.0 || max_y < -1.0 || min_y > 1.0 {
        return None;
    }
    let limit_x = width - 1;
    let limit_y = height - 1;
    let [uv_min_x, uv_min_y] = [
        (min_x * 0.5 + 0.5).clamp(0.0, 1.0),
        (min_y * 0.5 + 0.5).clamp(0.0, 1.0),
    ];
    let [uv_max_x, uv_max_y] = [
        (max_x * 0.5 + 0.5).clamp(0.0, 1.0),
        (max_y * 0.5 + 0.5).clamp(0.0, 1.0),
    ];
    let mut ax = ((uv_min_x * width as f32) as usize).min(limit_x) >> 1;
    let mut ay = ((uv_min_y * height as f32) as usize).min(limit_y) >> 1;
    let mut bx = ((uv_max_x * width as f32) as usize).min(limit_x) >> 1;
    let mut by = ((uv_max_y * height as f32) as usize).min(limit_y) >> 1;
    let mut level = 0usize;
    while level + 1 < levels && (bx - ax > 1 || by - ay > 1) {
        ax >>= 1;
        ay >>= 1;
        bx >>= 1;
        by >>= 1;
        level += 1;
    }
    (bx - ax <= 1 && by - ay <= 1).then_some((ax, ay, bx, by, level))
}

#[cfg(test)]
fn hiz_visible(nearest: f32, farthest: f32) -> bool {
    nearest.is_finite() && farthest.is_finite() && nearest >= farthest
}

pub fn feature_depth_occluded(nearest: f32, samples: &[f32]) -> bool {
    nearest.is_finite()
        && !samples.is_empty()
        && samples.iter().all(|value| value.is_finite())
        && samples.iter().all(|value| nearest < *value)
}

pub fn feature_hysteresis(streak: &mut u8, occluded: bool, history_valid: bool) -> bool {
    if !occluded {
        *streak = 0;
        return false;
    }
    *streak = streak.saturating_add(1).min(2);
    history_valid && *streak >= 2
}

pub fn feature_visibility(
    projection: FeatureVisibilityProjection,
    samples: &[f32],
) -> FeatureVisibility {
    match projection {
        FeatureVisibilityProjection::Outside => FeatureVisibility::Outside,
        FeatureVisibilityProjection::Inconclusive => FeatureVisibility::Visible,
        FeatureVisibilityProjection::Projected(value) => {
            if feature_depth_occluded(value.nearest, samples) {
                FeatureVisibility::Outside
            } else {
                FeatureVisibility::Visible
            }
        }
    }
}

impl CalibrationHiz {
    pub(crate) fn new() -> Self {
        Self {
            hiz: None,
            snapshot: None,
            retired: Vec::new(),
        }
    }

    pub(crate) fn ensure(
        &mut self,
        gpu: &Gpu,
        resources: &mut Resources,
        width: usize,
        height: usize,
        frame: u64,
    ) -> bool {
        let matches =
            self.hiz
                .as_ref()
                .is_some_and(|hiz| hiz.width == width && hiz.height == height)
                && self.snapshot.as_ref().is_some_and(|snapshot| {
                    snapshot.width() == width && snapshot.height() == height
                });
        if matches {
            return true;
        }
        let Some(hiz) = Hiz::new(gpu, resources, width, height) else {
            return false;
        };
        let shape = TextureShape {
            width,
            height,
            layers: 1,
            mips: 1,
            cube: false,
        };
        let Some(descriptor) = metal::texture_descriptor(
            MTLPixelFormat::R32Float,
            &shape,
            MTLTextureUsage::ShaderRead | MTLTextureUsage::ShaderWrite,
        ) else {
            return false;
        };
        let Some(snapshot) = gpu.device.newTextureWithDescriptor(&descriptor) else {
            return false;
        };
        resources.track(ProtocolObject::from_ref(&*snapshot));
        let old_hiz = self.hiz.replace(hiz);
        let old_snapshot = self.snapshot.replace(snapshot);
        if old_hiz.is_some() || old_snapshot.is_some() {
            self.retired.push((frame, old_hiz, old_snapshot));
        }
        true
    }

    pub(crate) fn release(&mut self, resources: &mut Resources, completed: u64) {
        let retired = std::mem::take(&mut self.retired);
        for (frame, hiz, snapshot) in retired {
            if !retired_ready(frame, completed) {
                self.retired.push((frame, hiz, snapshot));
                continue;
            }
            if let Some(Hiz {
                texture, levels, ..
            }) = hiz
            {
                resources.untrack(ProtocolObject::from_ref(&*texture));
                for level in levels {
                    resources.untrack_view(ProtocolObject::from_ref(&*level));
                }
            }
            if let Some(snapshot) = snapshot {
                resources.untrack(ProtocolObject::from_ref(&*snapshot));
            }
        }
    }
}

impl Hiz {
    pub(crate) fn new(
        gpu: &Gpu,
        resources: &mut Resources,
        width: usize,
        height: usize,
    ) -> Option<Self> {
        let (level_width, level_height, count) = hiz_layout(width, height);
        let shape = TextureShape {
            width: level_width,
            height: level_height,
            layers: 1,
            mips: count,
            cube: false,
        };
        let descriptor = metal::texture_descriptor(
            MTLPixelFormat::R32Float,
            &shape,
            MTLTextureUsage::ShaderRead | MTLTextureUsage::ShaderWrite,
        )?;
        let texture = gpu.device.newTextureWithDescriptor(&descriptor)?;
        resources.track(ProtocolObject::from_ref(&*texture));
        let levels = (0..count)
            .map(|base| metal::texture_view(&texture, base, 1))
            .collect::<Option<Vec<_>>>()?;
        for level in &levels {
            resources.track_view(ProtocolObject::from_ref(&**level));
        }
        Some(Self {
            texture,
            levels,
            width,
            height,
            level_width,
            level_height,
        })
    }

    pub(crate) fn size(&self, level: usize) -> (usize, usize) {
        let width = (self.level_width >> level).max(1);
        let height = (self.level_height >> level).max(1);
        (width, height)
    }

    pub(crate) fn texture(&self) -> &Texture {
        &self.texture
    }

    pub(crate) fn level(&self, index: usize) -> Option<&Texture> {
        self.levels.get(index)
    }

    pub(crate) fn mip_count(&self) -> usize {
        self.levels.len()
    }
}

#[cfg(test)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum SnapshotOrder {
    Prepare,
    Commands,
    Close,
    Snapshot,
    Finish,
    EndCommandBuffer,
    Commit,
}

#[cfg(test)]
fn snapshot_order_valid(order: &[SnapshotOrder]) -> bool {
    order
        == [
            SnapshotOrder::Prepare,
            SnapshotOrder::Commands,
            SnapshotOrder::Close,
            SnapshotOrder::Snapshot,
            SnapshotOrder::Finish,
            SnapshotOrder::EndCommandBuffer,
            SnapshotOrder::Commit,
        ]
}

fn fallback_instances(request: &CullRequest, layer: usize) -> u32 {
    let first = request.first.get(layer).copied().unwrap_or(request.count);
    let next_first = request
        .first
        .get(layer.saturating_add(1))
        .copied()
        .unwrap_or(request.count);
    let drawn = next_first.saturating_sub(first);
    if request.count == 0 {
        0
    } else if request.mode == 2 || layer + 1 < request.first.len() || request.phase == 1 {
        drawn
    } else {
        0
    }
}

fn fallback(units: &Buffer, out: &Buffer, request: &CullRequest) {
    let count = request.count as usize;
    if count > 0 {
        let length = count.saturating_mul(48);
        let _ = metal::copy_shared(units, request.units_offset, out, request.out_offset, length);
    }
    if request.from_args != 0 {
        let mut copied = true;
        for word in 0..15usize {
            let offset = request
                .units_offset
                .saturating_add(request.count_args_offset)
                .saturating_add(word * 4);
            let Some(value) = metal::read_shared_u32(units, offset) else {
                copied = false;
                break;
            };
            let target = request.args_offset.saturating_add(word * 4);
            copied &= metal::write_shared(out, target, &value.to_ne_bytes());
        }
        if copied {
            return;
        }
    }
    for layer in 0..request.first.len() {
        let first = request.first.get(layer).copied().unwrap_or(request.count);
        let record = [
            request.indices,
            fallback_instances(request, layer),
            0,
            0,
            first,
        ];
        let bytes: Vec<u8> = record.iter().flat_map(|word| word.to_ne_bytes()).collect();
        let offset = request.args_offset + layer * 20;
        let _ = metal::write_shared(out, offset, &bytes);
    }
}

fn feature_params(request: &FeatureCullRequest) -> Vec<f32> {
    request
        .clip
        .iter()
        .chain(request.previous_clip.iter())
        .copied()
        .chain(std::iter::once(f32::from_bits(request.count)))
        .collect()
}

fn feature_output_range(
    request: &FeatureCullRequest,
    output_length: usize,
) -> Option<(usize, usize)> {
    if !request.out_offset.is_multiple_of(4) {
        return None;
    }
    let bytes = (request.count as usize).checked_mul(4)?;
    let end = request.out_offset.checked_add(bytes)?;
    (end <= output_length).then_some((request.out_offset, end))
}

fn retired_ready(frame: u64, completed: u64) -> bool {
    frame < completed
}

fn feature_fallback(out: &Buffer, request: &FeatureCullRequest) {
    let Some((start, end)) = feature_output_range(request, out.length()) else {
        return;
    };
    let visible = 1u32.to_ne_bytes();
    let mut offset = start;
    while offset < end {
        if !metal::write_shared(out, offset, &visible) {
            return;
        }
        offset = offset.saturating_add(4);
    }
}

fn params(request: &CullRequest, width: usize, height: usize, levels: usize) -> Vec<f32> {
    let groups = (request.count as usize).div_ceil(GROUP_SIZE) as u32;
    let mut words: Vec<u32> = Vec::with_capacity(35);
    words.extend(request.clip.iter().map(|value| value.to_bits()));
    words.extend(
        request
            .camera_block
            .iter()
            .map(|value| u32::from_ne_bytes(value.to_ne_bytes())),
    );
    words.extend(request.camera_offset.iter().map(|value| value.to_bits()));
    words.extend([request.count, request.phase]);
    words.extend(request.first);
    words.push(request.count);
    words.extend([
        width as u32,
        height as u32,
        levels as u32,
        request.sections,
        request.indices,
        groups,
    ]);
    words.push(request.from_args);
    words.push(request.mode);
    words.extend(request.previous_clip.iter().map(|value| value.to_bits()));
    words.iter().map(|word| f32::from_bits(*word)).collect()
}

fn barrier(encoder: &ProtocolObject<dyn MTL4ComputeCommandEncoder>) {
    encoder.barrierAfterEncoderStages_beforeEncoderStages_visibilityOptions(
        MTLStages::Dispatch,
        MTLStages::Dispatch,
        MTL4VisibilityOptions::Device,
    );
}

fn dispatch_threads(
    encoder: &ProtocolObject<dyn MTL4ComputeCommandEncoder>,
    threads: usize,
    group: MTLSize,
) {
    if threads == 0 {
        return;
    }
    encoder.dispatchThreads_threadsPerThreadgroup(size1(threads), group);
}

fn dispatch_tiles(
    encoder: &ProtocolObject<dyn MTL4ComputeCommandEncoder>,
    width: usize,
    height: usize,
) {
    if width == 0 || height == 0 {
        return;
    }
    encoder.dispatchThreadgroups_threadsPerThreadgroup(
        MTLSize {
            width: width.div_ceil(GROUP_2D.width),
            height: height.div_ceil(GROUP_2D.height),
            depth: 1,
        },
        GROUP_2D,
    );
}

fn dispatch_groups(
    encoder: &ProtocolObject<dyn MTL4ComputeCommandEncoder>,
    groups: usize,
    group: MTLSize,
) {
    if groups == 0 {
        return;
    }
    encoder.dispatchThreadgroups_threadsPerThreadgroup(size1(groups), group);
}

impl Culler {
    pub fn new(gpu: &Gpu, resources: &mut Resources) -> Option<Self> {
        Some(Self {
            history_a: shared(gpu, resources, 65536 * 4)?,
            history_b: shared(gpu, resources, 65536 * 4)?,
            read_a: true,
            marks: shared(gpu, resources, 65536 * 4)?,
            visible: shared(gpu, resources, 65536 * 4)?,
            groups: shared(gpu, resources, (65536 / GROUP_SIZE) * 4)?,
            totals_a: shared(gpu, resources, 8 * 4)?,
            totals_b: shared(gpu, resources, 8 * 4)?,
            units: 65536,
            sections: 65536,
            hiz_resource: None,
            snapshot: None,
            snapshot_source: None,
            snapshot_generation: 0,
            snapshot_recorded: None,
            snapshot_pending_source: None,
            snapshot_frame_source: None,
            single_phase_valid: false,
            last_clip: None,
            next_clip: None,
            retired: Vec::new(),
            swap: false,
            pending: false,
            feature_history: shared(gpu, resources, 65536 * 4)?,
            feature_buffer_capacity: 65536,
            feature_capacity: 0,
            feature_streak: Vec::new(),
            feature_identity: None,
            feature_last_clip: None,
            feature_next_clip: None,
        })
    }

    fn release(&mut self, resources: &mut Resources, completed: u64) {
        let retired = std::mem::take(&mut self.retired);
        for (frame, item) in retired {
            if !retired_ready(frame, completed) {
                self.retired.push((frame, item));
                continue;
            }
            match item {
                Retired::Buffer(buffer) => {
                    resources.untrack(ProtocolObject::from_ref(&*buffer));
                }
                Retired::Texture(texture) => {
                    resources.untrack(ProtocolObject::from_ref(&*texture));
                }
                Retired::Snapshot { texture, view } => {
                    resources.untrack_view(ProtocolObject::from_ref(&*view));
                    resources.untrack(ProtocolObject::from_ref(&*texture));
                }
            }
        }
    }

    fn grow_units(&mut self, gpu: &Gpu, resources: &mut Resources, needed: usize, frame: u64) {
        if needed <= self.units {
            return;
        }
        let Some(capacity) = needed.checked_next_power_of_two() else {
            return;
        };
        let Some(marks) = shared(gpu, resources, capacity.saturating_mul(4)) else {
            return;
        };
        let Some(visible) = shared(gpu, resources, capacity.saturating_mul(4)) else {
            return;
        };
        let groups = capacity.div_ceil(GROUP_SIZE).saturating_mul(4);
        let Some(group_sums) = shared(gpu, resources, groups) else {
            return;
        };
        let old_marks = std::mem::replace(&mut self.marks, marks);
        let old_visible = std::mem::replace(&mut self.visible, visible);
        let old_groups = std::mem::replace(&mut self.groups, group_sums);
        self.retired.push((frame, Retired::Buffer(old_marks)));
        self.retired.push((frame, Retired::Buffer(old_visible)));
        self.retired.push((frame, Retired::Buffer(old_groups)));
        self.units = capacity;
    }

    fn grow_history(&mut self, gpu: &Gpu, resources: &mut Resources, needed: usize, frame: u64) {
        if needed <= self.sections {
            return;
        }
        let Some(capacity) = needed.checked_next_power_of_two() else {
            return;
        };
        let Some(first) = shared(gpu, resources, capacity.saturating_mul(4)) else {
            return;
        };
        let Some(second) = shared(gpu, resources, capacity.saturating_mul(4)) else {
            return;
        };
        let old_first = std::mem::replace(&mut self.history_a, first);
        let old_second = std::mem::replace(&mut self.history_b, second);
        self.retired.push((frame, Retired::Buffer(old_first)));
        self.retired.push((frame, Retired::Buffer(old_second)));
        self.sections = capacity;
    }

    pub fn reset_feature_history(&mut self) {
        self.feature_streak.clear();
        self.feature_capacity = 0;
        self.feature_identity = None;
        self.feature_last_clip = None;
        self.feature_next_clip = None;
    }

    fn grow_feature_history(
        &mut self,
        gpu: &Gpu,
        resources: &mut Resources,
        needed: usize,
        frame: u64,
    ) {
        if needed <= self.feature_buffer_capacity {
            return;
        }
        let Some(capacity) = needed.checked_next_power_of_two() else {
            return;
        };
        let Some(history) = shared(gpu, resources, capacity.saturating_mul(4)) else {
            return;
        };
        let old = std::mem::replace(&mut self.feature_history, history);
        self.retired.push((frame, Retired::Buffer(old)));
        self.feature_buffer_capacity = capacity;
    }

    fn prepare_feature_history(
        &mut self,
        gpu: &Gpu,
        resources: &mut Resources,
        input: FeatureHistoryInput,
        frame: u64,
    ) {
        self.grow_feature_history(gpu, resources, input.count, frame);
        let FeatureHistoryInput {
            count,
            level,
            width,
            height,
            clip,
            previous_clip,
        } = input;
        let identity = FeatureHistoryIdentity {
            clip,
            previous_clip,
            level,
            width,
            height,
            capacity: count,
        };
        let valid = matrix_valid(&clip)
            && matrix_valid(&previous_clip)
            && width > 0
            && height > 0
            && count > 0;
        let continuous = self
            .feature_last_clip
            .as_ref()
            .is_some_and(|last| last == &previous_clip);
        let reset = !valid
            || !continuous
            || self.feature_identity.is_none()
            || self.feature_identity.is_some_and(|old| {
                old.level != level
                    || old.width != width
                    || old.height != height
                    || old.capacity != count
            })
            || self.feature_capacity != count;
        if reset {
            let Some(history) = shared(
                gpu,
                resources,
                self.feature_buffer_capacity.saturating_mul(4),
            ) else {
                self.reset_feature_history();
                return;
            };
            let old = std::mem::replace(&mut self.feature_history, history);
            self.retired.push((frame, Retired::Buffer(old)));
            self.feature_streak = vec![0; count];
            self.feature_capacity = count;
            self.feature_identity = valid.then_some(identity);
        }
        self.feature_next_clip = valid.then_some(clip);
    }

    pub fn feature_hidden(&mut self, index: usize, occluded: bool) -> bool {
        let Some(streak) = self.feature_streak.get_mut(index) else {
            return false;
        };
        feature_hysteresis(streak, occluded, self.feature_identity.is_some())
    }

    pub(crate) fn retire_hiz(&mut self, hiz: Hiz, frame: u64) {
        let Hiz {
            texture, levels, ..
        } = hiz;
        self.retired.push((frame, Retired::Texture(texture)));
        for level in levels {
            self.retired.push((frame, Retired::Texture(level)));
        }
    }

    fn retire_snapshot(&mut self, snapshot: Snapshot, frame: u64) {
        self.retired.push((
            frame,
            Retired::Snapshot {
                texture: snapshot.texture,
                view: snapshot.view,
            },
        ));
    }

    fn ensure_snapshot(
        &mut self,
        gpu: &Gpu,
        resources: &mut Resources,
        source: SnapshotSource,
        frame: u64,
    ) -> bool {
        let matches = self
            .snapshot
            .as_ref()
            .is_some_and(|snapshot| snapshot_identity_matches(snapshot.source, source));
        if matches {
            return true;
        }
        let shape = TextureShape {
            width: source.width,
            height: source.height,
            layers: 1,
            mips: 1,
            cube: false,
        };
        let Some(descriptor) = metal::texture_descriptor(
            MTLPixelFormat::R32Float,
            &shape,
            MTLTextureUsage::ShaderRead | MTLTextureUsage::ShaderWrite,
        ) else {
            return false;
        };
        let Some(texture) = gpu.device.newTextureWithDescriptor(&descriptor) else {
            return false;
        };
        let Some(view) = metal::texture_view(&texture, 0, 1) else {
            return false;
        };
        resources.track(ProtocolObject::from_ref(&*texture));
        resources.track_view(ProtocolObject::from_ref(&*view));
        if let Some(old) = self.snapshot.replace(Snapshot {
            texture,
            view,
            source,
            validity: SnapshotValidity::Missing,
        }) {
            self.retire_snapshot(old, frame);
        }
        true
    }

    pub(crate) fn ensure_hiz(
        &mut self,
        gpu: &Gpu,
        resources: &mut Resources,
        width: usize,
        height: usize,
        frame: u64,
    ) {
        let current = self
            .hiz_resource
            .as_ref()
            .map(|hiz| (hiz.width, hiz.height));
        if !hiz_resize_needed(current, width, height) {
            return;
        }
        let Some(hiz) = Hiz::new(gpu, resources, width, height) else {
            return;
        };
        if let Some(old) = self.hiz_resource.replace(hiz) {
            self.retire_hiz(old, frame);
        }
    }

    pub(crate) fn hiz(&self) -> Option<&Hiz> {
        self.hiz_resource.as_ref()
    }

    pub fn prepare(
        &mut self,
        gpu: &Gpu,
        resources: &mut Resources,
        commands: &[Command],
        frame: u64,
        completed: u64,
    ) {
        self.release(resources, completed);
        if self.pending {
            if let Some(kept) = metal::read_shared_u32(&self.totals_a, 0) {
                stats::add(Counter::CullKeptA, kept as u64);
            }
            if let Some(kept) = metal::read_shared_u32(&self.totals_b, 0) {
                stats::add(Counter::CullKeptB, kept as u64);
            }
        }
        self.pending = false;
        self.single_phase_valid = false;
        self.snapshot_recorded = None;
        self.snapshot_pending_source = None;
        self.snapshot_frame_source = None;
        self.next_clip = None;
        self.feature_next_clip = None;
        let mut swap = false;
        for command in commands {
            if let Command::FeatureCull(request) = command {
                stats::add(Counter::FeatureTests, request.count as u64);
                let (width, height) = resources.images.get(request.depth).map_or((0, 0), |image| {
                    (image.texture.width(), image.texture.height())
                });
                self.prepare_feature_history(
                    gpu,
                    resources,
                    FeatureHistoryInput {
                        count: request.count as usize,
                        level: request.depth,
                        width,
                        height,
                        clip: request.clip,
                        previous_clip: request.previous_clip,
                    },
                    frame,
                );
                if request.depth != 0 {
                    self.ensure_hiz(gpu, resources, width, height, frame);
                }
                continue;
            }
            let Command::TerrainCull(request) = command else {
                continue;
            };
            if request.phase == 1 {
                stats::add(Counter::CullUnits, request.count as u64);
                self.pending = true;
            }
            self.grow_units(gpu, resources, request.count as usize, frame);
            self.grow_history(gpu, resources, request.sections as usize, frame);
            if request.mode == 2 {
                let Some(image) = resources.images.get(request.depth) else {
                    self.single_phase_valid = false;
                    continue;
                };
                let base_source = SnapshotSource {
                    root: image.root,
                    width: image.texture.width(),
                    height: image.texture.height(),
                    format: image.texture.pixelFormat(),
                    usage: image.usage,
                    generation: self.snapshot_generation,
                };
                if self
                    .snapshot_source
                    .is_none_or(|old| !snapshot_identity_matches(old, base_source))
                {
                    self.snapshot_generation = self.snapshot_generation.saturating_add(1);
                }
                let source = SnapshotSource {
                    generation: self.snapshot_generation,
                    ..base_source
                };
                let snapshot_ready = image.depth
                    && source.format == MTLPixelFormat::Depth32Float
                    && self.ensure_snapshot(gpu, resources, source, frame);
                if !snapshot_ready {
                    self.single_phase_valid = false;
                }
            } else if request.depth != 0
                && let Some((width, height)) = resources
                    .images
                    .get(request.depth)
                    .map(|image| (image.texture.width(), image.texture.height()))
            {
                self.ensure_hiz(gpu, resources, width, height, frame);
            }
            if request.mode == 2 {
                let Some(image) = resources.images.get(request.depth) else {
                    continue;
                };
                let source = SnapshotSource {
                    root: image.root,
                    width: image.texture.width(),
                    height: image.texture.height(),
                    format: image.texture.pixelFormat(),
                    usage: image.usage,
                    generation: self.snapshot_generation,
                };
                let first_source = self.snapshot_frame_source.is_none();
                let source_matches_frame =
                    self.snapshot_frame_source.is_none_or(|old| old == source);
                if source_matches_frame {
                    self.snapshot_frame_source = Some(source);
                    self.snapshot_source = Some(source);
                } else {
                    self.single_phase_valid = false;
                }
                let source_matches = self
                    .snapshot
                    .as_ref()
                    .is_some_and(|snapshot| snapshot.source == source);
                let valid = source_matches_frame
                    && snapshot_readable(
                        self.snapshot_validity(),
                        source_matches,
                        matrices_continuous(&request.previous_clip, self.last_clip.as_ref()),
                    )
                    && matrix_valid(&request.clip);
                self.single_phase_valid = if first_source {
                    valid
                } else {
                    self.single_phase_valid && valid
                };
                self.next_clip = matrix_valid(&request.clip).then_some(request.clip);
            }
            swap |= request.phase == 1 || request.mode == 2;
        }
        self.swap = swap;
        if self.hiz().is_none()
            && let Some(hiz) = Hiz::new(gpu, resources, 1, 1)
        {
            self.hiz_resource = Some(hiz);
        }
    }

    pub fn finish(&mut self) {
        if let Some(recorded) = self.snapshot_recorded.take()
            && let Some(snapshot) = self.snapshot.as_mut()
        {
            let source_matches = self
                .snapshot_pending_source
                .is_some_and(|source| snapshot_identity_matches(snapshot.source, source));
            snapshot.validity = snapshot_transition(
                snapshot.validity,
                recorded.then_some(true),
                source_matches,
                recorded,
            );
            if recorded {
                if let Some(source) = self.snapshot_pending_source.take() {
                    snapshot.source = source;
                }
            } else {
                self.snapshot_pending_source = None;
            }
        }
        if self.swap {
            self.read_a = !self.read_a;
        }
        if let Some(clip) = self.next_clip.take() {
            self.last_clip = Some(clip);
        }
        if let Some(clip) = self.feature_next_clip.take() {
            self.feature_last_clip = Some(clip);
        }
    }

    pub fn snapshot_view(&self) -> Option<&Texture> {
        self.snapshot.as_ref().and_then(|snapshot| {
            (snapshot.validity == SnapshotValidity::Recorded).then_some(&snapshot.view)
        })
    }

    pub fn snapshot_source_for(
        &self,
        root: usize,
        width: usize,
        height: usize,
        format: MTLPixelFormat,
        usage: u64,
    ) -> Option<SnapshotSource> {
        self.snapshot_source.filter(|source| {
            source.root == root
                && source.width == width
                && source.height == height
                && source.format == format
                && source.usage == usage
        })
    }

    pub fn snapshot_validity(&self) -> SnapshotValidity {
        self.snapshot
            .as_ref()
            .map_or(SnapshotValidity::Missing, |snapshot| snapshot.validity)
    }

    pub fn snapshot_destination_root(&self) -> Option<usize> {
        self.snapshot
            .as_ref()
            .map(|snapshot| std::ptr::from_ref(&*snapshot.texture).addr())
    }

    pub fn snapshot_encode_hook<F>(
        &mut self,
        source: SnapshotSource,
        source_texture: &Texture,
        record: F,
    ) -> bool
    where
        F: FnOnce(&Texture, &Texture) -> bool,
    {
        if let Some(recorded) = self.snapshot_recorded {
            return recorded;
        }
        let Some(snapshot) = self.snapshot.as_ref() else {
            self.snapshot_recorded = Some(false);
            self.single_phase_valid = false;
            return false;
        };
        let valid = self.snapshot_source == Some(source)
            && snapshot_identity_matches(snapshot.source, source)
            && source_texture.width() == snapshot.texture.width()
            && source_texture.height() == snapshot.texture.height();
        let recorded = valid && record(source_texture, &snapshot.texture);
        self.snapshot_pending_source = recorded.then_some(source);
        self.snapshot_recorded = Some(recorded);
        if !recorded {
            self.single_phase_valid = false;
        }
        recorded
    }

    pub fn snapshot_encode_failed(&mut self) {
        if self.snapshot_recorded.is_none() {
            self.snapshot_recorded = Some(false);
            self.single_phase_valid = false;
        }
    }

    pub fn encode_feature(
        &self,
        gpu: &Gpu,
        resources: &Resources,
        scratch: &mut Scratch,
        timer: &mut PassTimer,
        request: &FeatureCullRequest,
    ) {
        let Some(out) = resources.buffers.get(request.out) else {
            return;
        };
        let Some(aabbs) = resources.buffers.get(request.aabbs) else {
            feature_fallback(out, request);
            return;
        };
        let Some(depth) = resources.images.get(request.depth) else {
            feature_fallback(out, request);
            return;
        };
        let width = depth.texture.width();
        let height = depth.texture.height();
        let Some(aabbs_address) = aabbs.gpuAddress().checked_add(request.aabbs_offset as u64)
        else {
            feature_fallback(out, request);
            return;
        };
        let Some(out_address) = out.gpuAddress().checked_add(request.out_offset as u64) else {
            feature_fallback(out, request);
            return;
        };
        let valid = request.valid_ranges(aabbs.length(), out.length(), width, height)
            && request.aabbs_offset.is_multiple_of(4)
            && request.out_offset.is_multiple_of(4)
            && matrix_valid(&request.clip)
            && matrix_valid(&request.previous_clip)
            && depth.depth
            && depth.texture.pixelFormat() == MTLPixelFormat::Depth32Float
            && request.aabbs != 0
            && request.out != 0
            && request.depth != 0
            && aabbs_address != 0
            && out_address != 0
            && self.feature_history.gpuAddress() != 0
            && self.feature_identity.is_some()
            && request.count as usize <= self.feature_buffer_capacity
            && self
                .hiz()
                .is_some_and(|hiz| hiz.width == width && hiz.height == height);
        if !valid {
            feature_fallback(out, request);
            return;
        }
        let Some(hiz) = self.hiz() else {
            feature_fallback(out, request);
            return;
        };
        let Some(params) = push_constants(scratch, &feature_params(request)) else {
            feature_fallback(out, request);
            return;
        };
        metal::bind_address(&gpu.table, 0, aabbs_address);
        metal::bind_address(&gpu.table, 1, params);
        metal::bind_address(&gpu.table, 2, out_address);
        metal::bind_address(&gpu.table, 3, self.feature_history.gpuAddress());
        metal::bind_texture(&gpu.table, 0, hiz.texture().gpuResourceID());
        timer.begin(&gpu.cmd, timing::PASS_CULL);
        let Some(encoder) = gpu.cmd.computeCommandEncoder() else {
            feature_fallback(out, request);
            timer.end(&gpu.cmd);
            return;
        };
        encoder.setArgumentTable(Some(&gpu.table));
        wait(
            &*encoder,
            MTLStages::Fragment | MTLStages::Dispatch,
            MTLStages::Dispatch,
        );
        encoder.setComputePipelineState(&gpu.kernels.feature_mark);
        dispatch_threads(&encoder, request.count as usize, GROUP_1D);
        encoder.barrierAfterStages_beforeQueueStages_visibilityOptions(
            MTLStages::Dispatch,
            MTLStages::Vertex | MTLStages::Fragment,
            MTL4VisibilityOptions::Device,
        );
        encoder.endEncoding();
        timer.end(&gpu.cmd);
    }

    pub fn encode(
        &self,
        gpu: &Gpu,
        resources: &Resources,
        hazards: &mut Hazards,
        scratch: &mut Scratch,
        timer: &mut PassTimer,
        request: &CullRequest,
    ) {
        let Some(units) = resources.buffers.get(request.units) else {
            return;
        };
        let Some(out) = resources.buffers.get(request.out) else {
            return;
        };
        let single_phase = request.mode == 2;
        if single_phase && (!self.single_phase_valid || self.snapshot_view().is_none()) {
            fallback(units, out, request);
            return;
        }
        if request.count == 0
            || request.count as usize > self.units
            || request.sections as usize > self.sections
        {
            fallback(units, out, request);
            return;
        }
        let hiz = self.hiz();
        let depth = if single_phase || request.phase == 0 {
            None
        } else {
            resources.images.get(request.depth)
        };
        if !single_phase && (hiz.is_none() || (request.phase != 0 && depth.is_none())) {
            fallback(units, out, request);
            return;
        }
        let (width, height, levels) = if let Some(hiz) = hiz {
            (hiz.width, hiz.height, hiz.mip_count())
        } else if let Some(snapshot) = self.snapshot.as_ref() {
            (snapshot.source.width, snapshot.source.height, 1)
        } else {
            fallback(units, out, request);
            return;
        };
        let values = params(request, width, height, levels);
        let Some(params) = push_constants(scratch, &values) else {
            fallback(units, out, request);
            return;
        };
        let history_read = if self.read_a {
            &self.history_a
        } else {
            &self.history_b
        };
        let history_write = if self.read_a {
            &self.history_b
        } else {
            &self.history_a
        };
        metal::bind_address(
            &gpu.table,
            0,
            units.gpuAddress() + request.units_offset as u64,
        );
        let args_source = if request.from_args != 0 {
            units.gpuAddress() + (request.units_offset + request.count_args_offset) as u64
        } else {
            units.gpuAddress() + request.units_offset as u64
        };
        metal::bind_address(&gpu.table, 11, args_source);
        metal::bind_address(&gpu.table, 1, params);
        metal::bind_address(&gpu.table, 2, self.groups.gpuAddress());
        let totals = if request.phase == 0 {
            &self.totals_a
        } else {
            &self.totals_b
        };
        metal::bind_address(&gpu.table, 3, totals.gpuAddress());
        metal::bind_address(&gpu.table, 4, out.gpuAddress() + request.out_offset as u64);
        metal::bind_address(&gpu.table, 5, self.marks.gpuAddress());
        metal::bind_address(&gpu.table, 6, self.visible.gpuAddress());
        metal::bind_address(&gpu.table, 7, history_read.gpuAddress());
        metal::bind_address(&gpu.table, 8, history_write.gpuAddress());
        metal::bind_address(&gpu.table, 9, out.gpuAddress() + request.args_offset as u64);

        timer.begin(&gpu.cmd, timing::PASS_CULL);
        let Some(encoder) = gpu.cmd.computeCommandEncoder() else {
            fallback(units, out, request);
            timer.end(&gpu.cmd);
            return;
        };
        encoder.setArgumentTable(Some(&gpu.table));
        let starts = if single_phase || request.phase == 0 {
            MTLStages::Dispatch
        } else {
            MTLStages::Fragment | MTLStages::Dispatch
        };
        if single_phase && let Some(root) = self.snapshot_destination_root() {
            let hazard = hazards.compute(root, ComputeAccess::Read);
            if !hazard.producer.is_empty()
                && let Some(fence) = hazards.fence(&gpu.device, root)
            {
                encoder.waitForFence_beforeEncoderStages(fence, MTLStages::Dispatch);
                wait(&*encoder, hazard.producer, MTLStages::Dispatch);
            }
            if !hazard.consumer.is_empty() {
                encoder.barrierAfterEncoderStages_beforeEncoderStages_visibilityOptions(
                    hazard.consumer,
                    MTLStages::Dispatch,
                    MTL4VisibilityOptions::Device,
                );
            }
        }
        wait(&*encoder, starts, MTLStages::Dispatch);

        let count = request.count as usize;
        if single_phase || request.phase == 0 {
            if !single_phase && let Some(hiz) = hiz {
                metal::bind_texture(&gpu.table, 0, hiz.texture().gpuResourceID());
            }
            if single_phase && let Some(snapshot) = self.snapshot_view() {
                metal::bind_texture(&gpu.table, 1, snapshot.gpuResourceID());
            }
            encoder.setComputePipelineState(&gpu.kernels.mark);
            dispatch_threads(&encoder, count.max(request.sections as usize), GROUP_1D);
        } else {
            let Some(depth) = depth else {
                fallback(units, out, request);
                encoder.endEncoding();
                timer.end(&gpu.cmd);
                return;
            };
            let Some(hiz) = hiz else {
                fallback(units, out, request);
                encoder.endEncoding();
                timer.end(&gpu.cmd);
                return;
            };
            let levels = hiz.mip_count();
            if levels == 0 {
                fallback(units, out, request);
                encoder.endEncoding();
                timer.end(&gpu.cmd);
                return;
            }
            let mut base = 0usize;
            while base < levels {
                let n = (levels - base).min(4);
                let source = if base == 0 {
                    depth.texture.gpuResourceID()
                } else {
                    match base.checked_sub(1).and_then(|index| hiz.level(index)) {
                        Some(texture) => texture.gpuResourceID(),
                        None => {
                            fallback(units, out, request);
                            encoder.endEncoding();
                            timer.end(&gpu.cmd);
                            return;
                        }
                    }
                };
                let Some(word) = push_constants(scratch, &[f32::from_bits(n as u32)]) else {
                    fallback(units, out, request);
                    encoder.endEncoding();
                    timer.end(&gpu.cmd);
                    return;
                };
                metal::bind_texture(&gpu.table, 0, source);
                metal::bind_address(&gpu.table, 10, word);
                for k in 0..4usize {
                    let index = if k < n { k } else { n - 1 };
                    let Some(texture) = base.checked_add(index).and_then(|at| hiz.level(at)) else {
                        continue;
                    };
                    metal::bind_texture(&gpu.table, k + 1, texture.gpuResourceID());
                }
                encoder.setComputePipelineState(if base == 0 {
                    &gpu.kernels.hiz_first4
                } else {
                    &gpu.kernels.hiz_reduce4
                });
                let (width, height) = hiz.size(base);
                dispatch_tiles(&encoder, width, height);
                barrier(&encoder);
                base += n;
            }
            metal::bind_texture(&gpu.table, 0, hiz.texture().gpuResourceID());
            if request.mode == 2
                && let Some(snapshot) = self.snapshot_view()
            {
                metal::bind_texture(&gpu.table, 1, snapshot.gpuResourceID());
            }
            encoder.setComputePipelineState(&gpu.kernels.mark);
            dispatch_threads(&encoder, count, GROUP_1D);
        }

        let groups = count.div_ceil(GROUP_SIZE);
        barrier(&encoder);
        encoder.setComputePipelineState(&gpu.kernels.scan_groups);
        dispatch_groups(&encoder, groups, GROUP_1D);
        barrier(&encoder);
        encoder.setComputePipelineState(&gpu.kernels.scan_offsets);
        dispatch_threads(&encoder, 1, UNIT_GROUP);
        barrier(&encoder);
        encoder.setComputePipelineState(&gpu.kernels.scatter);
        dispatch_groups(&encoder, groups, GROUP_1D);

        encoder.barrierAfterStages_beforeQueueStages_visibilityOptions(
            MTLStages::Dispatch,
            MTLStages::Vertex | MTLStages::Fragment,
            MTL4VisibilityOptions::Device,
        );
        encoder.endEncoding();
        timer.end(&gpu.cmd);
    }
}

#[cfg(test)]
mod tests {
    use objc2_metal::MTLPixelFormat;

    use crate::command::{CullRequest, FeatureCullRequest};

    use super::{
        FeatureVisibility, FeatureVisibilityProjection, SnapshotOrder, SnapshotSource,
        SnapshotValidity, fallback_instances, feature_depth_occluded,
        feature_history_identity_changed, feature_hysteresis, feature_output_range,
        feature_visibility, hiz_layout, hiz_level_bounds, hiz_resize_needed, hiz_visible,
        matrices_continuous, matrix_valid, project_feature_aabb, retired_ready,
        single_phase_reproject_visible, snapshot_identity_matches, snapshot_order_valid,
        snapshot_readable, snapshot_self_dependency, snapshot_transition,
    };

    #[test]
    fn hiz_layout_rounds_to_power_of_two() {
        assert_eq!(hiz_layout(1280, 720), (1024, 512, 11));
        assert_eq!(hiz_layout(1920, 1200), (1024, 1024, 11));
        assert_eq!(hiz_layout(1, 1), (1, 1, 1));
    }

    #[test]
    fn hiz_resize_keeps_matching_dimensions() {
        assert!(!hiz_resize_needed(Some((1920, 1200)), 1920, 1200));
        assert!(hiz_resize_needed(Some((1920, 1200)), 1921, 1200));
        assert!(hiz_resize_needed(None, 1920, 1200));
    }

    #[test]
    fn invalid_matrix_is_not_continuous_history() {
        let zero = [0.0; 16];
        assert!(!matrix_valid(&zero));
        assert!(!matrices_continuous(&zero, None));
    }

    #[test]
    fn snapshot_state_fails_open_until_a_recorded_copy_exists() {
        assert!(!snapshot_readable(SnapshotValidity::Missing, true, true));
        assert!(!snapshot_readable(SnapshotValidity::Invalid, true, true));
        assert!(!snapshot_readable(SnapshotValidity::Recorded, false, true));
        assert!(!snapshot_readable(SnapshotValidity::Recorded, true, false));
        assert!(snapshot_readable(SnapshotValidity::Recorded, true, true));
        assert!(single_phase_reproject_visible(
            [0.0; 6], &[1.0; 16], &[1.0; 16], 0, 600, &[0.1; 5]
        ));
    }

    #[test]
    fn single_phase_reprojection_fails_open_at_conservative_boundaries() {
        let identity = [
            1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0,
        ];
        let samples = [0.2; 5];
        assert!(single_phase_reproject_visible(
            [2.0, -0.1, -0.1, 2.1, 0.1, 0.1],
            &identity,
            &identity,
            800,
            600,
            &samples
        ));
        assert!(single_phase_reproject_visible(
            [-2.1, -0.1, -0.1, -2.0, 0.1, 0.1],
            &identity,
            &identity,
            800,
            600,
            &samples
        ));
        assert!(single_phase_reproject_visible(
            [-2.0, -0.1, -0.1, 2.1, 0.1, 0.1],
            &identity,
            &identity,
            800,
            600,
            &samples
        ));
        assert!(single_phase_reproject_visible(
            [-0.1, -0.1, -0.1, 0.1, 0.1, 0.1],
            &identity,
            &identity,
            800,
            600,
            &[0.2; 4]
        ));
        assert!(!single_phase_reproject_visible(
            [-0.1, -0.1, 0.1, 0.1, 0.1, 0.2],
            &identity,
            &identity,
            800,
            600,
            &[0.3; 5]
        ));
    }

    #[test]
    fn single_phase_reprojection_rejects_discontinuous_and_nonpositive_w() {
        let identity = [
            1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0,
        ];
        let discontinuous = [0.0; 16];
        assert!(!matrices_continuous(&identity, Some(&discontinuous)));
        assert!(single_phase_reproject_visible(
            [-0.1, -0.1, 0.1, 0.1, 0.1, 0.2],
            &identity,
            &discontinuous,
            800,
            600,
            &[0.3; 5]
        ));
        let mut behind = identity;
        if let Some(value) = behind.get_mut(15) {
            *value = -1.0;
        }
        assert!(single_phase_reproject_visible(
            [-0.1, -0.1, 0.1, 0.1, 0.1, 0.2],
            &identity,
            &behind,
            800,
            600,
            &[0.3; 5]
        ));
    }

    #[test]
    fn hiz_projection_requires_a_safe_level_and_uses_reversed_z_boundary() {
        assert!(hiz_level_bounds([-0.2, -0.2], [0.2, 0.2], 800, 600, 10).is_some());
        assert!(hiz_level_bounds([-1.0, -1.0], [1.0, 1.0], 800, 600, 1).is_none());
        assert!(hiz_visible(0.4, 0.4));
        assert!(hiz_visible(0.5, 0.4));
        assert!(!hiz_visible(0.3, 0.4));
    }

    #[test]
    fn snapshot_identity_includes_resource_contract() {
        let base = SnapshotSource {
            root: 1,
            width: 800,
            height: 600,
            format: MTLPixelFormat::Depth32Float,
            usage: 3,
            generation: 4,
        };
        assert!(snapshot_identity_matches(base, base));
        assert!(!snapshot_identity_matches(
            base,
            SnapshotSource { usage: 1, ..base }
        ));
        assert!(!snapshot_identity_matches(
            base,
            SnapshotSource { width: 801, ..base }
        ));
        assert!(snapshot_identity_matches(
            base,
            SnapshotSource {
                generation: 5,
                ..base
            }
        ));
        assert!(snapshot_self_dependency(base.root, base.root));
        assert!(!snapshot_self_dependency(base.root, base.root + 1));
    }

    #[test]
    fn snapshot_transition_distinguishes_failure_from_success() {
        assert_eq!(
            snapshot_transition(SnapshotValidity::Missing, Some(false), false, false),
            SnapshotValidity::Invalid
        );
        assert_eq!(
            snapshot_transition(SnapshotValidity::Invalid, Some(true), true, true),
            SnapshotValidity::Recorded
        );
        assert_eq!(
            snapshot_transition(SnapshotValidity::Recorded, None, true, true),
            SnapshotValidity::Recorded
        );
    }

    #[test]
    fn snapshot_is_after_all_commands_and_before_finish() {
        let order = [
            SnapshotOrder::Prepare,
            SnapshotOrder::Commands,
            SnapshotOrder::Close,
            SnapshotOrder::Snapshot,
            SnapshotOrder::Finish,
            SnapshotOrder::EndCommandBuffer,
            SnapshotOrder::Commit,
        ];
        assert!(snapshot_order_valid(&order));
        assert!(!snapshot_order_valid(&[
            SnapshotOrder::Prepare,
            SnapshotOrder::Commands,
            SnapshotOrder::Snapshot,
            SnapshotOrder::Close,
            SnapshotOrder::Finish,
            SnapshotOrder::EndCommandBuffer,
            SnapshotOrder::Commit,
        ]));
        assert!(!snapshot_order_valid(&[
            SnapshotOrder::Prepare,
            SnapshotOrder::Commands,
            SnapshotOrder::Close,
            SnapshotOrder::Snapshot,
            SnapshotOrder::Finish,
            SnapshotOrder::Commit,
            SnapshotOrder::EndCommandBuffer,
        ]));
    }

    #[test]
    fn fallback_instances_cover_single_phase_and_two_phase_layers() {
        let request = CullRequest {
            phase: 0,
            mode: 2,
            units: 1,
            units_offset: 0,
            count: 12,
            first: [0, 4, 9],
            indices: 36,
            sections: 1,
            out: 2,
            out_offset: 0,
            args_offset: 0,
            depth: 3,
            clip: [1.0; 16],
            previous_clip: [1.0; 16],
            camera_block: [0; 3],
            camera_offset: [0.0; 3],
            from_args: 0,
            count_args_offset: 0,
        };
        assert_eq!(fallback_instances(&request, 0), 4);
        assert_eq!(fallback_instances(&request, 1), 5);
        assert_eq!(fallback_instances(&request, 2), 3);

        let phase_a = CullRequest { mode: 0, ..request };
        assert_eq!(fallback_instances(&phase_a, 0), 4);
        assert_eq!(fallback_instances(&phase_a, 1), 5);
        assert_eq!(fallback_instances(&phase_a, 2), 0);
    }

    #[test]
    fn fallback_with_from_args_is_distinguished_from_layer_args() {
        let base = CullRequest {
            phase: 1,
            mode: 0,
            units: 1,
            units_offset: 0,
            count: 12,
            first: [0, 4, 9],
            indices: 36,
            sections: 1,
            out: 2,
            out_offset: 0,
            args_offset: 0,
            depth: 3,
            clip: [1.0; 16],
            previous_clip: [1.0; 16],
            camera_block: [0; 3],
            camera_offset: [0.0; 3],
            from_args: 0,
            count_args_offset: 0,
        };
        assert_eq!(fallback_instances(&base, 0), 4);
        assert_eq!(fallback_instances(&base, 2), 3);
        let from_args = CullRequest {
            from_args: 1,
            ..base
        };
        assert_ne!(from_args.from_args, base.from_args);
    }

    #[test]
    fn first_matrix_requires_a_previous_camera() {
        let current = std::array::from_fn(|index| index as f32 + 1.0);
        assert!(!matrices_continuous(&current, None));
    }

    #[test]
    fn discontinuous_matrix_invalidates_history() {
        let previous = std::array::from_fn(|index| index as f32 + 1.0);
        let last = std::array::from_fn(|index| index as f32 + 2.0);
        assert!(!matrices_continuous(&previous, Some(&last)));
        assert!(matrices_continuous(&previous, Some(&previous)));
    }

    #[test]
    fn feature_projection_is_conservative_at_edges_and_camera() {
        let matrix = [
            1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0,
        ];
        let edge = project_feature_aabb([1.0, -0.1, -0.1, 1.0, 0.1, 0.1], &matrix, 0.0);
        assert!(matches!(edge, FeatureVisibilityProjection::Projected(_)));
        let inside = project_feature_aabb([-1.0, -1.0, -1.0, 1.0, 1.0, 1.0], &matrix, 0.0);
        assert!(matches!(inside, FeatureVisibilityProjection::Inconclusive));
    }

    #[test]
    fn feature_projection_behind_camera_fails_open() {
        let matrix = [
            1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 0.0, 0.0,
        ];
        let result = project_feature_aabb([0.0; 6], &matrix, 0.0);
        assert!(matches!(result, FeatureVisibilityProjection::Inconclusive));
    }

    #[test]
    fn feature_depth_uses_reversed_z_strictness() {
        assert!(feature_depth_occluded(0.2, &[0.3, 0.4]));
        assert!(!feature_depth_occluded(0.3, &[0.3, 0.4]));
        assert!(!feature_depth_occluded(0.2, &[f32::NAN]));
        assert_eq!(
            feature_visibility(FeatureVisibilityProjection::Outside, &[]),
            FeatureVisibility::Outside
        );
    }

    #[test]
    fn feature_history_changes_for_each_contract_identity() {
        let matrix = std::array::from_fn(|index| (index + 1) as f32);
        let base = (7, 800, 600, 4, matrix, matrix);
        assert!(feature_history_identity_changed(None, base));
        assert!(feature_history_identity_changed(
            Some(base),
            (8, 800, 600, 4, matrix, matrix)
        ));
        assert!(feature_history_identity_changed(
            Some(base),
            (7, 801, 600, 4, matrix, matrix)
        ));
        assert!(feature_history_identity_changed(
            Some(base),
            (7, 800, 601, 4, matrix, matrix)
        ));
        assert!(feature_history_identity_changed(
            Some(base),
            (7, 800, 600, 5, matrix, matrix)
        ));
        assert!(feature_history_identity_changed(
            Some(base),
            (7, 800, 600, 4, [0.0; 16], matrix)
        ));
        assert!(feature_history_identity_changed(
            Some(base),
            (7, 800, 600, 4, matrix, [0.0; 16])
        ));
    }

    #[test]
    fn feature_hysteresis_requires_two_occluded_observations() {
        let mut streak = 0;
        assert!(!feature_hysteresis(&mut streak, true, true));
        assert!(!feature_hysteresis(&mut streak, false, true));
        assert!(!feature_hysteresis(&mut streak, true, true));
        assert!(feature_hysteresis(&mut streak, true, true));
        assert!(!feature_hysteresis(&mut streak, true, false));
    }

    #[test]
    fn retired_resources_wait_for_a_completed_submit() {
        assert!(!retired_ready(7, 7));
        assert!(retired_ready(7, 8));
    }

    #[test]
    fn feature_fallback_range_is_bounded_and_aligned() {
        let request = FeatureCullRequest {
            aabbs: 1,
            aabbs_offset: 0,
            count: 3,
            stride_words: 6,
            out: 2,
            out_offset: 4,
            depth: 3,
            clip: [1.0; 16],
            previous_clip: [1.0; 16],
        };
        assert_eq!(feature_output_range(&request, 20), Some((4, 16)));
        assert_eq!(feature_output_range(&request, 15), None);
        let misaligned = FeatureCullRequest {
            out_offset: 2,
            ..request
        };
        assert_eq!(feature_output_range(&misaligned, 20), None);
    }
}
