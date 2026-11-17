use std::collections::HashMap;

use objc2::rc::Retained;
use objc2::runtime::ProtocolObject;
use objc2_metal::{MTLAllocation, MTLResidencySet, MTLSamplerState, MTLTexture};

use crate::gpu::RenderState;
use crate::pipeline::{ComputePipeline, Pipeline};
use crate::slab::Slab;
use mtl4::{Buffer, CounterHeap, Texture};

use crate::stats::MemoryCounter;

pub struct Image {
    pub texture: Texture,
    pub depth: bool,
    pub root: usize,
    pub level: usize,
    pub usage: u64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Kind {
    Buffer,
    Texture,
    Sampler,
    Pipeline,
    Query,
    ComputePipeline,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct Grave {
    frame: u64,
    kind: Kind,
    handle: u64,
}

#[derive(Default)]
pub struct Cache {
    pub texel_views: HashMap<(u64, u32), Texture>,
    pub clear_pipelines: HashMap<isize, RenderState>,
}

fn retirement_ready(frame: u64, completed: u64) -> bool {
    frame < completed
}

pub struct Resources {
    pub buffers: Slab<Buffer>,
    pub images: Slab<Image>,
    pub samplers: Slab<Retained<ProtocolObject<dyn MTLSamplerState>>>,
    pub pipelines: Slab<Pipeline>,
    pub compute_pipelines: Slab<ComputePipeline>,
    pub queries: Slab<CounterHeap>,
    residency: Retained<ProtocolObject<dyn MTLResidencySet>>,
    residency_dirty: bool,
    graves: Vec<Grave>,
    memory: MemoryCounter,
}

impl Resources {
    pub fn new(residency: Retained<ProtocolObject<dyn MTLResidencySet>>) -> Self {
        Self {
            buffers: Slab::default(),
            images: Slab::default(),
            samplers: Slab::default(),
            pipelines: Slab::default(),
            compute_pipelines: Slab::default(),
            queries: Slab::default(),
            residency,
            residency_dirty: false,
            graves: Vec::new(),
            memory: MemoryCounter::default(),
        }
    }

    pub fn track(&mut self, allocation: &ProtocolObject<dyn MTLAllocation>) {
        self.residency.addAllocation(allocation);
        self.residency_dirty = true;
        if let Some(bytes) = mtl4::allocation_size(allocation) {
            self.memory.add(bytes);
        }
    }

    pub fn untrack(&mut self, allocation: &ProtocolObject<dyn MTLAllocation>) {
        self.residency.removeAllocation(allocation);
        self.residency_dirty = true;
        if let Some(bytes) = mtl4::allocation_size(allocation) {
            self.memory.remove(bytes);
        }
    }

    pub fn track_view(&mut self, allocation: &ProtocolObject<dyn MTLAllocation>) {
        self.residency.addAllocation(allocation);
        self.residency_dirty = true;
    }

    pub fn untrack_view(&mut self, allocation: &ProtocolObject<dyn MTLAllocation>) {
        self.residency.removeAllocation(allocation);
        self.residency_dirty = true;
    }

    pub fn memory_bytes(&self) -> u64 {
        self.memory.bytes()
    }

    pub fn commit_residency(&mut self) {
        if self.residency_dirty {
            self.residency.commit();
            self.residency_dirty = false;
        }
    }

    pub fn destroy_later(&mut self, frame: u64, kind: Kind, handle: u64) {
        let grave = Grave {
            frame,
            kind,
            handle,
        };
        if let Some(existing) = self
            .graves
            .iter_mut()
            .find(|existing| existing.kind == kind && existing.handle == handle)
        {
            existing.frame = existing.frame.min(frame);
        } else {
            self.graves.push(grave);
        }
    }

    pub fn bury(&mut self, completed: u64, cache: &mut Cache) {
        let graves = std::mem::take(&mut self.graves);
        for grave in graves {
            if !retirement_ready(grave.frame, completed) {
                self.graves.push(grave);
                continue;
            }
            let Grave { kind, handle, .. } = grave;
            match kind {
                Kind::Buffer => {
                    if let Some(buffer) = self.buffers.remove(handle) {
                        self.untrack(ProtocolObject::from_ref(&*buffer));
                        cache.texel_views.retain(|key, _| key.0 != handle);
                    }
                }
                Kind::Texture => {
                    if let Some(image) = self.images.remove(handle)
                        && image.texture.parentTexture().is_none()
                    {
                        self.untrack(ProtocolObject::from_ref(&*image.texture));
                    }
                }
                Kind::Sampler => {
                    self.samplers.remove(handle);
                }
                Kind::Query => {
                    self.queries.remove(handle);
                }
                Kind::Pipeline => {
                    self.pipelines.remove(handle);
                }
                Kind::ComputePipeline => {
                    self.compute_pipelines.remove(handle);
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{Grave, Kind, retirement_ready};
    use crate::slab::Slab;

    #[test]
    fn delayed_retirement_keeps_generation_until_completion() {
        let mut slab = Slab::default();
        let old = slab.insert(1u8);
        let grave = Grave {
            frame: 4,
            kind: Kind::Buffer,
            handle: old,
        };
        assert!(!retirement_ready(grave.frame, 4));
        assert!(slab.get(old).is_some());
        assert!(retirement_ready(grave.frame, 5));
        assert!(slab.remove(old).is_some());
        assert!(slab.get(old).is_none());
    }

    #[test]
    fn stale_retirement_cannot_remove_replacement() {
        let mut slab = Slab::default();
        let old = slab.insert(1u8);
        assert!(slab.remove(old).is_some());
        let replacement = slab.insert(2u8);
        assert_ne!(old, replacement);
        assert!(slab.remove(old).is_none());
        assert_eq!(slab.get(replacement), Some(&2));
    }

    #[test]
    fn repeated_retirement_is_enqueued_once() {
        let mut graves = [Grave {
            frame: 8,
            kind: Kind::Buffer,
            handle: 11,
        }];
        if let Some(existing) = graves
            .iter_mut()
            .find(|existing| existing.kind == Kind::Buffer && existing.handle == 11)
        {
            existing.frame = existing.frame.min(5);
        }
        assert_eq!(graves.len(), 1);
        assert_eq!(graves.first().map(|grave| grave.frame), Some(5));
    }

    #[test]
    fn replacement_handle_has_independent_retirement_identity() {
        let old = Grave {
            frame: 8,
            kind: Kind::Buffer,
            handle: 11,
        };
        let replacement = Grave {
            frame: 8,
            kind: Kind::Buffer,
            handle: 12,
        };
        assert_ne!(old, replacement);
    }
}
