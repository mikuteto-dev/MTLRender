use objc2::runtime::ProtocolObject;
use objc2_metal::{MTLDevice, MTLResourceOptions};

use crate::geometry_compute::IndirectArguments;
use crate::gpu::Gpu;
use crate::resources::{Kind, Resources};

#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd)]
pub struct GenerationId(u64);

impl GenerationId {
    pub const fn new(value: u64) -> Self {
        Self(value)
    }

    pub const fn value(self) -> u64 {
        self.0
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum GeometryStage {
    Dispatch,
    Vertex,
    Fragment,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct GeometryStages(u8);

impl GeometryStages {
    pub const EMPTY: Self = Self(0);
    pub const DISPATCH: Self = Self(1);
    pub const VERTEX: Self = Self(2);
    pub const FRAGMENT: Self = Self(4);

    pub const fn empty(self) -> bool {
        self.0 == 0
    }

    pub const fn contains(self, stage: GeometryStage) -> bool {
        self.0 & Self::from_stage(stage).0 != 0
    }

    pub const fn union(self, other: Self) -> Self {
        Self(self.0 | other.0)
    }

    pub const fn from_stage(stage: GeometryStage) -> Self {
        match stage {
            GeometryStage::Dispatch => Self::DISPATCH,
            GeometryStage::Vertex => Self::VERTEX,
            GeometryStage::Fragment => Self::FRAGMENT,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AccessMode {
    Read,
    Write,
    ReadWrite,
}

impl AccessMode {
    const fn merge(self, other: Self) -> Self {
        match (self, other) {
            (Self::Read, Self::Read) => Self::Read,
            (Self::Write, Self::Write) => Self::Write,
            _ => Self::ReadWrite,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum AccessKind {
    Image,
    Ssbo,
    AtomicBuffer,
    ScratchBuffer,
    Pipeline,
    Resource,
    IndirectArguments,
}

#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub enum ResourceIdentity {
    ImageRoot(usize),
    BufferHandle(u64),
    PipelineHandle(u64),
    ResourceHandle(u64),
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct AccessRecord {
    pub(crate) kind: AccessKind,
    pub(crate) resource: ResourceIdentity,
    pub(crate) mode: AccessMode,
    pub(crate) producer: GeometryStage,
    pub(crate) consumers: GeometryStages,
}

impl AccessRecord {
    pub const fn new(
        kind: AccessKind,
        resource: ResourceIdentity,
        mode: AccessMode,
        producer: GeometryStage,
        consumers: GeometryStages,
    ) -> Self {
        Self {
            kind,
            resource,
            mode,
            producer,
            consumers,
        }
    }

    pub const fn kind(self) -> AccessKind {
        self.kind
    }

    pub const fn resource(self) -> ResourceIdentity {
        self.resource
    }

    pub const fn mode(self) -> AccessMode {
        self.mode
    }

    pub const fn producer(self) -> GeometryStage {
        self.producer
    }

    pub const fn consumers(self) -> GeometryStages {
        self.consumers
    }

    fn merge(&mut self, other: Self) {
        self.mode = self.mode.merge(other.mode);
        self.producer = other.producer;
        self.consumers = self.consumers.union(other.consumers);
    }
}

#[derive(Default, Debug, Eq, PartialEq)]
pub struct AccessSet {
    records: Vec<AccessRecord>,
}

impl AccessSet {
    pub fn insert(&mut self, record: AccessRecord) {
        if let Some(existing) = self
            .records
            .iter_mut()
            .find(|existing| existing.kind == record.kind && existing.resource == record.resource)
        {
            existing.merge(record);
        } else {
            self.records.push(record);
        }
    }

    pub fn normalize(&mut self) {
        self.records
            .sort_by_key(|record| (record.kind, record.resource));
    }

    pub fn records(&self) -> &[AccessRecord] {
        &self.records
    }

    pub fn len(&self) -> usize {
        self.records.len()
    }

    pub fn is_empty(&self) -> bool {
        self.records.is_empty()
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CandidateState {
    Compiling,
    Encoded,
    Failed,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CandidateGeneration {
    pub(crate) id: GenerationId,
    pub(crate) state: CandidateState,
}

#[derive(Default, Debug, Eq, PartialEq)]
pub struct GenerationLifecycle {
    active: Option<GenerationId>,
    next: Option<GenerationId>,
    candidate: Option<CandidateGeneration>,
}

impl GenerationLifecycle {
    pub fn active(&self) -> Option<GenerationId> {
        self.active
    }

    pub fn next(&self) -> Option<GenerationId> {
        self.next
    }

    pub fn candidate(&self) -> Option<CandidateGeneration> {
        self.candidate
    }

    pub fn set_active(&mut self, id: GenerationId) {
        self.active = Some(id);
    }

    pub fn set_next(&mut self, id: GenerationId) {
        self.next = Some(id);
    }

    pub fn begin_candidate(&mut self, id: GenerationId) -> bool {
        if self.candidate.is_some() || self.active == Some(id) || self.next == Some(id) {
            return false;
        }
        self.candidate = Some(CandidateGeneration {
            id,
            state: CandidateState::Compiling,
        });
        true
    }

    pub fn mark_candidate(&mut self, id: GenerationId, state: CandidateState) -> bool {
        let Some(candidate) = self.candidate.as_mut() else {
            return false;
        };
        if candidate.id != id {
            return false;
        }
        candidate.state = state;
        true
    }

    pub fn discard_candidate(&mut self, id: GenerationId) -> bool {
        if self.candidate.is_some_and(|candidate| candidate.id == id) {
            self.candidate = None;
            true
        } else {
            false
        }
    }

    fn activate_candidate(&mut self, id: GenerationId) -> bool {
        if self.candidate.is_some_and(|candidate| {
            candidate.id == id && candidate.state == CandidateState::Encoded
        }) {
            self.active = Some(id);
            self.candidate = None;
            true
        } else {
            false
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum OutputState {
    Emitted {
        primitives: u32,
        arguments: IndirectArguments,
    },
    ZeroOutput,
}

impl OutputState {
    pub const fn zero() -> Self {
        Self::ZeroOutput
    }

    pub const fn primitives(self) -> u32 {
        match self {
            Self::Emitted { primitives, .. } => primitives,
            Self::ZeroOutput => 0,
        }
    }

    pub const fn arguments(self) -> Option<IndirectArguments> {
        match self {
            Self::Emitted { arguments, .. } => Some(arguments),
            Self::ZeroOutput => None,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RetirementIntent {
    pub(crate) frame: u64,
    pub(crate) output: Option<u64>,
    pub(crate) arguments: Option<u64>,
    pub(crate) state: OutputState,
}

impl RetirementIntent {
    pub const fn new(
        frame: u64,
        output: Option<u64>,
        arguments: Option<u64>,
        state: OutputState,
    ) -> Self {
        Self {
            frame,
            output,
            arguments,
            state,
        }
    }

    pub const fn retires_zero_output(self) -> bool {
        matches!(self.state, OutputState::ZeroOutput)
            && self.output.is_some()
            && self.arguments.is_some()
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct DispatchStats {
    pub(crate) dispatches: u32,
    pub(crate) input_primitives: u64,
    pub(crate) zero_output: u32,
    pub(crate) emitted_primitives: u64,
    pub(crate) retired_outputs: u32,
    pub(crate) failures: u32,
    pub(crate) recoveries: u32,
}

impl DispatchStats {
    pub const fn dispatches(self) -> u32 {
        self.dispatches
    }

    pub const fn input_primitives(self) -> u64 {
        self.input_primitives
    }

    pub const fn zero_output(self) -> u32 {
        self.zero_output
    }

    pub const fn emitted_primitives(self) -> u64 {
        self.emitted_primitives
    }

    pub const fn retired_outputs(self) -> u32 {
        self.retired_outputs
    }

    pub const fn failures(self) -> u32 {
        self.failures
    }

    pub const fn recoveries(self) -> u32 {
        self.recoveries
    }

    pub fn record_input(&mut self, primitives: u64) {
        self.input_primitives = self.input_primitives.saturating_add(primitives);
    }

    pub fn record_failure(&mut self) {
        self.failures = self.failures.saturating_add(1);
    }

    pub fn record_recovery(&mut self) {
        self.recoveries = self.recoveries.saturating_add(1);
    }

    pub fn record(&mut self, output: OutputState) {
        self.dispatches = self.dispatches.saturating_add(1);
        self.emitted_primitives = self
            .emitted_primitives
            .saturating_add(u64::from(output.primitives()));
        if matches!(output, OutputState::ZeroOutput) {
            self.zero_output = self.zero_output.saturating_add(1);
        }
    }

    pub fn record_retirement(&mut self, intent: RetirementIntent) {
        if intent.output.is_some() || intent.arguments.is_some() {
            self.retired_outputs = self.retired_outputs.saturating_add(1);
        }
    }
}

pub struct GeometryRuntime {
    lifecycle: GenerationLifecycle,
    active_program: Option<ResourceIdentity>,
    candidate_program: Option<ResourceIdentity>,
    candidate_output: Option<u64>,
    candidate_arguments: Option<u64>,
    active_output: Option<u64>,
    active_arguments: Option<u64>,
    accesses: AccessSet,
    request_open: bool,
    stats: DispatchStats,
}

impl Default for GeometryRuntime {
    fn default() -> Self {
        Self::new()
    }
}

impl GeometryRuntime {
    pub fn initialize(_gpu: &Gpu, _resources: &mut Resources) -> Option<Self> {
        Some(Self::new())
    }

    pub const fn new() -> Self {
        Self {
            lifecycle: GenerationLifecycle {
                active: None,
                next: None,
                candidate: None,
            },
            active_program: None,
            candidate_program: None,
            candidate_output: None,
            candidate_arguments: None,
            active_output: None,
            active_arguments: None,
            accesses: AccessSet {
                records: Vec::new(),
            },
            request_open: false,
            stats: DispatchStats {
                dispatches: 0,
                input_primitives: 0,
                zero_output: 0,
                emitted_primitives: 0,
                retired_outputs: 0,
                failures: 0,
                recoveries: 0,
            },
        }
    }

    pub fn lifecycle(&self) -> &GenerationLifecycle {
        &self.lifecycle
    }

    pub fn accesses(&self) -> &AccessSet {
        &self.accesses
    }

    pub fn stats(&self) -> DispatchStats {
        self.stats
    }

    pub fn prepare(&mut self, _frame: u64, _completed: u64) {
        self.accesses = AccessSet::default();
        self.request_open = false;
    }

    pub fn begin_request(&mut self, program: ResourceIdentity, generation: GenerationId) -> bool {
        if self.request_open
            || self.lifecycle.active() == Some(generation)
            || !self.lifecycle.begin_candidate(generation)
        {
            return false;
        }
        self.request_open = true;
        self.candidate_program = Some(program);
        self.accesses = AccessSet::default();
        true
    }

    pub fn discard_request(&mut self) -> bool {
        let Some(candidate) = self.lifecycle.candidate() else {
            return false;
        };
        if !self.request_open {
            return false;
        }
        self.candidate_output = None;
        self.candidate_arguments = None;
        self.candidate_program = None;
        self.lifecycle.discard_candidate(candidate.id);
        self.request_open = false;
        self.stats.record_failure();
        self.active_program.is_some()
    }

    pub fn collect_access(&mut self, access: AccessRecord) -> bool {
        if !self.request_open {
            return false;
        }
        self.accesses.insert(access);
        true
    }

    pub fn normalize_accesses(&mut self) -> bool {
        if !self.request_open {
            return false;
        }
        self.accesses.normalize();
        true
    }

    pub fn allocate_output(
        &mut self,
        gpu: &Gpu,
        resources: &mut Resources,
        bytes: usize,
    ) -> Option<u64> {
        if !self.request_open || self.candidate_output.is_some() {
            return None;
        }
        let buffer = gpu
            .device
            .newBufferWithLength_options(bytes.max(16), MTLResourceOptions::StorageModeShared)?;
        resources.track(ProtocolObject::from_ref(&*buffer));
        let handle = resources.buffers.insert(buffer);
        self.candidate_output = Some(handle);
        Some(handle)
    }

    pub fn set_arguments(&mut self, arguments: u64) -> bool {
        if !self.request_open {
            return false;
        }
        self.candidate_arguments = Some(arguments);
        true
    }

    pub fn finish_request(
        &mut self,
        resources: &mut Resources,
        frame: u64,
        input_primitives: u64,
        output: OutputState,
    ) -> bool {
        let Some(candidate) = self.lifecycle.candidate() else {
            return false;
        };
        if !self.request_open
            || !self
                .lifecycle
                .mark_candidate(candidate.id, CandidateState::Encoded)
        {
            return false;
        }
        self.accesses.normalize();
        self.stats.record_input(input_primitives);
        self.stats.record(output);
        if let Some(handle) = self.active_output {
            resources.destroy_later(frame, Kind::Buffer, handle);
        }
        if let Some(handle) = self.active_arguments {
            resources.destroy_later(frame, Kind::Buffer, handle);
        }
        self.active_output = self.candidate_output.take();
        self.active_arguments = self.candidate_arguments.take();
        self.lifecycle.activate_candidate(candidate.id);
        self.active_program = self.candidate_program.take();
        self.request_open = false;
        true
    }

    pub fn fail_request(&mut self, resources: &mut Resources, frame: u64) -> bool {
        let Some(candidate) = self.lifecycle.candidate() else {
            return false;
        };
        if !self.request_open
            || !self
                .lifecycle
                .mark_candidate(candidate.id, CandidateState::Failed)
        {
            return false;
        }
        if let Some(handle) = self.candidate_output.take() {
            resources.destroy_later(frame, Kind::Buffer, handle);
        }
        self.candidate_arguments = None;
        self.candidate_program = None;
        self.lifecycle.discard_candidate(candidate.id);
        self.request_open = false;
        self.stats.record_failure();
        if self.active_program.is_some() {
            self.stats.record_recovery();
        }
        true
    }

    pub fn retire(&mut self, resources: &mut Resources, frame: u64) {
        if let Some(handle) = self.active_output.take() {
            resources.destroy_later(frame, Kind::Buffer, handle);
            self.stats.retired_outputs = self.stats.retired_outputs.saturating_add(1);
        }
        if let Some(handle) = self.active_arguments.take() {
            resources.destroy_later(frame, Kind::Buffer, handle);
        }
        self.active_program = None;
        self.lifecycle = GenerationLifecycle::default();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn access(kind: AccessKind, resource: ResourceIdentity, mode: AccessMode) -> AccessRecord {
        AccessRecord::new(
            kind,
            resource,
            mode,
            GeometryStage::Dispatch,
            GeometryStages::DISPATCH,
        )
    }

    #[test]
    fn normalization_merges_read_write_once() {
        let mut set = AccessSet::default();
        set.insert(access(
            AccessKind::Ssbo,
            ResourceIdentity::BufferHandle(9),
            AccessMode::Read,
        ));
        let mut write = access(
            AccessKind::Ssbo,
            ResourceIdentity::BufferHandle(9),
            AccessMode::Write,
        );
        write.consumers = GeometryStages::VERTEX;
        set.insert(write);
        set.normalize();
        assert_eq!(set.len(), 1);
        let Some(record) = set.records().first() else {
            return;
        };
        assert_eq!(record.mode, AccessMode::ReadWrite);
        assert!(record.consumers.contains(GeometryStage::Vertex));
    }

    #[test]
    fn independent_resources_remain_separate() {
        let mut set = AccessSet::default();
        set.insert(access(
            AccessKind::Image,
            ResourceIdentity::ImageRoot(3),
            AccessMode::Read,
        ));
        set.insert(access(
            AccessKind::Image,
            ResourceIdentity::ImageRoot(4),
            AccessMode::Read,
        ));
        set.insert(access(
            AccessKind::Ssbo,
            ResourceIdentity::BufferHandle(3),
            AccessMode::Read,
        ));
        assert_eq!(set.len(), 3);
    }

    #[test]
    fn generation_identity_keeps_failed_candidate_disposable() {
        let mut lifecycle = GenerationLifecycle::default();
        lifecycle.set_active(GenerationId::new(4));
        lifecycle.set_next(GenerationId::new(5));
        assert!(lifecycle.begin_candidate(GenerationId::new(6)));
        assert!(lifecycle.mark_candidate(GenerationId::new(6), CandidateState::Failed));
        assert!(lifecycle.discard_candidate(GenerationId::new(6)));
        assert_eq!(lifecycle.active(), Some(GenerationId::new(4)));
        assert_eq!(lifecycle.next(), Some(GenerationId::new(5)));
        assert!(lifecycle.candidate().is_none());
    }

    #[test]
    fn zero_output_retirement_keeps_frame_and_allocations() {
        let intent = RetirementIntent::new(27, Some(11), Some(12), OutputState::zero());
        assert!(intent.retires_zero_output());
        assert_eq!(intent.frame, 27);
        let mut stats = DispatchStats::default();
        stats.record(OutputState::zero());
        stats.record_retirement(intent);
        assert_eq!(stats.zero_output, 1);
        assert_eq!(stats.retired_outputs, 1);
    }

    #[test]
    fn runtime_collects_stage_declarations_without_merging_resources() {
        let mut runtime = GeometryRuntime::new();
        assert!(runtime.begin_request(ResourceIdentity::PipelineHandle(7), GenerationId::new(3),));
        assert!(runtime.collect_access(AccessRecord::new(
            AccessKind::Image,
            ResourceIdentity::ImageRoot(1),
            AccessMode::ReadWrite,
            GeometryStage::Dispatch,
            GeometryStages::VERTEX.union(GeometryStages::FRAGMENT),
        )));
        assert!(runtime.collect_access(AccessRecord::new(
            AccessKind::Ssbo,
            ResourceIdentity::BufferHandle(1),
            AccessMode::ReadWrite,
            GeometryStage::Dispatch,
            GeometryStages::FRAGMENT,
        )));
        assert!(runtime.normalize_accesses());
        assert_eq!(runtime.accesses().len(), 2);
        assert_eq!(
            runtime
                .accesses()
                .records()
                .first()
                .map(|record| record.kind()),
            Some(AccessKind::Image)
        );
        assert_eq!(
            runtime
                .accesses()
                .records()
                .get(1)
                .map(|record| record.kind()),
            Some(AccessKind::Ssbo)
        );
    }

    #[test]
    fn failed_candidate_can_be_followed_by_a_successful_candidate() {
        let mut runtime = GeometryRuntime::new();
        assert!(runtime.begin_request(ResourceIdentity::PipelineHandle(9), GenerationId::new(1),));
        runtime.candidate_output = Some(31);
        runtime.candidate_arguments = Some(32);
        assert_eq!(
            runtime.lifecycle().candidate().map(|value| value.id),
            Some(GenerationId::new(1))
        );
        assert_eq!(runtime.stats().failures(), 0);
        assert!(!runtime.discard_request());
        assert!(runtime.candidate_output.is_none());
        assert!(runtime.candidate_arguments.is_none());
        assert!(runtime.lifecycle().candidate().is_none());
        runtime.prepare(2, 0);
        assert!(runtime.begin_request(ResourceIdentity::PipelineHandle(9), GenerationId::new(2),));
        assert_eq!(
            runtime.lifecycle().candidate().map(|value| value.id),
            Some(GenerationId::new(2))
        );
    }

    #[test]
    fn access_records_cover_compute_to_each_render_stage_without_extra_bits() {
        let mut runtime = GeometryRuntime::new();
        assert!(runtime.begin_request(ResourceIdentity::PipelineHandle(10), GenerationId::new(1)));
        assert!(runtime.collect_access(AccessRecord::new(
            AccessKind::Image,
            ResourceIdentity::ImageRoot(10),
            AccessMode::ReadWrite,
            GeometryStage::Dispatch,
            GeometryStages::VERTEX,
        )));
        assert!(runtime.collect_access(AccessRecord::new(
            AccessKind::Ssbo,
            ResourceIdentity::BufferHandle(10),
            AccessMode::ReadWrite,
            GeometryStage::Dispatch,
            GeometryStages::FRAGMENT,
        )));
        assert!(runtime.collect_access(AccessRecord::new(
            AccessKind::AtomicBuffer,
            ResourceIdentity::BufferHandle(11),
            AccessMode::Write,
            GeometryStage::Dispatch,
            GeometryStages::DISPATCH,
        )));
        assert!(runtime.normalize_accesses());
        let records = runtime.accesses().records();
        assert_eq!(records.len(), 3);
        let image = records
            .iter()
            .find(|record| record.kind() == AccessKind::Image);
        assert_eq!(
            image.map(|record| record.consumers()),
            Some(GeometryStages::VERTEX)
        );
        let ssbo = records
            .iter()
            .find(|record| record.kind() == AccessKind::Ssbo);
        assert_eq!(
            ssbo.map(|record| record.consumers()),
            Some(GeometryStages::FRAGMENT)
        );
        let atomic = records
            .iter()
            .find(|record| record.kind() == AccessKind::AtomicBuffer);
        assert_eq!(atomic.map(|record| record.mode()), Some(AccessMode::Write));
    }

    #[test]
    fn successful_candidate_publishes_indirect_output_after_failure() {
        let mut lifecycle = GenerationLifecycle::default();
        lifecycle.set_active(GenerationId::new(4));
        assert!(lifecycle.begin_candidate(GenerationId::new(5)));
        assert!(lifecycle.mark_candidate(GenerationId::new(5), CandidateState::Failed));
        assert!(lifecycle.discard_candidate(GenerationId::new(5)));
        assert!(lifecycle.begin_candidate(GenerationId::new(6)));
        assert!(lifecycle.mark_candidate(GenerationId::new(6), CandidateState::Encoded));
        assert!(lifecycle.activate_candidate(GenerationId::new(6)));
        assert_eq!(lifecycle.active(), Some(GenerationId::new(6)));
        assert!(lifecycle.candidate().is_none());

        let output = OutputState::Emitted {
            primitives: 2,
            arguments: IndirectArguments {
                index_count: 6,
                instance_count: 1,
                first_index: 0,
                base_vertex: 0,
                base_instance: 0,
            },
        };
        assert_eq!(output.arguments().map(|value| value.index_count), Some(6));
        assert_eq!(output.primitives(), 2);
    }

    #[test]
    fn retirement_statistics_are_exactly_once_for_zero_output() {
        let intent = RetirementIntent::new(9, Some(21), Some(22), OutputState::zero());
        let mut stats = DispatchStats::default();
        stats.record_retirement(intent);
        assert_eq!(stats.retired_outputs(), 1);
        assert_eq!(stats.zero_output(), 0);
        stats.record(OutputState::zero());
        assert_eq!(stats.retired_outputs(), 1);
        assert_eq!(stats.zero_output(), 1);
    }
}
