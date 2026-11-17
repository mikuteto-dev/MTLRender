package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class TerrainRendererNoPackGateTest {
	@Test
	void emptyConfigurationDoesNotObservePackState() {
		PackTerrainRenderState.Probe probe = () -> {
			throw new AssertionError("pack state was observed");
		};

		PackTerrainRenderState state = PackTerrainRenderState.select("", probe);

		assertFalse(state.packActive());
		assertEquals(PackTerrainRenderState.none(), state);
	}

	@Test
	void nullConfigurationUsesTheSameNoPackState() {
		PackTerrainRenderState state = PackTerrainRenderState.select(null, () -> {
			throw new AssertionError("pack state was observed");
		});

		assertFalse(state.packActive());
	}
}
