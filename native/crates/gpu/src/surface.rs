use std::sync::atomic::{AtomicU8, AtomicU64, Ordering};
use std::sync::mpsc::{Receiver, TryRecvError, sync_channel};

use objc2::Message;
use objc2::rc::Retained;
use objc2::runtime::ProtocolObject;
use objc2_app_kit::{NSWindow, NSWindowOcclusionState};
use objc2_foundation::NSSize;
use objc2_metal::{MTL4CommandQueue, MTLDevice, MTLPixelFormat};
use objc2_quartz_core::{CAMetalDrawable, CAMetalLayer};

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum PresentMode {
    Fifo = 0,
    Immediate = 1,
    Offscreen = 2,
}

impl PresentMode {
    pub fn from_code(code: u32) -> Self {
        match code {
            1 => Self::Immediate,
            2 => Self::Offscreen,
            _ => Self::Fifo,
        }
    }
}

pub(crate) struct PresentedEvent {
    pub generation: u64,
    pub submitted_frame: u64,
    pub timestamp_ns: u64,
    pub drawable_id: u64,
}

pub struct Surface {
    window: Retained<NSWindow>,
    pub layer: Retained<CAMetalLayer>,
    mode: AtomicU8,
    generation: AtomicU64,
    acquired: AtomicU64,
    presented: AtomicU64,
    events: Vec<Receiver<PresentedEvent>>,
}

impl Surface {
    pub(crate) fn attach(
        device: &ProtocolObject<dyn MTLDevice>,
        queue: &ProtocolObject<dyn MTL4CommandQueue>,
        window: &NSWindow,
    ) -> Option<Self> {
        let view = window.contentView()?;
        let layer = CAMetalLayer::new();
        layer.setDevice(Some(device));
        layer.setPixelFormat(MTLPixelFormat::BGRA8Unorm);
        layer.setFramebufferOnly(true);
        layer.setOpaque(true);
        layer.setMaximumDrawableCount(3);
        layer.setAllowsNextDrawableTimeout(false);
        layer.setContentsScale(window.backingScaleFactor());
        view.setLayer(Some(&layer));
        view.setWantsLayer(true);
        queue.addResidencySet(&layer.residencySet());
        Some(Self {
            window: window.retain(),
            layer,
            mode: AtomicU8::new(PresentMode::Fifo as u8),
            generation: AtomicU64::new(0),
            acquired: AtomicU64::new(0),
            presented: AtomicU64::new(0),
            events: Vec::new(),
        })
    }

    pub fn configure(&self, width: u32, height: u32, mode: PresentMode, _refresh: u32) {
        self.layer.setDrawableSize(NSSize {
            width: f64::from(width),
            height: f64::from(height),
        });
        self.layer
            .setDisplaySyncEnabled(mode != PresentMode::Immediate);
        self.mode.store(mode as u8, Ordering::Release);
        self.acquired.store(0, Ordering::Release);
        self.presented.store(0, Ordering::Release);
        self.generation.fetch_add(1, Ordering::AcqRel);
    }

    pub fn offscreen(&self) -> bool {
        self.mode.load(Ordering::Relaxed) == PresentMode::Offscreen as u8
    }

    pub fn visible(&self) -> bool {
        self.window
            .occlusionState()
            .contains(NSWindowOcclusionState::Visible)
    }

    pub fn next_drawable(
        &mut self,
        submitted_frame: u64,
        evaluation: bool,
    ) -> Option<Retained<ProtocolObject<dyn CAMetalDrawable>>> {
        let generation = self.generation.load(Ordering::Acquire);
        let drawable = self.layer.nextDrawable()?;
        self.acquired.fetch_add(1, Ordering::Relaxed);
        if evaluation && self.mode.load(Ordering::Acquire) == PresentMode::Fifo as u8 {
            let (presented, events) = sync_channel(1);
            self.events.push(events);
            mtl4::add_presented_handler(&drawable, submitted_frame, move |frame, timestamp, id| {
                let _ = presented.try_send(PresentedEvent {
                    generation,
                    submitted_frame: frame,
                    timestamp_ns: timestamp,
                    drawable_id: id,
                });
            });
        }
        Some(drawable)
    }

    pub(crate) fn drain_presented(&mut self, eval: &mut crate::eval::EvalSession) {
        self.events.retain_mut(|events| {
            loop {
                match events.try_recv() {
                    Ok(event) => {
                        if event.generation == self.generation.load(Ordering::Acquire) {
                            self.presented.fetch_add(1, Ordering::Relaxed);
                            eval.record_presented(
                                event.submitted_frame,
                                event.timestamp_ns,
                                event.drawable_id,
                            );
                        }
                    }
                    Err(TryRecvError::Empty) => return true,
                    Err(TryRecvError::Disconnected) => return false,
                }
            }
        });
    }
}
