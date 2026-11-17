const CALIBRATION_VERSION: u32 = 1;
pub const METAL_MAX_TEXTURE_DIMENSION: u32 = 16_384;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub enum CalibrationMode {
    TwoPhaseHiz = 1,
    SinglePhaseHiz = 2,
    Off = 4,
}

impl CalibrationMode {
    pub fn from_code(code: u32) -> Option<Self> {
        match code {
            1 => Some(Self::TwoPhaseHiz),
            2 => Some(Self::SinglePhaseHiz),
            4 => Some(Self::Off),
            _ => None,
        }
    }

    pub const fn code(self) -> u32 {
        self as u32
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CalibrationResolution {
    width: u32,
    height: u32,
}

impl CalibrationResolution {
    pub fn new(width: u32, height: u32) -> Option<Self> {
        (width != 0
            && height != 0
            && width <= METAL_MAX_TEXTURE_DIMENSION
            && height <= METAL_MAX_TEXTURE_DIMENSION)
            .then_some(Self { width, height })
    }

    pub const fn width(self) -> u32 {
        self.width
    }

    pub const fn height(self) -> u32 {
        self.height
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub enum CalibrationDrawPath {
    NonIndexed = 1,
    Indexed = 2,
    Mesh = 3,
}

impl CalibrationDrawPath {
    pub fn from_code(code: u32) -> Option<Self> {
        match code {
            1 => Some(Self::NonIndexed),
            2 => Some(Self::Indexed),
            3 => Some(Self::Mesh),
            _ => None,
        }
    }

    pub const fn code(self) -> u32 {
        self as u32
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CalibrationPayload {
    pub version: u32,
    pub mode: u32,
    pub draw_path: u32,
    pub indexed: u32,
    pub compact: u32,
    pub probe_interval: u32,
    pub lod_min_ring: u32,
    pub width: u32,
    pub height: u32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CalibrationProfile {
    version: u32,
    mode: CalibrationMode,
    draw_path: CalibrationDrawPath,
    indexed: bool,
    compact: bool,
    probe_interval: u32,
    lod_min_ring: u32,
    resolution: CalibrationResolution,
}

impl CalibrationProfile {
    pub fn new(
        mode: CalibrationMode,
        draw_path: CalibrationDrawPath,
        indexed: bool,
        compact: bool,
        probe_interval: u32,
        lod_min_ring: u32,
        resolution: CalibrationResolution,
    ) -> Option<Self> {
        (probe_interval != 0 && lod_min_ring <= 3).then_some(Self {
            version: CALIBRATION_VERSION,
            mode,
            draw_path,
            indexed,
            compact,
            probe_interval,
            lod_min_ring,
            resolution,
        })
    }

    pub fn from_payload(payload: CalibrationPayload) -> Option<Self> {
        if payload.version != CALIBRATION_VERSION || payload.indexed > 1 || payload.compact > 1 {
            return None;
        }
        let mode = CalibrationMode::from_code(payload.mode)?;
        let draw_path = CalibrationDrawPath::from_code(payload.draw_path)?;
        let resolution = CalibrationResolution::new(payload.width, payload.height)?;
        Self::new(
            mode,
            draw_path,
            payload.indexed == 1,
            payload.compact == 1,
            payload.probe_interval,
            payload.lod_min_ring,
            resolution,
        )
    }

    pub const fn version(self) -> u32 {
        self.version
    }

    pub const fn mode(self) -> CalibrationMode {
        self.mode
    }

    pub const fn draw_path(self) -> CalibrationDrawPath {
        self.draw_path
    }

    pub const fn indexed(self) -> bool {
        self.indexed
    }

    pub const fn compact(self) -> bool {
        self.compact
    }

    pub const fn probe_interval(self) -> u32 {
        self.probe_interval
    }

    pub const fn lod_min_ring(self) -> u32 {
        self.lod_min_ring
    }

    pub const fn resolution(self) -> CalibrationResolution {
        self.resolution
    }

    pub const fn to_payload(self) -> CalibrationPayload {
        CalibrationPayload {
            version: self.version,
            mode: self.mode.code(),
            draw_path: self.draw_path.code(),
            indexed: self.indexed as u32,
            compact: self.compact as u32,
            probe_interval: self.probe_interval,
            lod_min_ring: self.lod_min_ring,
            width: self.resolution.width(),
            height: self.resolution.height(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{
        CalibrationDrawPath, CalibrationMode, CalibrationPayload, CalibrationProfile,
        CalibrationResolution, METAL_MAX_TEXTURE_DIMENSION,
    };

    fn profile() -> Option<CalibrationProfile> {
        CalibrationProfile::new(
            CalibrationMode::TwoPhaseHiz,
            CalibrationDrawPath::Indexed,
            true,
            false,
            240,
            3,
            CalibrationResolution::new(1280, 720)?,
        )
    }

    #[test]
    fn accepts_all_native_modes() {
        for mode in [
            CalibrationMode::TwoPhaseHiz,
            CalibrationMode::SinglePhaseHiz,
            CalibrationMode::Off,
        ] {
            let Some(resolution) = CalibrationResolution::new(1, 1) else {
                return;
            };
            assert!(
                CalibrationProfile::new(
                    mode,
                    CalibrationDrawPath::NonIndexed,
                    false,
                    false,
                    1,
                    0,
                    resolution,
                )
                .is_some()
            );
        }
    }

    #[test]
    fn rejects_unknown_version_mode_and_cpu_mode() {
        let Some(profile) = profile() else {
            return;
        };
        let payload = profile.to_payload();
        assert!(
            CalibrationProfile::from_payload(CalibrationPayload {
                version: 2,
                ..payload
            })
            .is_none()
        );
        assert!(
            CalibrationProfile::from_payload(CalibrationPayload { mode: 9, ..payload }).is_none()
        );
        assert!(
            CalibrationProfile::from_payload(CalibrationPayload { mode: 3, ..payload }).is_none()
        );
    }

    #[test]
    fn rejects_invalid_resolution_and_partial_fields() {
        assert!(CalibrationResolution::new(0, 720).is_none());
        assert!(CalibrationResolution::new(1280, 0).is_none());
        assert!(CalibrationResolution::new(u32::MAX, 720).is_none());
        assert!(CalibrationResolution::new(1280, METAL_MAX_TEXTURE_DIMENSION + 1).is_none());
        let Some(profile) = profile() else {
            return;
        };
        let payload = profile.to_payload();
        assert!(
            CalibrationProfile::from_payload(CalibrationPayload {
                indexed: 2,
                ..payload
            })
            .is_none()
        );
        assert!(
            CalibrationProfile::from_payload(CalibrationPayload {
                probe_interval: 0,
                ..payload
            })
            .is_none()
        );
    }

    #[test]
    fn preserves_pixel_resolution_in_payload() {
        let Some(profile) = profile() else {
            return;
        };
        let payload = profile.to_payload();
        assert_eq!(payload.width, 1280);
        assert_eq!(payload.height, 720);
        assert_eq!(CalibrationProfile::from_payload(payload), Some(profile));
    }
}
