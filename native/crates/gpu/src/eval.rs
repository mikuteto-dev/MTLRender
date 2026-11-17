use std::collections::VecDeque;

const MAX_FRAMES: usize = 4096;

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct EvalFrame {
    pub submitted_frame: u64,
    pub present_timestamp_ns: u64,
    pub present_interval_ns: u64,
    pub presented_drawable_id: u64,
    pub presented_sequence: u64,
    pub gpu_busy_ns: u64,
    pub gpu_energy_nj: u64,
    pub cpu_process_ns: u64,
    pub cpu_thread_count: u32,
    pub cpu_reserved: u32,
    pub phys_footprint_bytes: u64,
    pub metal_allocated_size_bytes: u64,
    pub validity_flags: u32,
    pub reserved: u32,
}

impl EvalFrame {
    pub const PRESENT_TIMESTAMP_VALID: u32 = 1 << 0;
    pub const PRESENT_INTERVAL_VALID: u32 = 1 << 1;
    pub const PRESENTED_DRAWABLE_ID_VALID: u32 = 1 << 2;
    pub const PRESENTED_SEQUENCE_VALID: u32 = 1 << 3;
    pub const GPU_BUSY_VALID: u32 = 1 << 4;
    pub const GPU_BUSY_UNRESOLVED: u32 = 1 << 5;
    pub const GPU_FEEDBACK_VALID: u32 = 1 << 6;
    pub const GPU_ENERGY_VALID: u32 = 1 << 7;
    pub const CPU_PROCESS_VALID: u32 = 1 << 8;
    pub const CPU_THREAD_COUNT_VALID: u32 = 1 << 9;
    pub const PHYS_FOOTPRINT_VALID: u32 = 1 << 10;
    pub const METAL_ALLOCATED_SIZE_VALID: u32 = 1 << 11;
    pub const GPU_ENERGY_UNAVAILABLE: u32 = 1 << 12;
    pub const GPU_ENERGY_RESET: u32 = 1 << 13;
    pub const CPU_PROCESS_UNAVAILABLE: u32 = 1 << 14;
    pub const CPU_THREAD_COUNT_UNAVAILABLE: u32 = 1 << 15;
    pub const PHYS_FOOTPRINT_UNAVAILABLE: u32 = 1 << 16;
    pub const METAL_ALLOCATED_SIZE_UNAVAILABLE: u32 = 1 << 17;
}

#[derive(Default)]
pub(crate) struct EvalSession {
    active: bool,
    frame_open: bool,
    frames: VecDeque<EvalFrame>,
    last_present_timestamp_ns: u64,
    next_present_sequence: u64,
    energy_delta: EnergyDelta,
}

impl EvalSession {
    pub(crate) fn start(&mut self) -> bool {
        self.active = true;
        self.frame_open = false;
        self.frames.clear();
        self.last_present_timestamp_ns = 0;
        self.next_present_sequence = 0;
        self.energy_delta = EnergyDelta::default();
        self.record_energy_counter(None);
        true
    }

    pub(crate) fn stop(&mut self) {
        self.active = false;
        self.frame_open = false;
    }

    pub(crate) fn active(&self) -> bool {
        self.active
    }

    pub(crate) fn begin_frame(&mut self, submitted_frame: u64) -> bool {
        if !self.active {
            return false;
        }
        if self.frames.len() == MAX_FRAMES {
            self.frames.pop_front();
        }
        self.frames.push_back(EvalFrame {
            submitted_frame,
            ..EvalFrame::default()
        });
        self.frame_open = true;
        true
    }

    pub(crate) fn finish_frame(&mut self) {
        if self.active {
            self.frame_open = false;
        }
    }

    pub(crate) fn record_presented(
        &mut self,
        submitted_frame: u64,
        timestamp_ns: u64,
        drawable_id: u64,
    ) {
        if !self.active || timestamp_ns == 0 {
            return;
        }
        let interval_ns = timestamp_ns.saturating_sub(self.last_present_timestamp_ns);
        let sequence = self.next_present_sequence.saturating_add(1);
        let Some(frame) = self
            .frames
            .iter_mut()
            .find(|frame| frame.submitted_frame == submitted_frame)
        else {
            return;
        };
        frame.present_timestamp_ns = timestamp_ns;
        frame.present_interval_ns = interval_ns;
        frame.presented_drawable_id = drawable_id;
        frame.presented_sequence = sequence;
        frame.validity_flags |= EvalFrame::PRESENT_TIMESTAMP_VALID
            | EvalFrame::PRESENT_INTERVAL_VALID
            | EvalFrame::PRESENTED_DRAWABLE_ID_VALID
            | EvalFrame::PRESENTED_SEQUENCE_VALID
            | EvalFrame::GPU_BUSY_UNRESOLVED;
        self.last_present_timestamp_ns = timestamp_ns;
        self.next_present_sequence = sequence;
    }

