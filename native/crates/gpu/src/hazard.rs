use std::collections::HashMap;
use std::collections::hash_map::Entry;

use objc2::rc::Retained;
use objc2::runtime::ProtocolObject;
use objc2_metal::{MTLDevice, MTLFence, MTLStages};

use crate::command::ComputeAccess;
use crate::geometry_runtime::{AccessMode, ResourceIdentity};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ComputeHazard {
    pub producer: MTLStages,
    pub consumer: MTLStages,
}

#[derive(Clone, Copy)]
struct State {
    frame: u64,
    producer: MTLStages,
    consumers: MTLStages,
    waited: MTLStages,
}

#[derive(Default)]
pub struct Hazards {
    states: HashMap<ResourceIdentity, State>,
    fences: HashMap<ResourceIdentity, Retained<ProtocolObject<dyn MTLFence>>>,
    frame: u64,
}

impl Hazards {
    pub fn begin_frame(&mut self, frame: u64, completed: u64) {
        self.frame = frame;
        self.states.retain(|_, state| state.frame > completed);
        self.fences.retain(|root, _| self.states.contains_key(root));
    }

    pub fn write(&mut self, root: usize) -> MTLStages {
        let hazard = self.write_resource(ResourceIdentity::ImageRoot(root), MTLStages::Fragment);
        hazard.producer | hazard.consumer
    }

    pub fn write_at(&mut self, root: usize, producer: MTLStages) -> ComputeHazard {
        self.write_resource(ResourceIdentity::ImageRoot(root), producer)
    }

    pub fn read(&mut self, root: usize, stages: MTLStages) -> bool {
        self.read_resource(ResourceIdentity::ImageRoot(root), stages)
    }

    pub fn read_resource(&mut self, root: ResourceIdentity, stages: MTLStages) -> bool {
        let Some(state) = self.states.get_mut(&root) else {
            self.states.insert(
                root,
                State {
                    frame: self.frame,
                    producer: MTLStages::empty(),
                    consumers: stages,
                    waited: MTLStages::empty(),
                },
            );
            return false;
        };
        state.frame = self.frame;
        state.consumers |= stages;
        let pending = state.producer != MTLStages::empty() && !state.waited.contains(stages);
        state.waited |= stages;
        pending
    }

    pub fn compute(&mut self, root: usize, access: ComputeAccess) -> ComputeHazard {
        let mode = match access {
            ComputeAccess::Read => AccessMode::Read,
            ComputeAccess::Write => AccessMode::Write,
            ComputeAccess::ReadWrite => AccessMode::ReadWrite,
        };
        self.compute_resource(ResourceIdentity::ImageRoot(root), mode)
    }

    pub fn compute_resource(
        &mut self,
        root: ResourceIdentity,
        access: AccessMode,
    ) -> ComputeHazard {
        let stage = MTLStages::Dispatch;
        match access {
            AccessMode::Read => self.read_hazard(root, stage),
            AccessMode::Write => self.write_resource(root, stage),
            AccessMode::ReadWrite => {
                let hazard = self.read_hazard(root, stage);
                let next = self.write_resource(root, stage);
                if let Some(state) = self.states.get_mut(&root) {
                    state.consumers |= stage;
                }
                ComputeHazard {
                    producer: hazard.producer | next.producer,
                    consumer: hazard.consumer | next.consumer,
                }
            }
        }
    }

    pub fn compute_buffer(&mut self, handle: u64, access: AccessMode) -> ComputeHazard {
        self.compute_resource(ResourceIdentity::BufferHandle(handle), access)
    }

    fn read_hazard(&mut self, root: ResourceIdentity, stage: MTLStages) -> ComputeHazard {
        let producer = self
            .states
            .get(&root)
            .map_or(MTLStages::empty(), |state| state.producer);
        let pending = self.read_resource(root, stage);
        ComputeHazard {
            producer: if pending {
                producer
            } else {
                MTLStages::empty()
            },
            consumer: MTLStages::empty(),
        }
    }

    pub fn write_resource(&mut self, root: ResourceIdentity, producer: MTLStages) -> ComputeHazard {
        let hazard = self.states.get(&root).map_or(
            ComputeHazard {
                producer: MTLStages::empty(),
                consumer: MTLStages::empty(),
            },
            |state| ComputeHazard {
                producer: state.producer,
                consumer: state.consumers,
            },
        );
        self.states.insert(
            root,
            State {
                frame: self.frame,
                producer,
                consumers: MTLStages::empty(),
                waited: MTLStages::empty(),
            },
        );
        hazard
    }

    pub fn fence(
        &mut self,
        device: &ProtocolObject<dyn MTLDevice>,
        root: usize,
    ) -> Option<&ProtocolObject<dyn MTLFence>> {
        self.fence_resource(device, ResourceIdentity::ImageRoot(root))
    }

