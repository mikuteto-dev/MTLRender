use core::num::NonZeroU32;

pub const TESS_FACTOR_STRIDE: usize = 24;
pub const TESS_VERTEX_STRIDE: usize = 16;
pub const TESS_TRIANGLE_STRIDE: usize = 16;
pub const TESS_ARGUMENT_STRIDE: usize = 16;
pub const TESS_OFFSET_STRIDE: usize = 8;
pub const TESS_MAX_FACTOR: f32 = 64.0;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Domain {
    Triangles,
    Quads,
    Isolines,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Spacing {
    Equal,
    FractionalEven,
    FractionalOdd,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Factors {
    pub outer: [f32; 4],
    pub inner: [f32; 2],
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Counts {
    pub vertices: usize,
    pub triangles: usize,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TessellateRequest {
    pub domain: Domain,
    pub spacing: Spacing,
    pub factors: Factors,
    pub patch_count: u32,
    pub factor_offset: usize,
    pub factor_length: usize,
    pub vertex_offset: usize,
    pub vertex_length: usize,
    pub triangle_offset: usize,
    pub triangle_length: usize,
    pub argument_offset: usize,
    pub argument_length: usize,
    pub offset_offset: usize,
    pub offset_length: usize,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RequestError {
    NonFiniteFactor,
    ZeroFactor,
    FactorBufferLayout,
    OutputBufferLayout,
    Capacity,
    DispatchDimensions,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Dispatch {
    pub groups: NonZeroU32,
}

fn normalized_factor(spacing: Spacing, value: f32) -> Result<u32, RequestError> {
    if !value.is_finite() {
        return Err(RequestError::NonFiniteFactor);
    }
    if value <= 0.0 {
        return Err(RequestError::ZeroFactor);
    }
    let bounded = value.clamp(1.0, TESS_MAX_FACTOR);
    let rounded = bounded.ceil() as u32;
    let even = rounded.is_multiple_of(2);
    match spacing {
        Spacing::Equal => Ok(rounded),
        Spacing::FractionalEven => Ok(if even {
            rounded
        } else {
            rounded.saturating_add(1)
        }),
        Spacing::FractionalOdd => Ok(if even {
            rounded.saturating_add(1)
        } else {
            rounded
        }),
    }
}

pub fn normalize_factors(spacing: Spacing, factors: Factors) -> Result<Factors, RequestError> {
    let outer = factors
        .outer
        .into_iter()
        .map(|value| normalized_factor(spacing, value).map(|value| value as f32))
        .collect::<Result<Vec<_>, _>>()?;
    let inner = factors
        .inner
        .into_iter()
        .map(|value| normalized_factor(spacing, value).map(|value| value as f32))
        .collect::<Result<Vec<_>, _>>()?;
    let Some(a) = outer.first().copied() else {
        return Err(RequestError::DispatchDimensions);
    };
    let Some(b) = outer.get(1).copied() else {
        return Err(RequestError::DispatchDimensions);
    };
    let Some(c) = outer.get(2).copied() else {
        return Err(RequestError::DispatchDimensions);
    };
    let Some(d) = outer.get(3).copied() else {
        return Err(RequestError::DispatchDimensions);
    };
    let Some(x) = inner.first().copied() else {
        return Err(RequestError::DispatchDimensions);
    };
    let Some(y) = inner.get(1).copied() else {
        return Err(RequestError::DispatchDimensions);
    };
    Ok(Factors {
        outer: [a, b, c, d],
        inner: [x, y],
    })
}

pub fn indices(
    domain: Domain,
    factor_u: usize,
    factor_v: usize,
) -> Result<Vec<[usize; 3]>, RequestError> {
    if factor_u == 0 || factor_v == 0 {
        return Err(RequestError::ZeroFactor);
    }
    let mut result = Vec::new();
    match domain {
        Domain::Triangles => {
            for i in 0..factor_u * factor_u {
                let mut row = 0;
                while (row + 1) * (row + 1) <= i {
                    row += 1;
                }
                let local = i - row * row;
                let column = local / 2;
                let row_start = row * (row + 1) / 2;
                let next_start = (row + 1) * (row + 2) / 2;
                let mut first = row_start + column;
                let mut second = first + 1;
                let mut third = next_start + column;
                if local % 2 != 0 {
                    first = second;
                    second = next_start + column + 1;
                    third = next_start + column;
                }
                result.push([first, second, third]);
            }
        }
        Domain::Quads => {
            for row in 0..factor_v {
                for column in 0..factor_u {
                    let base = row * (factor_u + 1) + column;
                    let next = base + factor_u + 1;
                    result.push([base, base + 1, next + 1]);
                    result.push([base, next + 1, next]);
                }
            }
        }
        Domain::Isolines => {
            for line in 0..factor_u {
                for segment in 0..factor_v {
                    let base = line * (factor_v + 1) + segment;
                    result.push([base, base + 1, base + 1]);
                }
            }
        }
    }
    Ok(result)
}

pub fn counts(domain: Domain, spacing: Spacing, factors: Factors) -> Result<Counts, RequestError> {
    let normalized = normalize_factors(spacing, factors)?;
    let value = |items: &[f32]| items.iter().copied().fold(1.0, f32::max) as usize;
    match domain {
        Domain::Triangles => {
            let n = value(&normalized.outer).max(value(&normalized.inner));
            let vertices = n
                .checked_add(1)
                .and_then(|v| v.checked_add(1))
                .and_then(|v| v.checked_mul(n.checked_add(1)?))
                .map(|v| v / 2)
                .ok_or(RequestError::Capacity)?;
            Ok(Counts {
                vertices,
                triangles: n.checked_mul(n).ok_or(RequestError::Capacity)?,
            })
        }
        Domain::Quads => {
            let u = normalized
                .outer
                .iter()
                .step_by(2)
                .copied()
                .chain(normalized.inner.first().copied())
                .fold(1.0, f32::max) as usize;
            let v = normalized
                .outer
                .iter()
                .skip(1)
                .step_by(2)
                .copied()
                .chain(normalized.inner.get(1).copied())
                .fold(1.0, f32::max) as usize;
            Ok(Counts {
                vertices: u
                    .checked_add(1)
                    .and_then(|x| v.checked_add(1).and_then(|y| x.checked_mul(y)))
                    .ok_or(RequestError::Capacity)?,
                triangles: u
                    .checked_mul(v)
                    .and_then(|x| x.checked_mul(2))
                    .ok_or(RequestError::Capacity)?,
            })
        }
        Domain::Isolines => {
            let lines = normalized.outer.first().copied().unwrap_or(1.0) as usize;
            let segments = normalized.outer.get(1).copied().unwrap_or(1.0) as usize;
            Ok(Counts {
                vertices: lines
                    .checked_mul(segments.checked_add(1).ok_or(RequestError::Capacity)?)
                    .ok_or(RequestError::Capacity)?,
                triangles: lines.checked_mul(segments).ok_or(RequestError::Capacity)?,
            })
        }
    }
}

fn range_ok(offset: usize, length: usize, stride: usize) -> bool {
    length >= stride && length.is_multiple_of(stride) && offset.checked_add(length).is_some()
}

pub fn validate(request: &TessellateRequest) -> Result<(Counts, Dispatch), RequestError> {
    if request.patch_count == 0 || request.patch_count > 1 << 20 {
        return Err(RequestError::DispatchDimensions);
    }
    if !range_ok(
        request.factor_offset,
        request.factor_length,
        TESS_FACTOR_STRIDE,
    ) || !request.factor_length.is_multiple_of(TESS_FACTOR_STRIDE)
        || request.factor_length / TESS_FACTOR_STRIDE < request.patch_count as usize
    {
        return Err(RequestError::FactorBufferLayout);
    }
    if !range_ok(
        request.vertex_offset,
        request.vertex_length,
        TESS_VERTEX_STRIDE,
    ) || !range_ok(
        request.triangle_offset,
        request.triangle_length,
        TESS_TRIANGLE_STRIDE,
    ) || !range_ok(
        request.argument_offset,
        request.argument_length,
        TESS_ARGUMENT_STRIDE,
    ) || !range_ok(
        request.offset_offset,
        request.offset_length,
        TESS_OFFSET_STRIDE,
    ) {
        return Err(RequestError::OutputBufferLayout);
    }
    let result = counts(request.domain, request.spacing, request.factors)?;
    let patches = request.patch_count as usize;
    if result.vertices > request.vertex_length / TESS_VERTEX_STRIDE / patches
        || result.triangles > request.triangle_length / TESS_TRIANGLE_STRIDE / patches
    {
        return Err(RequestError::Capacity);
    }
    let groups = NonZeroU32::new(request.patch_count).ok_or(RequestError::DispatchDimensions)?;
    Ok((result, Dispatch { groups }))
}

pub fn encode_request(request: &TessellateRequest) -> Result<Vec<u32>, RequestError> {
    let (_, dispatch) = validate(request)?;
    let domain = match request.domain {
        Domain::Triangles => 0,
        Domain::Quads => 1,
        Domain::Isolines => 2,
    };
    let spacing = match request.spacing {
        Spacing::Equal => 0,
        Spacing::FractionalEven => 1,
        Spacing::FractionalOdd => 2,
    };
    let words = [
        request.patch_count,
        domain,
        spacing,
        0,
        request.factor_offset as u32,
        request.factor_length as u32,
        request.vertex_offset as u32,
        request.vertex_length as u32,
        request.triangle_offset as u32,
        request.triangle_length as u32,
        request.argument_offset as u32,
        request.argument_length as u32,
        request.offset_offset as u32,
        request.offset_length as u32,
        dispatch.groups.get(),
    ];
    Ok(words.to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn factors(value: f32) -> Factors {
        Factors {
            outer: [value; 4],
            inner: [value; 2],
        }
    }

    #[test]
    fn normalizes_spacing_and_bounds() {
        assert_eq!(
            normalize_factors(Spacing::Equal, factors(0.5))
                .ok()
                .and_then(|v| v.outer.first().copied()),
            Some(1.0)
        );
        assert_eq!(
            normalize_factors(Spacing::FractionalEven, factors(3.1))
                .ok()
                .and_then(|v| v.outer.first().copied()),
            Some(4.0)
        );
        assert_eq!(
            normalize_factors(Spacing::FractionalOdd, factors(4.1))
                .ok()
                .and_then(|v| v.outer.first().copied()),
            Some(5.0)
        );
        assert_eq!(
            normalize_factors(Spacing::Equal, factors(65.0))
                .ok()
                .and_then(|v| v.outer.first().copied()),
            Some(64.0)
        );
    }

    #[test]
    fn counts_all_domains() {
        assert_eq!(
            counts(Domain::Triangles, Spacing::Equal, factors(2.0)).ok(),
            Some(Counts {
                vertices: 6,
                triangles: 4
            })
        );
        assert_eq!(
            counts(Domain::Quads, Spacing::Equal, factors(2.0)).ok(),
            Some(Counts {
                vertices: 9,
                triangles: 8
            })
        );
        assert_eq!(
            counts(Domain::Isolines, Spacing::Equal, factors(2.0)).ok(),
            Some(Counts {
                vertices: 6,
                triangles: 4
            })
        );
    }

    #[test]
    fn keeps_counter_clockwise_quad_winding() {
        assert_eq!(
            indices(Domain::Quads, 1, 1).ok(),
            Some(vec![[0, 1, 3], [0, 3, 2]])
        );
    }

    #[test]
    fn rejects_bad_factors_and_capacity() {
        assert_eq!(
            normalize_factors(Spacing::Equal, factors(0.0)),
            Err(RequestError::ZeroFactor)
        );
        assert_eq!(
            normalize_factors(Spacing::Equal, factors(f32::NAN)),
            Err(RequestError::NonFiniteFactor)
        );
        let request = TessellateRequest {
            domain: Domain::Quads,
            spacing: Spacing::Equal,
            factors: factors(4.0),
            patch_count: 2,
            factor_offset: 0,
            factor_length: 48,
            vertex_offset: 0,
            vertex_length: 16,
            triangle_offset: 0,
            triangle_length: 16,
            argument_offset: 0,
            argument_length: 32,
            offset_offset: 0,
            offset_length: 16,
        };
        assert_eq!(validate(&request), Err(RequestError::Capacity));
    }
}
