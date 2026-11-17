pub mod mesh;

pub const INPUT_MAGIC: u32 = 0x4c4f_4431;
pub const INPUT_VERSION: u32 = 1;
pub const INPUT_HEADER_BYTES: usize = 64;
pub const VOXEL_BYTES: usize = 12;
pub const MATERIAL_BYTES: usize = 24;
pub const OUTPUT_MAGIC: u32 = 0x4c4d_5331;
pub const OUTPUT_VERSION: u32 = 1;
pub const OUTPUT_HEADER_BYTES: usize = 64;
pub const FLAG_COMPLETE: u32 = 1;
pub const VOXEL_SIDE: usize = 32;

#[derive(Clone, Copy)]
pub struct Input<'a> {
    pub level: u32,
    pub origin: [i32; 3],
    pub voxels: &'a [u8],
    pub materials: &'a [u8],
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Output {
    pub bytes: usize,
    pub required: usize,
    pub quads: [u32; mesh::BUCKETS],
}

#[derive(Debug, PartialEq, Eq)]
pub enum Error {
    Truncated,
    WrongMagic,
    WrongVersion,
    Incomplete,
    InvalidFlags,
    InvalidDimensions,
    Oversized,
    InvalidRange,
}

fn u32_at(input: &[u8], offset: usize) -> Result<u32, Error> {
    let end = offset.checked_add(4).ok_or(Error::InvalidRange)?;
    let bytes = input.get(offset..end).ok_or(Error::Truncated)?;
    let values: [u8; 4] = bytes.try_into().map_err(|_| Error::Truncated)?;
    Ok(u32::from_le_bytes(values))
}

fn i32_at(input: &[u8], offset: usize) -> Result<i32, Error> {
    Ok(i32::from_le_bytes(u32_at(input, offset)?.to_le_bytes()))
}

fn range(input: &[u8], offset: usize, length: usize) -> Result<&[u8], Error> {
    let end = offset.checked_add(length).ok_or(Error::InvalidRange)?;
    input.get(offset..end).ok_or(Error::Truncated)
}

pub fn parse(input: &[u8]) -> Result<Input<'_>, Error> {
    if input.len() < INPUT_HEADER_BYTES {
        return Err(Error::Truncated);
    }
    if u32_at(input, 0)? != INPUT_MAGIC {
        return Err(Error::WrongMagic);
    }
    if u32_at(input, 4)? != INPUT_VERSION {
        return Err(Error::WrongVersion);
    }
    if u32_at(input, 8)? as usize != INPUT_HEADER_BYTES {
        return Err(Error::InvalidRange);
    }
    let flags = u32_at(input, 12)?;
    if flags & !FLAG_COMPLETE != 0 {
        return Err(Error::InvalidFlags);
    }
    if flags & FLAG_COMPLETE == 0 {
        return Err(Error::Incomplete);
    }
    let level = u32_at(input, 16)?;
    if level > 4 {
        return Err(Error::InvalidDimensions);
    }
    let origin = [i32_at(input, 20)?, i32_at(input, 24)?, i32_at(input, 28)?];
    let voxel_count = u32_at(input, 32)? as usize;
    let material_count = u32_at(input, 36)? as usize;
    if voxel_count != VOXEL_SIDE * VOXEL_SIDE * VOXEL_SIDE || material_count > 1_048_576 {
        return Err(Error::Oversized);
    }
    let voxel_offset = u32_at(input, 40)? as usize;
    let material_offset = u32_at(input, 44)? as usize;
    let total = u32_at(input, 48)? as usize;
    if total != input.len() || voxel_offset < INPUT_HEADER_BYTES || material_offset < voxel_offset {
        return Err(Error::InvalidRange);
    }
    let voxel_bytes = voxel_count
        .checked_mul(VOXEL_BYTES)
        .ok_or(Error::Oversized)?;
    let material_bytes = material_count
        .checked_mul(MATERIAL_BYTES)
        .ok_or(Error::Oversized)?;
    let voxels = range(input, voxel_offset, voxel_bytes)?;
    let materials = range(input, material_offset, material_bytes)?;
    if voxel_offset
        .checked_add(voxels.len())
        .ok_or(Error::InvalidRange)?
        > material_offset
    {
        return Err(Error::InvalidRange);
    }
    if material_offset
        .checked_add(materials.len())
        .ok_or(Error::InvalidRange)?
        > total
    {
        return Err(Error::InvalidRange);
    }
    Ok(Input {
        level,
        origin,
        voxels,
        materials,
    })
}

fn put_u32(output: &mut [u8], offset: usize, value: u32) -> Result<(), Error> {
    let end = offset.checked_add(4).ok_or(Error::InvalidRange)?;
    output
        .get_mut(offset..end)
        .ok_or(Error::InvalidRange)?
        .copy_from_slice(&value.to_le_bytes());
    Ok(())
}

