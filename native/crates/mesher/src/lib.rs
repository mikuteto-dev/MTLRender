pub const INPUT_STRIDE: usize = 28;
pub const OUTPUT_STRIDE: usize = 28;
pub const TABLE_MAGIC: u32 = 0x4d54_4232;
pub const TABLE_VERSION: u32 = 2;
pub const SNAPSHOT_MAGIC: u32 = 0x4d53_4832;
pub const SNAPSHOT_VERSION: u32 = 1;
pub const PACK_SNAPSHOT_VERSION: u32 = 2;
pub const PACK_OUTPUT_MAGIC: u32 = 0x4d50_4832;
pub const PACK_OUTPUT_VERSION: u32 = 1;
pub const PACK_RECORD_STRIDE: usize = 16;
pub const CELL_SIDE: usize = 18;
pub const CELL_COUNT: usize = CELL_SIDE * CELL_SIDE * CELL_SIDE;
pub const CENTER_SIDE: usize = 16;
pub const CENTER_COUNT: usize = CENTER_SIDE * CENTER_SIDE * CENTER_SIDE;
pub const TABLE_STATE_HEADER: usize = 16;
pub const STATE_FORCE_OPAQUE: u32 = 1 << 20;
pub const STATE_VIEW_BLOCKING: u32 = 1 << 21;
pub const STATE_COLLISION_FULL: u32 = 1 << 22;
pub const STATE_SOLID_RENDER: u32 = 1 << 23;
pub const QUAD_UNCULLED: u32 = 1 << 1;

#[derive(Default)]
pub struct Table {
    states: Vec<State>,
}

struct State {
    id: u32,
    flags: u32,
    skip_group: u32,
    faces: Vec<Shape>,
    selectors: Vec<Selector>,
}

struct Shape {
    boxes: Vec<Box3>,
}

#[derive(Clone, Copy)]
struct Box3 {
    min_x: f32,
    min_y: f32,
    min_z: f32,
    max_x: f32,
    max_y: f32,
    max_z: f32,
}

struct Selector {
    states: Vec<u32>,
    variants: Vec<Variant>,
}

struct Variant {
    weight: u32,
    parts: Vec<Part>,
}

struct Part {
    ambient_occlusion: bool,
    quads: Vec<Quad>,
}

struct Quad {
    vertices: Vec<Vertex>,
    direction: u32,
    tint: i32,
    flags: u32,
    layer: u32,
}

#[derive(Clone, Copy)]
struct Vertex {
    x: f32,
    y: f32,
    z: f32,
    u: f32,
    v: f32,
}

struct Snapshot {
    flags: u32,
    metadata_mask: u32,
    cardinal: Vec<f32>,
    cells: Vec<Cell>,
    centers: Vec<Center>,
}

#[derive(Clone)]
struct Cell {
    state: u32,
    light: u32,
    skip_mask: u32,
    shade: f32,
    tints: Vec<u32>,
}

#[derive(Clone)]
struct Center {
    seed: u64,
    offset: Vec<f32>,
}

struct Reader<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> Reader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, offset: 0 }
    }

    fn take(&mut self, count: usize) -> Option<&'a [u8]> {
        let end = self.offset.checked_add(count)?;
        let value = self.bytes.get(self.offset..end)?;
        self.offset = end;
        Some(value)
    }

    fn u32(&mut self) -> Option<u32> {
        let bytes = self.take(4)?;
        let a = *bytes.first()?;
        let b = *bytes.get(1)?;
        let c = *bytes.get(2)?;
        let d = *bytes.get(3)?;
        Some(u32::from_le_bytes([a, b, c, d]))
    }

    fn u64(&mut self) -> Option<u64> {
        let bytes = self.take(8)?;
        let mut value = [0; 8];
        for (target, source) in value.iter_mut().zip(bytes.iter()) {
            *target = *source;
        }
        Some(u64::from_le_bytes(value))
    }

    fn f32(&mut self) -> Option<f32> {
        Some(f32::from_bits(self.u32()?))
    }

    fn count(&mut self, item_size: usize) -> Option<usize> {
        let count = usize::try_from(self.u32()?).ok()?;
        (count <= self.bytes.len().saturating_sub(self.offset) / item_size).then_some(count)
    }

    fn done(&self) -> bool {
        self.offset == self.bytes.len()
    }
}

impl Table {
    pub fn new() -> Self {
        Self { states: Vec::new() }
    }

    pub fn add_state(&mut self, id: u32, flags: u32, bytes: &[u8]) -> bool {
        let Some(state) = parse_state(id, flags, bytes) else {
            return false;
        };
        if let Some(existing) = self.states.iter_mut().find(|state| state.id == id) {
            *existing = state;
        } else {
            self.states.push(state);
        }
        true
    }

    pub fn state_count(&self) -> usize {
        self.states.len()
    }

    pub fn quad_count(&self) -> usize {
        self.states
            .iter()
            .flat_map(|state| state.selectors.iter())
            .flat_map(|selector| selector.variants.iter())
            .flat_map(|variant| variant.parts.iter())
            .map(|part| part.quads.len())
            .sum()
    }

    pub fn max_output_bytes(&self) -> usize {
        self.max_output_bytes_for(0)
    }

    pub fn max_output_bytes_for(&self, metadata_mask: u32) -> usize {
        let mut maximum = 0usize;
        for state in &self.states {
            let mut state_maximum = 0usize;
            for selector in &state.selectors {
                let selector_maximum =
                    selector
                        .variants
                        .iter()
                        .try_fold(0usize, |maximum, variant| {
                            let count = variant.parts.iter().try_fold(0usize, |count, part| {
                                count.checked_add(part.quads.len())
                            })?;
                            Some(maximum.max(count))
                        });
                let Some(selector_maximum) = selector_maximum else {
                    return usize::MAX;
                };
                let Some(next) = state_maximum.checked_add(selector_maximum) else {
                    return usize::MAX;
                };
                state_maximum = next;
            }
            maximum = maximum.max(state_maximum);
        }
        let Some(quads) = maximum.checked_mul(CENTER_COUNT) else {
            return usize::MAX;
        };
        let Some(vertices) = quads.checked_mul(4) else {
            return usize::MAX;
        };
        let Some(bytes) = vertices.checked_mul(OUTPUT_STRIDE) else {
            return usize::MAX;
        };
        let metadata = if metadata_mask == 0 {
            0
        } else {
            match quads.checked_mul(PACK_RECORD_STRIDE) {
                Some(value) => value,
                None => return usize::MAX,
            }
        };
        match bytes
            .checked_add(metadata)
            .and_then(|value| value.checked_add(if metadata_mask == 0 { 12 } else { 36 }))
        {
            Some(value) => value,
            None => usize::MAX,
        }
    }

    pub fn state_signature(&self, id: u32) -> Option<(u32, usize)> {
        self.states
            .iter()
            .find(|state| state.id == id)
            .map(|state| (state.flags, state.quad_count()))
    }
}

impl State {
    fn quad_count(&self) -> usize {
        self.selectors
            .iter()
            .flat_map(|selector| selector.variants.iter())
            .flat_map(|variant| variant.parts.iter())
            .map(|part| part.quads.len())
            .sum()
    }

    fn matching_variants(&self, id: u32) -> impl Iterator<Item = &[Variant]> {
        self.selectors.iter().filter_map(move |selector| {
            selector
                .states
                .contains(&id)
                .then_some(selector.variants.as_slice())
        })
    }
}

const QUAD_BYTES: usize = 112;

fn parse_state(id: u32, flags: u32, bytes: &[u8]) -> Option<State> {
    let mut reader = Reader::new(bytes);
    if reader.u32()? != TABLE_MAGIC || reader.u32()? != TABLE_VERSION {
        return None;
    }
    let state_flags = reader.u32()?;
    let skip_group = reader.u32()?;
    let mut faces = Vec::with_capacity(6);
    for _ in 0..6 {
        let count = reader.count(24)?;
        let mut boxes = Vec::with_capacity(count);
        for _ in 0..count {
            boxes.push(Box3 {
                min_x: reader.f32()?,
                min_y: reader.f32()?,
                min_z: reader.f32()?,
                max_x: reader.f32()?,
                max_y: reader.f32()?,
                max_z: reader.f32()?,
            });
        }
        faces.push(Shape { boxes });
    }
    let selector_count = reader.count(8)?;
    let mut selectors = Vec::with_capacity(selector_count);
    for _ in 0..selector_count {
        let state_count = reader.count(4)?;
        let mut states = Vec::with_capacity(state_count);
        for _ in 0..state_count {
            states.push(reader.u32()?);
        }
        let variant_count = reader.count(8)?;
        let mut variants = Vec::with_capacity(variant_count);
        for _ in 0..variant_count {
            let weight = reader.u32()?;
            let part_count = reader.count(8)?;
            let mut parts = Vec::with_capacity(part_count);
            for _ in 0..part_count {
                let part_flags = reader.u32()?;
                let quad_count = reader.count(QUAD_BYTES)?;
                let mut quads = Vec::with_capacity(quad_count);
                for _ in 0..quad_count {
                    let mut vertices = Vec::with_capacity(4);
                    for _ in 0..4 {
                        vertices.push(Vertex {
                            x: reader.f32()?,
                            y: reader.f32()?,
                            z: reader.f32()?,
                            u: reader.f32()?,
                            v: reader.f32()?,
                        });
                        let _ = reader.u32()?;
                    }
                    let direction = reader.u32()?;
                    let tint = i32::try_from(reader.u32()?).ok()?;
                    let flags = reader.u32()?;
                    let layer = reader.u32()?;
                    if direction >= 6 || layer >= 3 {
                        return None;
                    }
                    quads.push(Quad {
                        vertices,
                        direction,
                        tint,
                        flags,
                        layer,
                    });
                }
                parts.push(Part {
                    ambient_occlusion: part_flags & 1 != 0,
                    quads,
                });
            }
            variants.push(Variant { weight, parts });
        }
        if variants.is_empty() {
            return None;
        }
        selectors.push(Selector { states, variants });
    }
    if selectors.is_empty() || !reader.done() {
        return None;
    }
    Some(State {
        id,
        flags: state_flags | flags,
        skip_group,
        faces,
        selectors,
    })
}