    pub(crate) fn record_present(&mut self, timestamp_ns: u64, interval_ns: u64) {
        let Some(frame) = self.current_mut() else {
            return;
        };
        frame.present_timestamp_ns = timestamp_ns;
        frame.present_interval_ns = interval_ns;
        frame.validity_flags |=
            EvalFrame::PRESENT_TIMESTAMP_VALID | EvalFrame::PRESENT_INTERVAL_VALID;
    }

    pub(crate) fn record_gpu(&mut self, busy_ns: u64) {
        let Some(frame) = self.current_mut() else {
            return;
        };
        frame.gpu_busy_ns = busy_ns;
        frame.validity_flags &= !EvalFrame::GPU_BUSY_UNRESOLVED;
        frame.validity_flags |= EvalFrame::GPU_BUSY_VALID;
    }

    pub(crate) fn record_gpu_for_frame(&mut self, submitted_frame: u64, busy_ns: u64, valid: bool) {
        let Some(frame) = self
            .frames
            .iter_mut()
            .find(|frame| frame.submitted_frame == submitted_frame)
        else {
            return;
        };
        if valid {
            frame.gpu_busy_ns = busy_ns;
            frame.validity_flags &= !EvalFrame::GPU_BUSY_UNRESOLVED;
            frame.validity_flags |= EvalFrame::GPU_BUSY_VALID;
        } else {
            frame.validity_flags |= EvalFrame::GPU_BUSY_UNRESOLVED;
        }
    }

    pub(crate) fn record_feedback(
        &mut self,
        submitted_frame: u64,
        start_ns: u64,
        end_ns: u64,
        valid: bool,
    ) {
        let Some(frame) = self
            .frames
            .iter_mut()
            .find(|frame| frame.submitted_frame == submitted_frame)
        else {
            return;
        };
        if valid && start_ns > 0 && end_ns > start_ns {
            frame.validity_flags |= EvalFrame::GPU_FEEDBACK_VALID;
        } else {
            frame.validity_flags |= EvalFrame::GPU_BUSY_UNRESOLVED;
        }
    }

    pub(crate) fn record_energy(&mut self, energy_nj: u64) {
        self.record_energy_sample(Some(energy_nj), false);
    }

    pub(crate) fn record_energy_counter(&mut self, counter: Option<u64>) {
        let (energy_nj, reset) = self.energy_delta.sample(counter);
        self.record_energy_sample(energy_nj, reset);
    }

    pub(crate) fn record_energy_sample(&mut self, energy_nj: Option<u64>, reset: bool) {
        let Some(frame) = self.current_mut() else {
            return;
        };
        match energy_nj {
            Some(value) => {
                frame.gpu_energy_nj = value;
                frame.validity_flags |= EvalFrame::GPU_ENERGY_VALID;
                if reset {
                    frame.validity_flags |= EvalFrame::GPU_ENERGY_RESET;
                }
            }
            None => frame.validity_flags |= EvalFrame::GPU_ENERGY_UNAVAILABLE,
        }
    }

    pub(crate) fn record_process(
        &mut self,
        cpu_process_ns: Option<u64>,
        thread_count: Option<u32>,
    ) {
        let Some(frame) = self.current_mut() else {
            return;
        };
        if let Some(value) = cpu_process_ns {
            frame.cpu_process_ns = value;
            frame.validity_flags |= EvalFrame::CPU_PROCESS_VALID;
        } else {
            frame.validity_flags |= EvalFrame::CPU_PROCESS_UNAVAILABLE;
        }
        if let Some(value) = thread_count {
            frame.cpu_thread_count = value;
            frame.validity_flags |= EvalFrame::CPU_THREAD_COUNT_VALID;
        } else {
            frame.validity_flags |= EvalFrame::CPU_THREAD_COUNT_UNAVAILABLE;
        }
    }

    pub(crate) fn record_memory(
        &mut self,
        phys_footprint_bytes: Option<u64>,
        metal_allocated_size_bytes: Option<u64>,
    ) {
        let Some(frame) = self.current_mut() else {
            return;
        };
        if let Some(value) = phys_footprint_bytes {
            frame.phys_footprint_bytes = value;
            frame.validity_flags |= EvalFrame::PHYS_FOOTPRINT_VALID;
        } else {
            frame.validity_flags |= EvalFrame::PHYS_FOOTPRINT_UNAVAILABLE;
        }
        if let Some(value) = metal_allocated_size_bytes {
            frame.metal_allocated_size_bytes = value;
            frame.validity_flags |= EvalFrame::METAL_ALLOCATED_SIZE_VALID;
        } else {
            frame.validity_flags |= EvalFrame::METAL_ALLOCATED_SIZE_UNAVAILABLE;
        }
    }

