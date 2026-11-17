use objc2::rc::Retained;
use objc2::runtime::ProtocolObject;
use objc2_metal::{
    MTL4CommandAllocator, MTL4CommandBuffer, MTL4CommandQueue, MTLAllocation, MTLDevice,
    MTLResidencySet, MTLResidencySetDescriptor, MTLResource, MTLResourceOptions, MTLSharedEvent,
};
use std::sync::mpsc::sync_channel;

use mtl4::Buffer;

pub const FRAMES_IN_FLIGHT: u64 = 2;
pub const SCRATCH_SIZE: usize = 64 * 1024;
const WAIT_TIMEOUT_MS: u64 = 5000;

pub struct Frame {
    pub allocator: Retained<ProtocolObject<dyn MTL4CommandAllocator>>,
    pub scratch: Buffer,
}

pub struct CalibrationOwner {
    pub allocator: Retained<ProtocolObject<dyn MTL4CommandAllocator>>,
    pub command: Retained<ProtocolObject<dyn MTL4CommandBuffer>>,
    pub event: Retained<ProtocolObject<dyn MTLSharedEvent>>,
    residency: Retained<ProtocolObject<dyn MTLResidencySet>>,
    value: u64,
}

fn ignore_gpu_time(_: f64, _: f64) {}

impl CalibrationOwner {
    pub fn new(
        device: &ProtocolObject<dyn MTLDevice>,
        queue: &ProtocolObject<dyn MTL4CommandQueue>,
    ) -> Option<Self> {
        let residency = device
            .newResidencySetWithDescriptor_error(&MTLResidencySetDescriptor::new())
            .ok()?;
        queue.addResidencySet(&residency);
        Some(Self {
            allocator: device.newCommandAllocator()?,
            command: device.newCommandBuffer()?,
            event: device.newSharedEvent()?,
            residency,
            value: 0,
        })
    }

    pub fn track(&self, allocation: &ProtocolObject<dyn MTLAllocation>) {
        self.residency.addAllocation(allocation);
    }

    pub fn track_commit(&self) {
        self.residency.commit();
    }

    pub fn completion(&self) -> u64 {
        self.value
    }

    pub fn prepare(&mut self) {
        self.allocator.reset();
        self.command
            .beginCommandBufferWithAllocator(&self.allocator);
        self.command.useResidencySet(&self.residency);
    }

    pub fn commit(&mut self, queue: &ProtocolObject<dyn MTL4CommandQueue>) -> Option<u64> {
        let value = self.value.checked_add(1)?;
        let (sender, _receiver) = sync_channel(1);
        mtl4::commit(queue, &self.command, value, sender, ignore_gpu_time);
        self.value = value;
        queue.signalEvent_value(ProtocolObject::from_ref(&*self.event), value);
        Some(value)
    }

    pub fn completed(&self) -> u64 {
        self.event.signaledValue()
    }

    pub fn untrack(&self, allocation: &ProtocolObject<dyn MTLAllocation>) {
        self.residency.removeAllocation(allocation);
        self.residency.commit();
    }

    pub fn wait(&self, value: u64, timeout_ms: u64) -> bool {
        self.event
            .waitUntilSignaledValue_timeoutMS(value, timeout_ms)
    }
}

pub struct Frames {
    slots: Vec<Frame>,
    pub event: Retained<ProtocolObject<dyn MTLSharedEvent>>,
    pub submitted: u64,
    scratch_bytes: u64,
}

impl Frames {
    pub fn new(device: &ProtocolObject<dyn MTLDevice>) -> Option<Self> {
        let slots = (0..FRAMES_IN_FLIGHT)
            .map(|_| {
                Some(Frame {
                    allocator: device.newCommandAllocator()?,
                    scratch: device.newBufferWithLength_options(
                        SCRATCH_SIZE,
                        MTLResourceOptions::StorageModeShared,
                    )?,
                })
            })
            .collect::<Option<Vec<_>>>()?;
        let scratch_bytes = slots.iter().fold(0_u64, |total, frame| {
            total.saturating_add(
                u64::try_from(MTLResource::allocatedSize(&*frame.scratch)).unwrap_or(0),
            )
        });
        Some(Self {
            slots,
            event: device.newSharedEvent()?,
            submitted: 0,
            scratch_bytes,
        })
    }

    pub fn slots(&self) -> &[Frame] {
        &self.slots
    }

    pub fn memory_bytes(&self) -> u64 {
        self.scratch_bytes
    }

    pub fn completed(&self) -> u64 {
        self.event.signaledValue()
    }

    pub fn wait(&self, frame: u64, timeout_ms: u64) -> bool {
        completion_value(frame).is_some_and(|value| {
            self.event
                .waitUntilSignaledValue_timeoutMS(value, timeout_ms)
        })
    }

    pub fn begin(&self) -> Option<&Frame> {
        if self.submitted >= FRAMES_IN_FLIGHT {
            self.wait(self.submitted - FRAMES_IN_FLIGHT, WAIT_TIMEOUT_MS);
        }
        let frame = self
            .slots
            .get((self.submitted % FRAMES_IN_FLIGHT) as usize)?;
        frame.allocator.reset();
        Some(frame)
    }
}

fn completion_value(frame: u64) -> Option<u64> {
    frame.checked_add(1)
}

#[cfg(test)]
mod tests {
    use super::completion_value;

    #[test]
    fn frame_wait_uses_next_completion_value() {
        assert_eq!(completion_value(7), Some(8));
    }

    #[test]
    fn frame_wait_rejects_counter_overflow() {
        assert_eq!(completion_value(u64::MAX), None);
    }
}