pub fn mesh_block_quads(input: &[u8], output: &mut [u8]) -> bool {
    if !input.len().is_multiple_of(INPUT_STRIDE) || output.len() != input.len() {
        return false;
    }
    let (sources, _) = input.as_chunks::<INPUT_STRIDE>();
    let (targets, _) = output.as_chunks_mut::<OUTPUT_STRIDE>();
    for (source, target) in sources.iter().zip(targets.iter_mut()) {
        copy_vertex(source, target);
    }
    true
}

pub struct SectionBatchItem<'a> {
    pub input: &'a [u8],
    pub output: &'a mut [u8],
    pub result: SectionBatchResult,
}

#[derive(Default)]
pub struct SectionBatchResult {
    pub written: usize,
}

pub fn mesh_sections(table: &Table, items: &mut [SectionBatchItem<'_>]) {
    for item in items {
        item.result.written = mesh_section(table, item.input, item.output).unwrap_or(0);
    }
}

pub fn mesh_section(table: &Table, input: &[u8], output: &mut [u8]) -> Option<usize> {
    let snapshot = parse_snapshot(input)?;
    let mut layers = vec![Vec::new(), Vec::new(), Vec::new()];
    let mut metadata = vec![Vec::new(), Vec::new(), Vec::new()];
    for y in 1..=16 {
        for z in 1..=16 {
            for x in 1..=16 {
                let center_index = center_index(x - 1, y - 1, z - 1)?;
                let cell_index = cell_index(x, y, z)?;
                let cell = snapshot.cells.get(cell_index)?;
                let center = snapshot.centers.get(center_index)?;
                let Some(state) = table.states.iter().find(|state| state.id == cell.state) else {
                    continue;
                };
                let selection_seed = if state.selectors.len() > 1 {
                    next_long(center.seed)?
                } else {
                    center.seed
                };
                for variants in state.matching_variants(cell.state) {
                    let Some(variant) = select_variant(variants, selection_seed) else {
                        continue;
                    };
                    for part in &variant.parts {
                        for quad in &part.quads {
                            let position = Position { x, y, z };
                            if quad.direction < 6
                                && quad.flags & QUAD_UNCULLED == 0
                                && !should_render_face(
                                    table,
                                    &snapshot,
                                    state,
                                    position,
                                    quad.direction,
                                )?
                            {
                                continue;
                            }
                            let output_quad = shade_quad(
                                &MeshContext {
                                    table,
                                    snapshot: &snapshot,
                                    position,
                                },
                                state,
                                center,
                                part.ambient_occlusion,
                                quad,
                            )?;
                            let layer = if state.flags & STATE_FORCE_OPAQUE != 0
                                && snapshot.flags & 2 == 0
                            {
                                0
                            } else {
                                usize::try_from(quad.layer).ok()?.min(2)
                            };
                            let target = layers.get_mut(layer)?;
                            target.extend_from_slice(&output_quad.vertices);
                            if snapshot.metadata_mask != 0 {
                                let metadata_target = metadata.get_mut(layer)?;
                                metadata_target.extend_from_slice(&output_quad.metadata);
                            }
                        }
                    }
                }
            }
        }
    }
    let lengths = [
        layers.first().map(Vec::len)?,
        layers.get(1).map(Vec::len)?,
        layers.get(2).map(Vec::len)?,
    ];
    let metadata_lengths = [
        metadata.first().map(Vec::len)?,
        metadata.get(1).map(Vec::len)?,
        metadata.get(2).map(Vec::len)?,
    ];
    let metadata_mode = snapshot.metadata_mask != 0;
    let header: usize = if metadata_mode { 36 } else { 12 };
    let total = header
        .checked_add(lengths.iter().copied().sum::<usize>())?
        .checked_add(metadata_lengths.iter().copied().sum::<usize>())?;
    if output.len() < total {
        return None;
    }
    let mut offset: usize;
    if metadata_mode {
        write_u32(output, 0, PACK_OUTPUT_MAGIC);
        write_u32(output, 4, PACK_OUTPUT_VERSION);
        write_u32(output, 8, snapshot.metadata_mask);
        for (index, length) in lengths.iter().enumerate() {
            write_u32(output, 12 + index * 4, u32::try_from(*length).ok()?);
        }
        for (index, length) in metadata_lengths.iter().enumerate() {
            write_u32(output, 24 + index * 4, u32::try_from(*length).ok()?);
        }
        offset = 36;
    } else {
        for (index, length) in lengths.iter().enumerate() {
            write_u32(output, index * 4, u32::try_from(*length).ok()?);
        }
        offset = 12;
    }
    for layer in layers {
        let end = offset.checked_add(layer.len())?;
        output.get_mut(offset..end)?.copy_from_slice(&layer);
        offset = end;
    }
    if metadata_mode {
        for layer in metadata {
            let end = offset.checked_add(layer.len())?;
            output.get_mut(offset..end)?.copy_from_slice(&layer);
            offset = end;
        }
    }
    Some(total)
}

fn parse_snapshot(bytes: &[u8]) -> Option<Snapshot> {
    let mut reader = Reader::new(bytes);
    if reader.u32()? != SNAPSHOT_MAGIC {
        return None;
    }
    let version = reader.u32()?;
    if version != SNAPSHOT_VERSION && version != PACK_SNAPSHOT_VERSION {
        return None;
    }
    let flags = reader.u32()?;
    let metadata_mask = reader.u32()?;
    if (version == SNAPSHOT_VERSION && metadata_mask != 0) || metadata_mask & !15 != 0 {
        return None;
    }
    let mut cardinal = Vec::with_capacity(6);
    for _ in 0..6 {
        cardinal.push(reader.f32()?);
    }
    let mut cells = Vec::with_capacity(CELL_COUNT);
    for _ in 0..CELL_COUNT {
        let state = reader.u32()?;
        let light = reader.u32()?;
        let shade = reader.f32()?;
        let mut tints = Vec::with_capacity(4);
        for _ in 0..4 {
            tints.push(reader.u32()?);
        }
        let skip_mask = reader.u32()?;
        cells.push(Cell {
            state,
            light,
            skip_mask,
            shade,
            tints,
        });
    }
    let mut centers = Vec::with_capacity(CENTER_COUNT);
    for _ in 0..CENTER_COUNT {
        let seed = reader.u64()?;
        let mut offset = Vec::with_capacity(3);
        for _ in 0..3 {
            offset.push(reader.f32()?);
        }
        centers.push(Center { seed, offset });
    }
    Some(Snapshot {
        flags,
        metadata_mask,
        cardinal,
        cells,
        centers,
    })
}

fn select_variant(variants: &[Variant], seed: u64) -> Option<&Variant> {
    let total = variants
        .iter()
        .try_fold(0u32, |sum, variant| sum.checked_add(variant.weight))?;
    if total == 0 || total > i32::MAX as u32 {
        return None;
    }
    let choice = next_int(seed, total)?;
    let mut cursor = 0u32;
    for variant in variants {
        cursor = cursor.checked_add(variant.weight)?;
        if choice < cursor {
            return Some(variant);
        }
    }
    None
}

fn next_long(seed: u64) -> Option<u64> {
    let mut state = (seed ^ 25_214_903_917) & 281_474_976_710_655;
    state = state.wrapping_mul(25_214_903_917).wrapping_add(11) & 281_474_976_710_655;
    let upper = state >> 16;
    state = state.wrapping_mul(25_214_903_917).wrapping_add(11) & 281_474_976_710_655;
    let lower = state >> 16;
    let upper = i64::from(i32::from_ne_bytes((upper as u32).to_ne_bytes()));
    let lower = i64::from(i32::from_ne_bytes((lower as u32).to_ne_bytes()));
    Some((upper.wrapping_shl(32).wrapping_add(lower)) as u64)
}

fn next_int(seed: u64, bound: u32) -> Option<u32> {
    if bound == 0 {
        return None;
    }
    let mut state = (seed ^ 25_214_903_917) & 281_474_976_710_655;
    loop {
        state = state.wrapping_mul(25_214_903_917).wrapping_add(11) & 281_474_976_710_655;
        let bits = (state >> 17) as u32;
        if bound & (bound - 1) == 0 {
            return Some(((u64::from(bound) * u64::from(bits)) >> 31) as u32);
        }
        let value = bits % bound;
        let remainder = bits.wrapping_sub(value).wrapping_add(bound - 1);
        if remainder < (1 << 31) {
            return Some(value);
        }
    }
}

fn should_render_face(
    table: &Table,
    snapshot: &Snapshot,
    state: &State,
    position: Position,
    direction: u32,
) -> Option<bool> {
    let direction = usize::try_from(direction).ok()?;
    let (nx, ny, nz) = offset(position.x, position.y, position.z, direction)?;
    let cell = snapshot
        .cells
        .get(cell_index(position.x, position.y, position.z)?)?;
    if cell.skip_mask & (1 << direction) != 0 {
        return Some(false);
    }
    let neighbor_cell = snapshot.cells.get(cell_index(nx, ny, nz)?)?;
    let Some(neighbor) = table
        .states
        .iter()
        .find(|candidate| candidate.id == neighbor_cell.state)
    else {
        return Some(true);
    };
    if state.skip_group != 0 && state.skip_group == neighbor.skip_group {
        return Some(false);
    }
    let opposite = opposite(direction)?;
    let neighbor_shape = neighbor.faces.get(opposite)?;
    if is_full(neighbor_shape) || neighbor_shape.boxes.is_empty() {
        if neighbor_shape.boxes.is_empty() {
            return Some(true);
        }
        return Some(false);
    }
    let own_shape = state.faces.get(direction)?;
    if own_shape.boxes.is_empty() {
        return Some(true);
    }
    Some(!shape_covers(own_shape, neighbor_shape, direction))
}

fn shape_covers(first: &Shape, second: &Shape, direction: usize) -> bool {
    let mut u = Vec::new();
    let mut v = Vec::new();
    for value in &first.boxes {
        add_plane_coords(value, direction, &mut u, &mut v);
    }
    for value in &second.boxes {
        add_plane_coords(value, direction, &mut u, &mut v);
    }
    u.sort_by(f32::total_cmp);
    v.sort_by(f32::total_cmp);
    u.dedup_by(|a, b| *a == *b);
    v.dedup_by(|a, b| *a == *b);
    for value in &first.boxes {
        let (u0, u1, v0, v1) = plane_rect(value, direction);
        for pair_u in u.windows(2) {
            let Some(u_left) = pair_u.first() else {
                continue;
            };
            let Some(u_right) = pair_u.get(1) else {
                continue;
            };
            if *u_left < u0 || *u_right > u1 || *u_left == *u_right {
                continue;
            }
            for pair_v in v.windows(2) {
                let Some(v_left) = pair_v.first() else {
                    continue;
                };
                let Some(v_right) = pair_v.get(1) else {
                    continue;
                };
                if *v_left < v0 || *v_right > v1 || *v_left == *v_right {
                    continue;
                }
                let u_mid = (*u_left + *u_right) * 0.5;
                let v_mid = (*v_left + *v_right) * 0.5;
                if !second.boxes.iter().any(|candidate| {
                    let (cu0, cu1, cv0, cv1) = plane_rect(candidate, direction);
                    u_mid >= cu0 && u_mid <= cu1 && v_mid >= cv0 && v_mid <= cv1
                }) {
                    return false;
                }
            }
        }
    }
    true
}

fn add_plane_coords(value: &Box3, direction: usize, u: &mut Vec<f32>, v: &mut Vec<f32>) {
    let (u0, u1, v0, v1) = plane_rect(value, direction);
    u.push(u0);
    u.push(u1);
    v.push(v0);
    v.push(v1);
}

fn plane_rect(value: &Box3, direction: usize) -> (f32, f32, f32, f32) {
    match direction {
        0 | 1 => (value.min_x, value.max_x, value.min_z, value.max_z),
        2 | 3 => (value.min_x, value.max_x, value.min_y, value.max_y),
        _ => (value.min_y, value.max_y, value.min_z, value.max_z),
    }
}

fn is_full(shape: &Shape) -> bool {
    shape.boxes.len() == 1
        && shape.boxes.first().is_some_and(|value| {
            value.min_x == 0.0
                && value.min_y == 0.0
                && value.min_z == 0.0
                && value.max_x == 1.0
                && value.max_y == 1.0
                && value.max_z == 1.0
        })
}

#[derive(Clone, Copy)]
struct Position {
    x: usize,
    y: usize,
    z: usize,
}

struct ShadedQuad {
    vertices: Vec<u8>,
    metadata: Vec<u8>,
}

struct MeshContext<'a> {
    table: &'a Table,
    snapshot: &'a Snapshot,
    position: Position,
}