pub fn required_bytes(counts: [u32; mesh::BUCKETS]) -> Result<usize, Error> {
    let total = counts.iter().try_fold(0usize, |sum, count| {
        let count = usize::try_from(*count).map_err(|_| Error::Oversized)?;
        let bytes = count
            .checked_mul(mesh::QUAD_BYTES)
            .ok_or(Error::Oversized)?;
        sum.checked_add(bytes).ok_or(Error::Oversized)
    })?;
    OUTPUT_HEADER_BYTES
        .checked_add(total)
        .ok_or(Error::Oversized)
}

pub fn required_for(input: &[u8]) -> Result<usize, Error> {
    let parsed = parse(input)?;
    let (_, counts) = mesh::generate(&parsed)?;
    required_bytes(counts)
}

pub fn mesh(input: &[u8], output: &mut [u8]) -> Result<Output, Error> {
    let parsed = parse(input)?;
    let (quads, counts) = mesh::generate(&parsed)?;
    let required = required_bytes(counts)?;
    if output.len() < required {
        return Err(Error::InvalidRange);
    }
    output
        .get_mut(..required)
        .ok_or(Error::InvalidRange)?
        .fill(0);
    put_u32(output, 0, OUTPUT_MAGIC)?;
    put_u32(output, 4, OUTPUT_VERSION)?;
    put_u32(output, 8, parsed.level)?;
    put_u32(output, 12, mesh::QUAD_BYTES as u32)?;
    let mut coordinate = 0;
    while coordinate < parsed.origin.len() {
        put_u32(
            output,
            16 + coordinate * 4,
            parsed
                .origin
                .get(coordinate)
                .copied()
                .ok_or(Error::InvalidRange)? as u32,
        )?;
        coordinate += 1;
    }
    let mut bucket = 0;
    while bucket < counts.len() {
        put_u32(
            output,
            28 + bucket * 4,
            counts.get(bucket).copied().ok_or(Error::InvalidRange)?,
        )?;
        bucket += 1;
    }
    put_u32(output, 56, required as u32)?;
    let mut offset = OUTPUT_HEADER_BYTES;
    for quad in quads {
        mesh::write_quad(output, offset, quad)?;
        offset = offset
            .checked_add(mesh::QUAD_BYTES)
            .ok_or(Error::Oversized)?;
    }
    Ok(Output {
        bytes: required,
        required,
        quads: counts,
    })
}

pub const BLOCK_VERTEX_STRIDE: usize = 28;
pub const QUAD_STRIDE: usize = BLOCK_VERTEX_STRIDE * 4;
pub const CELL_SIDE: usize = 16;
pub const FACE_COUNT: usize = 7;

const VERTEX_SIZE: usize = 28;
const COLOR_OFFSET: usize = 12;
const UV_OFFSET: usize = 16;
const LIGHT_OFFSET: usize = 24;
const AXES: usize = 3;
const CELLS: usize = 16;
const UNASSIGNED: usize = 3;

#[derive(Clone, Copy, PartialEq, Eq)]
struct Key {
    axis: usize,
    u: usize,
    v: usize,
    plane: u32,
    color: u32,
    light: u32,
    uv_min_u: u32,
    uv_min_v: u32,
    uv_max_u: u32,
    uv_max_v: u32,
}

struct Group {
    key: Key,
    pattern: u32,
    rows: [u64; CELLS],
}

struct Rect {
    u0: usize,
    u1: usize,
    v0: usize,
    v1: usize,
}