    pub(crate) fn read(&self, out: &mut [EvalFrame]) -> usize {
        if !self.active || out.is_empty() {
            return 0;
        }
        let count = out.len().min(self.frames.len());
        out.iter_mut()
            .zip(self.frames.iter().take(count))
            .for_each(|(destination, source)| *destination = *source);
        count
    }

    fn current_mut(&mut self) -> Option<&mut EvalFrame> {
        if self.active && self.frame_open {
            self.frames.back_mut()
        } else {
            None
        }
    }
}

pub const ENERGY_SOURCE: &str = "unavailable";

#[derive(Debug, Default, PartialEq, Eq)]
pub(crate) struct EnergyDelta {
    previous: Option<u64>,
}

impl EnergyDelta {
    pub(crate) fn sample(&mut self, counter: Option<u64>) -> (Option<u64>, bool) {
        let Some(current) = counter else {
            return (None, false);
        };
        let Some(previous) = self.previous.replace(current) else {
            return (None, false);
        };
        if current < previous {
            return (None, true);
        }
        (Some(current - previous), false)
    }
}

#[cfg(test)]
mod tests {
    use std::mem::{align_of, offset_of, size_of};

    use super::{ENERGY_SOURCE, EnergyDelta, EvalFrame, EvalSession, MAX_FRAMES};

    #[test]
    fn layout_and_zero_frame_are_stable() {
        assert_eq!(size_of::<EvalFrame>(), 96);
        assert_eq!(align_of::<EvalFrame>(), 8);
        assert_eq!(offset_of!(EvalFrame, submitted_frame), 0);
        assert_eq!(offset_of!(EvalFrame, present_timestamp_ns), 8);
        assert_eq!(offset_of!(EvalFrame, present_interval_ns), 16);
        assert_eq!(offset_of!(EvalFrame, presented_drawable_id), 24);
        assert_eq!(offset_of!(EvalFrame, presented_sequence), 32);
        assert_eq!(offset_of!(EvalFrame, gpu_busy_ns), 40);
        assert_eq!(offset_of!(EvalFrame, gpu_energy_nj), 48);
        assert_eq!(offset_of!(EvalFrame, cpu_process_ns), 56);
        assert_eq!(offset_of!(EvalFrame, cpu_thread_count), 64);
        assert_eq!(offset_of!(EvalFrame, phys_footprint_bytes), 72);
        assert_eq!(offset_of!(EvalFrame, metal_allocated_size_bytes), 80);
        assert_eq!(offset_of!(EvalFrame, validity_flags), 88);
        assert_eq!(offset_of!(EvalFrame, reserved), 92);
        let mut session = EvalSession::default();
        let mut out = [EvalFrame::default(); 1];
        assert_eq!(session.read(&mut out), 0);
        assert!(session.start());
        assert_eq!(session.read(&mut []), 0);
        assert!(session.begin_frame(0));
        session.finish_frame();
        assert_eq!(session.read(&mut out), 1);
        assert_eq!(out.first(), Some(&EvalFrame::default()));
    }

    #[test]
    fn reads_multiple_frames_and_respects_capacity() {
        let mut session = EvalSession::default();
        session.start();
        assert!(session.begin_frame(7));
        session.finish_frame();
        assert!(session.begin_frame(8));
        session.finish_frame();
        let mut one = [EvalFrame::default(); 1];
        assert_eq!(session.read(&mut one), 1);
        assert_eq!(one.first().map(|frame| frame.submitted_frame), Some(7));
        let mut two = [EvalFrame::default(); 2];
        assert_eq!(session.read(&mut two), 2);
        assert_eq!(two.get(1).map(|frame| frame.submitted_frame), Some(8));
    }

