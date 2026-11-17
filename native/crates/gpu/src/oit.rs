#[derive(Debug, PartialEq)]
struct Layer {
    color: [f32; 3],
    alpha: f32,
    depth: f32,
}

#[derive(Debug, PartialEq)]
struct KBuffer {
    layers: Vec<Layer>,
    capacity: usize,
}

impl KBuffer {
    fn new(capacity: usize) -> Option<Self> {
        (4..=8).contains(&capacity).then_some(Self {
            layers: Vec::with_capacity(capacity),
            capacity,
        })
    }

    fn insert(&mut self, color: [f32; 3], alpha: f32, depth: f32) {
        let layer = Layer {
            color: {
                let [red, green, blue] = color;
                [red * alpha, green * alpha, blue * alpha]
            },
            alpha,
            depth,
        };
        let position = match self
            .layers
            .iter()
            .position(|existing| existing.depth > depth)
        {
            Some(position) => position,
            None => self.layers.len(),
        };
        self.layers.insert(position, layer);
        if self.layers.len() > self.capacity {
            let far = self.layers.remove(0);
            let next = self.layers.remove(0);
            self.layers.insert(0, over(far, next));
        }
    }

    fn resolve(&self) -> ([f32; 3], f32) {
        self.layers.iter().fold(([0.0; 3], 0.0), |acc, layer| {
            over_color(acc, (layer.color, layer.alpha))
        })
    }
}

fn over(far: Layer, near: Layer) -> Layer {
    let merged = over_color((far.color, far.alpha), (near.color, near.alpha));
    Layer {
        color: merged.0,
        alpha: merged.1,
        depth: near.depth,
    }
}

fn over_color(far: ([f32; 3], f32), near: ([f32; 3], f32)) -> ([f32; 3], f32) {
    let remaining = 1.0 - far.1;
    let [far_red, far_green, far_blue] = far.0;
    let [near_red, near_green, near_blue] = near.0;
    (
        [
            far_red + near_red * remaining,
            far_green + near_green * remaining,
            far_blue + near_blue * remaining,
        ],
        far.1 + near.1 * remaining,
    )
}

#[cfg(test)]
mod tests {
    use super::KBuffer;

    fn assert_close(actual: f32, expected: f32) {
        assert!((actual - expected).abs() < 1.0e-6, "{actual} != {expected}");
    }

    #[test]
    fn accepts_only_supported_capacities() {
        assert!(KBuffer::new(3).is_none());
        for capacity in 4..=8 {
            assert!(KBuffer::new(capacity).is_some());
        }
        assert!(KBuffer::new(9).is_none());
    }

    #[test]
    fn keeps_reversed_z_layers_far_to_near() {
        let Some(mut buffer) = KBuffer::new(4) else {
            return;
        };
        buffer.insert([1.0, 0.0, 0.0], 0.5, 0.9);
        buffer.insert([0.0, 1.0, 0.0], 0.5, 0.2);
        buffer.insert([0.0, 0.0, 1.0], 0.5, 0.8);
        let depths: Vec<f32> = buffer.layers.iter().map(|layer| layer.depth).collect();
        assert_eq!(depths, [0.2, 0.8, 0.9]);
    }

    #[test]
    fn equal_depth_is_stable() {
        let Some(mut buffer) = KBuffer::new(4) else {
            return;
        };
        buffer.insert([1.0, 0.0, 0.0], 1.0, 0.5);
        buffer.insert([0.0, 1.0, 0.0], 1.0, 0.5);
        let Some(first) = buffer.layers.first() else {
            return;
        };
        assert_eq!(first.color, [1.0, 0.0, 0.0]);
        let Some(second) = buffer.layers.get(1) else {
            return;
        };
        assert_eq!(second.color, [0.0, 1.0, 0.0]);
    }

    #[test]
    fn overflow_merges_the_two_farthest_layers() {
        let Some(mut buffer) = KBuffer::new(4) else {
            return;
        };
        buffer.insert([1.0, 0.0, 0.0], 0.5, 0.1);
        buffer.insert([0.0, 1.0, 0.0], 0.5, 0.2);
        buffer.insert([0.0, 0.0, 1.0], 0.5, 0.3);
        buffer.insert([1.0, 1.0, 1.0], 0.5, 0.4);
        buffer.insert([1.0, 1.0, 0.0], 0.5, 0.5);
        assert_eq!(buffer.layers.len(), 4);
        let Some(merged) = buffer.layers.first() else {
            return;
        };
        let [red, green, blue] = merged.color;
        assert_close(red, 0.5);
        assert_close(green, 0.25);
        assert_close(blue, 0.0);
        assert_close(merged.alpha, 0.75);
        assert_close(merged.depth, 0.2);
    }

    #[test]
    fn alpha_edges_and_opaque_layers_match_over() {
        let Some(mut empty) = KBuffer::new(4) else {
            return;
        };
        empty.insert([0.2, 0.4, 0.8], 0.0, 0.1);
        assert_eq!(empty.resolve(), ([0.0, 0.0, 0.0], 0.0));

        let Some(mut opaque) = KBuffer::new(4) else {
            return;
        };
        opaque.insert([0.2, 0.4, 0.8], 1.0, 0.1);
        opaque.insert([1.0, 0.0, 0.0], 1.0, 0.9);
        assert_eq!(opaque.resolve(), ([0.2, 0.4, 0.8], 1.0));
    }

    #[test]
    fn every_supported_capacity_overflows_without_losing_near_order() {
        for capacity in 4..=8 {
            let Some(mut buffer) = KBuffer::new(capacity) else {
                return;
            };
            for index in 0..=capacity {
                buffer.insert([1.0, 0.0, 0.0], 0.25, index as f32);
            }
            assert_eq!(buffer.layers.len(), capacity);
            let Some(last) = buffer.layers.last() else {
                return;
            };
            assert_eq!(last.depth, capacity as f32);
        }
    }
}