pub fn merge(
    source: &[u8],
    color_shift: u32,
    light_shift: u32,
    output: &mut [u8],
    faces: &mut [u32],
) -> Option<usize> {
    if !source.len().is_multiple_of(QUAD_STRIDE)
        || output.len() < source.len()
        || faces.len() < FACE_COUNT
    {
        return None;
    }
    let quad_count = source.len() / QUAD_STRIDE;
    let mut buckets = Vec::with_capacity(quad_count);
    let mut keys = Vec::with_capacity(quad_count);
    let mut patterns = Vec::with_capacity(quad_count);
    let mut counts = [0usize; FACE_COUNT];
    let mut quad = 0usize;
    while quad < quad_count {
        let base = quad.checked_mul(QUAD_STRIDE)?;
        let bucket = classify(source, base);
        let key = if bucket == UNASSIGNED {
            None
        } else {
            key(source, base, axis(bucket), color_shift, light_shift)
        };
        let pattern = match key {
            Some(value) => pattern(source, base, value),
            None => 0,
        };
        let count = counts.get_mut(bucket)?;
        *count = count.checked_add(1)?;
        buckets.push(bucket);
        keys.push(key);
        patterns.push(pattern);
        quad += 1;
    }

    let mut groups: Vec<Vec<Group>> = Vec::with_capacity(AXES + 1);
    let mut bucket = 0usize;
    while bucket < FACE_COUNT {
        groups.push(Vec::new());
        bucket += 1;
    }
    quad = 0;
    while quad < quad_count {
        let bucket = *buckets.get(quad)?;
        if let Some(key) = *keys.get(quad)? {
            let bucket_groups = groups.get_mut(bucket)?;
            let mut found = false;
            let mut group_index = 0usize;
            while group_index < bucket_groups.len() {
                if bucket_groups.get(group_index)?.key == key {
                    let group = bucket_groups.get_mut(group_index)?;
                    let row = group.rows.get_mut(key.u)?;
                    *row |= 1u64 << key.v;
                    found = true;
                    break;
                }
                group_index += 1;
            }
            if !found {
                let mut rows = [0u64; CELLS];
                let row = rows.get_mut(key.u)?;
                *row |= 1u64 << key.v;
                bucket_groups.push(Group {
                    key,
                    pattern: *patterns.get(quad)?,
                    rows,
                });
            }
        }
        quad += 1;
    }

    let mut merged_counts = [0usize; FACE_COUNT];
    bucket = 0;
    while bucket < FACE_COUNT {
        if bucket != UNASSIGNED {
            let bucket_groups = groups.get(bucket)?;
            let mut group_index = 0usize;
            while group_index < bucket_groups.len() {
                let group = bucket_groups.get(group_index)?;
                let merged = rectangle_count(&group.rows);
                let count = merged_counts.get_mut(bucket)?;
                *count = count.checked_add(merged)?;
                group_index += 1;
            }
        }
        bucket += 1;
    }
    let mut faces_out = [0usize; FACE_COUNT];
    bucket = 0;
    while bucket < FACE_COUNT {
        let candidates = count_candidates(&buckets, &keys, bucket);
        let merged = *merged_counts.get(bucket)?;
        let face_count = merged
            .checked_add(counts.get(bucket).copied()?)?
            .checked_sub(candidates)?;
        *faces_out.get_mut(bucket)? = face_count;
        *faces.get_mut(bucket)? = u32::try_from(face_count).ok()?;
        bucket += 1;
    }

    let mut offsets = [0usize; FACE_COUNT];
    let mut total = 0usize;
    bucket = 0;
    while bucket < FACE_COUNT {
        *offsets.get_mut(bucket)? = total;
        total = total.checked_add(faces_out.get(bucket).copied()?.checked_mul(QUAD_STRIDE)?)?;
        bucket += 1;
    }
    if total > source.len() {
        return None;
    }
    let target = output.get_mut(..total)?;
    target.fill(0);
    let mut merged_at = [0usize; FACE_COUNT];
    bucket = 0;
    while bucket < FACE_COUNT {
        if bucket != UNASSIGNED {
            let bucket_groups = groups.get(bucket)?;
            let mut group_index = 0usize;
            while group_index < bucket_groups.len() {
                let group = bucket_groups.get(group_index)?;
                let written = write_rectangles(
                    target,
                    offsets.get(bucket)?.checked_add(*merged_at.get(bucket)?)?,
                    group,
                )?;
                *merged_at.get_mut(bucket)? = merged_at.get(bucket)?.checked_add(written)?;
                group_index += 1;
            }
        }
        bucket += 1;
    }
    let mut pass_at = [0usize; FACE_COUNT];
    quad = 0;
    while quad < quad_count {
        if keys.get(quad)?.is_none() {
            let bucket = *buckets.get(quad)?;
            let position = offsets
                .get(bucket)?
                .checked_add(*merged_at.get(bucket)?)?
                .checked_add(pass_at.get(bucket)?.checked_mul(QUAD_STRIDE)?)?;
            let from = quad.checked_mul(QUAD_STRIDE)?;
            target
                .get_mut(position..position + QUAD_STRIDE)?
                .copy_from_slice(source.get(from..from + QUAD_STRIDE)?);
            *pass_at.get_mut(bucket)? = pass_at.get(bucket)?.checked_add(1)?;
        }
        quad += 1;
    }
    Some(total)
}

fn count_candidates(buckets: &[usize], keys: &[Option<Key>], bucket: usize) -> usize {
    let mut count = 0usize;
    let mut at = 0usize;
    while at < buckets.len() {
        if buckets.get(at) == Some(&bucket) && keys.get(at).is_some_and(Option::is_some) {
            count += 1;
        }
        at += 1;
    }
    count
}

fn rectangle_count(rows: &[u64; CELLS]) -> usize {
    let mut used = [0u64; CELLS];
    let mut count = 0usize;
    let mut row = 0usize;
    while row < CELLS {
        let mut column = 0usize;
        while column < CELLS {
            let bit = 1u64 << column;
            if rows.get(row).is_some_and(|value| value & bit != 0)
                && used.get(row).is_some_and(|value| value & bit == 0)
            {
                let right = rectangle_right(rows, &used, row, column);
                let bottom = rectangle_bottom(rows, &used, row, column, right);
                mark_rectangle(&mut used, row, bottom, column, right);
                count += 1;
                column = right;
            }
            column += 1;
        }
        row += 1;
    }
    count
}