    #[test]
    fn records_flags_without_measurement_producers() {
        let mut session = EvalSession::default();
        session.start();
        assert!(session.begin_frame(11));
        session.record_presented(11, 12, 13);
        session.record_gpu(14);
        session.record_energy(15);
        session.record_process(Some(16), Some(3));
        session.record_memory(Some(17), Some(18));
        session.finish_frame();
        let mut out = [EvalFrame::default(); 1];
        assert_eq!(session.read(&mut out), 1);
        let Some(frame) = out.first() else {
            return;
        };
        assert_eq!(frame.present_timestamp_ns, 12);
        assert_eq!(frame.present_interval_ns, 12);
        assert_eq!(frame.presented_drawable_id, 13);
        assert_eq!(frame.presented_sequence, 1);
        assert_eq!(frame.gpu_busy_ns, 14);
        assert_eq!(frame.gpu_energy_nj, 15);
        assert_eq!(frame.cpu_process_ns, 16);
        assert_eq!(frame.cpu_thread_count, 3);
        assert_eq!(frame.phys_footprint_bytes, 17);
        assert_eq!(frame.metal_allocated_size_bytes, 18);
        assert_eq!(
            frame.validity_flags,
            EvalFrame::PRESENT_TIMESTAMP_VALID
                | EvalFrame::PRESENT_INTERVAL_VALID
                | EvalFrame::PRESENTED_DRAWABLE_ID_VALID
                | EvalFrame::PRESENTED_SEQUENCE_VALID
                | EvalFrame::GPU_BUSY_VALID
                | EvalFrame::GPU_ENERGY_VALID
                | EvalFrame::CPU_PROCESS_VALID
                | EvalFrame::CPU_THREAD_COUNT_VALID
                | EvalFrame::PHYS_FOOTPRINT_VALID
                | EvalFrame::METAL_ALLOCATED_SIZE_VALID
        );
    }

    #[test]
    fn failed_measurements_are_not_zero_valid_samples() {
        let mut session = EvalSession::default();
        session.start();
        assert!(session.begin_frame(12));
        session.record_process(None, None);
        session.record_memory(None, None);
        session.finish_frame();
        let mut out = [EvalFrame::default(); 1];
        assert_eq!(session.read(&mut out), 1);
        let Some(frame) = out.first() else {
            return;
        };
        assert_eq!(frame.cpu_process_ns, 0);
        assert_eq!(frame.cpu_thread_count, 0);
        assert_eq!(frame.phys_footprint_bytes, 0);
        assert_eq!(frame.metal_allocated_size_bytes, 0);
        assert_eq!(
            frame.validity_flags,
            EvalFrame::CPU_PROCESS_UNAVAILABLE
                | EvalFrame::CPU_THREAD_COUNT_UNAVAILABLE
                | EvalFrame::PHYS_FOOTPRINT_UNAVAILABLE
                | EvalFrame::METAL_ALLOCATED_SIZE_UNAVAILABLE
        );
    }

    #[test]
    fn presented_frames_keep_submit_identity_and_sequence() {
        let mut session = EvalSession::default();
        session.start();
        assert!(session.begin_frame(20));
        session.finish_frame();
        assert!(session.begin_frame(21));
        session.finish_frame();
        session.record_presented(20, 1_000, 7);
        session.record_presented(21, 2_000, 8);
        let mut out = [EvalFrame::default(); 2];
        assert_eq!(session.read(&mut out), 2);
        assert_eq!(out.first().map(|frame| frame.presented_sequence), Some(1));
        assert_eq!(out.get(1).map(|frame| frame.presented_sequence), Some(2));
        assert_eq!(
            out.first().map(|frame| frame.presented_drawable_id),
            Some(7)
        );
        assert_eq!(out.get(1).map(|frame| frame.presented_drawable_id), Some(8));
    }

    #[test]
    fn energy_delta_rejects_first_sample_and_counter_reset() {
        let mut delta = EnergyDelta::default();
        assert_eq!(delta.sample(Some(10)), (None, false));
        assert_eq!(delta.sample(Some(17)), (Some(7), false));
        assert_eq!(delta.sample(Some(3)), (None, true));
        assert_eq!(delta.sample(None), (None, false));
        assert_eq!(delta.sample(Some(8)), (Some(5), false));
    }

    #[test]
    fn missing_energy_is_not_a_valid_zero_sample() {
        let mut session = EvalSession::default();
        session.start();
        assert!(session.begin_frame(9));
        session.record_energy_sample(None, false);
        session.finish_frame();
        let mut out = [EvalFrame::default(); 1];
        assert_eq!(session.read(&mut out), 1);
        let Some(frame) = out.first() else {
            return;
        };
        assert_eq!(frame.gpu_energy_nj, 0);
        assert_eq!(frame.validity_flags, EvalFrame::GPU_ENERGY_UNAVAILABLE);
    }

    #[test]
    fn stopped_session_is_empty_and_ring_is_bounded() {
        let mut session = EvalSession::default();
        session.start();
        for frame in 0..=MAX_FRAMES {
            assert!(session.begin_frame(frame as u64));
            session.finish_frame();
        }
        let mut out = [EvalFrame::default(); 1];
        assert_eq!(session.read(&mut out), 1);
        assert_eq!(out.first().map(|frame| frame.submitted_frame), Some(1));
        session.stop();
        assert_eq!(session.read(&mut out), 0);
        assert_eq!(ENERGY_SOURCE, "unavailable");
    }
}
