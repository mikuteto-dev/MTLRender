package dev.mikuteto.mtlrender.particle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ParticleLightCacheTest {
	@Test
	void reusesBlockPositionUntilInvalidated() {
		ParticleLightCache.clear();
		AtomicInteger lookups = new AtomicInteger();
		assertEquals(7, ParticleLightCache.get(11L, () -> {
			lookups.incrementAndGet();
			return 7;
		}));
		assertEquals(7, ParticleLightCache.get(11L, () -> {
			lookups.incrementAndGet();
			return 9;
		}));
		assertEquals(1, lookups.get());
		ParticleLightCache.clear();
		assertEquals(9, ParticleLightCache.get(11L, () -> {
			lookups.incrementAndGet();
			return 9;
		}));
		assertEquals(2, lookups.get());
	}
}