fn rectangle_right(rows: &[u64; CELLS], used: &[u64; CELLS], row: usize, start: usize) -> usize {
    let mut right = start;
    while right + 1 < CELLS {
        let bit = 1u64 << (right + 1);
        if !rows.get(row).is_some_and(|value| value & bit != 0)
            || used.get(row).is_some_and(|value| value & bit != 0)
        {
            break;
        }
        right += 1;
    }
    right
}

fn rectangle_bottom(
    rows: &[u64; CELLS],
    used: &[u64; CELLS],
    row: usize,
    from: usize,
    to: usize,
) -> usize {
    let mut bottom = row;
    while bottom + 1 < CELLS && available(rows, used, bottom + 1, from, to) {
        bottom += 1;
    }
    bottom
}

fn available(rows: &[u64; CELLS], used: &[u64; CELLS], row: usize, from: usize, to: usize) -> bool {
    let width = to - from + 1;
    let mask = if width == CELLS {
        u64::MAX
    } else {
        ((1u64 << width) - 1) << from
    };
    rows.get(row).is_some_and(|value| value & mask == mask)
        && used.get(row).is_some_and(|value| value & mask == 0)
}

fn mark_rectangle(used: &mut [u64; CELLS], row: usize, bottom: usize, from: usize, to: usize) {
    let width = to - from + 1;
    let mask = if width == CELLS {
        u64::MAX
    } else {
        ((1u64 << width) - 1) << from
    };
    let mut at = row;
    while at <= bottom {
        if let Some(value) = used.get_mut(at) {
            *value |= mask;
        }
        at += 1;
    }
}

fn write_rectangles(target: &mut [u8], base: usize, group: &Group) -> Option<usize> {
    let mut used = [0u64; CELLS];
    let mut at = base;
    let mut row = 0usize;
    while row < CELLS {
        let mut column = 0usize;
        while column < CELLS {
            let bit = 1u64 << column;
            if group.rows.get(row).is_some_and(|value| value & bit != 0)
                && used.get(row).is_some_and(|value| value & bit == 0)
            {
                let right = rectangle_right(&group.rows, &used, row, column);
                let bottom = rectangle_bottom(&group.rows, &used, row, column, right);
                mark_rectangle(&mut used, row, bottom, column, right);
                write_quad(
                    target,
                    at,
                    group,
                    Rect {
                        u0: row,
                        u1: bottom,
                        v0: column,
                        v1: right,
                    },
                )?;
                at = at.checked_add(QUAD_STRIDE)?;
                column = right;
            }
            column += 1;
        }
        row += 1;
    }
    at.checked_sub(base)
}

fn write_quad(target: &mut [u8], base: usize, group: &Group, rect: Rect) -> Option<()> {
    let key = group.key;
    let pattern = group.pattern;
    let u_axis = (key.axis + 1) % AXES;
    let v_axis = (key.axis + 2) % AXES;
    let u_min = rect.u0 as f32;
    let u_max = (rect.u1 + 1) as f32;
    let v_min = rect.v0 as f32;
    let v_max = (rect.v1 + 1) as f32;
    let mut vertex = 0usize;
    while vertex < 4 {
        let offset = base.checked_add(vertex.checked_mul(VERTEX_SIZE)?)?;
        put_f32(
            target,
            offset.checked_add(key.axis.checked_mul(4)?)?,
            f32::from_bits(key.plane),
        )?;
        put_f32(
            target,
            offset.checked_add(u_axis.checked_mul(4)?)?,
            if pattern & (1 << (vertex * 4)) != 0 {
                u_max
            } else {
                u_min
            },
        )?;
        put_f32(
            target,
            offset.checked_add(v_axis.checked_mul(4)?)?,
            if pattern & (1 << (vertex * 4 + 1)) != 0 {
                v_max
            } else {
                v_min
            },
        )?;
        put_u32_slice(target, offset.checked_add(COLOR_OFFSET)?, key.color)?;
        put_f32(
            target,
            offset.checked_add(UV_OFFSET)?,
            if pattern & (1 << (vertex * 4 + 2)) != 0 {
                f32::from_bits(key.uv_max_u)
            } else {
                f32::from_bits(key.uv_min_u)
            },
        )?;
        put_f32(
            target,
            offset.checked_add(UV_OFFSET + 4)?,
            if pattern & (1 << (vertex * 4 + 3)) != 0 {
                f32::from_bits(key.uv_max_v)
            } else {
                f32::from_bits(key.uv_min_v)
            },
        )?;
        put_u32_slice(target, offset.checked_add(LIGHT_OFFSET)?, key.light)?;
        vertex += 1;
    }
    Some(())
}

fn classify(source: &[u8], base: usize) -> usize {
    let mut axis_value = 0usize;
    while axis_value < AXES {
        let plane = position(source, base, 0, axis_value);
        if (0.0..=16.0).contains(&plane)
            && (1..4).all(|vertex| position(source, base, vertex, axis_value) == plane)
        {
            return orientation(source, base, axis_value);
        }
        axis_value += 1;
    }
    UNASSIGNED
}

