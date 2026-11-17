package dev.mikuteto.mtlrender.particle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ParticleSortKeyTest {
	@Test
	void reusesOnlyWhenCameraCollectionLayerAndTextureAreStable() {
		ParticleSortKey base = new ParticleSortKey(4L, 1.0, 2.0, 3.0, 5, 6);
		assertTrue(base.matches(new ParticleSortKey(4L, 1.0, 2.0, 3.0, 5, 6)));
		assertFalse(base.matches(new ParticleSortKey(4L, 2.0, 2.0, 3.0, 5, 6)));
		assertFalse(base.matches(new ParticleSortKey(5L, 1.0, 2.0, 3.0, 5, 6)));
		assertFalse(base.matches(new ParticleSortKey(4L, 1.0, 2.0, 3.0, 7, 6)));
		assertFalse(base.matches(new ParticleSortKey(4L, 1.0, 2.0, 3.0, 5, 8)));
	}
}
