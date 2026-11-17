package dev.mikuteto.mtlrender.blockentity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class StaticBlockEntityBakeReloadTest {
	@Test
	void reloadRejectsInFlightOldPublicationAndKeepsDynamicFallback() {
		StaticBlockEntityBakeRegistry registry = new StaticBlockEntityBakeRegistry(() -> true);
		StaticBlockEntityBakePlan oldPlan = plan(10, true, true);
		registry.replace(10, List.of(oldPlan));
		StaticBlockEntityBakePublication inFlight = StaticBlockEntityBakePublication.candidate(4L, 80L, oldPlan);

		registry.replace(11, List.of(plan(11, false, true)));

		assertFalse(registry.isCurrent(inFlight));
		assertFalse(registry.tryPublish(inFlight));
		assertTrue(registry.published(4L).isEmpty());
		StaticBlockEntityBakePlan current = registry.lookup(new StaticBlockEntityBakeKey(11, "minecraft:chest", "oak")).orElseThrow();
		assertFalse(current.hasStaticPortion());
		assertTrue(current.fallbacks().stream().anyMatch(fallback ->
			fallback.reason() == StaticBlockEntityBakeFallback.Reason.DYNAMIC_SUBPART_RETAINED));
	}

	private static StaticBlockEntityBakePlan plan(long generation, boolean staticPart, boolean dynamicPart) {
		return new StaticBlockEntityBakePlan(
			new StaticBlockEntityBakeKey(generation, "minecraft:chest", "oak"),
			staticPart ? List.of(new StaticBlockEntityBakePart("base", new byte[] {1}, "chest")) : List.of(),
			dynamicPart ? List.of(new StaticBlockEntityBakeFallback(
				StaticBlockEntityBakeFallback.Reason.DYNAMIC_SUBPART_RETAINED, "lid")) : List.of()
		);
	}
}