fn shade_quad(
    context: &MeshContext<'_>,
    state: &State,
    center: &Center,
    ambient_occlusion: bool,
    quad: &Quad,
) -> Option<ShadedQuad> {
    let table = context.table;
    let snapshot = context.snapshot;
    let position = context.position;
    let cell = snapshot
        .cells
        .get(cell_index(position.x, position.y, position.z)?)?;
    let use_ao = snapshot.flags & 1 != 0 && state.flags & (15 << 9) == 0 && ambient_occlusion;
    let (colors, lights) = if use_ao {
        ambient_values(
            table, snapshot, state, position.x, position.y, position.z, quad,
        )?
    } else {
        flat_values(snapshot, state, position.x, position.y, position.z, quad)?
    };
    let tint = if quad.tint >= 0 {
        let tint = usize::try_from(quad.tint).ok()?;
        cell.tints.get(tint).copied().unwrap_or(u32::MAX)
    } else {
        u32::MAX
    };
    let offset_x = *center.offset.first()?;
    let offset_y = *center.offset.get(1)?;
    let offset_z = *center.offset.get(2)?;
    let mut output = Vec::with_capacity(OUTPUT_STRIDE * 4);
    for vertex_index in 0..4 {
        let vertex = quad.vertices.get(vertex_index)?;
        let color = multiply_color(*colors.get(vertex_index)?, tint);
        let light = light_with_emission(*lights.get(vertex_index)?, (quad.flags >> 2) & 15);
        put_f32(&mut output, vertex.x + (position.x - 1) as f32 + offset_x);
        put_f32(&mut output, vertex.y + (position.y - 1) as f32 + offset_y);
        put_f32(&mut output, vertex.z + (position.z - 1) as f32 + offset_z);
        put_u32(&mut output, to_abgr(color));
        put_f32(&mut output, vertex.u);
        put_f32(&mut output, vertex.v);
        put_u32(&mut output, light);
    }
    let mut metadata = Vec::new();
    if snapshot.metadata_mask != 0 {
        put_u32(
            &mut metadata,
            if snapshot.metadata_mask & 1 != 0 {
                state.id
            } else {
                0
            },
        );
        put_u32(
            &mut metadata,
            if snapshot.metadata_mask & 2 != 0 {
                pack_mid_uv(quad)
            } else {
                0
            },
        );
        put_u32(
            &mut metadata,
            if snapshot.metadata_mask & 4 != 0 {
                pack_normal_tangent(quad)?
            } else {
                0
            },
        );
        put_u32(
            &mut metadata,
            if snapshot.metadata_mask & 8 != 0 {
                pack_mid_block(quad, position)
            } else {
                0
            },
        );
    }
    Some(ShadedQuad {
        vertices: output,
        metadata,
    })
}

fn pack_mid_uv(quad: &Quad) -> u32 {
    let u = quad.vertices.iter().map(|vertex| vertex.u).sum::<f32>() * 0.25;
    let v = quad.vertices.iter().map(|vertex| vertex.v).sum::<f32>() * 0.25;
    ((u * 32768.0).round() as i32 as u32 & 0xffff)
        | (((v * 32768.0).round() as i32 as u32 & 0xffff) << 16)
}

fn pack_mid_block(quad: &Quad, position: Position) -> u32 {
    let x = quad.vertices.iter().map(|vertex| vertex.x).sum::<f32>() * 0.25;
    let y = quad.vertices.iter().map(|vertex| vertex.y).sum::<f32>() * 0.25;
    let z = quad.vertices.iter().map(|vertex| vertex.z).sum::<f32>() * 0.25;
    let px = ((position.x - 1) as f32 + 0.5 - x) * 64.0;
    let py = ((position.y - 1) as f32 + 0.5 - y) * 64.0;
    let pz = ((position.z - 1) as f32 + 0.5 - z) * 64.0;
    (px as i32 as u32 & 0xff) | ((py as i32 as u32 & 0xff) << 8) | ((pz as i32 as u32 & 0xff) << 16)
}

