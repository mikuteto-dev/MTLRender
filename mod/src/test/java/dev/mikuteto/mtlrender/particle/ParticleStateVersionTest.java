package dev.mikuteto.mtlrender.particle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ParticleStateVersionTest {
	@Test
	void changesOnCollectionMutation() {
		ParticleStateVersion version = new ParticleStateVersion();
		assertEquals(0L, version.value());
		version.changed();
		assertEquals(1L, version.value());
		version.changed();
		assertEquals(2L, version.value());
	}
}
