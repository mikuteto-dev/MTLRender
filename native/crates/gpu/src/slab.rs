pub struct Slab<T> {
    entries: Vec<(u32, Option<T>)>,
    free: Vec<u32>,
}

impl<T> Default for Slab<T> {
    fn default() -> Self {
        Self {
            entries: Vec::new(),
            free: Vec::new(),
        }
    }
}

impl<T> Slab<T> {
    pub fn insert(&mut self, value: T) -> u64 {
        if let Some(index) = self.free.pop()
            && let Some(entry) = self.entries.get_mut(index as usize)
        {
            entry.1 = Some(value);
            return (u64::from(entry.0) << 32) | u64::from(index);
        }
        self.entries.push((1, Some(value)));
        (1u64 << 32) | (self.entries.len() - 1) as u64
    }

    pub fn get(&self, handle: u64) -> Option<&T> {
        let (generation, index) = split(handle);
        self.entries
            .get(index)
            .filter(|e| e.0 == generation)
            .and_then(|e| e.1.as_ref())
    }

    pub fn find(&self, predicate: impl Fn(&T) -> bool) -> Option<&T> {
        self.entries
            .iter()
            .filter_map(|(_, value)| value.as_ref())
            .find(|value| predicate(value))
    }

    pub fn remove(&mut self, handle: u64) -> Option<T> {
        let (generation, index) = split(handle);
        let entry = self.entries.get_mut(index).filter(|e| e.0 == generation)?;
        let value = entry.1.take()?;
        entry.0 = entry.0.wrapping_add(1).max(1);
        self.free.push(index as u32);
        Some(value)
    }
}

fn split(handle: u64) -> (u32, usize) {
    ((handle >> 32) as u32, (handle & 0xffff_ffff) as usize)
}