fn pack_normal_tangent(quad: &Quad) -> Option<u32> {
    let first = quad.vertices.first()?;
    let second = quad.vertices.get(1)?;
    let third = quad.vertices.get(2)?;
    let e1 = (second.x - first.x, second.y - first.y, second.z - first.z);
    let e2 = (third.x - first.x, third.y - first.y, third.z - first.z);
    let normal = (
        e1.1 * e2.2 - e1.2 * e2.1,
        e1.2 * e2.0 - e1.0 * e2.2,
        e1.0 * e2.1 - e1.1 * e2.0,
    );
    let length = (normal.0 * normal.0 + normal.1 * normal.1 + normal.2 * normal.2).sqrt();
    if length <= f32::EPSILON {
        return None;
    }
    let normal = (normal.0 / length, normal.1 / length, normal.2 / length);
    let du1 = second.u - first.u;
    let dv1 = second.v - first.v;
    let du2 = third.u - first.u;
    let dv2 = third.v - first.v;
    let determinant = du1 * dv2 - du2 * dv1;
    if determinant.abs() <= f32::EPSILON {
        return None;
    }
    let scale = 1.0 / determinant;
    let tangent = (
        (dv2 * e1.0 - dv1 * e2.0) * scale,
        (dv2 * e1.1 - dv1 * e2.1) * scale,
        (dv2 * e1.2 - dv1 * e2.2) * scale,
    );
    let denominator = normal.0.abs() + normal.1.abs() + normal.2.abs();
    let px = normal.0 / denominator;
    let py = normal.1 / denominator;
    let (nx, ny) = if normal.2 > 0.0 {
        (px, py)
    } else {
        (
            (1.0 - py.abs()) * if px >= 0.0 { 1.0 } else { -1.0 },
            (1.0 - px.abs()) * if py >= 0.0 { 1.0 } else { -1.0 },
        )
    };
    let encode =
        |value: f32| -> u32 { (((value.clamp(-1.0, 1.0) * 2047.0).round() as i32) & 0xfff) as u32 };
    let tangent_length =
        (tangent.0 * tangent.0 + tangent.1 * tangent.1 + tangent.2 * tangent.2).sqrt();
    let tangent = if tangent_length <= f32::EPSILON {
        (1.0, 0.0, 0.0)
    } else {
        (
            tangent.0 / tangent_length,
            tangent.1 / tangent_length,
            tangent.2 / tangent_length,
        )
    };
    let sign = if normal.2 >= 0.0 { 1.0 } else { -1.0 };
    let a = -1.0 / (sign + normal.2);
    let b = normal.0 * normal.1 * a;
    let basis = (
        (
            1.0 + sign * normal.0 * normal.0 * a,
            sign * b,
            -sign * normal.0,
        ),
        (
            normal.1 * (-sign * normal.0) - normal.2 * (sign * b),
            normal.2 * (1.0 + sign * normal.0 * normal.0 * a) - normal.0 * (-sign * normal.0),
            normal.0 * (sign * b) - normal.1 * (1.0 + sign * normal.0 * normal.0 * a),
        ),
    );
    let dot = normal.0 * tangent.0 + normal.1 * tangent.1 + normal.2 * tangent.2;
    let projected = (
        tangent.0 - normal.0 * dot,
        tangent.1 - normal.1 * dot,
        tangent.2 - normal.2 * dot,
    );
    let projected_length =
        (projected.0 * projected.0 + projected.1 * projected.1 + projected.2 * projected.2).sqrt();
    let projected = if projected_length <= f32::EPSILON {
        basis.0
    } else {
        (
            projected.0 / projected_length,
            projected.1 / projected_length,
            projected.2 / projected_length,
        )
    };
    let px = projected.0 * basis.0.0 + projected.1 * basis.0.1 + projected.2 * basis.0.2;
    let py = projected.0 * basis.1.0 + projected.1 * basis.1.1 + projected.2 * basis.1.2;
    let denominator = (px.abs() + py.abs()).max(f32::EPSILON);
    let diamond_x = px / denominator;
    let diamond_sign = if py >= 0.0 { 1.0 } else { -1.0 };
    let diamond = -diamond_sign * 0.25 * diamond_x + 0.5 + diamond_sign * 0.25;
    let tangent_byte = (diamond * 256.0).round().clamp(0.0, 256.0) as u32 & 0xff;
    Some((tangent_byte << 24) | (encode(nx) << 12) | encode(ny))
}

fn flat_values(
    snapshot: &Snapshot,
    state: &State,
    x: usize,
    y: usize,
    z: usize,
    quad: &Quad,
) -> Option<(Vec<u32>, Vec<u32>)> {
    let direction = usize::try_from(quad.direction).ok()?.min(5);
    let (nx, ny, nz) = offset(x, y, z, direction)?;
    let light = if quad.flags & QUAD_UNCULLED == 0 || face_cubic(quad, state) {
        snapshot.cells.get(cell_index(nx, ny, nz)?)?.light
    } else {
        snapshot.cells.get(cell_index(x, y, z)?)?.light
    };
    let brightness = cardinal(snapshot, quad);
    Some((vec![gray(brightness); 4], vec![light; 4]))
}

fn ambient_values(
    table: &Table,
    snapshot: &Snapshot,
    state: &State,
    x: usize,
    y: usize,
    z: usize,
    quad: &Quad,
) -> Option<(Vec<u32>, Vec<u32>)> {
    let direction = usize::try_from(quad.direction).ok()?.min(5);
    let base = if face_cubic(quad, state) {
        offset(x, y, z, direction)?
    } else {
        (x, y, z)
    };
    let adjacency = adjacency(direction)?;
    let mut side_light = Vec::with_capacity(4);
    let mut side_shade = Vec::with_capacity(4);
    for corner in &adjacency.corners {
        let (cx, cy, cz) = add(base, corner.0, corner.1, corner.2)?;
        let value = snapshot.cells.get(cell_index(cx, cy, cz)?)?;
        side_light.push(value.light);
        side_shade.push(value.shade);
    }
    let mut corner_light = Vec::with_capacity(4);
    let mut corner_shade = Vec::with_capacity(4);
    for pair in &adjacency.corner_pairs {
        let first = add(base, pair.0.0, pair.0.1, pair.0.2)?;
        let delta = direction_delta(direction)?;
        let first_corner = add(
            add(base, pair.0.0, pair.0.1, pair.0.2)?,
            delta.0,
            delta.1,
            delta.2,
        )?;
        let second_corner = add(
            add(base, pair.1.0, pair.1.1, pair.1.2)?,
            delta.0,
            delta.1,
            delta.2,
        )?;
        let first_blocking = snapshot
            .cells
            .get(cell_index(first_corner.0, first_corner.1, first_corner.2)?)?
            .state;
        let second_blocking = snapshot
            .cells
            .get(cell_index(
                second_corner.0,
                second_corner.1,
                second_corner.2,
            )?)?
            .state;
        let first_blocking = table
            .states
            .iter()
            .find(|candidate| candidate.id == first_blocking);
        let second_blocking = table
            .states
            .iter()
            .find(|candidate| candidate.id == second_blocking);
        if first_blocking.is_some_and(|value| {
            value.flags & STATE_VIEW_BLOCKING != 0 && value.flags & (15 << 13) != 0
        }) && second_blocking.is_some_and(|value| {
            value.flags & STATE_VIEW_BLOCKING != 0 && value.flags & (15 << 13) != 0
        }) {
            let value = snapshot.cells.get(cell_index(first.0, first.1, first.2)?)?;
            corner_light.push(value.light);
            corner_shade.push(value.shade);
        } else {
            let diagonal = add(
                base,
                pair.0.0 + pair.1.0,
                pair.0.1 + pair.1.1,
                pair.0.2 + pair.1.2,
            )?;
            let value = snapshot
                .cells
                .get(cell_index(diagonal.0, diagonal.1, diagonal.2)?)?;
            corner_light.push(value.light);
            corner_shade.push(value.shade);
        }
    }
    let center = snapshot.cells.get(cell_index(x, y, z)?)?;
    let next_point = offset(x, y, z, direction)?;
    let next = snapshot
        .cells
        .get(cell_index(next_point.0, next_point.1, next_point.2)?)?;
    let next_state = table
        .states
        .iter()
        .find(|candidate| candidate.id == next.state);
    let center_light = if face_cubic(quad, state)
        || next_state.is_none_or(|candidate| candidate.flags & STATE_SOLID_RENDER == 0)
    {
        next.light
    } else {
        center.light
    };
    let center_shade = if face_cubic(quad, state) {
        let value = snapshot.cells.get(cell_index(base.0, base.1, base.2)?)?;
        value.shade
    } else {
        center.shade
    };
    let shades = vec![
        (side_shade.get(3)? + side_shade.first()? + corner_shade.first()? + center_shade) * 0.25,
        (side_shade.get(2)? + side_shade.first()? + corner_shade.get(1)? + center_shade) * 0.25,
        (side_shade.get(2)? + side_shade.get(1)? + corner_shade.get(2)? + center_shade) * 0.25,
        (side_shade.get(3)? + side_shade.get(1)? + corner_shade.get(3)? + center_shade) * 0.25,
    ];
    let lights = vec![
        smooth_blend(
            *side_light.get(3)?,
            *side_light.first()?,
            *corner_light.first()?,
            center_light,
        ),
        smooth_blend(
            *side_light.get(2)?,
            *side_light.first()?,
            *corner_light.get(1)?,
            center_light,
        ),
        smooth_blend(
            *side_light.get(2)?,
            *side_light.get(1)?,
            *corner_light.get(2)?,
            center_light,
        ),
        smooth_blend(
            *side_light.get(3)?,
            *side_light.get(1)?,
            *corner_light.get(3)?,
            center_light,
        ),
    ];
    let (mut colors, mut remapped_lights) = if face_partial(quad) {
        weighted_values(&shades, &lights, quad, direction)
    } else {
        (shades.iter().map(|value| gray(*value)).collect(), lights)
    };
    let scale = cardinal(snapshot, quad);
    for color in &mut colors {
        *color = scale_gray(*color, scale);
    }
    remap_values(direction, &mut colors, &mut remapped_lights);
    Some((colors, remapped_lights))
}

