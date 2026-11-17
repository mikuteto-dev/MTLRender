use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;

#[derive(Default)]
pub struct MemoryCounter {
    bytes: AtomicU64,
}

impl MemoryCounter {
    pub fn add(&self, bytes: u64) {
        self.bytes.fetch_add(bytes, Ordering::Relaxed);
    }

    pub fn remove(&self, bytes: u64) -> bool {
        let mut current = self.bytes.load(Ordering::Relaxed);
        loop {
            if current < bytes {
                return false;
            }
            let next = current - bytes;
            match self.bytes.compare_exchange_weak(
                current,
                next,
                Ordering::Relaxed,
                Ordering::Relaxed,
            ) {
                Ok(_) => return true,
                Err(actual) => current = actual,
            }
        }
    }

    pub fn bytes(&self) -> u64 {
        self.bytes.load(Ordering::Relaxed)
    }
}

#[derive(Clone, Copy)]
pub enum Counter {
    GpuBusy = 0,
    FrameWait = 1,
    Encode = 2,
    Drawable = 3,
    Commit = 4,
    Presents = 5,
    CullUnits = 6,
    CullKeptA = 7,
    CullKeptB = 8,
    ExpandUnits = 9,
    ExpandQuads = 10,
    ExpandMismatchUnits = 11,
    ExpandMismatchLayers = 12,
    ExpandChecked = 13,
    SortMismatches = 14,
    SortChecked = 15,
    FeatureTests = 16,
    FeatureKept = 17,
    FeatureHidden = 18,
}

pub const FEATURE_COUNTERS: [Counter; 3] = [
    Counter::FeatureTests,
    Counter::FeatureKept,
    Counter::FeatureHidden,
];

const COUNTERS: usize = 19;

static NANOS: [AtomicU64; COUNTERS] = [const { AtomicU64::new(0) }; COUNTERS];

static LAST_END: AtomicU64 = AtomicU64::new(0);

pub fn add(counter: Counter, nanos: u64) {
    if let Some(slot) = NANOS.get(counter as usize) {
        slot.fetch_add(nanos, Ordering::Relaxed);
    }
}

pub fn set(counter: Counter, value: u64) {
    if let Some(slot) = NANOS.get(counter as usize) {
        slot.store(value, Ordering::Relaxed);
    }
}

pub fn record_gpu_seconds(start: f64, end: f64) {
    if !start.is_finite() || !end.is_finite() {
        return;
    }
    let Ok(previous_bits) =
        LAST_END.try_update(Ordering::Relaxed, Ordering::Relaxed, |last_bits| {
            let last_end = f64::from_bits(last_bits);
            let next_end = if end > last_end { end } else { last_end };
            Some(next_end.to_bits())
        })
    else {
        return;
    };
    let previous_end = f64::from_bits(previous_bits);
    let from = if start > previous_end {
        start
    } else {
        previous_end
    };
    let busy = end - from;
    if busy > 0.0 {
        add(Counter::GpuBusy, (busy * 1e9) as u64);
    }
}

pub fn timed<T>(counter: Counter, work: impl FnOnce() -> T) -> T {
    let start = Instant::now();
    let result = work();
    add(
        counter,
        u64::try_from(start.elapsed().as_nanos()).unwrap_or(u64::MAX),
    );
    result
}

pub fn nanos(index: usize) -> u64 {
    let _ = FEATURE_COUNTERS;
    NANOS
        .get(index)
        .map_or(0, |slot| slot.load(Ordering::Relaxed))
}

#[cfg(test)]
mod tests {
    use super::MemoryCounter;

    #[test]
    fn add_and_remove_tracks_live_bytes() {
        let counter = MemoryCounter::default();
        counter.add(64);
        counter.add(32);
        assert_eq!(counter.bytes(), 96);
        assert!(counter.remove(64));
        assert_eq!(counter.bytes(), 32);
        assert!(counter.remove(32));
        assert_eq!(counter.bytes(), 0);
    }

    #[test]
    fn failed_remove_does_not_change_counter() {
        let counter = MemoryCounter::default();
        counter.add(8);
        assert!(!counter.remove(9));
        assert_eq!(counter.bytes(), 8);
    }
}
