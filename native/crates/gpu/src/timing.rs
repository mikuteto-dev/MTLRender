use objc2::runtime::ProtocolObject;
use objc2_metal::{MTL4CommandBuffer, MTLDevice};

use crate::frames::FRAMES_IN_FLIGHT;
use mtl4::{self as metal, CounterHeap};

pub const PASS_COPY: u32 = 0;
pub const PASS_CLEAR: u32 = 1;
pub const PASS_CLEAR_REGION: u32 = 2;
pub const PASS_PRESENT: u32 = 3;
pub const PASS_CULL: u32 = 5;
pub const PASS_EXPAND: u32 = 6;
pub const PASS_PATH_TRACE: u32 = 7;
pub const PASS_PATH_DENOISE: u32 = 8;
pub const PASS_METALFX: u32 = 9;
pub const PASS_SORT: u32 = 10;
pub const PASS_COMPUTE: u32 = 11;

const MAX_ENCODERS: usize = 1024;
const NANOS_PER_SECOND: u128 = 1_000_000_000;

#[derive(Clone, Copy)]
struct Span {
    id: u32,
    passes: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Interval {
    start: u64,
    end: u64,
}

fn merged(intervals: &[Interval]) -> Vec<Interval> {
    let mut sorted: Vec<Interval> = intervals
        .iter()
        .copied()
        .filter(|interval| interval.start < interval.end)
        .collect();
    sorted.sort_by_key(|interval| interval.start);
    let mut result: Vec<Interval> = Vec::with_capacity(sorted.len());
    for interval in sorted {
        if let Some(last) = result.last_mut()
            && interval.start <= last.end
        {
            last.end = last.end.max(interval.end);
        } else {
            result.push(interval);
        }
    }
    result
}

fn duration(intervals: &[Interval]) -> u64 {
    merged(intervals)
        .into_iter()
        .map(|interval| interval.end.saturating_sub(interval.start))
        .sum()
}

fn new_duration(intervals: &[Interval], accounted: &[Interval]) -> u64 {
    let before = duration(accounted);
    let current = duration(intervals);
    let mut combined = Vec::with_capacity(intervals.len() + accounted.len());
    combined.extend_from_slice(accounted);
    combined.extend_from_slice(intervals);
    current
        .saturating_add(before)
        .saturating_sub(duration(&combined))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FrameBusy {
    pub frame: u64,
    pub nanos: u64,
    pub valid: bool,
}

pub struct PassTime {
    pub id: u32,
    pub nanos: u64,
    pub passes: u32,
}

pub struct PassTimer {
    heap: Option<CounterHeap>,
    enabled: bool,
    frame: u64,
    spans: Vec<Span>,
    open: Option<Span>,
}

impl PassTimer {
    pub fn begin(&mut self, cmd: &ProtocolObject<dyn MTL4CommandBuffer>, id: u32) {
        if self.enabled
            && let Some(heap) = &self.heap
            && self.spans.len() < MAX_ENCODERS
        {
            metal::write_timestamp(cmd, heap, self.spans.len() * 2);
            self.open = Some(Span { id, passes: 1 });
        }
    }

    pub fn merge(&mut self) {
        if let Some(span) = self.open.as_mut() {
            span.passes = span.passes.saturating_add(1);
        }
    }

    pub fn end(&mut self, cmd: &ProtocolObject<dyn MTL4CommandBuffer>) {
        if self.enabled
            && let Some(heap) = &self.heap
            && let Some(span) = self.open.take()
        {
            metal::write_timestamp(cmd, heap, self.spans.len() * 2 + 1);
            self.spans.push(span);
        }
    }
}

pub struct PassTimes {
    timers: Vec<PassTimer>,
    frequency: u128,
    ticks: Vec<u64>,
    resolved: Vec<PassTime>,
    accounted: Vec<Interval>,
    busy: Option<FrameBusy>,
}

impl PassTimes {
    pub fn new(device: &ProtocolObject<dyn MTLDevice>, enabled: bool) -> Option<Self> {
        let timers = (0..FRAMES_IN_FLIGHT)
            .map(|_| {
                let heap = Some(metal::timestamp_heap(device, MAX_ENCODERS * 2)?);
                Some(PassTimer {
                    heap,
                    enabled,
                    frame: 0,
                    spans: Vec::new(),
                    open: None,
                })
            })
            .collect::<Option<Vec<_>>>()?;
        Some(Self {
            timers,
            frequency: u128::from(device.queryTimestampFrequency().max(1)),
            ticks: Vec::new(),
            resolved: Vec::new(),
            accounted: Vec::new(),
            busy: None,
        })
    }

    pub fn start(&mut self, frame: u64, completed: u64) -> bool {
        let Some(timer) = self.timers.get_mut((frame % FRAMES_IN_FLIGHT) as usize) else {
            return false;
        };
        self.resolved.clear();
        self.busy = None;
        self.ticks.resize(timer.spans.len() * 2, 0);
        if timer.frame < completed {
            let frame_id = timer.frame;
            let resolved = timer
                .heap
                .as_ref()
                .is_some_and(|heap| metal::resolve_timestamps(heap, 0, &mut self.ticks));
            if resolved {
                let frequency = self.frequency;
                let mut intervals = Vec::with_capacity(timer.spans.len());
                for (span, pair) in timer.spans.iter().zip(self.ticks.as_chunks::<2>().0) {
                    let Some(begin) = pair.first().copied() else {
                        continue;
                    };
                    let Some(end) = pair.get(1).copied() else {
                        continue;
                    };
                    if begin == 0 || end <= begin {
                        continue;
                    }
                    let nanos = u128::from(end - begin) * NANOS_PER_SECOND / frequency;
                    self.resolved.push(PassTime {
                        id: span.id,
                        nanos: u64::try_from(nanos).unwrap_or(u64::MAX),
                        passes: span.passes,
                    });
                    intervals.push(Interval { start: begin, end });
                }
                let busy_ticks = new_duration(&intervals, &self.accounted);
                self.accounted.extend(intervals);
                self.busy = Some(FrameBusy {
                    frame: frame_id,
                    nanos: u64::try_from(u128::from(busy_ticks) * NANOS_PER_SECOND / frequency)
                        .unwrap_or(u64::MAX),
                    valid: true,
                });
            } else {
                self.busy = Some(FrameBusy {
                    frame: frame_id,
                    nanos: 0,
                    valid: false,
                });
            }
        }
        timer.frame = frame;
        timer.spans.clear();
        timer.open = None;
        true
    }

    pub fn timer(&mut self, frame: u64) -> Option<&mut PassTimer> {
        self.timers.get_mut((frame % FRAMES_IN_FLIGHT) as usize)
    }

    pub fn set_enabled(&mut self, enabled: bool) {
        for timer in &mut self.timers {
            timer.enabled = enabled;
        }
    }

    pub fn busy(&self) -> Option<FrameBusy> {
        self.busy
    }

    pub fn resolved(&self) -> &[PassTime] {
        &self.resolved
    }
}

#[cfg(test)]
mod tests {
    use super::{Interval, duration, new_duration};

    fn interval(start: u64, end: u64) -> Interval {
        Interval { start, end }
    }

    #[test]
    fn union_does_not_double_count_overlapping_spans() {
        assert_eq!(duration(&[interval(10, 30), interval(20, 40)]), 30);
        assert_eq!(new_duration(&[interval(20, 40)], &[interval(10, 30)]), 10);
    }

    #[test]
    fn union_counts_adjacent_spans_once_each() {
        assert_eq!(duration(&[interval(10, 20), interval(20, 35)]), 25);
    }

    #[test]
    fn union_ignores_empty_spans() {
        assert_eq!(duration(&[interval(10, 10), interval(20, 20)]), 0);
    }

    #[test]
    fn unresolved_span_is_not_attributed() {
        assert_eq!(duration(&[interval(30, 20)]), 0);
        assert_eq!(new_duration(&[interval(30, 20)], &[interval(0, 10)]), 0);
    }
}