type Offset = (i32, i32, i32);
type CornerPair = (Offset, Offset);

struct Adjacency {
    corners: Vec<Offset>,
    corner_pairs: Vec<CornerPair>,
}

fn adjacency(direction: usize) -> Option<Adjacency> {
    let values = match direction {
        0 => (
            vec![(-1, 0, 0), (1, 0, 0), (0, 0, -1), (0, 0, 1)],
            vec![
                ((-1, 0, 0), (0, 0, 1)),
                ((-1, 0, 0), (0, 0, -1)),
                ((1, 0, 0), (0, 0, -1)),
                ((1, 0, 0), (0, 0, 1)),
            ],
        ),
        1 => (
            vec![(1, 0, 0), (-1, 0, 0), (0, 0, -1), (0, 0, 1)],
            vec![
                ((1, 0, 0), (0, 0, 1)),
                ((1, 0, 0), (0, 0, -1)),
                ((-1, 0, 0), (0, 0, -1)),
                ((-1, 0, 0), (0, 0, 1)),
            ],
        ),
        2 => (
            vec![(0, 1, 0), (0, -1, 0), (1, 0, 0), (-1, 0, 0)],
            vec![
                ((0, 1, 0), (-1, 0, 0)),
                ((0, 1, 0), (1, 0, 0)),
                ((0, -1, 0), (1, 0, 0)),
                ((0, -1, 0), (-1, 0, 0)),
            ],
        ),
        3 => (
            vec![(-1, 0, 0), (1, 0, 0), (0, -1, 0), (0, 1, 0)],
            vec![
                ((-1, 0, 0), (0, 1, 0)),
                ((-1, 0, 0), (0, -1, 0)),
                ((1, 0, 0), (0, -1, 0)),
                ((1, 0, 0), (0, 1, 0)),
            ],
        ),
        4 => (
            vec![(0, 1, 0), (0, -1, 0), (0, 0, -1), (0, 0, 1)],
            vec![
                ((0, 1, 0), (0, 0, 1)),
                ((0, 1, 0), (0, 0, -1)),
                ((0, -1, 0), (0, 0, -1)),
                ((0, -1, 0), (0, 0, 1)),
            ],
        ),
        _ => (
            vec![(0, -1, 0), (0, 1, 0), (0, 0, -1), (0, 0, 1)],
            vec![
                ((0, -1, 0), (0, 0, 1)),
                ((0, -1, 0), (0, 0, -1)),
                ((0, 1, 0), (0, 0, -1)),
                ((0, 1, 0), (0, 0, 1)),
            ],
        ),
    };
    Some(Adjacency {
        corners: values.0,
        corner_pairs: values.1,
    })
}

fn face_partial(quad: &Quad) -> bool {
    let Some(first) = quad.vertices.first() else {
        return false;
    };
    let mut min_x = first.x;
    let mut min_y = first.y;
    let mut min_z = first.z;
    let mut max_x = first.x;
    let mut max_y = first.y;
    let mut max_z = first.z;
    for vertex in quad.vertices.iter().skip(1) {
        min_x = min_x.min(vertex.x);
        min_y = min_y.min(vertex.y);
        min_z = min_z.min(vertex.z);
        max_x = max_x.max(vertex.x);
        max_y = max_y.max(vertex.y);
        max_z = max_z.max(vertex.z);
    }
    match quad.direction {
        0 | 1 => min_x >= 0.0001 || min_z >= 0.0001 || max_x <= 0.9999 || max_z <= 0.9999,
        2 | 3 => min_x >= 0.0001 || min_y >= 0.0001 || max_x <= 0.9999 || max_y <= 0.9999,
        _ => min_y >= 0.0001 || min_z >= 0.0001 || max_y <= 0.9999 || max_z <= 0.9999,
    }
}

fn weighted_values(
    shades: &[f32],
    lights: &[u32],
    quad: &Quad,
    direction: usize,
) -> (Vec<u32>, Vec<u32>) {
    let shape = quad_shape(quad);
    let mut colors = Vec::with_capacity(4);
    let mut output_lights = Vec::with_capacity(4);
    for vertex in 0..4 {
        let indices = weight_indices(direction, vertex);
        let weights = weight_values(&shape, &indices);
        let shade = shades
            .iter()
            .zip(weights.iter())
            .map(|(value, weight)| value * weight)
            .sum::<f32>()
            .clamp(0.0, 1.0);
        let light = weighted_light(lights, &weights);
        colors.push(gray(shade));
        output_lights.push(light);
    }
    (colors, output_lights)
}

fn quad_shape(quad: &Quad) -> Vec<f32> {
    let Some(first) = quad.vertices.first() else {
        return vec![0.0; 12];
    };
    let mut min_x = first.x;
    let mut min_y = first.y;
    let mut min_z = first.z;
    let mut max_x = first.x;
    let mut max_y = first.y;
    let mut max_z = first.z;
    for vertex in quad.vertices.iter().skip(1) {
        min_x = min_x.min(vertex.x);
        min_y = min_y.min(vertex.y);
        min_z = min_z.min(vertex.z);
        max_x = max_x.max(vertex.x);
        max_y = max_y.max(vertex.y);
        max_z = max_z.max(vertex.z);
    }
    vec![
        min_y,
        max_y,
        min_z,
        max_z,
        min_x,
        max_x,
        1.0 - min_y,
        1.0 - max_y,
        1.0 - min_z,
        1.0 - max_z,
        1.0 - min_x,
        1.0 - max_x,
    ]
}

fn weight_indices(direction: usize, vertex: usize) -> Vec<usize> {
    let logical = vertex;
    match (direction, logical) {
        (0, 0) => vec![10, 3, 10, 9, 4, 9, 4, 3],
        (0, 1) => vec![10, 2, 10, 8, 4, 8, 4, 2],
        (0, 2) => vec![11, 2, 11, 8, 5, 8, 5, 2],
        (0, _) => vec![11, 3, 11, 9, 5, 9, 5, 3],
        (1, 0) => vec![5, 3, 5, 9, 11, 9, 11, 3],
        (1, 1) => vec![5, 2, 5, 8, 11, 8, 11, 2],
        (1, 2) => vec![4, 2, 4, 8, 10, 8, 10, 2],
        (1, _) => vec![4, 3, 4, 9, 10, 9, 10, 3],
        (2, 0) => vec![1, 10, 1, 4, 7, 4, 7, 10],
        (2, 1) => vec![1, 11, 1, 5, 7, 5, 7, 11],
        (2, 2) => vec![0, 11, 0, 5, 6, 5, 6, 11],
        (2, _) => vec![0, 10, 0, 4, 6, 4, 6, 10],
        (3, 0) => vec![1, 10, 7, 10, 7, 4, 1, 4],
        (3, 1) => vec![0, 10, 6, 10, 6, 4, 0, 4],
        (3, 2) => vec![0, 11, 6, 11, 6, 5, 0, 5],
        (3, _) => vec![1, 11, 7, 11, 7, 5, 1, 5],
        (4, 0) => vec![1, 3, 1, 9, 7, 9, 7, 3],
        (4, 1) => vec![1, 2, 1, 8, 7, 8, 7, 2],
        (4, 2) => vec![0, 2, 0, 8, 6, 8, 6, 2],
        (4, _) => vec![0, 3, 0, 9, 6, 9, 6, 3],
        (5, 0) => vec![6, 3, 6, 9, 0, 9, 0, 3],
        (5, 1) => vec![6, 2, 6, 8, 0, 8, 0, 2],
        (5, 2) => vec![7, 2, 7, 8, 1, 8, 1, 2],
        (_, _) => vec![7, 3, 7, 9, 1, 9, 1, 3],
    }
}