fn orientation(source: &[u8], base: usize, axis_value: usize) -> usize {
    let u = (axis_value + 1) % AXES;
    let v = (axis_value + 2) % AXES;
    let first = normal(source, base, 0, 1, 2, u, v);
    let second = normal(source, base, 2, 3, 0, u, v);
    if first > 0.0 && second > 0.0 {
        axis_value
    } else if first < 0.0 && second < 0.0 {
        FACE_COUNT - 1 - axis_value
    } else {
        UNASSIGNED
    }
}

fn normal(source: &[u8], base: usize, a: usize, b: usize, c: usize, u: usize, v: usize) -> f64 {
    let au = f64::from(position(source, base, a, u));
    let av = f64::from(position(source, base, a, v));
    (f64::from(position(source, base, b, u)) - au) * (f64::from(position(source, base, c, v)) - av)
        - (f64::from(position(source, base, b, v)) - av)
            * (f64::from(position(source, base, c, u)) - au)
}

fn key(
    source: &[u8],
    base: usize,
    axis_value: usize,
    color_shift: u32,
    light_shift: u32,
) -> Option<Key> {
    let u_axis = (axis_value + 1) % AXES;
    let v_axis = (axis_value + 2) % AXES;
    let mut u_min = position(source, base, 0, u_axis);
    let mut u_max = u_min;
    let mut v_min = position(source, base, 0, v_axis);
    let mut v_max = v_min;
    let mut vertex = 1usize;
    while vertex < 4 {
        let u = position(source, base, vertex, u_axis);
        let v = position(source, base, vertex, v_axis);
        u_min = u_min.min(u);
        u_max = u_max.max(u);
        v_min = v_min.min(v);
        v_max = v_max.max(v);
        vertex += 1;
    }
    if !square(source, base, u_axis, u_min, u_max)
        || !square(source, base, v_axis, v_min, v_max)
        || u_min < 0.0
        || u_min >= CELLS as f32
        || v_min < 0.0
        || v_min >= CELLS as f32
    {
        return None;
    }
    let mut uv_min_u = uv(source, base, 0, true);
    let mut uv_max_u = uv_min_u;
    let mut uv_min_v = uv(source, base, 0, false);
    let mut uv_max_v = uv_min_v;
    vertex = 1;
    while vertex < 4 {
        let u = uv(source, base, vertex, true);
        let v = uv(source, base, vertex, false);
        uv_min_u = uv_min_u.min(u);
        uv_max_u = uv_max_u.max(u);
        uv_min_v = uv_min_v.min(v);
        uv_max_v = uv_max_v.max(v);
        vertex += 1;
    }
    if uv_min_u == uv_max_u || uv_min_v == uv_max_v {
        return None;
    }
    vertex = 0;
    while vertex < 4 {
        let u = position(source, base, vertex, u_axis);
        let v = position(source, base, vertex, v_axis);
        if (u != u_min && u != u_max)
            || (v != v_min && v != v_max)
            || !endpoint(uv(source, base, vertex, true), uv_min_u, uv_max_u)
            || !endpoint(uv(source, base, vertex, false), uv_min_v, uv_max_v)
        {
            return None;
        }
        vertex += 1;
    }
    Some(Key {
        axis: axis_value,
        u: u_min as usize,
        v: v_min as usize,
        plane: position(source, base, 0, axis_value).to_bits(),
        color: flatten_color(source, base, color_shift),
        light: flatten_light(source, base, light_shift),
        uv_min_u: uv_min_u.to_bits(),
        uv_min_v: uv_min_v.to_bits(),
        uv_max_u: uv_max_u.to_bits(),
        uv_max_v: uv_max_v.to_bits(),
    })
}

fn pattern(source: &[u8], base: usize, key: Key) -> u32 {
    let u_axis = (key.axis + 1) % AXES;
    let v_axis = (key.axis + 2) % AXES;
    let u_max = key.u as f32 + 1.0;
    let v_max = key.v as f32 + 1.0;
    let uv_max_u = f32::from_bits(key.uv_max_u);
    let uv_max_v = f32::from_bits(key.uv_max_v);
    let mut result = 0u32;
    let mut vertex = 0usize;
    while vertex < 4 {
        let at = vertex * 4;
        result |= u32::from(position(source, base, vertex, u_axis) == u_max) << at;
        result |= u32::from(position(source, base, vertex, v_axis) == v_max) << (at + 1);
        result |= u32::from(uv(source, base, vertex, true) == uv_max_u) << (at + 2);
        result |= u32::from(uv(source, base, vertex, false) == uv_max_v) << (at + 3);
        vertex += 1;
    }
    result
}

fn square(source: &[u8], base: usize, axis_value: usize, min: f32, max: f32) -> bool {
    if min == max || max - min != 1.0 || min.fract() != 0.0 || max.fract() != 0.0 {
        return false;
    }
    (0..4).all(|vertex| {
        let value = position(source, base, vertex, axis_value);
        value == min || value == max
    })
}

