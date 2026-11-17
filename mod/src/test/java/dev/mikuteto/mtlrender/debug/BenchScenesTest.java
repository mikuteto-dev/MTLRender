package dev.mikuteto.mtlrender.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class BenchScenesTest {
	@Test
	void manifestHasStableVersionAndOrdering() {
		assertEquals(1, BenchScenes.manifestVersion());
		assertEquals(List.of("dev-plains", "dev-forest", "dev-windswept", "dev-desert"), ids(BenchScenes.development()));
		assertEquals(List.of("heldout-taiga", "heldout-swamp", "heldout-badlands", "heldout-snowy-peaks", "holdout_forest"), ids(BenchScenes.heldOut()));
	}

	@Test
	void scenesHaveUniqueIdsSeedsAndPaths() {
		List<BenchScene> scenes = Stream.concat(BenchScenes.development().stream(), BenchScenes.heldOut().stream()).toList();
		assertEquals(scenes.size(), scenes.stream().map(BenchScene::id).collect(java.util.stream.Collectors.toSet()).size());
		assertEquals(scenes.size(), scenes.stream().map(BenchScene::seed).collect(java.util.stream.Collectors.toSet()).size());
		assertEquals(scenes.size(), scenes.stream().map(scene -> scene.cameraPath().id()).collect(java.util.stream.Collectors.toSet()).size());
		assertTrue(scenes.stream().allMatch(scene -> scene.cameraPath().durationSeconds() > 0.0));
	}

	@Test
	void heldOutScenesAreNotDevelopmentAliases() {
		Set<Long> developmentSeeds = new HashSet<>();
		Set<String> developmentPaths = new HashSet<>();
		for (BenchScene scene : BenchScenes.development()) {
			developmentSeeds.add(scene.seed());
			developmentPaths.add(scene.cameraPath().id());
		}
		assertTrue(BenchScenes.heldOut().stream().allMatch(scene -> !developmentSeeds.contains(scene.seed())));
		assertTrue(BenchScenes.heldOut().stream().allMatch(scene -> !developmentPaths.contains(scene.cameraPath().id())));
		assertNotEquals(BenchScenes.development(), BenchScenes.heldOut());
		assertFalse(BenchScenes.heldOut().isEmpty());
	}

	@Test
	void lookupIsDeterministicAndUnknownIdsAreAbsent() {
		assertEquals("heldout-badlands", BenchScenes.byId("heldout-badlands").orElseThrow().id());
		assertTrue(BenchScenes.byId("missing").isEmpty());
	}

	private static List<String> ids(List<BenchScene> scenes) {
		return scenes.stream().map(BenchScene::id).toList();
	}
}
