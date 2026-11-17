package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.backend.PackPipelineDesc;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LayerInstancingTest {
	@Test
	void validatesInstanceCountAndPreservesMapping() {
		PackPipelineDesc.LayerInstancing layers = new PackPipelineDesc.LayerInstancing(3, 0, List.of(1, 2, 3));
		assertEquals(3, layers.instanceCount());
		assertEquals(List.of(1, 2, 3), layers.layerMapping());
	}

	@Test
	void rejectsInvalidLayerBoundsAndCounts() {
		assertThrows(IllegalArgumentException.class,
			() -> new PackPipelineDesc.LayerInstancing(2, 0, List.of(1)));
		assertThrows(IllegalArgumentException.class,
			() -> new PackPipelineDesc.LayerInstancing(1, 0, List.of(2048)));
	}
}
