use std::collections::HashMap;

use crate::{Error, Input, MATERIAL_BYTES, VOXEL_BYTES};

pub const BUCKETS: usize = 7;
pub const QUAD_BYTES: usize = 40;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Material {
    id: u32,
    layer: u32,
    flags: u32,
    color: u32,
    light: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Face {
    material: Material,
    face: usize,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Quad {
    x: u32,
    y: u32,
    z: u32,
    width: u32,
    height: u32,
    face: u32,
    material: Material,
}

fn word(input: &[u8], offset: usize) -> Result<u32, Error> {
    let end = offset.checked_add(4).ok_or(Error::InvalidRange)?;
    let bytes = input.get(offset..end).ok_or(Error::Truncated)?;
    let values: [u8; 4] = bytes.try_into().map_err(|_| Error::Truncated)?;
    Ok(u32::from_le_bytes(values))
}

fn materials(input: &Input<'_>) -> Result<HashMap<u32, Material>, Error> {
    let count = input.materials.len() / MATERIAL_BYTES;
    let mut values = HashMap::with_capacity(count);
    let mut index = 0;
    while index < count {
        let offset = index.checked_mul(MATERIAL_BYTES).ok_or(Error::Oversized)?;
        let state = word(input.materials, offset)?;
        let layer = word(
            input.materials,
            offset.checked_add(8).ok_or(Error::InvalidRange)?,
        )?;
        if state == 0 || layer > 2 {
            return Err(Error::InvalidRange);
        }
        let value = Material {
            id: word(
                input.materials,
                offset.checked_add(4).ok_or(Error::InvalidRange)?,
            )?,
            layer,
            flags: word(
                input.materials,
                offset.checked_add(12).ok_or(Error::InvalidRange)?,
            )?,
            color: word(
                input.materials,
                offset.checked_add(16).ok_or(Error::InvalidRange)?,
            )?,
            light: word(
                input.materials,
                offset.checked_add(20).ok_or(Error::InvalidRange)?,
            )?,
        };
        values.entry(state).or_insert(value);
        index = index.checked_add(1).ok_or(Error::Oversized)?;
    }
    Ok(values)
}

fn cell(
    input: &Input<'_>,
    values: &HashMap<u32, Material>,
    x: usize,
    y: usize,
    z: usize,
) -> Result<Option<Material>, Error> {
    let yz = y.checked_mul(32).ok_or(Error::Oversized)?;
    let zpart = z.checked_mul(32).ok_or(Error::Oversized)?;
    let index = x
        .checked_add(zpart)
        .and_then(|value| value.checked_add(yz.checked_mul(32)?))
        .ok_or(Error::Oversized)?;
    let offset = index.checked_mul(VOXEL_BYTES).ok_or(Error::Oversized)?;
    let state = word(input.voxels, offset)?;
    if state == 0 {
        return Ok(None);
    }
    values
        .get(&state)
        .copied()
        .ok_or(Error::InvalidRange)
        .map(Some)
}

fn visible(current: Face, neighbor: Option<Material>) -> bool {
    match neighbor {
        None => true,
        Some(value) => {
            if current.material.layer == 2 {
                value.layer != 2
            } else {
                value.layer == 2
            }
        }
    }
}

fn face_at(
    input: &Input<'_>,
    values: &HashMap<u32, Material>,
    x: usize,
    y: usize,
    z: usize,
    direction: usize,
) -> Result<Option<Face>, Error> {
    let Some(material) = cell(input, values, x, y, z)? else {
        return Ok(None);
    };
    let current = Face {
        material,
        face: direction,
    };
    let neighbor = match direction {
        0 => {
            if x == 0 {
                None
            } else {
                cell(input, values, x - 1, y, z)?
            }
        }
        1 => {
            if x == 31 {
                None
            } else {
                cell(input, values, x + 1, y, z)?
            }
        }
        2 => {
            if y == 0 {
                None
            } else {
                cell(input, values, x, y - 1, z)?
            }
        }
        3 => {
            if y == 31 {
                None
            } else {
                cell(input, values, x, y + 1, z)?
            }
        }
        4 => {
            if z == 0 {
                None
            } else {
                cell(input, values, x, y, z - 1)?
            }
        }
        5 => {
            if z == 31 {
                None
            } else {
                cell(input, values, x, y, z + 1)?
            }
        }
        _ => return Err(Error::InvalidRange),
    };
    if visible(current, neighbor) {
        Ok(Some(current))
    } else {
        Ok(None)
    }
}

fn same(left: Option<Face>, right: Option<Face>) -> bool {
    left == right
}

fn position(
    face: usize,
    slice: usize,
    u: usize,
    v: usize,
    width: usize,
    height: usize,
    scale: u32,
) -> Result<Quad, Error> {
    let s = u32::try_from(slice).map_err(|_| Error::Oversized)?;
    let a = u32::try_from(u).map_err(|_| Error::Oversized)?;
    let b = u32::try_from(v).map_err(|_| Error::Oversized)?;
    let w = u32::try_from(width).map_err(|_| Error::Oversized)?;
    let h = u32::try_from(height).map_err(|_| Error::Oversized)?;
    let scaled = |value: u32| value.checked_mul(scale).ok_or(Error::Oversized);
    let (x, y, z) = match face {
        0 => (scaled(s)?, scaled(b)?, scaled(a)?),
        1 => (
            scaled(s.checked_add(1).ok_or(Error::Oversized)?)?,
            scaled(b)?,
            scaled(a)?,
        ),
        2 => (scaled(a)?, scaled(s)?, scaled(b)?),
        3 => (
            scaled(a)?,
            scaled(s.checked_add(1).ok_or(Error::Oversized)?)?,
            scaled(b)?,
        ),
        4 => (scaled(a)?, scaled(b)?, scaled(s)?),
        5 => (
            scaled(a)?,
            scaled(b)?,
            scaled(s.checked_add(1).ok_or(Error::Oversized)?)?,
        ),
        _ => return Err(Error::InvalidRange),
    };
    let material = Material {
        id: 0,
        layer: 0,
        flags: 0,
        color: 0,
        light: 0,
    };
    Ok(Quad {
        x,
        y,
        z,
        width: w.checked_mul(scale).ok_or(Error::Oversized)?,
        height: h.checked_mul(scale).ok_or(Error::Oversized)?,
        face: u32::try_from(face).map_err(|_| Error::InvalidRange)?,
        material,
    })
}

fn emit_direction(
    input: &Input<'_>,
    values: &HashMap<u32, Material>,
    direction: usize,
    scale: u32,
    translucent: bool,
    output: &mut Vec<Quad>,
) -> Result<(), Error> {
    let mut slice = 0;
    while slice < 32 {
        let mut mask: [Option<Face>; 1024] = [None; 1024];
        let mut v = 0;
        while v < 32 {
            let mut u = 0;
            while u < 32 {
                let value = match direction {
                    0 | 1 => face_at(input, values, slice, v, u, direction)?,
                    2 | 3 => face_at(input, values, u, slice, v, direction)?,
                    _ => face_at(input, values, u, v, slice, direction)?,
                };
                let index = u
                    .checked_add(v.checked_mul(32).ok_or(Error::Oversized)?)
                    .ok_or(Error::Oversized)?;
                if let Some(slot) = mask.get_mut(index) {
                    *slot = value.filter(|face| (face.material.layer == 2) == translucent);
                }
                u += 1;
            }
            v += 1;
        }
        let mut row: usize = 0;
        while row < 32 {
            let mut column: usize = 0;
            while column < 32 {
                let index = column
                    .checked_add(row.checked_mul(32).ok_or(Error::Oversized)?)
                    .ok_or(Error::Oversized)?;
                let Some(face) = mask.get(index).copied().ok_or(Error::InvalidRange)? else {
                    column += 1;
                    continue;
                };
                let mut width = 1;
                while column + width < 32 {
                    let next = column + width;
                    let next_index = next
                        .checked_add(row.checked_mul(32).ok_or(Error::Oversized)?)
                        .ok_or(Error::Oversized)?;
                    if !same(
                        mask.get(next_index).copied().ok_or(Error::InvalidRange)?,
                        Some(face),
                    ) {
                        break;
                    }
                    width += 1;
                }
                let mut height = 1;
                while row + height < 32 {
                    let mut valid = true;
                    let mut across = 0;
                    while across < width {
                        let check = column + across;
                        let check_index = check
                            .checked_add((row + height).checked_mul(32).ok_or(Error::Oversized)?)
                            .ok_or(Error::Oversized)?;
                        if !same(
                            mask.get(check_index).copied().ok_or(Error::InvalidRange)?,
                            Some(face),
                        ) {
                            valid = false;
                            break;
                        }
                        across += 1;
                    }
                    if !valid {
                        break;
                    }
                    height += 1;
                }
                let mut clear_y = row;
                while clear_y < row + height {
                    let mut clear_x = column;
                    while clear_x < column + width {
                        let clear_index = clear_x
                            .checked_add(clear_y.checked_mul(32).ok_or(Error::Oversized)?)
                            .ok_or(Error::Oversized)?;
                        if let Some(slot) = mask.get_mut(clear_index) {
                            *slot = None;
                        }
                        clear_x += 1;
                    }
                    clear_y += 1;
                }
                let mut quad = position(direction, slice, column, row, width, height, scale)?;
                quad.material = face.material;
                output.push(quad);
                column += width;
            }
            row += 1;
        }
        slice += 1;
    }
    Ok(())
}

pub fn generate(input: &Input<'_>) -> Result<(Vec<Quad>, [u32; BUCKETS]), Error> {
    let shift = input.level.checked_add(0).ok_or(Error::InvalidDimensions)?;
    let scale = 1u32.checked_shl(shift).ok_or(Error::InvalidDimensions)?;
    let values = materials(input)?;
    let mut quads = Vec::new();
    let mut counts = [0u32; BUCKETS];
    let mut direction = 0;
    while direction < 6 {
        let start = quads.len();
        emit_direction(input, &values, direction, scale, false, &mut quads)?;
        let count = quads.len().checked_sub(start).ok_or(Error::Oversized)?;
        if let Some(slot) = counts.get_mut(direction) {
            *slot = u32::try_from(count).map_err(|_| Error::Oversized)?;
        } else {
            return Err(Error::InvalidRange);
        }
        direction += 1;
    }
    let start = quads.len();
    let mut translucent = Vec::new();
    let mut direction = 0;
    while direction < 6 {
        let before = translucent.len();
        emit_direction(input, &values, direction, scale, true, &mut translucent)?;
        let mut index = before;
        while index < translucent.len() {
            if let Some(quad) = translucent.get(index).copied() {
                quads.push(quad);
            }
            index += 1;
        }
        direction += 1;
    }
    let count = quads.len().checked_sub(start).ok_or(Error::Oversized)?;
    if let Some(slot) = counts.get_mut(6) {
        *slot = u32::try_from(count).map_err(|_| Error::Oversized)?;
    } else {
        return Err(Error::InvalidRange);
    }
    Ok((quads, counts))
}

pub fn write_quad(output: &mut [u8], offset: usize, quad: Quad) -> Result<(), Error> {
    let values = [
        quad.x,
        quad.y,
        quad.z,
        quad.width,
        quad.height,
        quad.face,
        quad.material.id,
        quad.material.color,
        quad.material.light,
        quad.material.flags,
    ];
    let end = offset.checked_add(QUAD_BYTES).ok_or(Error::InvalidRange)?;
    let target = output.get_mut(offset..end).ok_or(Error::InvalidRange)?;
    let mut index = 0;
    while index < values.len() {
        let from = index.checked_mul(4).ok_or(Error::InvalidRange)?;
        let to = from.checked_add(4).ok_or(Error::InvalidRange)?;
        let value = values.get(index).ok_or(Error::InvalidRange)?;
        target
            .get_mut(from..to)
            .ok_or(Error::InvalidRange)?
            .copy_from_slice(&value.to_le_bytes());
        index += 1;
    }
    Ok(())
}

pub fn quad_count(input: &Input<'_>) -> Result<[u32; BUCKETS], Error> {
    Ok(generate(input)?.1)
}