    pub fn fence_resource(
        &mut self,
        device: &ProtocolObject<dyn MTLDevice>,
        root: ResourceIdentity,
    ) -> Option<&ProtocolObject<dyn MTLFence>> {
        let fence = match self.fences.entry(root) {
            Entry::Occupied(entry) => entry.into_mut(),
            Entry::Vacant(entry) => entry.insert(device.newFence()?),
        };
        Some(&**fence)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn write_without_hazard_is_empty() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        assert!(hazards.write(1).is_empty());
    }

    #[test]
    fn write_after_render_waits_fragment() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        assert!(hazards.write(1).is_empty());
        assert_eq!(hazards.write(1), MTLStages::Fragment);
    }

    #[test]
    fn write_after_vertex_sample_waits_vertex() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        assert!(!hazards.read(1, MTLStages::Vertex));
        assert_eq!(hazards.write(1), MTLStages::Vertex);
    }

    #[test]
    fn write_after_fragment_sample_waits_fragment() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        assert!(!hazards.read(1, MTLStages::Fragment));
        assert_eq!(hazards.write(1), MTLStages::Fragment);
    }

    #[test]
    fn samples_merge_stages() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        hazards.read(1, MTLStages::Vertex);
        hazards.read(1, MTLStages::Fragment);
        assert_eq!(hazards.write(1), MTLStages::Vertex | MTLStages::Fragment);
    }

    #[test]
    fn read_read_has_no_hazard() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        assert!(!hazards.read(1, MTLStages::Vertex));
        assert!(!hazards.read(1, MTLStages::Fragment));
    }

    #[test]
    fn write_write_waits_for_previous_producer() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        hazards.compute(1, ComputeAccess::Write);
        assert_eq!(
            hazards.compute(1, ComputeAccess::Write),
            ComputeHazard {
                producer: MTLStages::Dispatch,
                consumer: MTLStages::empty(),
            }
        );
    }

    #[test]
    fn read_reports_in_flight_render() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        assert!(!hazards.read(1, MTLStages::Fragment));
        hazards.write(2);
        assert!(hazards.read(2, MTLStages::Fragment));
    }

    #[test]
    fn render_to_compute_read_waits_fragment() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        hazards.write(1);
        assert_eq!(
            hazards.compute(1, ComputeAccess::Read),
            ComputeHazard {
                producer: MTLStages::Fragment,
                consumer: MTLStages::empty(),
            }
        );
    }

    #[test]
    fn source_and_snapshot_destination_have_independent_root_tracking() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        hazards.write_at(11, MTLStages::Fragment);
        let source = hazards.compute(11, ComputeAccess::Read);
        let destination = hazards.write_at(12, MTLStages::Dispatch);
        assert_eq!(source.producer, MTLStages::Fragment);
        assert!(destination.producer.is_empty());
        assert_eq!(
            hazards.compute(12, ComputeAccess::Read).producer,
            MTLStages::Dispatch
        );
        assert_eq!(
            hazards.compute(11, ComputeAccess::Write).producer,
            MTLStages::Fragment
        );
    }

    #[test]
    fn blit_and_dispatch_consumers_are_retained_per_root() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        assert!(!hazards.read(21, MTLStages::Blit));
        assert_eq!(
            hazards.compute(21, ComputeAccess::Write).consumer,
            MTLStages::Blit
        );
        hazards.compute(22, ComputeAccess::Read);
        assert_eq!(
            hazards.compute(22, ComputeAccess::Write).consumer,
            MTLStages::Dispatch
        );
        assert_eq!(
            hazards.compute(21, ComputeAccess::Write).consumer,
            MTLStages::empty()
        );
    }

    #[test]
    fn compute_to_render_write_waits_dispatch() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        let hazard = hazards.compute(1, ComputeAccess::Write);
        assert_eq!(
            hazard,
            ComputeHazard {
                producer: MTLStages::empty(),
                consumer: MTLStages::empty(),
            }
        );
        assert_eq!(hazards.write(1), MTLStages::Dispatch);
    }

    #[test]
    fn compute_read_then_write_waits_dispatch_consumer() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        hazards.compute(1, ComputeAccess::Read);
        assert_eq!(
            hazards.compute(1, ComputeAccess::Write),
            ComputeHazard {
                producer: MTLStages::empty(),
                consumer: MTLStages::Dispatch,
            }
        );
    }

    #[test]
    fn read_write_records_both_compute_roles() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        hazards.compute(1, ComputeAccess::ReadWrite);
        assert_eq!(
            hazards.compute(1, ComputeAccess::Write),
            ComputeHazard {
                producer: MTLStages::Dispatch,
                consumer: MTLStages::Dispatch,
            }
        );
    }

    #[test]
    fn compute_write_is_disjoint_from_other_root() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        hazards.compute(1, ComputeAccess::Write);
        assert_eq!(
            hazards.compute(2, ComputeAccess::Write),
            ComputeHazard {
                producer: MTLStages::empty(),
                consumer: MTLStages::empty(),
            }
        );
    }

    #[test]
    fn compute_write_to_render_stages_is_stage_specific() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        hazards.compute_resource(ResourceIdentity::ImageRoot(31), AccessMode::Write);
        assert!(hazards.read(31, MTLStages::Vertex));
        assert!(!hazards.read(31, MTLStages::Vertex));
        assert!(hazards.read(31, MTLStages::Fragment));
    }

    #[test]
    fn compute_write_to_compute_read_waits_dispatch_once() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        hazards.compute_resource(ResourceIdentity::BufferHandle(44), AccessMode::Write);
        assert_eq!(
            hazards.compute_resource(ResourceIdentity::BufferHandle(44), AccessMode::Read),
            ComputeHazard {
                producer: MTLStages::Dispatch,
                consumer: MTLStages::empty(),
            }
        );
        assert_eq!(
            hazards.compute_resource(ResourceIdentity::BufferHandle(44), AccessMode::Read),
            ComputeHazard {
                producer: MTLStages::empty(),
                consumer: MTLStages::empty(),
            }
        );
    }

    #[test]
    fn image_and_buffer_accesses_are_independent() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        hazards.compute_resource(ResourceIdentity::ImageRoot(51), AccessMode::Write);
        assert!(
            hazards
                .compute_resource(ResourceIdentity::BufferHandle(51), AccessMode::Read)
                .producer
                .is_empty()
        );
    }

    #[test]
    fn begin_frame_prunes_completed() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        hazards.write(1);
        hazards.compute(2, ComputeAccess::ReadWrite);
        hazards.begin_frame(1, 1);
        assert!(hazards.write(1).is_empty());
        let hazard = hazards.compute(2, ComputeAccess::Write);
        assert!(hazard.producer.is_empty());
        assert!(hazard.consumer.is_empty());
    }

    #[test]
    fn compute_to_compute_has_only_dispatch_dependency() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        hazards.compute_resource(ResourceIdentity::BufferHandle(61), AccessMode::Write);
        assert_eq!(
            hazards.compute_resource(ResourceIdentity::BufferHandle(61), AccessMode::Read),
            ComputeHazard {
                producer: MTLStages::Dispatch,
                consumer: MTLStages::empty(),
            }
        );
    }

    #[test]
    fn compute_to_vertex_and_fragment_keep_consumer_stage_exact() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        hazards.compute_resource(ResourceIdentity::ImageRoot(62), AccessMode::Write);
        assert!(hazards.read(62, MTLStages::Vertex));
        assert_eq!(hazards.write(62), MTLStages::Dispatch | MTLStages::Vertex);

        hazards.compute_resource(ResourceIdentity::ImageRoot(63), AccessMode::Write);
        assert!(hazards.read(63, MTLStages::Fragment));
        assert_eq!(hazards.write(63), MTLStages::Dispatch | MTLStages::Fragment);
    }

    #[test]
    fn image_ssbo_and_atomic_write_accesses_track_independently() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        hazards.compute_resource(ResourceIdentity::ImageRoot(63), AccessMode::ReadWrite);
        hazards.compute_resource(ResourceIdentity::BufferHandle(63), AccessMode::Write);
        assert_eq!(
            hazards.compute_resource(ResourceIdentity::ImageRoot(63), AccessMode::Write),
            ComputeHazard {
                producer: MTLStages::Dispatch,
                consumer: MTLStages::Dispatch,
            }
        );
        assert_eq!(
            hazards.compute_resource(ResourceIdentity::BufferHandle(63), AccessMode::Read),
            ComputeHazard {
                producer: MTLStages::Dispatch,
                consumer: MTLStages::empty(),
            }
        );
        assert!(
            hazards
                .compute_resource(ResourceIdentity::BufferHandle(64), AccessMode::Read)
                .producer
                .is_empty()
        );
    }

    #[test]
    fn render_write_waits_only_for_overlapping_resource() {
        let mut hazards = Hazards::default();
        hazards.begin_frame(0, 0);
        hazards.compute_resource(ResourceIdentity::BufferHandle(65), AccessMode::Write);
        assert!(!hazards.read_resource(ResourceIdentity::BufferHandle(66), MTLStages::Vertex));
        assert!(!hazards.read_resource(ResourceIdentity::ImageRoot(65), MTLStages::Fragment));
        assert!(hazards.read_resource(ResourceIdentity::BufferHandle(65), MTLStages::Vertex));
    }
}
