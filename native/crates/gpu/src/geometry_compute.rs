use std::convert::TryFrom;

pub const GROUP_SIZE: u64 = 64;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Stage {
    Vertex,
    Count,
    Scan,
    Geometry,
    Compact,
    Indirect,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Dispatch {
    pub stage: Stage,
    pub groups: u64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct IndirectArguments {
    pub index_count: u32,
    pub instance_count: u32,
    pub first_index: u32,
    pub base_vertex: i32,
    pub base_instance: u32,
}

#[derive(Debug, Eq, PartialEq)]
pub struct Diagnostics {
    pub dispatches: Vec<Dispatch>,
    pub indirect: Option<IndirectArguments>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Error {
    EmptyInput,
    Overflow,
    DeviceLimit,
    MismatchedOutput,
}

pub fn groups(count: u64) -> Result<u64, Error> {
    if count == 0 {
        return Ok(0);
    }
    count
        .checked_add(GROUP_SIZE - 1)
        .map(|value| value / GROUP_SIZE)
        .ok_or(Error::Overflow)
}

pub fn exclusive_prefix(counts: &[u32]) -> Result<Vec<u32>, Error> {
    let mut result = Vec::with_capacity(counts.len());
    let mut sum = 0u32;
    for count in counts {
        result.push(sum);
        sum = sum.checked_add(*count).ok_or(Error::Overflow)?;
    }
    Ok(result)
}

pub fn compact_indices<T>(counts: &[u32], values: &[T]) -> Result<Vec<usize>, Error> {
    if counts.len() != values.len() {
        return Err(Error::MismatchedOutput);
    }
    let mut result = Vec::new();
    for (index, count) in counts.iter().enumerate() {
        if *count != 0 {
            result.push(index);
        }
    }
    Ok(result)
}

pub fn indirect_arguments(
    vertices: u64,
    primitives: u64,
) -> Result<Option<IndirectArguments>, Error> {
    if vertices == 0 || primitives == 0 {
        return Ok(None);
    }
    let index_count = primitives.checked_mul(3).ok_or(Error::Overflow)?;
    Ok(Some(IndirectArguments {
        index_count: u32::try_from(index_count).map_err(|_| Error::DeviceLimit)?,
        instance_count: 1,
        first_index: 0,
        base_vertex: 0,
        base_instance: 0,
    }))
}

pub fn diagnostics(
    primitive_count: u64,
    output_vertices: u64,
    output_primitives: u64,
    max_buffer_size: u64,
) -> Result<Diagnostics, Error> {
    if primitive_count == 0 || max_buffer_size == 0 {
        return Err(Error::EmptyInput);
    }
    let bytes = primitive_count
        .checked_mul(4)
        .and_then(|value| value.checked_add(output_vertices.checked_mul(16)?))
        .and_then(|value| value.checked_add(output_primitives.checked_mul(12)?))
        .ok_or(Error::Overflow)?;
    if bytes > max_buffer_size {
        return Err(Error::DeviceLimit);
    }
    let mut dispatches = Vec::with_capacity(6);
    for (stage, count) in [
        (Stage::Vertex, primitive_count),
        (Stage::Count, primitive_count),
        (Stage::Scan, primitive_count),
        (Stage::Geometry, primitive_count),
        (Stage::Compact, output_primitives),
    ] {
        let work = groups(count)?;
        if work != 0 {
            dispatches.push(Dispatch {
                stage,
                groups: work,
            });
        }
    }
    let indirect = indirect_arguments(output_vertices, output_primitives)?;
    if indirect.is_some() {
        dispatches.push(Dispatch {
            stage: Stage::Indirect,
            groups: 1,
        });
    }
    Ok(Diagnostics {
        dispatches,
        indirect,
    })
}

#[cfg(test)]
mod tests {
    use super::{Error, Stage, compact_indices, diagnostics, exclusive_prefix, indirect_arguments};

    #[test]
    fn scans_one_and_many_primitives() {
        assert_eq!(exclusive_prefix(&[3]), Ok(vec![0]));
        assert_eq!(exclusive_prefix(&[2, 4, 1]), Ok(vec![0, 2, 6]));
    }

    #[test]
    fn scans_at_group_boundary() {
        let values = vec![1u32; 64];
        let prefix = exclusive_prefix(&values);
        assert_eq!(
            prefix.ok().and_then(|value| value.get(63).copied()),
            Some(63)
        );
    }

    #[test]
    fn compacts_nonzero_outputs() {
        let values = [10u32, 20, 30, 40];
        assert_eq!(compact_indices(&[0, 2, 0, 1], &values), Ok(vec![1, 3]));
    }

    #[test]
    fn constructs_nonzero_indirect_arguments() {
        assert_eq!(
            indirect_arguments(4, 2),
            Ok(Some(super::IndirectArguments {
                index_count: 6,
                instance_count: 1,
                first_index: 0,
                base_vertex: 0,
                base_instance: 0,
            }))
        );
        assert_eq!(indirect_arguments(0, 2), Ok(None));
    }

    #[test]
    fn diagnostics_preserve_stage_order_and_skip_zero_draw() {
        let result = diagnostics(1, 4, 2, 4096);
        assert!(result.is_ok());
        let Ok(value) = result else { return };
        assert_eq!(
            value.dispatches.first().map(|item| item.stage),
            Some(Stage::Vertex)
        );
        assert_eq!(
            value.dispatches.last().map(|item| item.stage),
            Some(Stage::Indirect)
        );
        let empty = diagnostics(1, 0, 0, 4096);
        assert!(empty.is_ok());
        let Ok(empty) = empty else { return };
        assert!(empty.indirect.is_none());
        assert!(
            !empty
                .dispatches
                .iter()
                .any(|item| item.stage == Stage::Indirect)
        );
    }

    #[test]
    fn rejects_overflow_and_device_limits() {
        assert_eq!(exclusive_prefix(&[u32::MAX, 1]), Err(Error::Overflow));
        assert_eq!(
            indirect_arguments(1, u64::from(u32::MAX)),
            Err(Error::DeviceLimit)
        );
        assert_eq!(diagnostics(1, 1, 1, 1), Err(Error::DeviceLimit));
    }
}
