package dev.mikuteto.mtlrender.pack.tess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class TessellationStrategyTest {
	@Test
	void currentDefaultResolvesToCompute() {
		TessellationStrategy.Capabilities capabilities = TessellationStrategy.currentCapabilities();

		assertEquals(Optional.of(TessellationStrategy.COMPUTE), TessellationStrategy.AUTO.resolve(capabilities));
		assertTrue(TessellationStrategy.COMPUTE.available(capabilities));
	}

	@Test
	void explicitComputeWinsWhenMeshIsAlsoAvailable() {
		TessellationStrategy.Capabilities capabilities = new TessellationStrategy.Capabilities(true, true);

		assertEquals(Optional.of(TessellationStrategy.COMPUTE), TessellationStrategy.COMPUTE.resolve(capabilities));
	}

	@Test
	void meshIsUnavailableUntilItsCapabilityIsSupplied() {
		TessellationStrategy.Capabilities capabilities = TessellationStrategy.currentCapabilities();

		assertFalse(TessellationStrategy.MESH.available(capabilities));
		assertEquals(Optional.empty(), TessellationStrategy.MESH.resolve(capabilities));
		assertEquals(Optional.of(TessellationStrategy.MESH), TessellationStrategy.MESH.resolve(
			new TessellationStrategy.Capabilities(true, true)));
	}
}
