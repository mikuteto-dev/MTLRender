package dev.mikuteto.mtlrender.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class BenchWorldTest {
	@Test
	void manifestSceneProducesStableWorldAndPoints() {
		BenchScene scene = BenchScenes.byId("dev-plains").orElseThrow();
		List<BlockPos> first = BenchWorld.buildPoints(scene);
		List<BlockPos> second = BenchWorld.buildPoints(scene);
		assertEquals(first, second);
		assertEquals("mtlr-dev-plains", scene.worldName());
		assertEquals(20261020L, scene.seed());
		assertEquals(new BlockPos(-72, 200, -72), first.getFirst());
	}

	@Test
	void scenesDoNotShareGenerationWorldOrTeleportPoints() {
		BenchScene development = BenchScenes.byId("dev-plains").orElseThrow();
		BenchScene heldOut = BenchScenes.byId("heldout-taiga").orElseThrow();
		assertNotEquals(development.worldName(), heldOut.worldName());
		assertNotEquals(development.seed(), heldOut.seed());
		assertNotEquals(BenchWorld.buildPoints(development), BenchWorld.buildPoints(heldOut));
	}
}
