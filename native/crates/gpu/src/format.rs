pub fn is_depth(format: u32) -> bool {
    matches!(format, 250 | 252 | 253 | 260)
}

pub fn texel_size(format: u32) -> usize {
    match format {
        10..=14 => 1,
        20..=34 => 2,
        45..=48 => 3,
        95..=99 => 6,
        103..=115 => 8,
        120..=122 => 12,
        123..=125 => 16,
        _ => 4,
    }
}
