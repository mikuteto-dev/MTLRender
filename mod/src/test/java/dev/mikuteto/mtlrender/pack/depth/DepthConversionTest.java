package dev.mikuteto.mtlrender.pack.depth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DepthConversionTest {
	@Test
	void convertsPackDepthAtAllBoundaryValues() {
		assertEquals(1.0f, DepthConversion.gameToPack(0.0f));
		assertEquals(0.75f, DepthConversion.gameToPack(0.25f));
		assertEquals(0.5f, DepthConversion.gameToPack(0.5f));
		assertEquals(0.0f, DepthConversion.gameToPack(1.0f));
	}

	@Test
	void rejectsValuesOutsideTheDepthRange() {
		assertThrows(IllegalArgumentException.class, () -> DepthConversion.gameToPack(-0.01f));
		assertThrows(IllegalArgumentException.class, () -> DepthConversion.gameToPack(Float.NaN));
	}

	@Test
	void convertsOneResourceVersionAndSharesAliases() {
		PackDepthProducer<String> producer = new PackDepthProducer<>();
		AtomicInteger conversions = new AtomicInteger();
		Optional<String> first = producer.produce("depthtex0", 7L, "game-depth", source -> {
			conversions.incrementAndGet();
			return "pack-" + source;
		});
		Optional<String> alias = producer.produce("gdepth", 7L, "game-depth", source -> {
			conversions.incrementAndGet();
			return "unexpected-" + source;
		});

		assertEquals(Optional.of("pack-game-depth"), first);
		assertEquals(first, alias);
		assertEquals(1, conversions.get());
		assertEquals(first, producer.read("gdepthtex"));
	}

	@Test
	void handlesAbsenceAndColorNoOp() {
		PackDepthProducer<String> producer = new PackDepthProducer<>();
		assertTrue(producer.produce("depthtex1", 1L, null, value -> value).isEmpty());
		assertTrue(producer.produce("colortex0", 1L, "color", value -> value).isEmpty());
		assertTrue(producer.read("depthtex1").isEmpty());
	}
}
