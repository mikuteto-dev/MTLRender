package dev.mikuteto.mtlrender.pack.ids;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdMapTest {
	@Test
	void appliesPropertyMacros() {
		IdMap.Parsed parsed = IdMap.parse(
			"#if ENABLE\n#define target minecraft:oak_log\nblock.1=target\n#endif\n",
			"",
			"",
			Map.of("ENABLE", ""));

		assertEquals("minecraft:oak_log", parsed.blocks().getFirst().names().getFirst());
	}

	@Test
	void resolvesStatesItemsEntitiesTagsAndLayers() {
		IdMap.Parsed parsed = IdMap.parse(
			"block.1=oak_log:axis=y\nblock.2=%logs\nlayer.translucent=oak_log:axis=x\n",
			"item.3=stick\n",
			"entity.4=zombie\n",
			Map.of());
		IdMap.Resolved resolved = IdMap.resolve(parsed,
			List.of(
				new IdMap.BlockStateName(7, "minecraft:oak_log", Map.of("axis", "y")),
				new IdMap.BlockStateName(8, "minecraft:oak_log", Map.of("axis", "x")),
				new IdMap.BlockStateName(9, "minecraft:birch_log", Map.of("axis", "y"))),
			List.of("minecraft:air", "minecraft:stick"),
			List.of("minecraft:zombie"),
			Map.of("minecraft:logs", List.of("minecraft:oak_log", "minecraft:birch_log")));

		assertEquals(1, resolved.blockStateIds()[7]);
		assertEquals(2, resolved.blockStateIds()[8]);
		assertEquals(2, resolved.blockStateIds()[9]);
		assertEquals(3, resolved.itemIds()[1]);
		assertEquals(4, resolved.entityIds()[0]);
		assertEquals(IdMap.Layer.TRANSLUCENT.ordinal(), resolved.blockLayers()[8]);
		assertTrue(resolved.blockLayers()[7] < 0);
	}

	@Test
	void directBlocksTakePrecedenceOverTagsAndUnknownPropertiesDoNotMatch() {
		IdMap.Parsed parsed = IdMap.parse(
			"block.1=%logs\nblock.2=oak_log:missing=value\nblock.3=oak_log\n",
			"",
			"",
			Map.of());
		IdMap.Resolved resolved = IdMap.resolve(parsed,
			List.of(new IdMap.BlockStateName(4, "minecraft:oak_log", Map.of()),
				new IdMap.BlockStateName(5, "minecraft:birch_log", Map.of())),
			List.of(), List.of(),
			Map.of("minecraft:logs", List.of("minecraft:oak_log", "minecraft:birch_log")));

		assertEquals(3, resolved.blockStateIds()[4]);
		assertEquals(1, resolved.blockStateIds()[5]);
	}
}