fn flatten_color(source: &[u8], base: usize, shift: u32) -> u32 {
    let mut sums = [0u32; 4];
    let mut vertex = 0usize;
    while vertex < 4 {
        let color = raw_u32_at(source, base + vertex * VERTEX_SIZE + COLOR_OFFSET);
        if let Some(value) = sums.get_mut(0) {
            *value += color & 0xff;
        }
        if let Some(value) = sums.get_mut(1) {
            *value += (color >> 8) & 0xff;
        }
        if let Some(value) = sums.get_mut(2) {
            *value += (color >> 16) & 0xff;
        }
        if let Some(value) = sums.get_mut(3) {
            *value += color >> 24;
        }
        vertex += 1;
    }
    let r = quantize((sum_at(&sums, 0) + 2) / 4, shift);
    let g = quantize((sum_at(&sums, 1) + 2) / 4, shift);
    let b = quantize((sum_at(&sums, 2) + 2) / 4, shift);
    let a = quantize((sum_at(&sums, 3) + 2) / 4, shift);
    r | g << 8 | b << 16 | a << 24
}

fn flatten_light(source: &[u8], base: usize, shift: u32) -> u32 {
    let mut x = 0u32;
    let mut y = 0u32;
    let mut reserved = 0u32;
    let mut vertex = 0usize;
    while vertex < 4 {
        let light = raw_u32_at(source, base + vertex * VERTEX_SIZE + LIGHT_OFFSET);
        x += light & 0xff;
        y += (light >> 16) & 0xff;
        reserved |= light & 0xff00_ff00;
        vertex += 1;
    }
    reserved | quantize((x + 2) / 4, shift) | quantize((y + 2) / 4, shift) << 16
}

fn quantize(value: u32, shift: u32) -> u32 {
    if shift == 0 {
        return value;
    }
    if shift >= 8 {
        return 0;
    }
    let half = 1u32 << (shift - 1);
    ((value + half) >> shift << shift).min(255)
}

fn axis(bucket: usize) -> usize {
    if bucket < AXES {
        bucket
    } else {
        FACE_COUNT - 1 - bucket
    }
}

fn position(source: &[u8], base: usize, vertex: usize, axis_value: usize) -> f32 {
    f32::from_bits(raw_u32_at(
        source,
        base + vertex * VERTEX_SIZE + axis_value * 4,
    ))
}

fn uv(source: &[u8], base: usize, vertex: usize, u: bool) -> f32 {
    f32::from_bits(raw_u32_at(
        source,
        base + vertex * VERTEX_SIZE + UV_OFFSET + usize::from(!u) * 4,
    ))
}

fn endpoint(value: f32, min: f32, max: f32) -> bool {
    value == min || value == max
}

fn sum_at(values: &[u32; 4], offset: usize) -> u32 {
    match values.get(offset) {
        Some(value) => *value,
        None => 0,
    }
}

fn raw_u32_at(source: &[u8], offset: usize) -> u32 {
    let Some(bytes) = source.get(offset..offset + 4) else {
        return 0;
    };
    let Some(first) = bytes.first() else {
        return 0;
    };
    let Some(second) = bytes.get(1) else {
        return 0;
    };
    let Some(third) = bytes.get(2) else {
        return 0;
    };
    let Some(fourth) = bytes.get(3) else {
        return 0;
    };
    u32::from_ne_bytes([*first, *second, *third, *fourth])
}

fn put_u32_slice(target: &mut [u8], offset: usize, value: u32) -> Option<()> {
    target
        .get_mut(offset..offset + 4)?
        .copy_from_slice(&value.to_ne_bytes());
    Some(())
}