fn weight_values(shape: &[f32], indices: &[usize]) -> Vec<f32> {
    let mut result = Vec::with_capacity(4);
    for pair in indices.chunks(2).take(4) {
        let first = pair
            .first()
            .and_then(|index| shape.get(*index))
            .copied()
            .unwrap_or(0.0);
        let second = pair
            .get(1)
            .and_then(|index| shape.get(*index))
            .copied()
            .unwrap_or(0.0);
        result.push(first * second);
    }
    result
}

fn weighted_light(lights: &[u32], weights: &[f32]) -> u32 {
    let sky = lights
        .iter()
        .zip(weights.iter())
        .map(|(value, weight)| smooth_sky(*value) as f32 * *weight)
        .sum::<f32>() as u32;
    let block = lights
        .iter()
        .zip(weights.iter())
        .map(|(value, weight)| smooth_block(*value) as f32 * *weight)
        .sum::<f32>() as u32;
    block.min(255) | (sky.min(255) << 16)
}

fn remap_values(direction: usize, colors: &mut Vec<u32>, lights: &mut Vec<u32>) {
    let remap = match direction {
        1 => vec![2, 3, 0, 1],
        2 | 4 => vec![3, 0, 1, 2],
        5 => vec![1, 2, 3, 0],
        _ => vec![0, 1, 2, 3],
    };
    let mut remapped_colors = Vec::with_capacity(4);
    let mut remapped_lights = Vec::with_capacity(4);
    for source in remap {
        if let (Some(color), Some(light)) = (colors.get(source), lights.get(source)) {
            remapped_colors.push(*color);
            remapped_lights.push(*light);
        }
    }
    *colors = remapped_colors;
    *lights = remapped_lights;
}

fn scale_gray(color: u32, scale: f32) -> u32 {
    let channel = (((color >> 16) & 255) as f32 * scale)
        .floor()
        .clamp(0.0, 255.0) as u32;
    0xff00_0000 | (channel << 16) | (channel << 8) | channel
}

fn face_cubic(quad: &Quad, state: &State) -> bool {
    let Some(first) = quad.vertices.first() else {
        return false;
    };
    let mut min_x = first.x;
    let mut min_y = first.y;
    let mut min_z = first.z;
    let mut max_x = first.x;
    let mut max_y = first.y;
    let mut max_z = first.z;
    for vertex in quad.vertices.iter().skip(1) {
        min_x = min_x.min(vertex.x);
        min_y = min_y.min(vertex.y);
        min_z = min_z.min(vertex.z);
        max_x = max_x.max(vertex.x);
        max_y = max_y.max(vertex.y);
        max_z = max_z.max(vertex.z);
    }
    let full = state.flags & STATE_COLLISION_FULL != 0;
    match quad.direction {
        0 => min_y == max_y && (min_y < 0.0001 || full),
        1 => min_y == max_y && (max_y > 0.9999 || full),
        2 => min_z == max_z && (min_z < 0.0001 || full),
        3 => min_z == max_z && (max_z > 0.9999 || full),
        4 => min_x == max_x && (min_x < 0.0001 || full),
        5 => min_x == max_x && (max_x > 0.9999 || full),
        _ => false,
    }
}

fn cardinal(snapshot: &Snapshot, quad: &Quad) -> f32 {
    let index = if quad.flags & 1 == 0 {
        1
    } else {
        usize::try_from(quad.direction).unwrap_or(1).min(5)
    };
    snapshot.cardinal.get(index).copied().unwrap_or(1.0)
}

fn smooth_blend(a: u32, b: u32, c: u32, d: u32) -> u32 {
    let mut values = [a, b, c, d];
    if smooth_sky(d) > 2 || smooth_block(d) > 2 {
        for value in values.iter_mut().take(3) {
            if *value == 0 {
                *value = d;
            } else if smooth_sky(*value) == 0 {
                *value = (*value & 0x00ff_00ff) | (d & 0x00ff_0000);
            }
        }
    }
    values.iter().copied().sum::<u32>().wrapping_shr(2) & 0x00ff_00ff
}

fn smooth_block(value: u32) -> u32 {
    value & 255
}

fn smooth_sky(value: u32) -> u32 {
    (value >> 16) & 255
}

fn light_with_emission(value: u32, emission: u32) -> u32 {
    if emission == 0 {
        return value;
    }
    let block = (smooth_block(value) >> 4).max(emission).min(15) << 4;
    let sky = (smooth_sky(value) >> 4).max(emission).min(15) << 20;
    block | sky
}

fn gray(value: f32) -> u32 {
    let channel = (value * 255.0).floor().clamp(0.0, 255.0) as u32;
    0xff00_0000 | (channel << 16) | (channel << 8) | channel
}

fn multiply_color(first: u32, second: u32) -> u32 {
    if second == u32::MAX {
        return first;
    }
    let alpha = ((first >> 24) & 255) * ((second >> 24) & 255) / 255;
    let red = ((first >> 16) & 255) * ((second >> 16) & 255) / 255;
    let green = ((first >> 8) & 255) * ((second >> 8) & 255) / 255;
    let blue = (first & 255) * (second & 255) / 255;
    (alpha << 24) | (red << 16) | (green << 8) | blue
}

fn to_abgr(argb: u32) -> u32 {
    (argb & 0xff00_ff00) | ((argb & 0x00ff_0000) >> 16) | ((argb & 0x0000_00ff) << 16)
}

fn direction_delta(direction: usize) -> Option<(i32, i32, i32)> {
    match direction {
        0 => Some((0, -1, 0)),
        1 => Some((0, 1, 0)),
        2 => Some((0, 0, -1)),
        3 => Some((0, 0, 1)),
        4 => Some((-1, 0, 0)),
        5 => Some((1, 0, 0)),
        _ => None,
    }
}

fn offset(x: usize, y: usize, z: usize, direction: usize) -> Option<(usize, usize, usize)> {
    let delta = match direction {
        0 => (0i32, -1, 0),
        1 => (0, 1, 0),
        2 => (0, 0, -1),
        3 => (0, 0, 1),
        4 => (-1, 0, 0),
        5 => (1, 0, 0),
        _ => return None,
    };
    add((x, y, z), delta.0, delta.1, delta.2)
}

fn add(point: (usize, usize, usize), x: i32, y: i32, z: i32) -> Option<(usize, usize, usize)> {
    Some((
        usize::try_from(point.0 as i32 + x).ok()?,
        usize::try_from(point.1 as i32 + y).ok()?,
        usize::try_from(point.2 as i32 + z).ok()?,
    ))
}

fn cell_index(x: usize, y: usize, z: usize) -> Option<usize> {
    if x >= CELL_SIDE || y >= CELL_SIDE || z >= CELL_SIDE {
        return None;
    }
    y.checked_mul(CELL_SIDE)?
        .checked_add(z)?
        .checked_mul(CELL_SIDE)?
        .checked_add(x)
}

fn center_index(x: usize, y: usize, z: usize) -> Option<usize> {
    if x >= CENTER_SIDE || y >= CENTER_SIDE || z >= CENTER_SIDE {
        return None;
    }
    y.checked_mul(CENTER_SIDE)?
        .checked_add(z)?
        .checked_mul(CENTER_SIDE)?
        .checked_add(x)
}

fn opposite(direction: usize) -> Option<usize> {
    match direction {
        0 => Some(1),
        1 => Some(0),
        2 => Some(3),
        3 => Some(2),
        4 => Some(5),
        5 => Some(4),
        _ => None,
    }
}

fn write_u32(bytes: &mut [u8], offset: usize, value: u32) {
    if let Some(target) = bytes.get_mut(offset..offset.saturating_add(4)) {
        target.copy_from_slice(&value.to_le_bytes());
    }
}

fn put_u32(bytes: &mut Vec<u8>, value: u32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn put_f32(bytes: &mut Vec<u8>, value: f32) {
    put_u32(bytes, value.to_bits());
}

fn read_u32(bytes: &[u8], offset: usize) -> Option<u32> {
    let value = bytes.get(offset..offset.checked_add(4)?)?;
    let a = *value.first()?;
    let b = *value.get(1)?;
    let c = *value.get(2)?;
    let d = *value.get(3)?;
    Some(u32::from_le_bytes([a, b, c, d]))
}

fn copy_vertex(source: &[u8], target: &mut [u8]) {
    let position = source.get(0..12).unwrap_or(&[]);
    if let Some(destination) = target.get_mut(0..12) {
        destination.copy_from_slice(position);
    }
    let color = read_u32(source, 12).map(to_abgr).unwrap_or(0);
    write_u32(target, 12, color);
    let uv = source.get(16..24).unwrap_or(&[]);
    if let Some(destination) = target.get_mut(16..24) {
        destination.copy_from_slice(uv);
    }
    let light = read_u32(source, 24).unwrap_or(0);
    write_u32(target, 24, light);
}

#[cfg(test)]
mod tests {
    use super::{
        Box3, Cell, Center, INPUT_STRIDE, PACK_OUTPUT_MAGIC, PACK_SNAPSHOT_VERSION, Part,
        QUAD_UNCULLED, Quad, SNAPSHOT_MAGIC, SNAPSHOT_VERSION, STATE_COLLISION_FULL,
        STATE_SOLID_RENDER, STATE_VIEW_BLOCKING, SectionBatchItem, Shape, Snapshot, State,
        TABLE_MAGIC, TABLE_VERSION, Table, Vertex, cell_index, mesh_block_quads, mesh_section,
        mesh_sections,
    };

    fn test_quad(flags: u32, tint: i32) -> Quad {
        Quad {
            vertices: vec![
                Vertex {
                    x: 0.0,
                    y: 1.0,
                    z: 0.0,
                    u: 0.0,
                    v: 0.0,
                },
                Vertex {
                    x: 0.0,
                    y: 1.0,
                    z: 1.0,
                    u: 0.0,
                    v: 1.0,
                },
                Vertex {
                    x: 1.0,
                    y: 1.0,
                    z: 1.0,
                    u: 1.0,
                    v: 1.0,
                },
                Vertex {
                    x: 1.0,
                    y: 1.0,
                    z: 0.0,
                    u: 1.0,
                    v: 0.0,
                },
            ],
            direction: 1,
            tint,
            flags,
            layer: 0,
        }
    }

    fn test_state(id: u32, solid: bool, quad: Option<Quad>) -> State {
        State {
            id,
            flags: if solid {
                STATE_VIEW_BLOCKING | STATE_COLLISION_FULL | STATE_SOLID_RENDER | (15 << 13)
            } else {
                0
            },
            skip_group: 0,
            faces: (0..6)
                .map(|_| {
                    if solid {
                        Shape {
                            boxes: vec![Box3 {
                                min_x: 0.0,
                                min_y: 0.0,
                                min_z: 0.0,
                                max_x: 1.0,
                                max_y: 1.0,
                                max_z: 1.0,
                            }],
                        }
                    } else {
                        Shape { boxes: Vec::new() }
                    }
                })
                .collect(),
            selectors: vec![super::Selector {
                states: vec![id],
                variants: vec![super::Variant {
                    weight: 1,
                    parts: vec![Part {
                        ambient_occlusion: true,
                        quads: quad.into_iter().collect(),
                    }],
                }],
            }],
        }
    }

    fn test_snapshot(center: u32, tint: u32, side: bool, top: bool) -> Vec<u8> {
        let mut cells = vec![
            Cell {
                state: 0,
                light: 0x00ff_00ff,
                skip_mask: 0,
                shade: 1.0,
                tints: vec![u32::MAX; 4]
            };
            super::CELL_COUNT
        ];
        if let Some(value) = cell_index(9, 9, 9).and_then(|index| cells.get_mut(index)) {
            value.state = center;
            value.tints = vec![tint, u32::MAX, u32::MAX, u32::MAX];
        }
        if top && let Some(value) = cell_index(9, 10, 9).and_then(|index| cells.get_mut(index)) {
            value.state = 2;
        }
        if side {
            for point in [(10, 10, 9), (9, 10, 10)] {
                if let Some(value) =
                    cell_index(point.0, point.1, point.2).and_then(|index| cells.get_mut(index))
                {
                    value.state = center;
                    value.shade = 0.2;
                }
            }
        }
        let centers = vec![
            Center {
                seed: 0,
                offset: vec![0.0, 0.0, 0.0]
            };
            super::CENTER_COUNT
        ];
        encode_snapshot(&Snapshot {
            flags: 1,
            metadata_mask: 0,
            cardinal: vec![1.0; 6],
            cells,
            centers,
        })
    }

    fn encode_snapshot(snapshot: &Snapshot) -> Vec<u8> {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&SNAPSHOT_MAGIC.to_le_bytes());
        bytes.extend_from_slice(&SNAPSHOT_VERSION.to_le_bytes());
        bytes.extend_from_slice(&snapshot.flags.to_le_bytes());
        bytes.extend_from_slice(&snapshot.metadata_mask.to_le_bytes());
        for value in &snapshot.cardinal {
            bytes.extend_from_slice(&value.to_bits().to_le_bytes());
        }
        for cell in &snapshot.cells {
            bytes.extend_from_slice(&cell.state.to_le_bytes());
            bytes.extend_from_slice(&cell.light.to_le_bytes());
            bytes.extend_from_slice(&cell.shade.to_bits().to_le_bytes());
            for tint in &cell.tints {
                bytes.extend_from_slice(&tint.to_le_bytes());
            }
            bytes.extend_from_slice(&cell.skip_mask.to_le_bytes());
        }
        for center in &snapshot.centers {
            bytes.extend_from_slice(&center.seed.to_le_bytes());
            for value in &center.offset {
                bytes.extend_from_slice(&value.to_bits().to_le_bytes());
            }
        }
        bytes
    }

    fn render(table: &Table, input: &[u8]) -> Vec<u8> {
        let mut output = vec![0; 4096];
        let written = mesh_section(table, input, &mut output);
        assert!(written.is_some());
        output
    }

    fn pack_snapshot(mask: u32) -> Vec<u8> {
        let mut bytes = test_snapshot(37, u32::MAX, false, false);
        if let Some(value) = bytes.get_mut(4..8) {
            value.copy_from_slice(&PACK_SNAPSHOT_VERSION.to_le_bytes());
        }
        if let Some(value) = bytes.get_mut(12..16) {
            value.copy_from_slice(&mask.to_le_bytes());
        }
        bytes
    }

    #[test]
    fn emits_ordered_pack_metadata_with_state_id_and_mid_uv() {
        let mut table = Table::new();
        table.states.push(test_state(0, false, None));
        table
            .states
            .push(test_state(37, true, Some(test_quad(QUAD_UNCULLED, -1))));
        let output = render(&table, &pack_snapshot(3));
        assert_eq!(super::read_u32(&output, 0), Some(PACK_OUTPUT_MAGIC));
        assert_eq!(super::read_u32(&output, 12), Some(112));
        assert_eq!(super::read_u32(&output, 24), Some(16));
        assert_eq!(super::read_u32(&output, 36 + 112), Some(37));
        assert_eq!(super::read_u32(&output, 36 + 112 + 4), Some(0x4000_4000));
    }

    #[test]
    fn rejects_truncated_pack_output_and_keeps_empty_mask_legacy() {
        let mut table = Table::new();
        table.states.push(test_state(0, false, None));
        table
            .states
            .push(test_state(1, true, Some(test_quad(QUAD_UNCULLED, -1))));
        let input = pack_snapshot(1);
        let mut output = vec![0; 35];
        assert!(mesh_section(&table, &input, &mut output).is_none());
    }

    #[test]
    fn empty_batch_does_not_process_items() {
        let table = Table::new();
        let mut items = Vec::new();
        mesh_sections(&table, &mut items);
        assert!(items.is_empty());
    }

    #[test]
    fn batches_independent_sections_and_lengths() {
        let mut table = Table::new();
        table.states.push(test_state(0, false, None));
        table
            .states
            .push(test_state(1, true, Some(test_quad(0, -1))));
        let input = test_snapshot(1, u32::MAX, false, false);
        let mut first = vec![0; 4096];
        let mut second = vec![0; 4096];
        let mut small = vec![0; 11];
        let mut items = vec![
            SectionBatchItem {
                input: &input,
                output: &mut first,
                result: Default::default(),
            },
            SectionBatchItem {
                input: &input,
                output: &mut second,
                result: Default::default(),
            },
            SectionBatchItem {
                input: &input,
                output: &mut small,
                result: Default::default(),
            },
        ];
        mesh_sections(&table, &mut items);
        assert_eq!(
            items.first().map(|item| item.result.written),
            Some(12 + 112)
        );
        assert_eq!(items.get(1).map(|item| item.result.written), Some(12 + 112));
        assert_eq!(items.get(2).map(|item| item.result.written), Some(0));
        assert_eq!(first, second);
    }

    #[test]
    fn writes_block_vertex_layout() {
        let mut input = vec![0; INPUT_STRIDE];
        if let Some(bytes) = input.get_mut(12..16) {
            bytes.copy_from_slice(&0x1122_3344_u32.to_le_bytes());
        }
        if let Some(bytes) = input.get_mut(16..24) {
            bytes.copy_from_slice(&1.0_f32.to_le_bytes().repeat(2));
        }
        let mut output = vec![0; INPUT_STRIDE];
        assert!(mesh_block_quads(&input, &mut output));
        assert_eq!(output.get(12..16), Some(&0x1144_3322_u32.to_le_bytes()[..]));
        assert_eq!(output.get(16..24), input.get(16..24));
    }

    #[test]
    fn rejects_partial_quad_data() {
        let mut output = Vec::new();
        assert!(!mesh_block_quads(&[0; INPUT_STRIDE - 1], &mut output));
    }

    #[test]
    fn culls_a_fully_occluded_face() {
        let mut table = Table::new();
        table.states.push(test_state(0, false, None));
        table
            .states
            .push(test_state(1, true, Some(test_quad(0, -1))));
        table.states.push(test_state(2, true, None));
        let input = test_snapshot(1, u32::MAX, false, true);
        let output = render(&table, &input);
        assert_eq!(super::read_u32(&output, 0), Some(0));
    }

    #[test]
    fn keeps_unculled_quads_when_the_face_is_occluded() {
        let mut table = Table::new();
        table.states.push(test_state(0, false, None));
        table
            .states
            .push(test_state(1, true, Some(test_quad(QUAD_UNCULLED, -1))));
        table.states.push(test_state(2, true, None));
        let output = render(&table, &test_snapshot(1, u32::MAX, false, true));
        assert_eq!(super::read_u32(&output, 0), Some(112));
    }

    #[test]
    fn applies_tint_to_flat_lighting() {
        let mut table = Table::new();
        table.states.push(test_state(0, false, None));
        table
            .states
            .push(test_state(1, true, Some(test_quad(0, 0))));
        let input = test_snapshot(1, 0xff11_2233, false, false);
        let output = render(&table, &input);
        assert_eq!(super::read_u32(&output, 24), Some(0xff33_2211));
    }

    #[test]
    fn darkens_ambient_corner() {
        let mut table = Table::new();
        table.states.push(test_state(0, false, None));
        table
            .states
            .push(test_state(1, true, Some(test_quad(1, -1))));
        let clear_input = test_snapshot(1, u32::MAX, false, false);
        let blocked_input = test_snapshot(1, u32::MAX, true, false);
        let Some(clear_snapshot) = super::parse_snapshot(&clear_input) else {
            return;
        };
        let Some(blocked_snapshot) = super::parse_snapshot(&blocked_input) else {
            return;
        };
        let Some(state) = table.states.iter().find(|value| value.id == 1) else {
            return;
        };
        let quad = test_quad(1, -1);
        let clear_values = super::ambient_values(&table, &clear_snapshot, state, 9, 9, 9, &quad);
        let blocked_values =
            super::ambient_values(&table, &blocked_snapshot, state, 9, 9, 9, &quad);
        assert!(clear_values.is_some());
        assert!(blocked_values.is_some());
        if let (Some((clear_colors, _)), Some((blocked_colors, _))) = (clear_values, blocked_values)
        {
            assert!(
                blocked_colors
                    .iter()
                    .zip(clear_colors.iter())
                    .any(|(blocked, clear)| blocked < clear),
                "{blocked_colors:?} {clear_colors:?}"
            );
        }
        let clear = render(&table, &clear_input);
        let blocked = render(&table, &blocked_input);
        assert!(super::read_u32(&clear, 0).is_some());
        assert!(super::read_u32(&blocked, 0).is_some());
    }

    fn encoded_state(selectors: &[(Vec<u32>, usize)]) -> Vec<u8> {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&TABLE_MAGIC.to_le_bytes());
        bytes.extend_from_slice(&TABLE_VERSION.to_le_bytes());
        bytes.extend_from_slice(&0u32.to_le_bytes());
        bytes.extend_from_slice(&0u32.to_le_bytes());
        for _ in 0..6 {
            bytes.extend_from_slice(&0u32.to_le_bytes());
        }
        bytes.extend_from_slice(&u32::try_from(selectors.len()).unwrap_or(0).to_le_bytes());
        for (states, quad_count) in selectors {
            bytes.extend_from_slice(&u32::try_from(states.len()).unwrap_or(0).to_le_bytes());
            for state in states {
                bytes.extend_from_slice(&state.to_le_bytes());
            }
            bytes.extend_from_slice(&1u32.to_le_bytes());
            bytes.extend_from_slice(&1u32.to_le_bytes());
            bytes.extend_from_slice(&1u32.to_le_bytes());
            bytes.extend_from_slice(&1u32.to_le_bytes());
            bytes.extend_from_slice(&u32::try_from(*quad_count).unwrap_or(0).to_le_bytes());
            bytes.resize(
                bytes
                    .len()
                    .saturating_add(112usize.saturating_mul(*quad_count)),
                0,
            );
        }
        bytes
    }

    #[test]
    fn accepts_unconditional_selector() {
        let bytes = encoded_state(&[(vec![7], 1)]);
        let mut table = Table::new();
        assert!(table.add_state(7, 3, &bytes));
        assert_eq!(table.state_count(), 1);
        assert_eq!(table.quad_count(), 1);
        assert_eq!(table.state_signature(7), Some((3, 1)));
        let selector = table
            .states
            .first()
            .and_then(|state| state.selectors.first());
        assert!(selector.is_some_and(|value| value.states == [7]));
        assert!(selector.is_some_and(|value| value.variants.len() == 1));
    }

    #[test]
    fn accepts_selector_with_multiple_matching_states() {
        let bytes = encoded_state(&[(vec![3, 7, 11], 1)]);
        let mut table = Table::new();
        assert!(table.add_state(7, 0, &bytes));
        let selector = table
            .states
            .first()
            .and_then(|state| state.selectors.first());
        assert!(selector.is_some_and(|value| value.states == [3, 7, 11]));
        assert!(
            table
                .states
                .first()
                .is_some_and(|state| state.matching_variants(11).next().is_some())
        );
    }

    #[test]
    fn rejects_malformed_selector_lengths() {
        let mut bytes = encoded_state(&[(vec![7], 0)]);
        let selector_state_count_offset = 16 + 6 * 4 + 4;
        if let Some(value) =
            bytes.get_mut(selector_state_count_offset..selector_state_count_offset + 4)
        {
            value.copy_from_slice(&2u32.to_le_bytes());
        }
        let mut table = Table::new();
        assert!(!table.add_state(7, 0, &bytes));

        let empty_selector = encoded_state(&[(Vec::new(), 0)]);
        assert!(table.add_state(7, 0, &empty_selector));
    }

    #[test]
    fn accounts_for_all_selector_output_capacity() {
        let bytes = encoded_state(&[(vec![7], 2), (vec![7], 3)]);
        let mut table = Table::new();
        assert!(table.add_state(7, 0, &bytes));
        assert_eq!(table.quad_count(), 5);
        assert_eq!(
            table.max_output_bytes(),
            12 + super::CENTER_COUNT * 5 * 4 * 28
        );
    }

    #[test]
    fn meshes_matching_parts_in_order_and_layers() {
        let mut state = test_state(1, true, Some(test_quad(1, -1)));
        state.selectors.push(super::Selector {
            states: vec![1],
            variants: vec![super::Variant {
                weight: 1,
                parts: vec![Part {
                    ambient_occlusion: true,
                    quads: vec![test_quad(1, -1)],
                }],
            }],
        });
        state.selectors.push(super::Selector {
            states: vec![99],
            variants: vec![super::Variant {
                weight: 1,
                parts: vec![Part {
                    ambient_occlusion: true,
                    quads: vec![test_quad(1, -1)],
                }],
            }],
        });
        if let Some(selector) = state.selectors.get_mut(1)
            && let Some(variant) = selector.variants.first_mut()
            && let Some(part) = variant.parts.first_mut()
            && let Some(quad) = part.quads.first_mut()
        {
            quad.layer = 1;
        }
        let mut table = Table::new();
        table.states.push(test_state(0, false, None));
        table.states.push(state);
        let output = render(&table, &test_snapshot(1, u32::MAX, false, false));
        assert_eq!(super::read_u32(&output, 0), Some(112));
        assert_eq!(super::read_u32(&output, 4), Some(112));
        assert_eq!(super::read_u32(&output, 8), Some(0));

        let no_match = test_state(1, true, None);
        assert_eq!(no_match.matching_variants(99).count(), 0);
    }

    #[test]
    fn weighted_selection_matches_legacy_random_source() {
        let variants = [
            super::Variant {
                weight: 1,
                parts: Vec::new(),
            },
            super::Variant {
                weight: 3,
                parts: Vec::new(),
            },
        ];
        assert_eq!(
            super::select_variant(&variants, 4096).map(|v| v.weight),
            Some(1)
        );
        assert_eq!(
            super::select_variant(&variants, 0).map(|v| v.weight),
            Some(3)
        );
        assert_eq!(super::next_long(0), Some(13_483_975_608_033_169_720));
    }
}