fn put_f32(target: &mut [u8], offset: usize, value: f32) -> Option<()> {
    put_u32_slice(target, offset, value.to_bits())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn vertex(bytes: &mut Vec<u8>, position: [f32; 3], color: u32, uv: [f32; 2], light: u32) {
        for value in position {
            bytes.extend_from_slice(&value.to_ne_bytes());
        }
        bytes.extend_from_slice(&color.to_ne_bytes());
        for value in uv {
            bytes.extend_from_slice(&value.to_ne_bytes());
        }
        bytes.extend_from_slice(&light.to_ne_bytes());
    }

    fn quad(u: usize, v: usize, axis: usize, reverse: bool, color: u32, light: u32) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(QUAD_STRIDE);
        let positions = if axis == 0 {
            [
                [0.0, u as f32, v as f32],
                [0.0, (u + 1) as f32, v as f32],
                [0.0, (u + 1) as f32, (v + 1) as f32],
                [0.0, u as f32, (v + 1) as f32],
            ]
        } else {
            [
                [u as f32, v as f32, 0.0],
                [(u + 1) as f32, v as f32, 0.0],
                [(u + 1) as f32, (v + 1) as f32, 0.0],
                [u as f32, (v + 1) as f32, 0.0],
            ]
        };
        let order = if reverse { [0, 3, 2, 1] } else { [0, 1, 2, 3] };
        for index in order {
            let position = match positions.get(index) {
                Some(value) => *value,
                None => [0.0; 3],
            };
            let u = match position.get(1) {
                Some(value) => *value,
                None => 0.0,
            };
            let v = match position.get(2) {
                Some(value) => *value,
                None => 0.0,
            };
            vertex(&mut bytes, position, color, [u, v], light);
        }
        bytes
    }

    #[test]
    fn merge_rejects_malformed_lengths() {
        let mut output = [0; QUAD_STRIDE];
        let mut faces = [0; FACE_COUNT];
        assert!(merge(&[0; QUAD_STRIDE - 1], 0, 0, &mut output, &mut faces).is_none());
        assert!(
            merge(
                &[0; QUAD_STRIDE],
                0,
                0,
                &mut [0; QUAD_STRIDE - 1],
                &mut faces
            )
            .is_none()
        );
        assert!(
            merge(
                &[0; QUAD_STRIDE],
                0,
                0,
                &mut output,
                &mut [0; FACE_COUNT - 1]
            )
            .is_none()
        );
    }

    #[test]
    fn one_candidate_preserves_exact_bytes() {
        let source = quad(2, 3, 0, false, 0xff204060, 0x9a100020);
        let mut output = vec![0; source.len()];
        let mut faces = [0; FACE_COUNT];
        let written = merge(&source, 0, 0, &mut output, &mut faces);
        assert_eq!(written, Some(source.len()));
        assert_eq!(output, source);
        assert_eq!(faces, [1, 0, 0, 0, 0, 0, 0]);
    }

    #[test]
    fn full_plane_is_one_quad_and_never_grows() {
        let mut source = Vec::new();
        let mut v = 0usize;
        while v < CELLS {
            let mut u = 0usize;
            while u < CELLS {
                source.extend_from_slice(&quad(u, v, 0, false, 0xff204060, 0x9a100020));
                u += 1;
            }
            v += 1;
        }
        let mut output = vec![0; source.len()];
        let mut faces = [0; FACE_COUNT];
        assert!(merge(&source, 0, 0, &mut output, &mut faces).is_some());
        let written = source.len();
        assert_eq!(written, source.len());
        assert!(written <= source.len());
        assert_eq!(faces, [256, 0, 0, 0, 0, 0, 0]);
        assert_eq!(output, source);
    }

    #[test]
    fn unassigned_quad_is_copied_after_merged_buckets() {
        let mut source = quad(0, 0, 0, false, 0xff204060, 0x9a100020);
        let malformed = vec![0u8; QUAD_STRIDE];
        source.extend_from_slice(&malformed);
        let mut output = vec![0; source.len()];
        let mut faces = [0; FACE_COUNT];
        assert_eq!(
            merge(&source, 0, 0, &mut output, &mut faces),
            Some(source.len())
        );
        assert_eq!(faces, [1, 0, 0, 1, 0, 0, 0]);
        assert_eq!(output.get(QUAD_STRIDE..), source.get(QUAD_STRIDE..));
    }

    fn put(bytes: &mut [u8], offset: usize, value: u32) {
        if let Some(target) = bytes.get_mut(offset..offset + 4) {
            target.copy_from_slice(&value.to_le_bytes());
        }
    }

    fn input(flags: u32, total: usize) -> Vec<u8> {
        let voxel_offset = INPUT_HEADER_BYTES;
        let material_offset = voxel_offset + 32 * 32 * 32 * VOXEL_BYTES;
        let mut bytes = vec![0; material_offset];
        put(&mut bytes, 0, INPUT_MAGIC);
        put(&mut bytes, 4, INPUT_VERSION);
        put(&mut bytes, 8, INPUT_HEADER_BYTES as u32);
        put(&mut bytes, 12, flags);
        put(&mut bytes, 32, 32 * 32 * 32);
        put(&mut bytes, 40, voxel_offset as u32);
        put(&mut bytes, 44, material_offset as u32);
        put(&mut bytes, 48, total as u32);
        bytes
    }

    fn payload(level: u32, cells: &[(usize, u32)], materials: &[(u32, u32)]) -> Vec<u8> {
        let voxel_offset = INPUT_HEADER_BYTES;
        let voxel_bytes = 32 * 32 * 32 * VOXEL_BYTES;
        let material_offset = voxel_offset + voxel_bytes;
        let material_bytes = materials.len() * MATERIAL_BYTES;
        let total = material_offset + material_bytes;
        let mut bytes = vec![0; total];
        put(&mut bytes, 0, INPUT_MAGIC);
        put(&mut bytes, 4, INPUT_VERSION);
        put(&mut bytes, 8, INPUT_HEADER_BYTES as u32);
        put(&mut bytes, 12, FLAG_COMPLETE);
        put(&mut bytes, 16, level);
        put(&mut bytes, 32, 32 * 32 * 32);
        put(&mut bytes, 36, materials.len() as u32);
        put(&mut bytes, 40, voxel_offset as u32);
        put(&mut bytes, 44, material_offset as u32);
        put(&mut bytes, 48, total as u32);
        for cell in cells {
            let offset = voxel_offset + cell.0 * VOXEL_BYTES;
            put(&mut bytes, offset, cell.1);
        }
        for (index, material) in materials.iter().enumerate() {
            let offset = material_offset + index * MATERIAL_BYTES;
            put(&mut bytes, offset, material.0);
            put(&mut bytes, offset + 4, material.1);
            put(&mut bytes, offset + 8, material.1);
            put(&mut bytes, offset + 16, 0x11223344);
            put(&mut bytes, offset + 20, 0x000f000f);
        }
        bytes
    }

    fn cell(x: usize, y: usize, z: usize) -> usize {
        x + z * 32 + y * 1024
    }

    fn required_or_zero(bytes: &[u8]) -> usize {
        required_for(bytes).unwrap_or(0)
    }

    fn result(bytes: &[u8]) -> Output {
        let required = match required_for(bytes) {
            Ok(value) => value,
            Err(_) => {
                return Output {
                    bytes: 0,
                    required: 0,
                    quads: [0; mesh::BUCKETS],
                };
            }
        };
        let mut output = vec![0; required];
        match mesh(bytes, &mut output) {
            Ok(value) => value,
            Err(_) => Output {
                bytes: 0,
                required: 0,
                quads: [0; mesh::BUCKETS],
            },
        }
    }

    #[test]
    fn rejects_contract_errors() {
        assert!(matches!(parse(&[]), Err(Error::Truncated)));
        assert!(matches!(
            parse(&input(FLAG_COMPLETE, 0)),
            Err(Error::InvalidRange)
        ));
        let mut wrong = input(FLAG_COMPLETE, input(FLAG_COMPLETE, 0).len());
        if let Some(value) = wrong.get_mut(0) {
            *value = 0;
        }
        assert!(matches!(parse(&wrong), Err(Error::WrongMagic)));
        let mut version = input(FLAG_COMPLETE, input(FLAG_COMPLETE, 0).len());
        if let Some(value) = version.get_mut(4) {
            *value = 2;
        }
        assert!(matches!(parse(&version), Err(Error::WrongVersion)));
        let incomplete = input(0, input(0, 0).len());
        assert!(matches!(parse(&incomplete), Err(Error::Incomplete)));
    }

    #[test]
    fn all_air_has_no_faces() {
        let bytes = payload(0, &[], &[(1, 0)]);
        assert_eq!(result(&bytes).quads, [0; mesh::BUCKETS]);
    }

    #[test]
    fn solid_slab_is_greedy_and_removes_internal_faces() {
        let mut cells = Vec::new();
        let mut z = 0;
        while z < 32 {
            let mut x = 0;
            while x < 32 {
                cells.push((cell(x, 0, z), 1));
                x += 1;
            }
            z += 1;
        }
        let output = result(&payload(0, &cells, &[(1, 0)]));
        assert_eq!(output.quads, [1, 1, 1, 1, 1, 1, 0]);
    }

    #[test]
    fn adjacent_cells_remove_the_shared_face() {
        let bytes = payload(0, &[(cell(0, 0, 0), 1), (cell(1, 0, 0), 1)], &[(1, 0)]);
        let output = result(&bytes);
        assert_eq!(output.quads.iter().sum::<u32>(), 6);
    }

    #[test]
    fn translucent_cells_use_the_last_bucket() {
        let bytes = payload(0, &[(cell(0, 0, 0), 2)], &[(2, 2)]);
        assert_eq!(result(&bytes).quads, [0, 0, 0, 0, 0, 0, 6]);
    }

    #[test]
    fn level_scales_local_coordinates() {
        let bytes = payload(2, &[(cell(0, 0, 0), 1)], &[(1, 0)]);
        let required = required_or_zero(&bytes);
        let mut output = vec![0; required];
        let value = match mesh(&bytes, &mut output) {
            Ok(value) => value,
            Err(_) => Output {
                bytes: 0,
                required: 0,
                quads: [0; mesh::BUCKETS],
            },
        };
        assert_eq!(value.quads.iter().sum::<u32>(), 6);
        let x = output.get(OUTPUT_HEADER_BYTES).copied();
        assert_eq!(x, Some(0));
    }

    #[test]
    fn exact_capacity_is_accepted_and_short_capacity_is_rejected() {
        let bytes = payload(0, &[(cell(0, 0, 0), 1)], &[(1, 0)]);
        let required = required_or_zero(&bytes);
        let mut exact = vec![0; required];
        assert!(mesh(&bytes, &mut exact).is_ok());
        let mut short = vec![0; required.saturating_sub(1)];
        assert!(matches!(mesh(&bytes, &mut short), Err(Error::InvalidRange)));
    }

    #[test]
    fn malformed_material_reference_is_controlled() {
        let bytes = payload(0, &[(cell(0, 0, 0), 99)], &[(1, 0)]);
        assert!(matches!(required_for(&bytes), Err(Error::InvalidRange)));
    }
}
